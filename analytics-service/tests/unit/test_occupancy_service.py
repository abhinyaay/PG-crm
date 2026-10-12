from datetime import date

import pandas as pd

from app.services.occupancy import (
    bed_status_snapshot,
    build_occupancy_report,
    monthly_bed_day_occupancy,
    room_type_utilization,
)
from tests.helpers import frame, meta, period

BED_COLS = ["bed_id", "room_id", "property_id", "property_name", "status", "room_type"]
ALLOC_COLS = ["id", "bed_id", "property_id", "start_date", "end_date"]


def _beds(statuses, room_type="DOUBLE", property_id=1, name="Alpha"):
    return frame(
        BED_COLS,
        [(i + 1, 100 + i // 2, property_id, name, s, room_type) for i, s in enumerate(statuses)],
    )


def test_snapshot_rates():
    beds = _beds(["OCCUPIED"] * 6 + ["RESERVED"] + ["AVAILABLE"] * 2 + ["MAINTENANCE"])
    snap = bed_status_snapshot(beds)
    assert (snap.total_beds, snap.occupied_beds, snap.reserved_beds) == (10, 6, 1)
    assert (snap.available_beds, snap.maintenance_beds) == (2, 1)
    assert snap.occupancy_rate_pct == 66.67  # 6 / (10 - 1)
    assert snap.raw_occupancy_rate_pct == 60.0  # 6 / 10
    assert snap.vacancy_rate_pct == 22.22  # 2 / 9


def test_all_beds_in_maintenance_gives_null_rates_not_nan():
    snap = bed_status_snapshot(_beds(["MAINTENANCE", "MAINTENANCE"]))
    assert snap.occupancy_rate_pct is None
    assert snap.raw_occupancy_rate_pct == 0.0


def test_room_type_utilization():
    beds = frame(
        BED_COLS,
        [
            (1, 10, 1, "Alpha", "OCCUPIED", "DOUBLE"),
            (2, 10, 1, "Alpha", "OCCUPIED", "DOUBLE"),
            (3, 11, 1, "Alpha", "OCCUPIED", "DOUBLE"),
            (4, 11, 1, "Alpha", "AVAILABLE", "DOUBLE"),
            (5, 12, 1, "Alpha", "AVAILABLE", "SINGLE"),
        ],
    )
    result = {r.room_type: r for r in room_type_utilization(beds)}
    assert list(result) == ["SINGLE", "DOUBLE"]  # canonical room-type order
    double = result["DOUBLE"]
    assert (double.rooms, double.total_beds, double.occupied_beds) == (2, 4, 3)
    assert double.utilization_pct == 75.0
    assert (double.fully_occupied_rooms, double.partially_occupied_rooms, double.empty_rooms) == (1, 1, 0)
    assert result["SINGLE"].utilization_pct == 0.0
    assert result["SINGLE"].empty_rooms == 1


def test_bed_days_across_month_boundaries():
    allocations = frame(
        ALLOC_COLS,
        [
            (1, 1, 1, date(2026, 1, 20), date(2026, 2, 10)),  # Jan: 20..31 = 12d, Feb: 1..9 = 9d
            (2, 2, 1, date(2026, 2, 1), None),  # Feb: 28d (ongoing)
        ],
    )
    points = monthly_bed_day_occupancy(
        allocations, 2, period("2026-01", "2026-02").months, today=date(2026, 3, 15)
    )
    jan, feb = points
    assert (jan.occupied_bed_days, jan.available_bed_days, jan.days_counted) == (12, 62, 31)
    assert jan.occupancy_rate_pct == 19.35
    assert (feb.occupied_bed_days, feb.available_bed_days) == (37, 56)
    assert feb.occupancy_rate_pct == 66.07
    assert feb.average_occupied_beds == 1.32


def test_current_month_is_counted_to_today_and_future_months_are_empty():
    allocations = frame(ALLOC_COLS, [(1, 1, 1, date(2026, 2, 1), None)])
    mar, apr = monthly_bed_day_occupancy(
        allocations, 1, period("2026-03", "2026-04").months, today=date(2026, 3, 10)
    )
    assert (mar.days_counted, mar.occupied_bed_days, mar.occupancy_rate_pct) == (10, 10, 100.0)
    assert (apr.days_counted, apr.available_bed_days, apr.occupancy_rate_pct) == (0, 0, None)


def test_rate_capped_at_100_when_current_beds_fewer_than_history():
    allocations = frame(
        ALLOC_COLS,
        [(1, 1, 1, date(2026, 1, 1), None), (2, 2, 1, date(2026, 1, 1), None)],
    )
    (jan,) = monthly_bed_day_occupancy(allocations, 1, period("2026-01", "2026-01").months, date(2026, 5, 1))
    assert jan.occupied_bed_days == 62
    assert jan.occupancy_rate_pct == 100.0


def test_same_day_allocation_contributes_nothing():
    allocations = frame(ALLOC_COLS, [(1, 1, 1, date(2026, 1, 5), date(2026, 1, 5))])
    (jan,) = monthly_bed_day_occupancy(allocations, 1, period("2026-01", "2026-01").months, date(2026, 5, 1))
    assert jan.occupied_bed_days == 0


def test_empty_data_report():
    report = build_occupancy_report(
        pd.DataFrame(columns=BED_COLS),
        pd.DataFrame(columns=ALLOC_COLS),
        period=period("2026-01", "2026-02"),
        today=date(2026, 3, 1),
        meta=meta(),
    )
    assert report.snapshot.total_beds == 0
    assert report.snapshot.occupancy_rate_pct is None
    assert report.by_property == []
    assert report.by_room_type == []
    assert [p.occupancy_rate_pct for p in report.monthly_trend] == [None, None]
    assert "NaN" not in report.model_dump_json()


def test_full_report_by_property_sorted_by_occupancy():
    beds = pd.concat(
        [
            _beds(["OCCUPIED", "AVAILABLE"], property_id=1, name="Alpha"),
            _beds(["OCCUPIED"], property_id=2, name="Beta"),
        ]
    )
    report = build_occupancy_report(
        beds,
        pd.DataFrame(columns=ALLOC_COLS),
        period=period("2026-01", "2026-01"),
        today=date(2026, 3, 1),
        meta=meta(),
    )
    assert [p.property_name for p in report.by_property] == ["Beta", "Alpha"]
    assert report.by_property[1].occupancy_rate_pct == 50.0
    assert report.snapshot.total_beds == 3
