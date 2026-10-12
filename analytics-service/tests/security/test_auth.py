"""End-to-end authentication/authorization through the real TokenValidator (JWKS monkeypatched)."""

from __future__ import annotations

import time

import pytest

from tests.conftest import public_jwk
from tests.fakes import FakeRedisClient

URL = "/analytics/occupancy"


def _get(h, token=None, url=URL, **params):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    return h.client.get(url, headers=headers, params=params)


def _assert_error(response, status, code):
    assert response.status_code == status, response.text
    body = response.json()
    assert body["status"] == status
    assert body["code"] == code
    assert set(body) == {"timestamp", "status", "error", "code", "message", "path", "correlationId"}
    assert body["correlationId"] == response.headers["X-Correlation-Id"]
    assert body["path"] == URL


def test_missing_token_is_401(harness_factory):
    h = harness_factory()
    response = _get(h)
    _assert_error(response, 401, "UNAUTHORIZED")
    assert response.headers["WWW-Authenticate"] == "Bearer"
    assert response.json()["error"] == "Unauthorized"


def test_non_bearer_scheme_is_401(harness_factory):
    h = harness_factory()
    response = h.client.get(URL, headers={"Authorization": "Basic abc"})
    _assert_error(response, 401, "UNAUTHORIZED")


def test_valid_token_is_accepted(harness_factory, make_token):
    h = harness_factory()
    assert _get(h, make_token()).status_code == 200


def test_bad_signature_is_401(harness_factory, make_token, other_key):
    h = harness_factory()
    _assert_error(_get(h, make_token(key=other_key)), 401, "UNAUTHORIZED")


def test_garbage_token_is_401(harness_factory):
    h = harness_factory()
    _assert_error(_get(h, "not-a-jwt"), 401, "UNAUTHORIZED")


def test_hs256_token_is_rejected(harness_factory, make_token):
    h = harness_factory()
    _assert_error(_get(h, make_token(algorithm="HS256")), 401, "UNAUTHORIZED")


@pytest.mark.parametrize(
    "overrides",
    [
        {"aud": ["some-other-api"]},
        {"iss": "evil-issuer"},
        {"exp": int(time.time()) - 3600, "iat": int(time.time()) - 7200},
        {"token_type": "refresh"},
        {"sub": "not-a-number"},
    ],
    ids=["wrong-audience", "wrong-issuer", "expired", "refresh-token", "bad-sub"],
)
def test_invalid_claims_are_401(harness_factory, make_token, overrides):
    h = harness_factory()
    _assert_error(_get(h, make_token(**overrides)), 401, "UNAUTHORIZED")


@pytest.mark.parametrize("claim", ["jti", "exp", "token_type", "role"])
def test_missing_required_claim_is_401(harness_factory, make_token, claim):
    h = harness_factory()
    _assert_error(_get(h, make_token(drop=(claim,))), 401, "UNAUTHORIZED")


@pytest.mark.parametrize("role", ["STAFF", "TENANT", "SOMETHING_ELSE"])
def test_non_analytics_roles_are_403(harness_factory, make_token, role):
    h = harness_factory()
    _assert_error(_get(h, make_token(role=role)), 403, "ACCESS_DENIED")


def test_manager_limited_to_assigned_properties(harness_factory, make_token):
    h = harness_factory(manager_properties={5: [1, 2]})
    token = make_token(role="MANAGER", sub="5")
    _assert_error(_get(h, token, propertyId=3), 403, "ACCESS_DENIED")
    assert _get(h, token, propertyId=2).status_code == 200
    response = _get(h, token)
    assert response.status_code == 200
    assert response.json()["meta"]["propertyIds"] == [1, 2]


def test_manager_without_assignments_sees_nothing(harness_factory, make_token):
    h = harness_factory()
    response = _get(h, make_token(role="MANAGER", sub="99"))
    assert response.status_code == 200
    assert response.json()["meta"]["propertyIds"] == []


def test_property_admin_cannot_cross_organizations(harness_factory, make_token):
    h = harness_factory()
    token = make_token(role="PROPERTY_ADMIN", org=10)
    _assert_error(_get(h, token, organizationId=20), 403, "ACCESS_DENIED")
    _assert_error(_get(h, token, propertyId=7), 403, "ACCESS_DENIED")  # property of org 20
    assert _get(h, token, organizationId=10, propertyId=1).status_code == 200


def test_property_admin_without_org_claim_is_403(harness_factory, make_token):
    h = harness_factory()
    _assert_error(_get(h, make_token(role="PROPERTY_ADMIN", org=None)), 403, "ACCESS_DENIED")


def test_super_admin_can_filter_any_organization(harness_factory, make_token):
    h = harness_factory()
    response = _get(h, make_token(role="SUPER_ADMIN", org=None), organizationId=20)
    assert response.status_code == 200
    assert response.json()["meta"]["organizationId"] == 20


def test_revoked_jti_is_401(harness_factory, make_token):
    redis_client = FakeRedisClient()
    h = harness_factory(redis_client=redis_client)
    token = make_token(jti="revoked-1")
    redis_client.store["auth:revoked-jti:revoked-1"] = "1"
    _assert_error(_get(h, token), 401, "UNAUTHORIZED")


def test_invalid_before_marker(harness_factory, make_token):
    redis_client = FakeRedisClient()
    h = harness_factory(redis_client=redis_client)
    now = int(time.time())
    redis_client.store["auth:invalid-before:5"] = str(now)
    _assert_error(_get(h, make_token(sub="5", iat=now - 10)), 401, "UNAUTHORIZED")
    assert _get(h, make_token(sub="5", iat=now)).status_code == 200  # issued at/after the cutoff


def test_redis_down_fails_open(harness_factory, make_token):
    redis_client = FakeRedisClient(fail=True)
    h = harness_factory(redis_client=redis_client)
    response = _get(h, make_token())
    assert response.status_code == 200
    assert redis_client.calls >= 1


def test_unknown_kid_triggers_jwks_refetch(harness_factory, make_token, jwks, other_key):
    h = harness_factory()
    assert _get(h, make_token()).status_code == 200
    fetches = jwks.fetches
    # backend rotates keys: the new kid is not in the cached key set -> one refetch
    jwks.keys.append(public_jwk(other_key, "rotated-key"))
    assert _get(h, make_token(key=other_key, kid="rotated-key")).status_code == 200
    assert jwks.fetches == fetches + 1


def test_unknown_kid_not_published_is_401(harness_factory, make_token, other_key):
    h = harness_factory()
    _assert_error(_get(h, make_token(key=other_key, kid="nope")), 401, "UNAUTHORIZED")


def test_jwks_unreachable_is_503(harness_factory, make_token, jwks):
    jwks.down = True
    h = harness_factory()
    _assert_error(_get(h, make_token()), 503, "SERVICE_UNAVAILABLE")


def test_health_endpoints_need_no_token(harness_factory):
    h = harness_factory()
    assert h.client.get("/health/live").status_code == 200


# --- JWKS key handling (rotation, revocation of a key, refetch amplification) -------------------


class _Monotonic:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


def _validator(clock, *, cache_seconds=300, min_refresh_seconds=10.0):
    from app.core.security import TokenValidator
    from tests.conftest import AUDIENCE, ISSUER

    return TokenValidator(
        jwks_url="http://jwks.test/jwks",
        issuer=ISSUER,
        audience=AUDIENCE,
        jwks_cache_seconds=cache_seconds,
        jwks_min_refresh_seconds=min_refresh_seconds,
        monotonic=clock,
    )


def test_key_withdrawn_from_jwks_stops_working_after_cache_expiry(jwks, make_token, other_key):
    """A key removed from the JWKS (e.g. compromised) must not stay trusted forever by a per-kid cache."""
    from app.core.errors import UnauthorizedError

    clock = _Monotonic()
    validator = _validator(clock)
    token = make_token()
    assert validator.validate(token).user_id == 5
    jwks.keys = [public_jwk(other_key, "next-key")]
    clock.now += 301
    with pytest.raises(UnauthorizedError):
        validator.validate(token)
    assert validator.validate(make_token(key=other_key, kid="next-key")).user_id == 5


def test_unknown_kid_flood_triggers_at_most_one_refetch_per_interval(jwks, make_token, other_key):
    from app.core.errors import UnauthorizedError

    clock = _Monotonic()
    validator = _validator(clock, min_refresh_seconds=10)
    validator.validate(make_token())
    assert jwks.fetches == 1
    for i in range(25):
        with pytest.raises(UnauthorizedError):
            validator.validate(make_token(key=other_key, kid=f"random-{i}"))
    assert jwks.fetches == 2  # one forced refresh, the rest rejected without touching the backend
    # a real rotation after the interval is still picked up
    jwks.keys.append(public_jwk(other_key, "rotated"))
    clock.now += 11
    assert validator.validate(make_token(key=other_key, kid="rotated")).user_id == 5
    assert jwks.fetches == 3


def test_token_without_kid_is_rejected_without_fetching_jwks(harness_factory, make_token, jwks):
    h = harness_factory()
    _assert_error(_get(h, make_token(kid=None)), 401, "UNAUTHORIZED")
    assert jwks.fetches == 0


def test_jwks_outage_keeps_last_known_keys_and_backs_off(jwks, make_token, other_key):
    from app.core.errors import ServiceUnavailableError

    clock = _Monotonic()
    validator = _validator(clock, min_refresh_seconds=10)
    token = make_token()
    validator.validate(token)
    jwks.down = True
    clock.now += 301  # cache expired, refetch fails -> last known keys are still used
    assert validator.validate(token).user_id == 5
    assert validator.validate(token).user_id == 5
    assert jwks.fetches == 2  # no refetch storm while the endpoint is down
    with pytest.raises(ServiceUnavailableError):
        validator.validate(make_token(key=other_key, kid="unknown-during-outage"))
    jwks.down = False
    clock.now += 11
    validator.validate(token)
    assert jwks.fetches == 3


def test_jwks_with_only_non_rsa_keys_is_unavailable_not_500(harness_factory, make_token, jwks):
    jwks.keys = [{"kty": "oct", "k": "c2VjcmV0", "kid": "test-key-1", "use": "sig"}]
    h = harness_factory()
    _assert_error(_get(h, make_token()), 503, "SERVICE_UNAVAILABLE")


def test_alg_none_token_is_401(harness_factory, make_token):
    import jwt

    h = harness_factory()
    claims = jwt.decode(make_token(), options={"verify_signature": False})
    unsigned = jwt.encode(claims, None, algorithm="none", headers={"kid": "test-key-1"})
    _assert_error(_get(h, unsigned), 401, "UNAUTHORIZED")
