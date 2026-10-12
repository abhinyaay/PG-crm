from datetime import date

import pandas as pd

from app.services.revenue import build_revenue_report, net_rent_revenue_by_property
from tests.helpers import frame, meta, period, ts

PAY_COLS = [
    "id",
    "property_id",
    "invoice_id",
    "invoice_type",
    "invoice_status",
    "amount",
    "refunded_amount",
    "method",
    "paid_at",
]
INV_COLS = ["id", "property_id", "invoice_type", "billing_month", "status", "total_amount", "amount_paid"]


def _payments():
    return frame(
        PAY_COLS,
        [
            (1, 1, 11, "RENT", "PAID", 10000.0, 0.0, "UPI", ts("2026-01-05T10:00")),
            # 2026-01-31 19:00 UTC is 2026-02-01 00:30 IST -> February in the business timezone
            (2, 1, 12, "RENT", "PAID", 8000.0, 1000.0, "CASH", ts("2026-01-31T19:00")),
            (3, 1, 13, "SECURITY_DEPOSIT", "PAID", 20000.0, 5000.0, "BANK_TRANSFER", ts("2026-02-10T05:00")),
            (4, 1, 14, "RENT", "VOID", 5000.0, 0.0, "CASH", ts("2026-02-11T05:00")),  # VOID -> excluded
            (5, 2, 15, "RENT", "PAID", 12000.0, 0.0, "CARD", ts("2026-03-03T05:00")),
            (6, 1, 16, "RENT", "PAID", 999.0, 0.0, "UPI", ts("2025-12-15T05:00")),  # outside period
        ],
    )


def _invoices():
    return frame(
        INV_COLS,
        [
            (11, 1, "RENT", date(2026, 1, 1), "PAID", 10000.0, 10000.0),
            (12, 1, "RENT", date(2026, 1, 1), "PARTIALLY_PAID", 9000.0, 4000.0),
            (13, 1, "RENT", date(2026, 1, 1), "VOID", 7000.0, 0.0),
            (14, 1, "RENT", date(2026, 2, 1), "PAID", 8000.0, 8000.0),
            (15, 1, "SECURITY_DEPOSIT", None, "PAID", 20000.0, 20000.0),
        ],
    )


def test_monthly_revenue_excludes_deposits_void_and_uses_local_months():
    report = build_revenue_report(_payments(), _invoices(), period=period("2026-01", "2026-03"), meta=meta())
    jan, feb, mar = report.monthly
    assert (jan.net_rent_revenue, feb.net_rent_revenue, mar.net_rent_revenue) == (10000.0, 7000.0, 12000.0)
    assert feb.gross_rent_collected == 8000.0
    assert feb.rent_refunds == 1000.0
    assert feb.deposits_collected == 20000.0
    assert feb.deposit_refunds == 5000.0
    assert feb.total_refunds == 6000.0
    assert jan.mom_growth_pct is None
    assert feb.mom_growth_pct == -30.0
    assert mar.mom_growth_pct == 71.43


def test_billed_rent_and_collection_rate_by_billing_month():
    report = build_revenue_report(_payments(), _invoices(), period=period("2026-01", "2026-03"), meta=meta())
    jan, feb, mar = report.monthly
    assert (jan.billed_rent, jan.billed_rent_collected, jan.collection_rate_pct) == (19000.0, 14000.0, 73.68)
    assert feb.collection_rate_pct == 100.0
    assert mar.billed_rent == 0.0
    assert mar.collection_rate_pct is None


def test_totals():
    t = build_revenue_report(
        _payments(), _invoices(), period=period("2026-01", "2026-03"), meta=meta()
    ).totals
    assert t.net_rent_revenue == 29000.0
    assert t.gross_rent_collected == 30000.0
    assert t.deposits_collected == 20000.0
    assert t.total_refunds == 6000.0
    assert t.billed_rent == 27000.0
    assert t.collection_rate_pct == 81.48
    assert t.average_monthly_net_rent_revenue == 9666.67


def test_revenue_by_method():
    report = build_revenue_report(_payments(), _invoices(), period=period("2026-01", "2026-03"), meta=meta())
    by = {m.method: m for m in report.by_method}
    assert list(by) == ["CASH", "UPI", "CARD", "BANK_TRANSFER", "CHEQUE"]
    assert (by["UPI"].net_rent_revenue, by["UPI"].share_of_rent_revenue_pct) == (10000.0, 34.48)
    assert (by["CASH"].net_rent_revenue, by["CASH"].payment_count) == (7000.0, 1)
    assert by["CARD"].share_of_rent_revenue_pct == 41.38
    assert (by["BANK_TRANSFER"].net_rent_revenue, by["BANK_TRANSFER"].deposits_collected) == (0.0, 20000.0)
    assert by["CHEQUE"].payment_count == 0


def test_growth_from_zero_month_is_null():
    payments = frame(PAY_COLS, [(1, 1, 1, "RENT", "PAID", 100.0, 0.0, "UPI", ts("2026-02-05T05:00"))])
    report = build_revenue_report(
        payments, pd.DataFrame(columns=INV_COLS), period=period("2026-01", "2026-02"), meta=meta()
    )
    assert report.monthly[1].mom_growth_pct is None


def test_empty_revenue():
    report = build_revenue_report(
        pd.DataFrame(columns=PAY_COLS),
        pd.DataFrame(columns=INV_COLS),
        period=period("2026-01", "2026-02"),
        meta=meta(),
    )
    assert report.totals.net_rent_revenue == 0.0
    assert report.totals.collection_rate_pct is None
    assert all(m.net_rent_revenue == 0.0 for m in report.monthly)
    assert all(m.share_of_rent_revenue_pct is None for m in report.by_method)


def test_net_rent_revenue_by_property():
    series = net_rent_revenue_by_property(_payments(), period("2026-01", "2026-03"))
    assert series.to_dict() == {1: 17000.0, 2: 12000.0}
    assert net_rent_revenue_by_property(pd.DataFrame(columns=PAY_COLS), period("2026-01", "2026-01")).empty


def test_future_months_have_no_growth_and_do_not_dilute_the_average():
    """A period reaching past the current month (e.g. a calendar year) must not report -100% growth for
    the first future month nor divide the average by months that have not happened yet."""
    report = build_revenue_report(
        _payments(), _invoices(), period=period("2026-01", "2026-05"), meta=meta(), today=date(2026, 3, 20)
    )
    apr, may = report.monthly[3], report.monthly[4]
    assert (apr.net_rent_revenue, apr.mom_growth_pct, may.mom_growth_pct) == (0.0, None, None)
    assert report.monthly[2].mom_growth_pct is not None  # the current (partial) month is still compared
    assert report.totals.average_monthly_net_rent_revenue == round(29000.0 / 3, 2)


def test_period_entirely_in_the_future_has_zero_average():
    report = build_revenue_report(
        _payments(), _invoices(), period=period("2027-01", "2027-02"), meta=meta(), today=date(2026, 3, 20)
    )
    assert report.totals.average_monthly_net_rent_revenue == 0.0
    assert all(m.mom_growth_pct is None for m in report.monthly)
