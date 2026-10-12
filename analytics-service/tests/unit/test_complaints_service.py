import pandas as pd

from app.services.complaints import build_complaint_report
from tests.helpers import frame, meta, period, ts

COLS = [
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
]
NOW = ts("2026-03-15T06:00").to_pydatetime()


def _complaints():
    return frame(
        COLS,
        [
            (
                1,
                1,
                "NOISE",
                "HIGH",
                "RESOLVED",
                0,
                ts("2026-01-05T00:00"),
                ts("2026-01-06T00:00"),
                None,
                ts("2026-01-07T00:00"),
            ),
            # closed without resolved_at -> closed_at is used (12h)
            (
                2,
                1,
                "FOOD",
                "MEDIUM",
                "CLOSED",
                2,
                ts("2026-01-10T00:00"),
                None,
                ts("2026-01-10T12:00"),
                ts("2026-01-12T00:00"),
            ),
            (
                3,
                2,
                "BILLING",
                "LOW",
                "ESCALATED",
                1,
                ts("2026-02-01T00:00"),
                None,
                None,
                ts("2026-02-03T00:00"),
            ),
            (4, 2, "OTHER", "LOW", "OPEN", 0, ts("2026-03-14T00:00"), None, None, ts("2026-03-20T00:00")),
            # open since before the period and overdue
            (
                5,
                1,
                "SECURITY",
                "CRITICAL",
                "OPEN",
                0,
                ts("2025-11-01T00:00"),
                None,
                None,
                ts("2025-11-05T00:00"),
            ),
        ],
    )


def test_complaint_summary():
    report = build_complaint_report(_complaints(), period=period("2026-01", "2026-03"), now=NOW, meta=meta())
    s = report.summary
    assert (s.created_in_period, s.resolved_in_period, s.open_now, s.overdue_open_now) == (4, 2, 3, 2)
    assert (s.escalated_in_period, s.escalation_rate_pct) == (2, 50.0)
    assert s.avg_resolution_hours == 18.0
    assert s.median_resolution_hours == 18.0


def test_complaint_breakdowns_and_trend():
    report = build_complaint_report(_complaints(), period=period("2026-01", "2026-03"), now=NOW, meta=meta())
    assert {k.key: k.count for k in report.by_escalation_level} == {"0": 2, "1": 1, "2": 1, "3": 0}
    assert {k.key: k.count for k in report.by_status} == {
        "OPEN": 1,
        "IN_PROGRESS": 0,
        "ESCALATED": 1,
        "RESOLVED": 1,
        "CLOSED": 1,
    }
    assert {k.key: k.count for k in report.by_category}["SECURITY"] == 0
    jan, feb, mar = report.monthly
    assert (jan.created, jan.resolved, jan.escalated, jan.avg_resolution_hours) == (2, 2, 1, 18.0)
    assert (feb.created, feb.escalated, feb.resolved) == (1, 1, 0)
    assert mar.created == 1


def test_empty_complaints():
    report = build_complaint_report(
        pd.DataFrame(columns=COLS), period=period("2026-01", "2026-01"), now=NOW, meta=meta()
    )
    assert report.summary.escalation_rate_pct is None
    assert report.summary.open_now == 0
    assert all(k.count == 0 for k in report.by_status)
