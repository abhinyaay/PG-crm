"""Application factory. Run with ``uvicorn app.main:create_app --factory``."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import redis
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routers import analytics, health
from app.core.cache import ResultCache
from app.core.clock import Clock
from app.core.config import Settings, get_settings
from app.core.errors import register_exception_handlers
from app.core.logging import configure_logging
from app.core.middleware import CORRELATION_HEADER, CorrelationIdMiddleware
from app.core.redis_gateway import RedisGateway
from app.core.security import RevocationChecker, TokenValidator
from app.db.database import Database, SqlDatabase
from app.reports.registry import ExporterRegistry, default_registry

logger = logging.getLogger("pgms.analytics")

API_DESCRIPTION = """
Read-only analytics for the PG management platform.

* Authenticate with the **access token** issued by the backend (`Authorization: Bearer ...`).
* Allowed roles: SUPER_ADMIN, PROPERTY_ADMIN, MANAGER. Data is scoped to the caller's organization
  (and, for managers, to their assigned properties).
* Time-based endpoints accept `from` / `to` (`YYYY-MM`, default: last 12 months incl. current) and
  `format=json|csv`.
"""


def _build_redis(settings: Settings) -> RedisGateway | None:
    if not settings.redis_url:
        return None
    client = redis.Redis.from_url(
        settings.redis_url,
        socket_timeout=settings.redis_socket_timeout_seconds,
        socket_connect_timeout=settings.redis_socket_timeout_seconds,
        retry_on_timeout=False,
        decode_responses=True,
    )
    return RedisGateway(client, cooldown_seconds=settings.redis_retry_cooldown_seconds)


def create_app(
    settings: Settings | None = None,
    *,
    database: Database | None = None,
    redis_gateway: RedisGateway | None = None,
    use_redis: bool = True,
    token_validator: TokenValidator | None = None,
    clock: Clock | None = None,
    exporters: ExporterRegistry | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        logger.info("analytics service started", extra={"fields": {"timezone": settings.business_timezone}})
        yield
        dispose = getattr(app.state.database, "dispose", None)
        if callable(dispose):
            dispose()

    app = FastAPI(
        title="PGMS Analytics API",
        version="1.0.0",
        description=API_DESCRIPTION,
        docs_url="/analytics/docs",
        openapi_url="/analytics/openapi.json",
        redoc_url=None,
        lifespan=lifespan,
    )

    if redis_gateway is None and use_redis:
        redis_gateway = _build_redis(settings)

    app.state.settings = settings
    app.state.database = database or SqlDatabase(
        settings.database_url,
        statement_timeout_ms=settings.db_statement_timeout_ms,
        connect_timeout_seconds=settings.db_connect_timeout_seconds,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
    )
    app.state.redis = redis_gateway
    app.state.cache = ResultCache(redis_gateway, settings.cache_ttl_seconds)
    app.state.token_validator = token_validator or TokenValidator(
        jwks_url=settings.jwks_url,
        issuer=settings.jwt_issuer,
        audience=settings.jwt_audience,
        leeway_seconds=settings.jwt_leeway_seconds,
        jwks_cache_seconds=settings.jwks_cache_seconds,
        jwks_timeout_seconds=settings.jwks_timeout_seconds,
        jwks_min_refresh_seconds=settings.jwks_min_refresh_seconds,
    )
    app.state.revocation = RevocationChecker(redis_gateway)
    app.state.clock = clock or Clock(settings.business_timezone)
    app.state.exporters = exporters or default_registry

    register_exception_handlers(app)

    if settings.cors_origin_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origin_list,
            allow_methods=["GET", "OPTIONS"],
            allow_headers=["Authorization", "Content-Type", CORRELATION_HEADER],
            expose_headers=[CORRELATION_HEADER, "Content-Disposition"],
            max_age=600,
        )
    # Added last => outermost: every response (incl. CORS preflight and errors) carries the id.
    app.add_middleware(CorrelationIdMiddleware)

    app.include_router(health.router)
    app.include_router(analytics.router)
    return app
