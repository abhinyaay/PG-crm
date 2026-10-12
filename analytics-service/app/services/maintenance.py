"""Maintenance analytics (pure pandas)."""

from __future__ import annotations

from datetime import datetime

import pandas as pd

from app.schemas.common import ReportMeta
from app.schemas.maintenance import (
    CategoryResolution,
    MaintenanceMonth,
    MaintenanceReport,
    MaintenanceSummary,
)
from app.services.common import ensure_columns, ratio_pct, safe_mean, safe_median, to_utc
from app.services.period import ReportPeriod
from app.services.tickets import add_ticket_columns, key_counts

STATUSES = ("OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED")
OPEN_STATUSES = ("OPEN", "ASSIGNED", "IN_PROGRESS")
PRIORITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
CATEGORIES = (
    "ELECTRICAL",
    "PLUMBING",
    "CARPENTRY",
    "CLEANING",
    "APPLIANCE",
    "INTERNET",
    "PEST_CONTROL",
    "OTHER",
)
COLUMNS = (
    "id",
    "property_id",
    "category",
    "priority",
    "status",
    "created_at",
    "resolved_at",
    "closed_at",
    "sla_due_at",
    "sla_breached",
)


def prepare_requests(requests: pd.DataFrame, timezone: str) -> pd.DataFrame:
    df = add_ticket_columns(ensure_columns(requests, COLUMNS), timezone)
    df["sla_due_ts"] = to_utc(df["sla_due_at"])
    flag = df["sla_breached"].eq(True)
    late = df["resolved_ts"].notna() & df["sla_due_ts"].notna() & (df["resolved_ts"] > df["sla_due_ts"])
    df["breached"] = flag | late
    return df


def resolved_in(df: pd.DataFrame, months: list[pd.Period]) -> pd.DataFrame:
    """Requests resolved inside ``months`` (cancelled requests excluded)."""
    return df[df["resolved_ts"].notna() & (df["status"] != "CANCELLED") & df["resolved_month"].isin(months)]


def sla_compliance_pct(resolved: pd.DataFrame) -> float | None:
    return ratio_pct(int((~resolved["breached"]).sum()), len(resolved))


def open_breached_count(df: pd.DataFrame, now: datetime) -> int:
    open_now = df[df["status"].isin(OPEN_STATUSES)]
    overdue = open_now["sla_due_ts"].notna() & (open_now["sla_due_ts"] < pd.Timestamp(now))
    return int((open_now["sla_breached"].eq(True) | overdue).sum())


def build_maintenance_report(
    requests: pd.DataFrame, *, period: ReportPeriod, now: datetime, meta: ReportMeta
) -> MaintenanceReport:
    months = period.months
    df = prepare_requests(requests, period.timezone)
    created = df[df["created_month"].isin(months)]
    resolved = resolved_in(df, months)

    summary = MaintenanceSummary(
        created_in_period=int(len(created)),
        resolved_in_period=int(len(resolved)),
        open_now=int(df["status"].isin(OPEN_STATUSES).sum()),
        breached_open_now=open_breached_count(df, now),
        avg_resolution_hours=safe_mean(resolved["resolution_hours"]),
        median_resolution_hours=safe_median(resolved["resolution_hours"]),
        sla_compliance_pct=sla_compliance_pct(resolved),
    )

    monthly = []
    for m in months:
        res_m = resolved[resolved["resolved_month"] == m]
        monthly.append(
            MaintenanceMonth(
                month=str(m),
                created=int((created["created_month"] == m).sum()),
                resolved=int(len(res_m)),
                avg_resolution_hours=safe_mean(res_m["resolution_hours"]),
                sla_compliance_pct=sla_compliance_pct(res_m),
            )
        )

    by_category = []
    categories = list(CATEGORIES) + sorted(set(resolved["category"].dropna()) - set(CATEGORIES))
    for category in categories:
        rows = resolved[resolved["category"] == category]
        by_category.append(
            CategoryResolution(
                category=category,
                resolved=int(len(rows)),
                avg_resolution_hours=safe_mean(rows["resolution_hours"]),
                median_resolution_hours=safe_median(rows["resolution_hours"]),
                sla_compliance_pct=sla_compliance_pct(rows),
            )
        )

    return MaintenanceReport(
        meta=meta,
        summary=summary,
        by_status=key_counts(created["status"], STATUSES),
        by_category=key_counts(created["category"], CATEGORIES),
        by_priority=key_counts(created["priority"], PRIORITIES),
        resolution_by_category=by_category,
        monthly=monthly,
    )
