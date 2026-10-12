"""Analytics endpoints (``/analytics/*``). All require SUPER_ADMIN, PROPERTY_ADMIN or MANAGER."""

from __future__ import annotations

from typing import Annotated

import pandas as pd
from fastapi import APIRouter, Depends, Query, Response

from app.api.deps import (
    CacheDep,
    ClockDep,
    ExportersDep,
    FormatQuery,
    PeriodDep,
    ScopeDep,
    build_meta,
    cache_params,
    get_complaint_repository,
    get_finance_repository,
    get_maintenance_repository,
    get_occupancy_repository,
    get_property_repository,
    get_tenant_repository,
    require_analytics_user,
)
from app.api.rendering import EXPORT_RESPONSES, render
from app.repositories.complaints import ComplaintRepository
from app.repositories.finance import FinanceRepository
from app.repositories.maintenance import MaintenanceRepository
from app.repositories.occupancy import OccupancyRepository
from app.repositories.properties import PropertyRepository
from app.repositories.tenants import TenantRepository
from app.schemas.complaints import ComplaintReport
from app.schemas.maintenance import MaintenanceReport
from app.schemas.occupancy import OccupancyReport
from app.schemas.payments import PaymentsReport
from app.schemas.properties import PropertyPerformanceReport, PropertySortKey, SortOrder
from app.schemas.revenue import RevenueReport
from app.schemas.tenants import TenantReport
from app.services.complaints import build_complaint_report
from app.services.maintenance import build_maintenance_report
from app.services.occupancy import build_occupancy_report
from app.services.payments import build_payments_report
from app.services.period import ReportPeriod
from app.services.properties import PropertyInputs, build_property_report
from app.services.revenue import build_revenue_report
from app.services.tenants import build_tenant_report

router = APIRouter(
    prefix="/analytics",
    tags=["analytics"],
    dependencies=[Depends(require_analytics_user)],
    responses={
        400: {"description": "Invalid query parameters (VALIDATION_FAILED)"},
        401: {"description": "Missing/invalid/revoked access token (UNAUTHORIZED)"},
        403: {"description": "Role or scope not permitted (FORBIDDEN)"},
        503: {"description": "Database or JWKS unavailable (SERVICE_UNAVAILABLE)"},
    },
)

OccupancyRepoDep = Annotated[OccupancyRepository, Depends(get_occupancy_repository)]
FinanceRepoDep = Annotated[FinanceRepository, Depends(get_finance_repository)]
TenantRepoDep = Annotated[TenantRepository, Depends(get_tenant_repository)]
MaintenanceRepoDep = Annotated[MaintenanceRepository, Depends(get_maintenance_repository)]
ComplaintRepoDep = Annotated[ComplaintRepository, Depends(get_complaint_repository)]
PropertyRepoDep = Annotated[PropertyRepository, Depends(get_property_repository)]


@router.get(
    "/occupancy",
    response_model=OccupancyReport,
    responses=EXPORT_RESPONSES,
    summary="Bed occupancy snapshot, per-property and room-type utilisation, monthly bed-day trend",
)
def occupancy(
    scope: ScopeDep,
    period: PeriodDep,
    repo: OccupancyRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    fmt: FormatQuery = "json",
) -> OccupancyReport | Response:
    today = clock.today()

    def compute() -> OccupancyReport:
        beds = repo.bed_inventory(scope)
        allocations = repo.allocations_overlapping(scope, period.start_date, period.end_date)
        return build_occupancy_report(
            beds, allocations, period=period, today=today, meta=build_meta(scope, period, clock)
        )

    result = cache.get_or_compute("occupancy", scope, cache_params(period, clock), OccupancyReport, compute)
    return render(result, fmt, "occupancy", exporters, today)


@router.get(
    "/revenue",
    response_model=RevenueReport,
    responses=EXPORT_RESPONSES,
    summary="Net rent revenue, deposits, refunds, billed rent, collection rate, payment-method mix",
)
def revenue(
    scope: ScopeDep,
    period: PeriodDep,
    repo: FinanceRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    fmt: FormatQuery = "json",
) -> RevenueReport | Response:
    def compute() -> RevenueReport:
        payments = repo.payments_between(scope, period.start_ts, period.end_ts)
        invoices = repo.rent_invoices(scope, period.start_date, period.last_month.start_time.date())
        return build_revenue_report(
            payments, invoices, period=period, meta=build_meta(scope, period, clock), today=clock.today()
        )

    result = cache.get_or_compute("revenue", scope, cache_params(period, clock), RevenueReport, compute)
    return render(result, fmt, "revenue", exporters, clock.today())


@router.get(
    "/payments",
    response_model=PaymentsReport,
    responses=EXPORT_RESPONSES,
    summary="Outstanding receivables, aging buckets, payment-default trend, collection rate",
)
def payments(
    scope: ScopeDep,
    period: PeriodDep,
    repo: FinanceRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    fmt: FormatQuery = "json",
) -> PaymentsReport | Response:
    today = clock.today()

    def compute() -> PaymentsReport:
        open_invoices = repo.open_invoices(scope)
        rent_invoices = repo.rent_invoices(scope, period.start_date, period.last_month.start_time.date())
        return build_payments_report(
            open_invoices, rent_invoices, period=period, today=today, meta=build_meta(scope, period, clock)
        )

    result = cache.get_or_compute("payments", scope, cache_params(period, clock), PaymentsReport, compute)
    return render(result, fmt, "payments", exporters, today)


@router.get(
    "/tenants",
    response_model=TenantReport,
    responses=EXPORT_RESPONSES,
    summary="Active tenants, status & gender mix, move-ins/outs, stay duration, churn",
)
def tenants(
    scope: ScopeDep,
    period: PeriodDep,
    repo: TenantRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    fmt: FormatQuery = "json",
) -> TenantReport | Response:
    today = clock.today()

    def compute() -> TenantReport:
        return build_tenant_report(
            repo.tenants(scope),
            repo.allocations(scope, period.start_date),
            period=period,
            today=today,
            meta=build_meta(scope, period, clock),
        )

    result = cache.get_or_compute("tenants", scope, cache_params(period, clock), TenantReport, compute)
    return render(result, fmt, "tenants", exporters, today)


@router.get(
    "/maintenance",
    response_model=MaintenanceReport,
    responses=EXPORT_RESPONSES,
    summary="Maintenance volumes, resolution time, SLA compliance, monthly created vs resolved",
)
def maintenance(
    scope: ScopeDep,
    period: PeriodDep,
    repo: MaintenanceRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    fmt: FormatQuery = "json",
) -> MaintenanceReport | Response:
    def compute() -> MaintenanceReport:
        return build_maintenance_report(
            repo.requests(scope, period.start_ts, period.end_ts),
            period=period,
            now=clock.now_utc(),
            meta=build_meta(scope, period, clock),
        )

    result = cache.get_or_compute(
        "maintenance", scope, cache_params(period, clock), MaintenanceReport, compute
    )
    return render(result, fmt, "maintenance", exporters, clock.today())


@router.get(
    "/complaints",
    response_model=ComplaintReport,
    responses=EXPORT_RESPONSES,
    summary="Complaint volumes, escalation rate, resolution time, overdue open complaints",
)
def complaints(
    scope: ScopeDep,
    period: PeriodDep,
    repo: ComplaintRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    fmt: FormatQuery = "json",
) -> ComplaintReport | Response:
    def compute() -> ComplaintReport:
        return build_complaint_report(
            repo.complaints(scope, period.start_ts, period.end_ts),
            period=period,
            now=clock.now_utc(),
            meta=build_meta(scope, period, clock),
        )

    result = cache.get_or_compute("complaints", scope, cache_params(period, clock), ComplaintReport, compute)
    return render(result, fmt, "complaints", exporters, clock.today())


@router.get(
    "/properties",
    response_model=PropertyPerformanceReport,
    responses=EXPORT_RESPONSES,
    summary="Property performance comparison table with ranking",
)
def properties(
    scope: ScopeDep,
    period: PeriodDep,
    property_repo: PropertyRepoDep,
    occupancy_repo: OccupancyRepoDep,
    finance_repo: FinanceRepoDep,
    tenant_repo: TenantRepoDep,
    maintenance_repo: MaintenanceRepoDep,
    complaint_repo: ComplaintRepoDep,
    cache: CacheDep,
    clock: ClockDep,
    exporters: ExportersDep,
    sort_by: Annotated[
        PropertySortKey, Query(alias="sortBy", description="Column to rank by (whitelisted)")
    ] = "occupancyRate",
    order: Annotated[SortOrder, Query(description="asc or desc")] = "desc",
    fmt: FormatQuery = "json",
) -> PropertyPerformanceReport | Response:
    today = clock.today()
    this_month = pd.Period(today, freq="M")
    current = ReportPeriod(first_month=this_month, last_month=this_month, timezone=period.timezone)

    def compute() -> PropertyPerformanceReport:
        inputs = PropertyInputs(
            properties=property_repo.properties(scope),
            beds=occupancy_repo.bed_inventory(scope),
            tenants=tenant_repo.tenants(scope),
            current_month_payments=finance_repo.payments_between(scope, current.start_ts, current.end_ts),
            rent_invoices=finance_repo.rent_invoices(
                scope, period.start_date, period.last_month.start_time.date()
            ),
            open_invoices=finance_repo.open_invoices(scope),
            maintenance=maintenance_repo.requests(scope, period.start_ts, period.end_ts),
            complaints=complaint_repo.complaints(scope, period.start_ts, period.end_ts),
        )
        return build_property_report(
            inputs,
            period=period,
            current_month=current,
            today=today,
            now=clock.now_utc(),
            sort_by=sort_by,
            order=order,
            meta=build_meta(scope, period, clock),
        )

    result = cache.get_or_compute(
        "properties",
        scope,
        cache_params(period, clock, sortBy=sort_by, order=order),
        PropertyPerformanceReport,
        compute,
    )
    return render(result, fmt, "properties", exporters, today)
