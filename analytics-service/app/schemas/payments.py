from __future__ import annotations

from app.schemas.common import ApiModel, ReportMeta


class AgingBucket(ApiModel):
    bucket: str
    label: str
    invoice_count: int
    outstanding_amount: float
    share_pct: float | None


class OutstandingByProperty(ApiModel):
    property_id: int
    property_name: str
    invoice_count: int
    outstanding_amount: float
    overdue_amount: float


class OutstandingByType(ApiModel):
    invoice_type: str
    invoice_count: int
    outstanding_amount: float


class PaymentDefaultMonth(ApiModel):
    billing_month: str
    invoices: int
    evaluated: int
    paid_on_time: int
    paid_late: int
    unpaid_past_due: int
    not_yet_due: int
    default_rate_pct: float | None
    late_payment_rate_pct: float | None
    avg_days_late: float | None
    billed_amount: float
    collected_amount: float
    collection_rate_pct: float | None


class PaymentsSummary(ApiModel):
    outstanding_total: float = 0.0
    overdue_total: float = 0.0
    open_invoice_count: int = 0
    collection_rate_pct: float | None = None
    default_rate_pct: float | None = None
    avg_days_late: float | None = None


class PaymentsReport(ApiModel):
    meta: ReportMeta
    summary: PaymentsSummary
    aging: list[AgingBucket]
    outstanding_by_property: list[OutstandingByProperty]
    outstanding_by_type: list[OutstandingByType]
    default_trend: list[PaymentDefaultMonth]
