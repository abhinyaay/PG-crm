"""Receivables / payment-behaviour analytics (pure pandas)."""

from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

from app.schemas.common import ReportMeta
from app.schemas.payments import (
    AgingBucket,
    OutstandingByProperty,
    OutstandingByType,
    PaymentDefaultMonth,
    PaymentsReport,
    PaymentsSummary,
)
from app.services.common import (
    day,
    ensure_columns,
    money,
    ratio_pct,
    safe_mean,
    to_day,
    to_local_day,
    to_money,
    to_month,
)
from app.services.period import ReportPeriod

OPEN_STATUSES = ("ISSUED", "PARTIALLY_PAID", "OVERDUE")
INVOICE_TYPES = ("RENT", "SECURITY_DEPOSIT")
OPEN_INVOICE_COLUMNS = (
    "id",
    "property_id",
    "property_name",
    "invoice_type",
    "due_date",
    "status",
    "total_amount",
    "amount_paid",
)
RENT_INVOICE_COLUMNS = (
    "id",
    "property_id",
    "billing_month",
    "due_date",
    "status",
    "total_amount",
    "amount_paid",
    "last_paid_at",
)

# (code, label, min days past due inclusive, max inclusive) - None = unbounded
AGING_BUCKETS: tuple[tuple[str, str, int | None, int | None], ...] = (
    ("CURRENT", "Not yet due", None, 0),
    ("DAYS_1_30", "1-30 days past due", 1, 30),
    ("DAYS_31_60", "31-60 days past due", 31, 60),
    ("DAYS_61_90", "61-90 days past due", 61, 90),
    ("DAYS_90_PLUS", "90+ days past due", 91, None),
)


def prepare_open_invoices(open_invoices: pd.DataFrame, today: date) -> pd.DataFrame:
    """Open (ISSUED/PARTIALLY_PAID/OVERDUE) invoices with ``outstanding`` and ``days_past_due``."""
    oi = ensure_columns(open_invoices, OPEN_INVOICE_COLUMNS)
    oi = oi[oi["status"].isin(OPEN_STATUSES)].copy()
    oi["outstanding"] = (to_money(oi["total_amount"]) - to_money(oi["amount_paid"])).clip(lower=0.0)
    oi = oi[oi["outstanding"] > 0]
    oi["days_past_due"] = (day(today) - to_day(oi["due_date"])).dt.days.fillna(0).astype(int)
    return oi


def aging_bucket_codes(days_past_due: pd.Series) -> pd.Series:
    conditions = []
    for _, _, low, high in AGING_BUCKETS:
        cond = pd.Series(True, index=days_past_due.index)
        if low is not None:
            cond &= days_past_due >= low
        if high is not None:
            cond &= days_past_due <= high
        conditions.append(cond)
    codes = [code for code, *_ in AGING_BUCKETS]
    return pd.Series(np.select(conditions, codes, default=codes[0]), index=days_past_due.index)


def aging_buckets(oi: pd.DataFrame) -> list[AgingBucket]:
    total = float(oi["outstanding"].sum()) if len(oi) else 0.0
    codes = aging_bucket_codes(oi["days_past_due"]) if len(oi) else pd.Series(dtype=str)
    result = []
    for code, label, _, _ in AGING_BUCKETS:
        mask = codes == code
        amount = float(oi.loc[mask, "outstanding"].sum()) if len(oi) else 0.0
        result.append(
            AgingBucket(
                bucket=code,
                label=label,
                invoice_count=int(mask.sum()) if len(oi) else 0,
                outstanding_amount=money(amount),
                share_pct=ratio_pct(amount, total),
            )
        )
    return result


def outstanding_by_property(oi: pd.DataFrame) -> list[OutstandingByProperty]:
    if oi.empty:
        return []
    o = oi.assign(overdue=np.where(oi["days_past_due"] > 0, oi["outstanding"], 0.0))
    grouped = o.groupby(["property_id", "property_name"], as_index=False).agg(
        invoice_count=("id", "count"), outstanding=("outstanding", "sum"), overdue=("overdue", "sum")
    )
    grouped = grouped.sort_values(["outstanding", "property_name"], ascending=[False, True])
    return [
        OutstandingByProperty(
            property_id=int(r.property_id),
            property_name=str(r.property_name),
            invoice_count=int(r.invoice_count),
            outstanding_amount=money(r.outstanding),
            overdue_amount=money(r.overdue),
        )
        for r in grouped.itertuples(index=False)
    ]


def outstanding_by_type(oi: pd.DataFrame) -> list[OutstandingByType]:
    result = []
    for invoice_type in INVOICE_TYPES:
        rows = oi[oi["invoice_type"] == invoice_type]
        result.append(
            OutstandingByType(
                invoice_type=invoice_type,
                invoice_count=int(len(rows)),
                outstanding_amount=money(rows["outstanding"].sum()),
            )
        )
    return result


def classify_rent_invoices(rent_invoices: pd.DataFrame, today: date, timezone: str) -> pd.DataFrame:
    """Adds payment-behaviour flags to non-VOID RENT invoices.

    * ``paid_date``      = local date of the latest payment for the invoice
    * ``paid_late``      = status PAID and paid_date > due_date
    * ``paid_on_time``   = status PAID and not paid_late (incl. PAID with no payment rows)
    * ``unpaid_past_due``= not PAID and due_date < today
    * ``not_yet_due``    = not PAID and due_date >= today
    * ``evaluated``      = paid_on_time | paid_late | unpaid_past_due (outcome is known)
    """
    ri = ensure_columns(rent_invoices, RENT_INVOICE_COLUMNS)
    ri = ri[ri["status"] != "VOID"].copy()
    ri["total_amount"] = to_money(ri["total_amount"])
    ri["amount_paid"] = to_money(ri["amount_paid"])
    ri["month"] = to_month(ri["billing_month"])
    ri["due"] = to_day(ri["due_date"])
    ri["paid_date"] = to_local_day(ri["last_paid_at"], timezone)
    paid = ri["status"] == "PAID"
    ri["paid_late"] = paid & ri["paid_date"].notna() & (ri["paid_date"] > ri["due"])
    ri["paid_on_time"] = paid & ~ri["paid_late"]
    past_due = ri["due"] < day(today)
    ri["unpaid_past_due"] = ~paid & past_due
    ri["not_yet_due"] = ~paid & ~past_due
    ri["evaluated"] = ri["paid_on_time"] | ri["paid_late"] | ri["unpaid_past_due"]
    ri["days_late"] = np.where(ri["paid_late"], (ri["paid_date"] - ri["due"]).dt.days, np.nan)
    return ri


def default_trend(ri: pd.DataFrame, months: list[pd.Period]) -> list[PaymentDefaultMonth]:
    result = []
    for m in months:
        rows = ri[ri["month"] == m]
        evaluated = int(rows["evaluated"].sum())
        late = int(rows["paid_late"].sum())
        unpaid = int(rows["unpaid_past_due"].sum())
        billed = float(rows["total_amount"].sum())
        collected = float(rows["amount_paid"].sum())
        result.append(
            PaymentDefaultMonth(
                billing_month=str(m),
                invoices=int(len(rows)),
                evaluated=evaluated,
                paid_on_time=int(rows["paid_on_time"].sum()),
                paid_late=late,
                unpaid_past_due=unpaid,
                not_yet_due=int(rows["not_yet_due"].sum()),
                default_rate_pct=ratio_pct(late + unpaid, evaluated),
                late_payment_rate_pct=ratio_pct(late, evaluated),
                avg_days_late=safe_mean(rows.loc[rows["paid_late"], "days_late"]),
                billed_amount=money(billed),
                collected_amount=money(collected),
                collection_rate_pct=ratio_pct(collected, billed),
            )
        )
    return result


def build_payments_report(
    open_invoices: pd.DataFrame,
    rent_invoices: pd.DataFrame,
    *,
    period: ReportPeriod,
    today: date,
    meta: ReportMeta,
) -> PaymentsReport:
    oi = prepare_open_invoices(open_invoices, today)
    ri = classify_rent_invoices(rent_invoices, today, period.timezone)
    ri = ri[ri["month"].isin(period.months)]
    evaluated = int(ri["evaluated"].sum())
    summary = PaymentsSummary(
        outstanding_total=money(oi["outstanding"].sum()),
        overdue_total=money(oi.loc[oi["days_past_due"] > 0, "outstanding"].sum()),
        open_invoice_count=int(len(oi)),
        collection_rate_pct=ratio_pct(ri["amount_paid"].sum(), ri["total_amount"].sum()),
        default_rate_pct=ratio_pct(int(ri["paid_late"].sum()) + int(ri["unpaid_past_due"].sum()), evaluated),
        avg_days_late=safe_mean(ri.loc[ri["paid_late"], "days_late"]),
    )
    return PaymentsReport(
        meta=meta,
        summary=summary,
        aging=aging_buckets(oi),
        outstanding_by_property=outstanding_by_property(oi),
        outstanding_by_type=outstanding_by_type(oi),
        default_trend=default_trend(ri, period.months),
    )
