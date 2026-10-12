from __future__ import annotations

from app.schemas.common import ApiModel, ReportMeta


class RevenueMonth(ApiModel):
    month: str
    gross_rent_collected: float
    rent_refunds: float
    net_rent_revenue: float
    deposits_collected: float
    deposit_refunds: float
    total_refunds: float
    billed_rent: float
    billed_rent_collected: float
    collection_rate_pct: float | None
    mom_growth_pct: float | None


class RevenueByMethod(ApiModel):
    method: str
    payment_count: int
    net_rent_revenue: float
    deposits_collected: float
    share_of_rent_revenue_pct: float | None


class RevenueTotals(ApiModel):
    net_rent_revenue: float = 0.0
    gross_rent_collected: float = 0.0
    deposits_collected: float = 0.0
    total_refunds: float = 0.0
    billed_rent: float = 0.0
    billed_rent_collected: float = 0.0
    collection_rate_pct: float | None = None
    average_monthly_net_rent_revenue: float = 0.0


class RevenueReport(ApiModel):
    meta: ReportMeta
    totals: RevenueTotals
    monthly: list[RevenueMonth]
    by_method: list[RevenueByMethod]
