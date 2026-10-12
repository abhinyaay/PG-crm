"""Tenant lifecycle analytics (pure pandas).

A *stay* is the union of a tenant's allocation chain: a room transfer ends one allocation and starts
the next on the same day, so touching/overlapping allocations of the same tenant are merged into one
stay. A gap of at least one day starts a new stay (the tenant really left and came back).
"""

from __future__ import annotations

from datetime import date

import pandas as pd

from app.schemas.common import KeyCount, ReportMeta
from app.schemas.tenants import StayDurationStats, TenantMonth, TenantReport, TenantSummary
from app.services.common import (
    FAR_FUTURE,
    count_by,
    day,
    ensure_columns,
    ratio_pct,
    safe_mean,
    safe_median,
    to_day,
)
from app.services.period import ReportPeriod

TENANT_STATUSES = ("PROSPECTIVE", "ACTIVE", "ON_NOTICE", "MOVED_OUT")
ACTIVE_STATUSES = ("ACTIVE", "ON_NOTICE")
GENDERS = ("MALE", "FEMALE", "OTHER", "UNKNOWN")
TENANT_COLUMNS = ("id", "property_id", "status", "gender")
ALLOCATION_COLUMNS = ("id", "tenant_id", "property_id", "status", "start_date", "end_date")
STAY_COLUMNS = ("tenant_id", "stay_start", "stay_end", "ongoing", "allocation_count")


def build_stays(allocations: pd.DataFrame) -> pd.DataFrame:
    """Collapse allocations into stays.

    Returns one row per stay: ``tenant_id, stay_start, stay_end (NaT if ongoing), ongoing,
    allocation_count``. ``stay_end`` is exclusive (= the check-out date).
    """
    a = ensure_columns(allocations, ALLOCATION_COLUMNS)
    a = a.assign(start=to_day(a["start_date"]), end=to_day(a["end_date"]).fillna(FAR_FUTURE))
    a = a[a["start"].notna() & a["tenant_id"].notna()]
    if a.empty:
        return pd.DataFrame(
            {
                "tenant_id": pd.Series(dtype="int64"),
                "stay_start": pd.Series(dtype="datetime64[ns]"),
                "stay_end": pd.Series(dtype="datetime64[ns]"),
                "ongoing": pd.Series(dtype=bool),
                "allocation_count": pd.Series(dtype="int64"),
            }
        )
    a = a.sort_values(["tenant_id", "start", "end"]).reset_index(drop=True)
    running_end = a.groupby("tenant_id")["end"].cummax()
    previous_end = running_end.groupby(a["tenant_id"]).shift()
    new_stay = previous_end.isna() | (a["start"] > previous_end)
    a["stay_no"] = new_stay.astype(int).groupby(a["tenant_id"]).cumsum()
    stays = a.groupby(["tenant_id", "stay_no"], as_index=False).agg(
        stay_start=("start", "min"), stay_end=("end", "max"), allocation_count=("start", "size")
    )
    stays["ongoing"] = stays["stay_end"] >= FAR_FUTURE
    stays.loc[stays["ongoing"], "stay_end"] = pd.NaT
    return stays[list(STAY_COLUMNS)]


def monthly_movements(stays: pd.DataFrame, months: list[pd.Period], today: date) -> list[TenantMonth]:
    end_filled = stays["stay_end"].fillna(FAR_FUTURE)
    result = []
    for m in months:
        ms = m.start_time.normalize()
        me = (m + 1).start_time.normalize()
        move_ins = int(((stays["stay_start"] >= ms) & (stays["stay_start"] < me)).sum())
        move_outs = int(((stays["stay_end"] >= ms) & (stays["stay_end"] < me)).sum())
        if ms > day(today):
            active_at_start = 0
        else:
            # in residence on the night before the month began: start < ms <= end (end exclusive)
            active_at_start = int(((stays["stay_start"] < ms) & (end_filled >= ms)).sum())
        result.append(
            TenantMonth(
                month=str(m),
                move_ins=move_ins,
                move_outs=move_outs,
                active_at_month_start=active_at_start,
                churn_rate_pct=ratio_pct(move_outs, active_at_start),
            )
        )
    return result


def stay_duration_stats(stays: pd.DataFrame, period: ReportPeriod, today: date) -> StayDurationStats:
    """Completed stays = stays that ended (checked out) inside the period. Ongoing stays are measured
    from their start to ``today``."""
    start, end = day(period.start_date), day(period.end_date)
    completed = stays[(~stays["ongoing"]) & (stays["stay_end"] >= start) & (stays["stay_end"] < end)]
    completed_days = (completed["stay_end"] - completed["stay_start"]).dt.days.astype(float)
    ongoing = stays[stays["ongoing"] & (stays["stay_start"] <= day(today))]
    ongoing_days = (day(today) - ongoing["stay_start"]).dt.days.astype(float)
    combined = pd.concat([completed_days, ongoing_days], ignore_index=True)
    return StayDurationStats(
        completed_stays=int(len(completed_days)),
        average_completed_stay_days=safe_mean(completed_days),
        median_completed_stay_days=safe_median(completed_days),
        stays_including_ongoing=int(len(combined)),
        average_stay_days_including_ongoing=safe_mean(combined),
        median_stay_days_including_ongoing=safe_median(combined),
    )


def _key_counts(counts: dict[str, int]) -> list[KeyCount]:
    total = sum(counts.values())
    return [KeyCount(key=k, count=v, share_pct=ratio_pct(v, total)) for k, v in counts.items()]


def build_tenant_report(
    tenants: pd.DataFrame,
    allocations: pd.DataFrame,
    *,
    period: ReportPeriod,
    today: date,
    meta: ReportMeta,
) -> TenantReport:
    t = ensure_columns(tenants, TENANT_COLUMNS)
    status_counts = count_by(t["status"], TENANT_STATUSES)
    active = t[t["status"].isin(ACTIVE_STATUSES)]
    gender_counts = count_by(active["gender"], GENDERS, other_key="UNKNOWN")

    stays = build_stays(allocations)
    monthly = monthly_movements(stays, period.months, today)
    a = ensure_columns(allocations, ALLOCATION_COLUMNS)
    currently_allocated = int(a.loc[a["status"] == "ACTIVE", "tenant_id"].nunique())

    summary = TenantSummary(
        total_tenants=int(len(t)),
        active_tenants=int(len(active)),
        currently_allocated_tenants=currently_allocated,
        move_ins_in_period=sum(m.move_ins for m in monthly),
        move_outs_in_period=sum(m.move_outs for m in monthly),
    )
    return TenantReport(
        meta=meta,
        summary=summary,
        by_status=_key_counts(status_counts),
        gender_mix=_key_counts(gender_counts),
        stay_duration=stay_duration_stats(stays, period, today),
        monthly=monthly,
    )
