"""Property performance comparison (pure pandas)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime

import pandas as pd

from app.schemas.common import ReportMeta
from app.schemas.properties import PropertyPerformance, PropertyPerformanceReport, PropertySortKey, SortOrder
from app.services import complaints as complaint_svc
from app.services import maintenance as maintenance_svc
from app.services.common import ensure_columns, money, ratio_pct
from app.services.payments import prepare_open_invoices
from app.services.period import ReportPeriod
from app.services.revenue import net_rent_revenue_by_property, prepare_rent_invoices
from app.services.tenants import ACTIVE_STATUSES

PROPERTY_COLUMNS = ("id", "code", "name", "city", "status")

# API sort key -> PropertyPerformance attribute
SORT_FIELDS: dict[str, str] = {
    "name": "property_name",
    "occupancyRate": "occupancy_rate_pct",
    "totalBeds": "total_beds",
    "activeTenants": "active_tenants",
    "currentMonthRevenue": "current_month_revenue",
    "revenuePerBed": "revenue_per_bed",
    "collectionRate": "collection_rate_pct",
    "outstanding": "outstanding_amount",
    "openMaintenance": "open_maintenance",
    "slaCompliance": "sla_compliance_pct",
    "openComplaints": "open_complaints",
}


@dataclass(frozen=True)
class PropertyInputs:
    properties: pd.DataFrame
    beds: pd.DataFrame
    tenants: pd.DataFrame
    current_month_payments: pd.DataFrame
    rent_invoices: pd.DataFrame
    open_invoices: pd.DataFrame
    maintenance: pd.DataFrame
    complaints: pd.DataFrame


def _per_property(series: pd.Series, property_id: int, default: float = 0.0) -> float:
    if series.empty or property_id not in series.index:
        return default
    return float(series.loc[property_id])


def rank_rows(
    rows: list[dict[str, object]], sort_by: PropertySortKey, order: SortOrder
) -> list[PropertyPerformance]:
    """Sort by the whitelisted key (nulls last) and assign a competition rank (ties share a rank)."""
    attr = SORT_FIELDS[sort_by]
    ascending = order == "asc"
    if not rows:
        return []
    df = pd.DataFrame(rows)
    key = df[attr].str.lower() if attr == "property_name" else pd.to_numeric(df[attr], errors="coerce")
    df = df.assign(_key=key).sort_values(
        ["_key", "property_name"], ascending=[ascending, True], na_position="last", kind="mergesort"
    )
    if attr == "property_name":
        ranks = pd.Series(range(1, len(df) + 1), index=df.index)
    else:
        ranks = df["_key"].rank(method="min", ascending=ascending, na_option="bottom").astype(int)
    out = df.drop(columns=["_key"]).assign(rank=ranks)
    out = out.astype(object).where(out.notna(), None)
    return [PropertyPerformance(**record) for record in out.to_dict(orient="records")]


def build_property_report(
    inputs: PropertyInputs,
    *,
    period: ReportPeriod,
    current_month: ReportPeriod,
    today: date,
    now: datetime,
    sort_by: PropertySortKey,
    order: SortOrder,
    meta: ReportMeta,
) -> PropertyPerformanceReport:
    props = ensure_columns(inputs.properties, PROPERTY_COLUMNS)
    beds = ensure_columns(inputs.beds, ("bed_id", "property_id", "status"))
    tenants = ensure_columns(inputs.tenants, ("id", "property_id", "status"))

    total_beds = beds.groupby("property_id").size()
    maint_beds = beds[beds["status"] == "MAINTENANCE"].groupby("property_id").size()
    occupied = beds[beds["status"] == "OCCUPIED"].groupby("property_id").size()
    active_tenants = tenants[tenants["status"].isin(ACTIVE_STATUSES)].groupby("property_id").size()

    revenue = net_rent_revenue_by_property(inputs.current_month_payments, current_month)

    inv = prepare_rent_invoices(ensure_columns(inputs.rent_invoices, ("property_id",)), period)
    billed = inv.groupby("property_id")["total_amount"].sum()
    collected = inv.groupby("property_id")["amount_paid"].sum()

    oi = prepare_open_invoices(inputs.open_invoices, today)
    outstanding = oi.groupby("property_id")["outstanding"].sum()

    mr = maintenance_svc.prepare_requests(inputs.maintenance, period.timezone)
    open_maint = mr[mr["status"].isin(maintenance_svc.OPEN_STATUSES)].groupby("property_id").size()
    resolved = maintenance_svc.resolved_in(mr, period.months)

    cp = complaint_svc.prepare_complaints(inputs.complaints, period.timezone)
    open_complaints = cp[cp["status"].isin(complaint_svc.OPEN_STATUSES)].groupby("property_id").size()

    rows: list[dict[str, object]] = []
    for prop in props.itertuples(index=False):
        pid = int(prop.id)
        beds_total = int(_per_property(total_beds, pid))
        beds_occupied = int(_per_property(occupied, pid))
        operational = beds_total - int(_per_property(maint_beds, pid))
        month_revenue = _per_property(revenue, pid)
        rows.append(
            {
                "property_id": pid,
                "property_code": None if pd.isna(prop.code) else str(prop.code),
                "property_name": str(prop.name),
                "city": None if pd.isna(prop.city) else str(prop.city),
                "status": None if pd.isna(prop.status) else str(prop.status),
                "total_beds": beds_total,
                "occupied_beds": beds_occupied,
                "occupancy_rate_pct": ratio_pct(beds_occupied, operational),
                "active_tenants": int(_per_property(active_tenants, pid)),
                "current_month_revenue": money(month_revenue),
                "revenue_per_bed": money(month_revenue / beds_total) if beds_total else None,
                "collection_rate_pct": ratio_pct(_per_property(collected, pid), _per_property(billed, pid)),
                "outstanding_amount": money(_per_property(outstanding, pid)),
                "open_maintenance": int(_per_property(open_maint, pid)),
                "sla_compliance_pct": maintenance_svc.sla_compliance_pct(
                    resolved[resolved["property_id"] == pid]
                ),
                "open_complaints": int(_per_property(open_complaints, pid)),
            }
        )

    return PropertyPerformanceReport(
        meta=meta,
        current_month=str(current_month.first_month),
        sort_by=sort_by,
        order=order,
        properties=rank_rows(rows, sort_by, order),
    )
