from __future__ import annotations

import time
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import jwt
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
from jwt import PyJWKClient

from app.api import deps
from app.core.clock import FixedClock
from app.core.config import Settings
from app.core.redis_gateway import RedisGateway
from app.core.security import Principal, Role
from app.main import create_app
from tests.fakes import (
    FakeAccessRepository,
    FakeComplaintRepository,
    FakeDatabase,
    FakeFinanceRepository,
    FakeMaintenanceRepository,
    FakeOccupancyRepository,
    FakePropertyRepository,
    FakeRedisClient,
    FakeTenantRepository,
)
from tests.helpers import ts

ISSUER = "pgms-auth"
AUDIENCE = "pgms-api"
KID = "test-key-1"


def _new_key() -> rsa.RSAPrivateKey:
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture(scope="session")
def signing_key() -> rsa.RSAPrivateKey:
    return _new_key()


@pytest.fixture(scope="session")
def other_key() -> rsa.RSAPrivateKey:
    return _new_key()


def public_jwk(private_key: rsa.RSAPrivateKey, kid: str) -> dict[str, Any]:
    jwk = jwt.algorithms.RSAAlgorithm.to_jwk(private_key.public_key(), as_dict=True)
    return {**jwk, "kid": kid, "use": "sig", "alg": "RS256"}


@dataclass
class JwksServer:
    """Stands in for the backend's JWKS endpoint (PyJWKClient.fetch_data is monkeypatched)."""

    keys: list[dict[str, Any]] = field(default_factory=list)
    fetches: int = 0
    down: bool = False


@pytest.fixture
def jwks(monkeypatch, signing_key) -> JwksServer:
    server = JwksServer(keys=[public_jwk(signing_key, KID)])

    def fake_fetch(self):
        server.fetches += 1
        if server.down:
            raise jwt.PyJWKClientConnectionError("connection refused")
        data = {"keys": list(server.keys)}
        if self.jwk_set_cache is not None:  # mimic the real fetch_data, which populates the cache
            self.jwk_set_cache.put(data)
        return data

    monkeypatch.setattr(PyJWKClient, "fetch_data", fake_fetch)
    return server


@pytest.fixture
def make_token(signing_key) -> Callable[..., str]:
    def _make(
        *,
        sub: str = "5",
        role: str = "PROPERTY_ADMIN",
        org: int | None = 10,
        key: rsa.RSAPrivateKey | None = None,
        kid: str | None = KID,
        drop: tuple[str, ...] = (),
        algorithm: str = "RS256",
        **overrides: Any,
    ) -> str:
        now = int(time.time())
        claims: dict[str, Any] = {
            "iss": ISSUER,
            "aud": [AUDIENCE],
            "sub": sub,
            "role": role,
            "org": org,
            "email": "user@example.com",
            "jti": str(uuid.uuid4()),
            "iat": now,
            "exp": now + 900,
            "token_type": "access",
            **overrides,
        }
        for name in drop:
            claims.pop(name, None)
        headers = {"kid": kid} if kid is not None else None
        if algorithm == "HS256":
            return jwt.encode(claims, "x" * 32, algorithm="HS256", headers=headers)
        return jwt.encode(claims, key or signing_key, algorithm=algorithm, headers=headers)

    return _make


FIXED_NOW = ts("2026-10-05T06:30").to_pydatetime()


@pytest.fixture
def settings() -> Settings:
    return Settings(
        database_url="postgresql+psycopg://analytics_ro@localhost:5432/test",
        redis_url="",
        jwks_url="http://jwks.test/jwks",
        jwt_issuer=ISSUER,
        jwt_audience=AUDIENCE,
        business_timezone="Asia/Kolkata",
        cache_ttl_seconds=300,
        log_level="WARNING",
        cors_origins="http://localhost:5173",
    )


@dataclass
class Harness:
    client: TestClient
    app: Any
    redis: FakeRedisClient
    db: FakeDatabase
    repos: dict[str, Any]
    access: FakeAccessRepository


@pytest.fixture
def harness_factory(settings, jwks) -> Callable[..., Harness]:
    created: list[TestClient] = []

    def _build(
        *,
        principal: Principal | None = None,
        redis_client: FakeRedisClient | None = None,
        empty: bool = False,
        fail: Exception | None = None,
        manager_properties: dict[int, list[int]] | None = None,
        db: FakeDatabase | None = None,
        override_repositories: bool = True,
    ) -> Harness:
        redis_client = redis_client or FakeRedisClient()
        db = db or FakeDatabase()
        app = create_app(
            settings,
            database=db,
            redis_gateway=RedisGateway(redis_client, cooldown_seconds=0),
            clock=FixedClock(FIXED_NOW, "Asia/Kolkata"),
        )
        access = FakeAccessRepository(manager_properties or {})
        repos = {
            "occupancy": FakeOccupancyRepository(empty=empty, fail=fail),
            "finance": FakeFinanceRepository(empty=empty, fail=fail),
            "tenants": FakeTenantRepository(empty=empty, fail=fail),
            "maintenance": FakeMaintenanceRepository(empty=empty, fail=fail),
            "complaints": FakeComplaintRepository(empty=empty, fail=fail),
            "properties": FakePropertyRepository(empty=empty, fail=fail),
        }
        overrides = app.dependency_overrides
        overrides[deps.get_access_repository] = lambda: access
        if override_repositories:
            overrides[deps.get_occupancy_repository] = lambda: repos["occupancy"]
            overrides[deps.get_finance_repository] = lambda: repos["finance"]
            overrides[deps.get_tenant_repository] = lambda: repos["tenants"]
            overrides[deps.get_maintenance_repository] = lambda: repos["maintenance"]
            overrides[deps.get_complaint_repository] = lambda: repos["complaints"]
            overrides[deps.get_property_repository] = lambda: repos["properties"]
        if principal is not None:
            overrides[deps.get_principal] = lambda: principal
        client = TestClient(app)
        created.append(client)
        return Harness(client=client, app=app, redis=redis_client, db=db, repos=repos, access=access)

    yield _build
    for client in created:
        client.close()


def principal(role: Role = Role.PROPERTY_ADMIN, org: int | None = 10, user_id: int = 5) -> Principal:
    return Principal(user_id=user_id, role=role, organization_id=org, email=None, jti="j", issued_at=0)
