from datetime import date

import pandas as pd
import pytest

from app.services.properties import PropertyInputs, build_property_report, rank_rows
from tests.helpers import frame, meta, period, ts

NOW = ts("2026-03-15T06:00").to_pydatetime()
TODAY = date(2026, 3, 15)


def _inputs(**overrides):
    base = {
        "properties": frame(
            ["id", "code", "name", "city", "status"],
            [
                (1, "ALP", "Alpha", "Pune", "ACTIVE"),
                (2, "BET", "Beta", "Pune", "ACTIVE"),
                (3, "GAM", "Gamma", None, "ACTIVE"),
            ],
        ),
        "beds": frame(
            ["bed_id", "property_id", "status"],
            [
                (1, 1, "OCCUPIED"),
                (2, 1, "OCCUPIED"),
                (3, 1, "AVAILABLE"),
                (4, 1, "MAINTENANCE"),
                (5, 2, "OCCUPIED"),
                (6, 2, "AVAILABLE"),
            ],
        ),
        "tenants": frame(
            ["id", "property_id", "status"],
            [(1, 1, "ACTIVE"), (2, 1, "ON_NOTICE"), (3, 2, "ACTIVE"), (4, 2, "MOVED_OUT")],
        ),
        "current_month_payments": frame(
            [
                "id",
                "property_id",
                "invoice_id",
                "invoice_type",
                "invoice_status",
                "amount",
                "refunded_amount",
                "method",
                "paid_at",
            ],
            [
                (1, 1, 1, "RENT", "PAID", 9000.0, 0.0, "UPI", ts("2026-03-02T05:00")),
                (2, 1, 2, "SECURITY_DEPOSIT", "PAID", 50000.0, 0.0, "UPI", ts("2026-03-02T05:00")),
                (3, 2, 3, "RENT", "PAID", 6000.0, 1000.0, "CASH", ts("2026-03-03T05:00")),
            ],
        ),
        "rent_invoices": frame(
            ["id", "property_id", "invoice_type", "billing_month", "status", "total_amount", "amount_paid"],
            [
                (1, 1, "RENT", date(2026, 3, 1), "PAID", 9000.0, 9000.0),
                (3, 2, "RENT", date(2026, 3, 1), "PARTIALLY_PAID", 8000.0, 6000.0),
            ],
        ),
        "open_invoices": frame(
            [
                "id",
                "property_id",
                "property_name",
                "invoice_type",
                "due_date",
                "status",
                "total_amount",
                "amount_paid",
            ],
            [(3, 2, "Beta", "RENT", date(2026, 3, 5), "PARTIALLY_PAID", 8000.0, 6000.0)],
        ),
        "maintenance": frame(
            [
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
            ],
            [
                (
                    1,
                    1,
                    "PLUMBING",
                    "LOW",
                    "OPEN",
                    ts("2026-03-01T00:00"),
                    None,
                    None,
                    ts("2026-03-05T00:00"),
                    False,
                ),
                (
                    2,
                    2,
                    "PLUMBING",
                    "LOW",
                    "RESOLVED",
                    ts("2026-03-01T00:00"),
                    ts("2026-03-01T05:00"),
                    None,
                    ts("2026-03-02T00:00"),
                    False,
                ),
            ],
        ),
        "complaints": frame(
            [
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
            ],
            [(1, 2, "NOISE", "LOW", "OPEN", 0, ts("2026-03-01T00:00"), None, None, ts("2026-03-04T00:00"))],
        ),
    }
    base.update(overrides)
    return PropertyInputs(**base)


def _report(sort_by="occupancyRate", order="desc", inputs=None):
    return build_property_report(
        inputs or _inputs(),
        period=period("2026-01", "2026-03"),
        current_month=period("2026-03", "2026-03"),
        today=TODAY,
        now=NOW,
        sort_by=sort_by,
        order=order,
        meta=meta(),
    )


def test_property_metrics():
    rows = {r.property_name: r for r in _report().properties}
    alpha, beta, gamma = rows["Alpha"], rows["Beta"], rows["Gamma"]
    assert (alpha.total_beds, alpha.occupied_beds, alpha.occupancy_rate_pct) == (4, 2, 66.67)
    assert alpha.active_tenants == 2
    assert alpha.current_month_revenue == 9000.0  # deposit excluded
    assert alpha.revenue_per_bed == 2250.0
    assert alpha.collection_rate_pct == 100.0
    assert (alpha.open_maintenance, alpha.sla_compliance_pct) == (1, None)
    assert (beta.current_month_revenue, beta.collection_rate_pct, beta.outstanding_amount) == (
        5000.0,
        75.0,
        2000.0,
    )
    assert (beta.sla_compliance_pct, beta.open_complaints) == (100.0, 1)
    assert (gamma.total_beds, gamma.occupancy_rate_pct, gamma.revenue_per_bed) == (0, None, None)


def test_ranking_desc_puts_nulls_last():
    report = _report("occupancyRate", "desc")
    assert [(r.property_name, r.rank) for r in report.properties] == [("Alpha", 1), ("Beta", 2), ("Gamma", 3)]


def test_ranking_asc_and_by_name():
    assert [r.property_name for r in _report("revenuePerBed", "asc").properties] == ["Alpha", "Beta", "Gamma"]
    report = _report("name", "desc")
    assert [r.property_name for r in report.properties] == ["Gamma", "Beta", "Alpha"]
    assert [r.rank for r in report.properties] == [1, 2, 3]


def test_ties_share_rank():
    rows = rank_rows(
        [
            {**_row(1, "A"), "open_complaints": 2},
            {**_row(2, "B"), "open_complaints": 2},
            {**_row(3, "C"), "open_complaints": 1},
        ],
        "openComplaints",
        "desc",
    )
    assert [(r.property_name, r.rank) for r in rows] == [("A", 1), ("B", 1), ("C", 3)]


def _row(pid, name):
    return {
        "property_id": pid,
        "property_code": None,
        "property_name": name,
        "city": None,
        "status": "ACTIVE",
        "total_beds": 0,
        "occupied_beds": 0,
        "occupancy_rate_pct": None,
        "active_tenants": 0,
        "current_month_revenue": 0.0,
        "revenue_per_bed": None,
        "collection_rate_pct": None,
        "outstanding_amount": 0.0,
        "open_maintenance": 0,
        "sla_compliance_pct": None,
        "open_complaints": 0,
    }


@pytest.mark.parametrize(
    "sort_by", ["outstanding", "slaCompliance", "totalBeds", "activeTenants", "collectionRate"]
)
def test_every_sort_key_works(sort_by):
    assert len(_report(sort_by).properties) == 3


def test_empty_properties():
    empty = PropertyInputs(*(pd.DataFrame() for _ in range(8)))
    report = _report(inputs=empty)
    assert report.properties == []
    assert report.current_month == "2026-03"
