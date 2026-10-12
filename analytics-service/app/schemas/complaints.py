from __future__ import annotations

from app.schemas.common import ApiModel, KeyCount, ReportMeta


class ComplaintSummary(ApiModel):
    created_in_period: int = 0
    resolved_in_period: int = 0
    open_now: int = 0
    overdue_open_now: int = 0
    escalated_in_period: int = 0
    escalation_rate_pct: float | None = None
    avg_resolution_hours: float | None = None
    median_resolution_hours: float | None = None


class ComplaintMonth(ApiModel):
    month: str
    created: int
    resolved: int
    escalated: int
    avg_resolution_hours: float | None


class ComplaintReport(ApiModel):
    meta: ReportMeta
    summary: ComplaintSummary
    by_status: list[KeyCount]
    by_category: list[KeyCount]
    by_priority: list[KeyCount]
    by_escalation_level: list[KeyCount]
    monthly: list[ComplaintMonth]
