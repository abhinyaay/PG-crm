"""FastAPI dependency providers. Tests override these via ``app.dependency_overrides``."""

from __future__ import annotations

from typing import Annotated, Literal

from fastapi import Depends, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.authorization import resolve_scope
from app.core.cache import ResultCache
from app.core.clock import Clock
from app.core.config import Settings
from app.core.context import get_request_context
from app.core.errors import UnauthorizedError, ValidationFailedError
from app.core.scope import AnalyticsScope
from app.core.security import Principal, RevocationChecker, TokenValidator, ensure_analytics_role
from app.db.database import Database
from app.reports.registry import ExporterRegistry
from app.repositories.access import AccessRepository
from app.repositories.complaints import ComplaintRepository
from app.repositories.finance import FinanceRepository
from app.repositories.maintenance import MaintenanceRepository
from app.repositories.occupancy import OccupancyRepository
from app.repositories.properties import PropertyRepository
from app.repositories.tenants import TenantRepository
from app.schemas.common import ReportMeta
from app.services.period import MONTH_PATTERN, ReportPeriod, build_period

# --- infrastructure ---------------------------------------------------------------------------


def get_app_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_database(request: Request) -> Database:
    return request.app.state.database


def get_clock(request: Request) -> Clock:
    return request.app.state.clock


def get_cache(request: Request) -> ResultCache:
    return request.app.state.cache


def get_exporters(request: Request) -> ExporterRegistry:
    return request.app.state.exporters


def get_token_validator(request: Request) -> TokenValidator:
    return request.app.state.token_validator


def get_revocation_checker(request: Request) -> RevocationChecker:
    return request.app.state.revocation


# --- repositories -----------------------------------------------------------------------------


def get_access_repository(db: Annotated[Database, Depends(get_database)]) -> AccessRepository:
    return AccessRepository(db)


def get_occupancy_repository(db: Annotated[Database, Depends(get_database)]) -> OccupancyRepository:
    return OccupancyRepository(db)


def get_finance_repository(db: Annotated[Database, Depends(get_database)]) -> FinanceRepository:
    return FinanceRepository(db)


def get_tenant_repository(db: Annotated[Database, Depends(get_database)]) -> TenantRepository:
    return TenantRepository(db)


def get_maintenance_repository(db: Annotated[Database, Depends(get_database)]) -> MaintenanceRepository:
    return MaintenanceRepository(db)


def get_complaint_repository(db: Annotated[Database, Depends(get_database)]) -> ComplaintRepository:
    return ComplaintRepository(db)


def get_property_repository(db: Annotated[Database, Depends(get_database)]) -> PropertyRepository:
    return PropertyRepository(db)


# --- security ---------------------------------------------------------------------------------

# Ids are BIGINT in PostgreSQL; larger values would only fail inside the database (as a 503).
MAX_ID = 2**63 - 1

bearer_scheme = HTTPBearer(auto_error=False, description="Access token issued by the PGMS backend")


def get_principal(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
    validator: Annotated[TokenValidator, Depends(get_token_validator)],
    revocation: Annotated[RevocationChecker, Depends(get_revocation_checker)],
) -> Principal:
    if credentials is None or not credentials.credentials:
        raise UnauthorizedError("Authentication is required")
    principal = validator.validate(credentials.credentials)
    if revocation.is_revoked(principal):
        raise UnauthorizedError("Access token has been revoked")
    ctx = get_request_context()
    if ctx is not None:
        ctx.user_id = principal.user_id
    return principal


def require_analytics_user(principal: Annotated[Principal, Depends(get_principal)]) -> Principal:
    ensure_analytics_role(principal)
    return principal


def get_scope(
    principal: Annotated[Principal, Depends(require_analytics_user)],
    access: Annotated[AccessRepository, Depends(get_access_repository)],
    organization_id: Annotated[
        int | None,
        Query(alias="organizationId", gt=0, le=MAX_ID, description="SUPER_ADMIN: filter to one organization"),
    ] = None,
    property_id: Annotated[
        int | None,
        Query(alias="propertyId", gt=0, le=MAX_ID, description="Restrict to one property in your scope"),
    ] = None,
) -> AnalyticsScope:
    return resolve_scope(principal, organization_id=organization_id, property_id=property_id, access=access)


# --- common query parameters ------------------------------------------------------------------


def get_period(
    clock: Annotated[Clock, Depends(get_clock)],
    settings: Annotated[Settings, Depends(get_app_settings)],
    from_month: Annotated[
        str | None,
        Query(
            alias="from",
            pattern=MONTH_PATTERN,
            description="First month (YYYY-MM). Default: 11 months before `to`.",
            examples=["2026-01"],
        ),
    ] = None,
    to_month: Annotated[
        str | None,
        Query(
            alias="to",
            pattern=MONTH_PATTERN,
            description="Last month (YYYY-MM), inclusive. Default: current month.",
            examples=["2026-12"],
        ),
    ] = None,
) -> ReportPeriod:
    try:
        return build_period(
            from_month,
            to_month,
            today=clock.today(),
            timezone=clock.timezone,
            max_months=settings.max_report_months,
        )
    except ValueError as exc:
        raise ValidationFailedError(str(exc)) from exc


ExportFormat = Literal["json", "csv"]
FormatQuery = Annotated[
    ExportFormat, Query(alias="format", description="Response format: json (default) or csv")
]

ScopeDep = Annotated[AnalyticsScope, Depends(get_scope)]
PeriodDep = Annotated[ReportPeriod, Depends(get_period)]
ClockDep = Annotated[Clock, Depends(get_clock)]
CacheDep = Annotated[ResultCache, Depends(get_cache)]
ExportersDep = Annotated[ExporterRegistry, Depends(get_exporters)]


def build_meta(scope: AnalyticsScope, period: ReportPeriod | None, clock: Clock) -> ReportMeta:
    return ReportMeta(
        generated_at=clock.now_utc(),
        timezone=clock.timezone,
        organization_id=scope.organization_id,
        property_ids=list(scope.property_ids) if scope.property_ids is not None else None,
        from_month=str(period.first_month) if period else None,
        to_month=str(period.last_month) if period else None,
    )


def cache_params(period: ReportPeriod | None, clock: Clock, **extra: object) -> dict[str, object]:
    params: dict[str, object] = {"today": clock.today().isoformat(), **extra}
    if period is not None:
        params.update(period.as_params())
    return params
