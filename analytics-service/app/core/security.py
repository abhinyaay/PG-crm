"""Access-token validation compatible with the Spring Boot auth server.

* RS256 JWTs, public keys from the backend JWKS endpoint (see :class:`JwksKeyStore`: the key set is
  cached for ``JWKS_CACHE_SECONDS``, refetched on an unknown ``kid`` at most once per
  ``JWKS_MIN_REFRESH_SECONDS``, and the last known keys survive a JWKS outage).
* ``iss``/``aud``/``exp`` validated, ``token_type`` must be ``access``.
* Revocation via the shared Redis: ``auth:revoked-jti:{jti}`` and ``auth:invalid-before:{sub}``.
  Redis problems fail OPEN (logged): tokens live 15 minutes and this service is read-only.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from enum import StrEnum
from typing import Any

import jwt
from jwt import PyJWK, PyJWKClient, PyJWKSet

from app.core.errors import ForbiddenError, ServiceUnavailableError, UnauthorizedError
from app.core.redis_gateway import RedisGateway, RedisUnavailableError

logger = logging.getLogger("pgms.analytics.security")

REVOKED_JTI_PREFIX = "auth:revoked-jti:"
INVALID_BEFORE_PREFIX = "auth:invalid-before:"


class Role(StrEnum):
    SUPER_ADMIN = "SUPER_ADMIN"
    PROPERTY_ADMIN = "PROPERTY_ADMIN"
    MANAGER = "MANAGER"
    STAFF = "STAFF"
    TENANT = "TENANT"


ANALYTICS_ROLES = frozenset({Role.SUPER_ADMIN, Role.PROPERTY_ADMIN, Role.MANAGER})


@dataclass(frozen=True)
class Principal:
    user_id: int
    role: Role
    organization_id: int | None
    email: str | None
    jti: str
    issued_at: int


class JwksKeyStore:
    """Thread-safe cache of the backend's RSA signing keys, keyed by ``kid``.

    * The whole key set is replaced on every successful fetch, so a key the backend withdraws stops
      being trusted after at most ``cache_seconds`` (PyJWKClient's ``cache_keys`` would keep it forever).
    * An unknown ``kid`` while the cache is fresh forces a refetch (key rotation), but at most once per
      ``min_refresh_seconds``: random ``kid`` values cannot turn this service into a request amplifier
      against the backend.
    * When a fetch fails the last known keys keep working and further attempts back off for
      ``min_refresh_seconds``; with no usable key the caller gets a connection error (503).
    """

    def __init__(
        self,
        client: PyJWKClient,
        *,
        cache_seconds: float,
        min_refresh_seconds: float,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self._client = client
        self._cache_seconds = cache_seconds
        self._min_refresh = min_refresh_seconds
        self._monotonic = monotonic
        self._lock = threading.Lock()
        self._keys: dict[str, PyJWK] = {}
        self._fetched_at: float | None = None
        self._last_forced_refresh: float | None = None
        self._retry_after = float("-inf")

    def get(self, kid: str) -> PyJWK:
        with self._lock:
            now = self._monotonic()
            key = self._keys.get(kid)
            fresh = self._fetched_at is not None and now - self._fetched_at < self._cache_seconds
            if key is not None and fresh:
                return key
            if now < self._retry_after:  # a recent fetch failed: do not hammer the endpoint
                return self._fallback(key, kid)
            if fresh:  # unknown kid with an up-to-date key set: possible rotation, refetch rate-limited
                last = self._last_forced_refresh
                if last is not None and now - last < self._min_refresh:
                    raise jwt.PyJWKClientError(f'Unable to find a signing key that matches: "{kid}"')
                self._last_forced_refresh = now
            try:
                keys = self._fetch()
            except (jwt.PyJWTError, ValueError) as exc:
                self._retry_after = now + self._min_refresh
                logger.warning("unable to fetch JWKS", extra={"fields": {"error": str(exc)}})
                return self._fallback(key, kid)
            self._keys, self._fetched_at = keys, now
            key = keys.get(kid)
            if key is None:
                raise jwt.PyJWKClientError(f'Unable to find a signing key that matches: "{kid}"')
            return key

    @staticmethod
    def _fallback(key: PyJWK | None, kid: str) -> PyJWK:
        if key is not None:
            return key  # last known good key; the JWKS endpoint is currently unreachable
        raise jwt.PyJWKClientConnectionError(f'JWKS unavailable and no cached key for "{kid}"')

    def _fetch(self) -> dict[str, PyJWK]:
        data = self._client.fetch_data()
        if not isinstance(data, dict):
            raise jwt.PyJWKClientError("The JWKS endpoint did not return a JSON object")
        keys = {
            k.key_id: k
            for k in PyJWKSet.from_dict(data).keys
            if k.key_id and k.key_type == "RSA" and k.public_key_use in ("sig", None)
        }
        if not keys:
            raise jwt.PyJWKClientError("The JWKS endpoint did not contain any RSA signing keys")
        return keys


class TokenValidator:
    def __init__(
        self,
        *,
        jwks_url: str,
        issuer: str,
        audience: str,
        leeway_seconds: int = 30,
        jwks_cache_seconds: int = 300,
        jwks_timeout_seconds: float = 5.0,
        jwks_min_refresh_seconds: float = 10.0,
        jwk_client: PyJWKClient | None = None,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self._issuer = issuer
        self._audience = audience
        self._leeway = leeway_seconds
        # PyJWKClient is only used as the HTTP fetcher; caching is done by JwksKeyStore.
        self.jwk_client = jwk_client or PyJWKClient(
            jwks_url, cache_keys=False, cache_jwk_set=False, timeout=jwks_timeout_seconds
        )
        self.keys = JwksKeyStore(
            self.jwk_client,
            cache_seconds=jwks_cache_seconds,
            min_refresh_seconds=jwks_min_refresh_seconds,
            monotonic=monotonic,
        )

    def validate(self, token: str) -> Principal:
        try:
            kid = jwt.get_unverified_header(token).get("kid")
            if not isinstance(kid, str) or not kid:
                # the backend always sets a kid; never let a kid-less token trigger a JWKS fetch
                raise jwt.InvalidTokenError("token header has no kid")
            signing_key = self.keys.get(kid)
        except jwt.PyJWKClientConnectionError as exc:
            raise ServiceUnavailableError("Unable to verify access token at this time") from exc
        except (jwt.PyJWKClientError, jwt.InvalidTokenError) as exc:
            logger.info("token rejected", extra={"fields": {"reason": str(exc)}})
            raise UnauthorizedError("Invalid access token") from exc

        try:
            claims: dict[str, Any] = jwt.decode(
                token,
                key=signing_key.key,
                algorithms=["RS256"],
                audience=self._audience,
                issuer=self._issuer,
                leeway=self._leeway,
                options={"require": ["exp", "iat", "iss", "aud", "sub", "jti"]},
            )
        except jwt.ExpiredSignatureError as exc:
            raise UnauthorizedError("Access token has expired") from exc
        except jwt.InvalidTokenError as exc:
            logger.info("token rejected", extra={"fields": {"reason": str(exc)}})
            raise UnauthorizedError("Invalid access token") from exc

        return self._principal_from_claims(claims)

    @staticmethod
    def _principal_from_claims(claims: dict[str, Any]) -> Principal:
        if claims.get("token_type") != "access":
            raise UnauthorizedError("Token is not an access token")
        try:
            user_id = int(str(claims["sub"]))
            issued_at = int(claims["iat"])
        except (KeyError, TypeError, ValueError) as exc:
            raise UnauthorizedError("Invalid access token") from exc

        raw_role = claims.get("role")
        if not isinstance(raw_role, str) or not raw_role:
            raise UnauthorizedError("Invalid access token")
        try:
            role = Role(raw_role)
        except ValueError as exc:
            raise ForbiddenError("Role is not permitted to use analytics") from exc

        raw_org = claims.get("org")
        organization_id: int | None
        if raw_org is None:
            organization_id = None
        else:
            try:
                organization_id = int(raw_org)
            except (TypeError, ValueError) as exc:
                raise UnauthorizedError("Invalid access token") from exc

        email = claims.get("email")
        return Principal(
            user_id=user_id,
            role=role,
            organization_id=organization_id,
            email=email if isinstance(email, str) else None,
            jti=str(claims["jti"]),
            issued_at=issued_at,
        )


class RevocationChecker:
    def __init__(self, redis: RedisGateway | None) -> None:
        self._redis = redis
        if redis is None:
            logger.warning("REDIS_URL not configured: token revocation checks are disabled")

    def is_revoked(self, principal: Principal) -> bool:
        if self._redis is None:
            return False
        try:
            revoked, invalid_before = self._redis.mget(
                [f"{REVOKED_JTI_PREFIX}{principal.jti}", f"{INVALID_BEFORE_PREFIX}{principal.user_id}"]
            )
        except RedisUnavailableError:
            logger.warning(
                "revocation check skipped: redis unavailable (failing open)",
                extra={"fields": {"jti": principal.jti}},
            )
            return False
        if revoked is not None:
            return True
        if invalid_before is not None:
            try:
                cutoff = int(float(invalid_before))
            except ValueError:
                logger.warning("ignoring malformed invalid-before marker")
                return False
            return principal.issued_at < cutoff
        return False


def ensure_analytics_role(principal: Principal) -> None:
    if principal.role not in ANALYTICS_ROLES:
        raise ForbiddenError("Role is not permitted to use analytics")
