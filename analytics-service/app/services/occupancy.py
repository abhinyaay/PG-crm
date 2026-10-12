"""Occupancy analytics (pure pandas)."""

from __future__ import annotations

from datetime import date

import pandas as pd

from app.schemas.common import ReportMeta
from app.schemas.occupancy import (
    OccupancyReport,
    OccupancySnapshot,
    OccupancyTrendPoint,
    PropertyOccupancy,
    RoomTypeUtilization,
)
from app.services.common import FAR_FUTURE, day, ensure_columns, ratio_pct, round_or_none, to_day
from app.services.period import ReportPeriod

BED_STATUSES = ("AVAILABLE", "OCCUPIED", "RESERVED", "MAINTENANCE")
BED_COLUMNS = ("bed_id", "room_id", "property_id", "property_name", "status", "room_type")
ALLOCATION_COLUMNS = ("id", "bed_id", "property_id", "start_date", "end_date")
ROOM_TYPE_ORDER = ("SINGLE", "DOUBLE", "TRIPLE", "QUAD", "DORMITORY")


def bed_status_snapshot(beds: pd.DataFrame) -> OccupancySnapshot:
    counts = beds["status"].value_counts() if len(beds) else pd.Series(dtype=int)
    occupied = int(counts.get("OCCUPIED", 0))
    reserved = int(counts.get("RESERVED", 0))
    available = int(counts.get("AVAILABLE", 0))
    maintenance = int(counts.get("MAINTENANCE", 0))
    total = int(len(beds))
    operational = total - maintenance
    return OccupancySnapshot(
        total_beds=total,
        occupied_beds=occupied,
        reserved_beds=reserved,
        available_beds=available,
        maintenance_beds=maintenance,
        occupancy_rate_pct=ratio_pct(occupied, operational),
        raw_occupancy_rate_pct=ratio_pct(occupied, total),
        vacancy_rate_pct=ratio_pct(available, operational),
    )


def property_occupancy(beds: pd.DataFrame) -> list[PropertyOccupancy]:
    rows: list[PropertyOccupancy] = []
    if beds.empty:
        return rows
    for (property_id, name), group in beds.groupby(["property_id", "property_name"], sort=False):
        snap = bed_status_snapshot(group)
        rows.append(
            PropertyOccupancy(property_id=int(property_id), property_name=str(name), **snap.model_dump())
        )
    rows.sort(key=lambda r: (-(r.occupancy_rate_pct or 0.0), r.property_name))
    return rows


def room_type_utilization(beds: pd.DataFrame) -> list[RoomTypeUtilization]:
    if beds.empty:
        return []
    b = beds.assign(
        is_occupied=(beds["status"] == "OCCUPIED").astype(int),
        is_maintenance=(beds["status"] == "MAINTENANCE").astype(int),
    )
    rooms = b.groupby(["room_type", "room_id"], as_index=False).agg(
        beds=("bed_id", "count"), occupied=("is_occupied", "sum"), maintenance=("is_maintenance", "sum")
    )
    rooms["fully_rooms"] = (rooms["occupied"] == rooms["beds"]).astype(int)
    rooms["empty_rooms"] = (rooms["occupied"] == 0).astype(int)
    rooms["partial_rooms"] = 1 - rooms["fully_rooms"] - rooms["empty_rooms"]
    summary = (
        rooms.groupby("room_type")
        .agg(
            rooms=("room_id", "count"),
            total_beds=("beds", "sum"),
            occupied=("occupied", "sum"),
            maintenance=("maintenance", "sum"),
            fully_rooms=("fully_rooms", "sum"),
            partial_rooms=("partial_rooms", "sum"),
            empty_rooms=("empty_rooms", "sum"),
        )
        .reset_index()
    )
    order = {name: i for i, name in enumerate(ROOM_TYPE_ORDER)}
    result = [
        RoomTypeUtilization(
            room_type=str(row.room_type),
            rooms=int(row.rooms),
            total_beds=int(row.total_beds),
            occupied_beds=int(row.occupied),
            maintenance_beds=int(row.maintenance),
            utilization_pct=ratio_pct(row.occupied, row.total_beds - row.maintenance),
            fully_occupied_rooms=int(row.fully_rooms),
            partially_occupied_rooms=int(row.partial_rooms),
            empty_rooms=int(row.empty_rooms),
        )
        for row in summary.itertuples(index=False)
    ]
    result.sort(key=lambda r: (order.get(r.room_type, len(order)), r.room_type))
    return result


def monthly_bed_day_occupancy(
    allocations: pd.DataFrame, bed_count: int, months: list[pd.Period], today: date
) -> list[OccupancyTrendPoint]:
    """Occupied bed-days / available bed-days per month.

    * An allocation occupies a bed on every day ``d`` with ``start_date <= d < end_date``
      (``end_date`` NULL = still occupied).
    * Days after ``today`` are never counted (the current month is measured month-to-date).
    * Denominator = ``bed_count`` (the CURRENT bed inventory) x days counted. Historical bed
      additions/removals are not versioned in the read model, so this is an approximation; the rate is
      capped at 100%.
    """
    a = ensure_columns(allocations, ALLOCATION_COLUMNS)
    starts = to_day(a["start_date"])
    ends = to_day(a["end_date"]).fillna(FAR_FUTURE)
    valid = starts.notna()
    starts, ends = starts[valid], ends[valid]
    horizon = day(today) + pd.Timedelta(days=1)

    points: list[OccupancyTrendPoint] = []
    for month in months:
        window_start = month.start_time.normalize()
        window_end = min((month + 1).start_time.normalize(), horizon)
        days = max((window_end - window_start).days, 0)
        if days == 0:
            points.append(
                OccupancyTrendPoint(
                    month=str(month),
                    days_counted=0,
                    occupied_bed_days=0,
                    available_bed_days=0,
                    occupancy_rate_pct=None,
                    average_occupied_beds=None,
                )
            )
            continue
        overlap = (ends.clip(upper=window_end) - starts.clip(lower=window_start)).dt.days.clip(lower=0)
        occupied = int(overlap.sum()) if len(overlap) else 0
        available = int(bed_count) * days
        rate = ratio_pct(occupied, available)
        points.append(
            OccupancyTrendPoint(
                month=str(month),
                days_counted=days,
                occupied_bed_days=occupied,
                available_bed_days=available,
                occupancy_rate_pct=min(rate, 100.0) if rate is not None else None,
                average_occupied_beds=round_or_none(occupied / days),
            )
        )
    return points


def build_occupancy_report(
    beds: pd.DataFrame,
    allocations: pd.DataFrame,
    *,
    period: ReportPeriod,
    today: date,
    meta: ReportMeta,
) -> OccupancyReport:
    beds = ensure_columns(beds, BED_COLUMNS)
    snapshot = bed_status_snapshot(beds)
    return OccupancyReport(
        meta=meta,
        snapshot=snapshot,
        by_property=property_occupancy(beds),
        by_room_type=room_type_utilization(beds),
        monthly_trend=monthly_bed_day_occupancy(allocations, snapshot.total_beds, period.months, today),
    )
