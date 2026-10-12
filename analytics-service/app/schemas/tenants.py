from __future__ import annotations

from app.schemas.common import ApiModel, KeyCount, ReportMeta


class StayDurationStats(ApiModel):
    completed_stays: int = 0
    average_completed_stay_days: float | None = None
    median_completed_stay_days: float | None = None
    stays_including_ongoing: int = 0
    average_stay_days_including_ongoing: float | None = None
    median_stay_days_including_ongoing: float | None = None


class TenantMonth(ApiModel):
    month: str
    move_ins: int
    move_outs: int
    active_at_month_start: int
    churn_rate_pct: float | None


class TenantSummary(ApiModel):
    total_tenants: int = 0
    active_tenants: int = 0
    currently_allocated_tenants: int = 0
    move_ins_in_period: int = 0
    move_outs_in_period: int = 0


class TenantReport(ApiModel):
    meta: ReportMeta
    summary: TenantSummary
    by_status: list[KeyCount]
    gender_mix: list[KeyCount]
    stay_duration: StayDurationStats
    monthly: list[TenantMonth]
