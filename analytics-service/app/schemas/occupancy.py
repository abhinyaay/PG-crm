from __future__ import annotations

from pydantic import Field

from app.schemas.common import ApiModel, ReportMeta


class OccupancySnapshot(ApiModel):
    total_beds: int = 0
    occupied_beds: int = 0
    reserved_beds: int = 0
    available_beds: int = 0
    maintenance_beds: int = 0
    occupancy_rate_pct: float | None = Field(
        default=None, description="occupied / (total - maintenance) * 100; null when no operational beds"
    )
    raw_occupancy_rate_pct: float | None = Field(
        default=None, description="occupied / total * 100; null when there are no beds"
    )
    vacancy_rate_pct: float | None = Field(
        default=None, description="available / (total - maintenance) * 100; null when no operational beds"
    )


class PropertyOccupancy(OccupancySnapshot):
    property_id: int
    property_name: str


class RoomTypeUtilization(ApiModel):
    room_type: str
    rooms: int
    total_beds: int
    occupied_beds: int
    maintenance_beds: int
    utilization_pct: float | None
    fully_occupied_rooms: int
    partially_occupied_rooms: int
    empty_rooms: int


class OccupancyTrendPoint(ApiModel):
    month: str
    days_counted: int
    occupied_bed_days: int
    available_bed_days: int
    occupancy_rate_pct: float | None
    average_occupied_beds: float | None


class OccupancyReport(ApiModel):
    meta: ReportMeta
    snapshot: OccupancySnapshot
    by_property: list[PropertyOccupancy]
    by_room_type: list[RoomTypeUtilization]
    monthly_trend: list[OccupancyTrendPoint]
