from datetime import date

import pandas as pd

from app.services.payments import (
    aging_bucket_codes,
    build_payments_report,
    classify_rent_invoices,
)
from tests.helpers import frame, meta, period, ts

OPEN_COLS = [
    "id",
    "property_id",
    "property_name",
    "invoice_type",
    "due_date",
    "status",
    "total_amount",
    "amount_paid",
]
RENT_COLS = [
    "id",
    "property_id",
    "billing_month",
    "due_date",
    "status",
    "total_amount",
    "amount_paid",
    "last_paid_at",
]
TODAY = date(2026, 3, 15)


def _open_invoices():
    return frame(
        OPEN_COLS,
        [
            (1, 1, "Alpha", "RENT", date(2026, 3, 20), "ISSUED", 10000.0, 0.0),  # not yet due
            (2, 1, "Alpha", "RENT", date(2026, 3, 15), "PARTIALLY_PAID", 5000.0, 2000.0),  # due today
            (3, 1, "Alpha", "RENT", date(2026, 3, 1), "OVERDUE", 8000.0, 0.0),  # 14 days
            (4, 2, "Beta", "RENT", date(2026, 2, 13), "OVERDUE", 1000.0, 0.0),  # 30 days
            (5, 2, "Beta", "RENT", date(2026, 2, 12), "OVERDUE", 2000.0, 0.0),  # 31 days
            (6, 2, "Beta", "RENT", date(2026, 1, 10), "OVERDUE", 4000.0, 0.0),  # 64 days
            (7, 2, "Beta", "SECURITY_DEPOSIT", date(2025, 11, 1), "OVERDUE", 6000.0, 0.0),  # 134 days
            (8, 1, "Alpha", "RENT", date(2026, 1, 1), "PAID", 9000.0, 9000.0),  # not open
            (9, 1, "Alpha", "RENT", date(2026, 1, 1), "VOID", 9000.0, 0.0),  # VOID
        ],
    )


def _rent_invoices():
    return frame(
        RENT_COLS,
        [
            (1, 1, date(2026, 1, 1), date(2026, 1, 5), "PAID", 10000.0, 10000.0, ts("2026-01-04T10:00")),
            # 2026-01-05 20:00 UTC is 2026-01-06 01:30 IST -> one day late in the business timezone
            (2, 1, date(2026, 1, 1), date(2026, 1, 5), "PAID", 10000.0, 10000.0, ts("2026-01-05T20:00")),
            (3, 1, date(2026, 1, 1), date(2026, 1, 5), "PAID", 10000.0, 10000.0, ts("2026-01-15T05:00")),
            (
                4,
                1,
                date(2026, 1, 1),
                date(2026, 1, 5),
                "PARTIALLY_PAID",
                10000.0,
                5000.0,
                ts("2026-01-03T05:00"),
            ),
            (5, 1, date(2026, 3, 1), date(2026, 3, 20), "ISSUED", 10000.0, 0.0, None),
            (6, 1, date(2026, 3, 1), date(2026, 3, 20), "PAID", 10000.0, 10000.0, ts("2026-03-02T05:00")),
            (7, 1, date(2026, 1, 1), date(2026, 1, 5), "VOID", 10000.0, 0.0, None),
        ],
    )


def test_aging_bucket_boundaries():
    days = pd.Series([-5, 0, 1, 30, 31, 60, 61, 90, 91, 500])
    assert aging_bucket_codes(days).tolist() == [
        "CURRENT",
        "CURRENT",
        "DAYS_1_30",
        "DAYS_1_30",
        "DAYS_31_60",
        "DAYS_31_60",
        "DAYS_61_90",
        "DAYS_61_90",
        "DAYS_90_PLUS",
        "DAYS_90_PLUS",
    ]


def test_aging_and_outstanding():
    report = build_payments_report(
        _open_invoices(), _rent_invoices(), period=period("2026-01", "2026-03"), today=TODAY, meta=meta()
    )
    aging = {b.bucket: b for b in report.aging}
    assert (aging["CURRENT"].invoice_count, aging["CURRENT"].outstanding_amount) == (2, 13000.0)
    assert (aging["DAYS_1_30"].invoice_count, aging["DAYS_1_30"].outstanding_amount) == (2, 9000.0)
    assert aging["DAYS_31_60"].outstanding_amount == 2000.0
    assert aging["DAYS_61_90"].outstanding_amount == 4000.0
    assert aging["DAYS_90_PLUS"].outstanding_amount == 6000.0
    assert aging["CURRENT"].share_pct == 38.24  # 13000 / 34000

    s = report.summary
    assert s.outstanding_total == 34000.0
    assert s.overdue_total == 21000.0
    assert s.open_invoice_count == 7

    by_prop = {p.property_name: p for p in report.outstanding_by_property}
    assert (by_prop["Alpha"].outstanding_amount, by_prop["Alpha"].overdue_amount) == (21000.0, 8000.0)
    assert (by_prop["Beta"].outstanding_amount, by_prop["Beta"].invoice_count) == (13000.0, 4)
    assert report.outstanding_by_property[0].property_name == "Alpha"  # sorted by outstanding desc

    by_type = {t.invoice_type: t.outstanding_amount for t in report.outstanding_by_type}
    assert by_type == {"RENT": 28000.0, "SECURITY_DEPOSIT": 6000.0}


def test_late_payment_detection_uses_latest_payment_in_business_timezone():
    ri = classify_rent_invoices(_rent_invoices(), TODAY, "Asia/Kolkata").set_index("id")
    assert 7 not in ri.index  # VOID excluded
    assert bool(ri.loc[1, "paid_on_time"])
    assert bool(ri.loc[2, "paid_late"])
    assert ri.loc[2, "days_late"] == 1
    assert bool(ri.loc[3, "paid_late"])
    assert ri.loc[3, "days_late"] == 10
    assert bool(ri.loc[4, "unpaid_past_due"])
    assert bool(ri.loc[5, "not_yet_due"])
    assert not bool(ri.loc[5, "evaluated"])
    assert bool(ri.loc[6, "paid_on_time"])
    assert bool(ri.loc[6, "evaluated"])
    # in UTC the 20:00 payment would have been on the due date
    assert not bool(
        classify_rent_invoices(_rent_invoices(), TODAY, "UTC").set_index("id").loc[2, "paid_late"]
    )


def test_default_trend_per_billing_month():
    report = build_payments_report(
        _open_invoices(), _rent_invoices(), period=period("2026-01", "2026-03"), today=TODAY, meta=meta()
    )
    jan, feb, mar = report.default_trend
    assert (jan.invoices, jan.evaluated, jan.paid_on_time, jan.paid_late, jan.unpaid_past_due) == (
        4,
        4,
        1,
        2,
        1,
    )
    assert jan.default_rate_pct == 75.0
    assert jan.late_payment_rate_pct == 50.0
    assert jan.avg_days_late == 5.5
    assert jan.collection_rate_pct == 87.5
    assert (feb.invoices, feb.default_rate_pct, feb.avg_days_late) == (0, None, None)
    assert (mar.invoices, mar.evaluated, mar.not_yet_due, mar.default_rate_pct) == (2, 1, 1, 0.0)

    s = report.summary
    assert s.default_rate_pct == 60.0  # (2 late + 1 unpaid) / 5 evaluated
    assert s.avg_days_late == 5.5
    assert s.collection_rate_pct == 75.0  # 45000 / 60000


def test_empty_payments_report():
    report = build_payments_report(
        pd.DataFrame(columns=OPEN_COLS),
        pd.DataFrame(columns=RENT_COLS),
        period=period("2026-01", "2026-02"),
        today=TODAY,
        meta=meta(),
    )
    assert report.summary.outstanding_total == 0.0
    assert report.summary.collection_rate_pct is None
    assert [b.invoice_count for b in report.aging] == [0, 0, 0, 0, 0]
    assert all(b.share_pct is None for b in report.aging)
    assert report.outstanding_by_property == []
    assert len(report.default_trend) == 2
    assert "NaN" not in report.model_dump_json()
