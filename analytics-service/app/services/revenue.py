"""Revenue analytics (pure pandas).

Revenue = net cash collected (``amount - refunded_amount``) on RENT invoices, bucketed by the local
month of ``paid_at``. Security deposits are a liability and are reported separately.
"""

from __future__ import annotations

from datetime import date

import pandas as pd

from app.schemas.common import ReportMeta
from app.schemas.revenue import RevenueByMethod, RevenueMonth, RevenueReport, RevenueTotals
from app.services.common import ensure_columns, money, ratio_pct, to_local_month, to_money, to_month
from app.services.period import ReportPeriod

PAYMENT_METHODS = ("CASH", "UPI", "CARD", "BANK_TRANSFER", "CHEQUE")
PAYMENT_COLUMNS = (
    "id",
    "invoice_id",
    "invoice_type",
    "invoice_status",
    "amount",
    "refunded_amount",
    "method",
    "paid_at",
)
RENT_INVOICE_COLUMNS = ("id", "invoice_type", "billing_month", "status", "total_amount", "amount_paid")


def prepare_payments(payments: pd.DataFrame, period: ReportPeriod) -> pd.DataFrame:
    """Normalise payment rows, drop payments on VOID invoices and rows outside the period."""
    p = ensure_columns(payments, PAYMENT_COLUMNS)
    p = p[p["invoice_status"].fillna("") != "VOID"].copy()
    p["amount"] = to_money(p["amount"])
    p["refunded_amount"] = to_money(p["refunded_amount"])
    p["net"] = p["amount"] - p["refunded_amount"]
    p["month"] = to_local_month(p["paid_at"], period.timezone)
    return p[p["month"].isin(period.months)]


def prepare_rent_invoices(invoices: pd.DataFrame, period: ReportPeriod) -> pd.DataFrame:
    """Non-VOID RENT invoices whose billing month falls in the period."""
    inv = ensure_columns(invoices, RENT_INVOICE_COLUMNS)
    inv = inv[(inv["status"] != "VOID") & (inv["invoice_type"].fillna("RENT") == "RENT")].copy()
    inv["total_amount"] = to_money(inv["total_amount"])
    inv["amount_paid"] = to_money(inv["amount_paid"])
    inv["month"] = to_month(inv["billing_month"])
    return inv[inv["month"].isin(period.months)]


def _monthly_sum(df: pd.DataFrame, column: str, months: list[pd.Period]) -> pd.Series:
    if df.empty:
        return pd.Series(0.0, index=months)
    return df.groupby("month")[column].sum().reindex(months, fill_value=0.0)


def build_revenue_report(
    payments: pd.DataFrame,
    rent_invoices: pd.DataFrame,
    *,
    period: ReportPeriod,
    meta: ReportMeta,
    today: date | None = None,
) -> RevenueReport:
    """``today`` marks months after the current one as future: they get no MoM growth and are left out
    of ``averageMonthlyNetRentRevenue`` (the current, partial month is included)."""
    months = period.months
    current = pd.Period(today, freq="M") if today is not None else None
    elapsed = [m for m in months if current is None or m <= current]
    p = prepare_payments(payments, period)
    rent = p[p["invoice_type"] == "RENT"]
    deposits = p[p["invoice_type"] == "SECURITY_DEPOSIT"]
    inv = prepare_rent_invoices(rent_invoices, period)

    gross = _monthly_sum(rent, "amount", months)
    rent_refunds = _monthly_sum(rent, "refunded_amount", months)
    net = _monthly_sum(rent, "net", months)
    dep = _monthly_sum(deposits, "amount", months)
    dep_refunds = _monthly_sum(deposits, "refunded_amount", months)
    billed = _monthly_sum(inv, "total_amount", months)
    billed_paid = _monthly_sum(inv, "amount_paid", months)

    monthly: list[RevenueMonth] = []
    previous: float | None = None
    for m in months:
        value = float(net[m])
        growth = None
        is_future = current is not None and m > current
        if previous is not None and round(previous, 2) != 0 and not is_future:
            growth = ratio_pct(value - previous, abs(previous))
        monthly.append(
            RevenueMonth(
                month=str(m),
                gross_rent_collected=money(gross[m]),
                rent_refunds=money(rent_refunds[m]),
                net_rent_revenue=money(value),
                deposits_collected=money(dep[m]),
                deposit_refunds=money(dep_refunds[m]),
                total_refunds=money(rent_refunds[m] + dep_refunds[m]),
                billed_rent=money(billed[m]),
                billed_rent_collected=money(billed_paid[m]),
                collection_rate_pct=ratio_pct(billed_paid[m], billed[m]),
                mom_growth_pct=growth,
            )
        )
        previous = value

    total_net = float(net.sum())
    by_method: list[RevenueByMethod] = []
    methods = list(PAYMENT_METHODS) + sorted(set(p["method"].dropna()) - set(PAYMENT_METHODS))
    for method in methods:
        rows = p[p["method"] == method]
        rent_rows = rows[rows["invoice_type"] == "RENT"]
        method_net = float(rent_rows["net"].sum())
        by_method.append(
            RevenueByMethod(
                method=method,
                payment_count=int(len(rows)),
                net_rent_revenue=money(method_net),
                deposits_collected=money(
                    rows.loc[rows["invoice_type"] == "SECURITY_DEPOSIT", "amount"].sum()
                ),
                share_of_rent_revenue_pct=ratio_pct(method_net, total_net),
            )
        )

    totals = RevenueTotals(
        net_rent_revenue=money(total_net),
        gross_rent_collected=money(gross.sum()),
        deposits_collected=money(dep.sum()),
        total_refunds=money(rent_refunds.sum() + dep_refunds.sum()),
        billed_rent=money(billed.sum()),
        billed_rent_collected=money(billed_paid.sum()),
        collection_rate_pct=ratio_pct(billed_paid.sum(), billed.sum()),
        average_monthly_net_rent_revenue=money(total_net / len(elapsed)) if elapsed else 0.0,
    )
    return RevenueReport(meta=meta, totals=totals, monthly=monthly, by_method=by_method)


def net_rent_revenue_by_property(payments: pd.DataFrame, period: ReportPeriod) -> pd.Series:
    """Net RENT revenue per property_id within ``period`` (used by the property comparison)."""
    p = prepare_payments(ensure_columns(payments, (*PAYMENT_COLUMNS, "property_id")), period)
    p = p[p["invoice_type"] == "RENT"]
    if p.empty:
        return pd.Series(dtype=float)
    return p.groupby("property_id")["net"].sum()
