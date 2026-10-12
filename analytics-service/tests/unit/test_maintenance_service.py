import pandas as pd

from app.services.maintenance import build_maintenance_report
from tests.helpers import frame, meta, period, ts

COLS = [
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
]
NOW = ts("2026-03-15T06:00").to_pydatetime()


def _requests():
    return frame(
        COLS,
        [
            # resolved in 10h, within SLA
            (
                1,
                1,
                "PLUMBING",
                "HIGH",
                "RESOLVED",
                ts("2026-01-10T00:00"),
                ts("2026-01-10T10:00"),
                None,
                ts("2026-01-10T12:00"),
                False,
            ),
            # resolved in 30h, after SLA although the flag was never set -> breached
            (
                2,
                1,
                "ELECTRICAL",
                "CRITICAL",
                "CLOSED",
                ts("2026-01-20T00:00"),
                ts("2026-01-21T06:00"),
                ts("2026-01-22T00:00"),
                ts("2026-01-21T00:00"),
                False,
            ),
            # resolved in 20h, flagged breached
            (
                3,
                2,
                "PLUMBING",
                "LOW",
                "RESOLVED",
                ts("2026-02-01T00:00"),
                ts("2026-02-01T20:00"),
                None,
                ts("2026-02-03T00:00"),
                True,
            ),
            # open and past SLA due
            (
                4,
                1,
                "CLEANING",
                "MEDIUM",
                "OPEN",
                ts("2026-03-01T00:00"),
                None,
                None,
                ts("2026-03-02T00:00"),
                False,
            ),
            # open, created before the period, SLA not yet due
            (
                5,
                2,
                "INTERNET",
                "LOW",
                "IN_PROGRESS",
                ts("2025-12-20T00:00"),
                None,
                None,
                ts("2026-04-01T00:00"),
                False,
            ),
            (
                6,
                1,
                "OTHER",
                "LOW",
                "CANCELLED",
                ts("2026-02-10T00:00"),
                None,
                None,
                ts("2026-02-12T00:00"),
                False,
            ),
            # open and flagged breached
            (
                7,
                2,
                "APPLIANCE",
                "HIGH",
                "ASSIGNED",
                ts("2026-03-10T00:00"),
                None,
                None,
                ts("2026-03-11T00:00"),
                True,
            ),
        ],
    )


def test_summary_counts_resolution_and_sla():
    report = build_maintenance_report(_requests(), period=period("2026-01", "2026-03"), now=NOW, meta=meta())
    s = report.summary
    assert (s.created_in_period, s.resolved_in_period, s.open_now, s.breached_open_now) == (6, 3, 3, 2)
    assert s.avg_resolution_hours == 20.0
    assert s.median_resolution_hours == 20.0
    assert s.sla_compliance_pct == 33.33


def test_breakdowns_only_count_requests_created_in_period():
    report = build_maintenance_report(_requests(), period=period("2026-01", "2026-03"), now=NOW, meta=meta())
    status = {k.key: k.count for k in report.by_status}
    assert status == {"OPEN": 1, "ASSIGNED": 1, "IN_PROGRESS": 0, "RESOLVED": 2, "CLOSED": 1, "CANCELLED": 1}
    category = {k.key: k.count for k in report.by_category}
    assert category["PLUMBING"] == 2
    assert category["INTERNET"] == 0  # created before the period
    priority = {k.key: k.count for k in report.by_priority}
    assert priority == {"LOW": 2, "MEDIUM": 1, "HIGH": 2, "CRITICAL": 1}


def test_monthly_trend_and_per_category_resolution():
    report = build_maintenance_report(_requests(), period=period("2026-01", "2026-03"), now=NOW, meta=meta())
    jan, feb, mar = report.monthly
    assert (jan.created, jan.resolved, jan.avg_resolution_hours, jan.sla_compliance_pct) == (2, 2, 20.0, 50.0)
    assert (feb.created, feb.resolved, feb.sla_compliance_pct) == (2, 1, 0.0)
    assert (mar.created, mar.resolved, mar.avg_resolution_hours) == (2, 0, None)
    by_cat = {c.category: c for c in report.resolution_by_category}
    assert (by_cat["PLUMBING"].resolved, by_cat["PLUMBING"].avg_resolution_hours) == (2, 15.0)
    assert by_cat["PLUMBING"].sla_compliance_pct == 50.0
    assert (by_cat["ELECTRICAL"].avg_resolution_hours, by_cat["ELECTRICAL"].sla_compliance_pct) == (30.0, 0.0)
    assert by_cat["CARPENTRY"].avg_resolution_hours is None


def test_empty_maintenance():
    report = build_maintenance_report(
        pd.DataFrame(columns=COLS), period=period("2026-01", "2026-02"), now=NOW, meta=meta()
    )
    assert report.summary.created_in_period == 0
    assert report.summary.sla_compliance_pct is None
    assert report.summary.avg_resolution_hours is None
    assert len(report.monthly) == 2
    assert "NaN" not in report.model_dump_json()
