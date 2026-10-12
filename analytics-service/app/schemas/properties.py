from __future__ import annotations

from typing import Literal

from app.schemas.common import ApiModel, ReportMeta

PropertySortKey = Literal[
    "name",
    "occupancyRate",
    "totalBeds",
    "activeTenants",
    "currentMonthRevenue",
    "revenuePerBed",
    "collectionRate",
    "outstanding",
    "openMaintenance",
    "slaCompliance",
    "openComplaints",
]
SortOrder = Literal["asc", "desc"]


class PropertyPerformance(ApiModel):
    rank: int
    property_id: int
    property_code: str | None
    property_name: str
    city: str | None
    status: str | None
    total_beds: int
    occupied_beds: int
    occupancy_rate_pct: float | None
    active_tenants: int
    current_month_revenue: float
    revenue_per_bed: float | None
    collection_rate_pct: float | None
    outstanding_amount: float
    open_maintenance: int
    sla_compliance_pct: float | None
    open_complaints: int


class PropertyPerformanceReport(ApiModel):
    meta: ReportMeta
    current_month: str
    sort_by: PropertySortKey
    order: SortOrder
    properties: list[PropertyPerformance]
