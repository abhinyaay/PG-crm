from __future__ import annotations

from app.schemas.common import ApiModel, KeyCount, ReportMeta


class MaintenanceSummary(ApiModel):
    created_in_period: int = 0
    resolved_in_period: int = 0
    open_now: int = 0
    breached_open_now: int = 0
    avg_resolution_hours: float | None = None
    median_resolution_hours: float | None = None
    sla_compliance_pct: float | None = None


class MaintenanceMonth(ApiModel):
    month: str
    created: int
    resolved: int
    avg_resolution_hours: float | None
    sla_compliance_pct: float | None


class CategoryResolution(ApiModel):
    category: str
    resolved: int
    avg_resolution_hours: float | None
    median_resolution_hours: float | None
    sla_compliance_pct: float | None


class MaintenanceReport(ApiModel):
    meta: ReportMeta
    summary: MaintenanceSummary
    by_status: list[KeyCount]
    by_category: list[KeyCount]
    by_priority: list[KeyCount]
    resolution_by_category: list[CategoryResolution]
    monthly: list[MaintenanceMonth]
