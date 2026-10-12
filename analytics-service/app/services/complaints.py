"""Complaint analytics (pure pandas)."""

from __future__ import annotations

from datetime import datetime

import pandas as pd

from app.schemas.common import ReportMeta
from app.schemas.complaints import ComplaintMonth, ComplaintReport, ComplaintSummary
from app.services.common import ensure_columns, ratio_pct, safe_mean, safe_median, to_utc
from app.services.period import ReportPeriod
from app.services.tickets import add_ticket_columns, key_counts

STATUSES = ("OPEN", "IN_PROGRESS", "ESCALATED", "RESOLVED", "CLOSED")
OPEN_STATUSES = ("OPEN", "IN_PROGRESS", "ESCALATED")
PRIORITIES = ("LOW", "MEDIUM", "HIGH", "CRITICAL")
CATEGORIES = ("NOISE", "CLEANLINESS", "FOOD", "STAFF_BEHAVIOUR", "SECURITY", "BILLING", "ROOMMATE", "OTHER")
ESCALATION_LEVELS = ("0", "1", "2", "3")
COLUMNS = (
    "id",
    "property_id",
    "category",
    "priority",
    "status",
    "escalation_level",
    "created_at",
    "resolved_at",
    "closed_at",
    "due_at",
)


def prepare_complaints(complaints: pd.DataFrame, timezone: str) -> pd.DataFrame:
    df = add_ticket_columns(ensure_columns(complaints, COLUMNS), timezone)
    df["level"] = pd.to_numeric(df["escalation_level"], errors="coerce").fillna(0).astype(int)
    df["due_ts"] = to_utc(df["due_at"])
    return df


def overdue_open_count(df: pd.DataFrame, now: datetime) -> int:
    open_now = df[df["status"].isin(OPEN_STATUSES)]
    return int((open_now["due_ts"].notna() & (open_now["due_ts"] < pd.Timestamp(now))).sum())


def build_complaint_report(
    complaints: pd.DataFrame, *, period: ReportPeriod, now: datetime, meta: ReportMeta
) -> ComplaintReport:
    months = period.months
    df = prepare_complaints(complaints, period.timezone)
    created = df[df["created_month"].isin(months)]
    resolved = df[df["resolved_ts"].notna() & df["resolved_month"].isin(months)]
    escalated = created[created["level"] > 0]

    summary = ComplaintSummary(
        created_in_period=int(len(created)),
        resolved_in_period=int(len(resolved)),
        open_now=int(df["status"].isin(OPEN_STATUSES).sum()),
        overdue_open_now=overdue_open_count(df, now),
        escalated_in_period=int(len(escalated)),
        escalation_rate_pct=ratio_pct(len(escalated), len(created)),
        avg_resolution_hours=safe_mean(resolved["resolution_hours"]),
        median_resolution_hours=safe_median(resolved["resolution_hours"]),
    )
    monthly = []
    for m in months:
        res_m = resolved[resolved["resolved_month"] == m]
        monthly.append(
            ComplaintMonth(
                month=str(m),
                created=int((created["created_month"] == m).sum()),
                resolved=int(len(res_m)),
                escalated=int((escalated["created_month"] == m).sum()),
                avg_resolution_hours=safe_mean(res_m["resolution_hours"]),
            )
        )
    return ComplaintReport(
        meta=meta,
        summary=summary,
        by_status=key_counts(created["status"], STATUSES),
        by_category=key_counts(created["category"], CATEGORIES),
        by_priority=key_counts(created["priority"], PRIORITIES),
        by_escalation_level=key_counts(created["level"].astype(str), ESCALATION_LEVELS),
        monthly=monthly,
    )
