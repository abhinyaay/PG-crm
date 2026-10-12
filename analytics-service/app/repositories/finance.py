"""Invoices & payments (shared by the revenue, payments and property endpoints)."""

from __future__ import annotations

from datetime import date, datetime

import pandas as pd

from app.core.scope import AnalyticsScope
from app.repositories.base import BaseRepository, scope_clause


class FinanceRepository(BaseRepository):
    def payments_between(self, scope: AnalyticsScope, start: datetime, end: datetime) -> pd.DataFrame:
        """Payments with ``start <= paid_at < end``; payments on VOID invoices are excluded."""
        where, params = scope_clause(scope, org_column="p.organization_id", property_column="p.property_id")
        return self._fetch(
            f"""
            SELECT p.id, p.property_id, p.invoice_id, p.invoice_type, i.status AS invoice_status,
                   p.amount::float8 AS amount, p.refunded_amount::float8 AS refunded_amount,
                   p.method, p.status, p.paid_at
            FROM analytics.payments p
            JOIN analytics.invoices i ON i.id = p.invoice_id
            WHERE p.paid_at >= :start_ts AND p.paid_at < :end_ts
              AND i.status <> 'VOID'
              AND {where}
            """,
            {**params, "start_ts": start, "end_ts": end},
        )

    def rent_invoices(self, scope: AnalyticsScope, first_month: date, last_month: date) -> pd.DataFrame:
        """Non-VOID RENT invoices for billing months in [first_month, last_month], with the timestamp
        of the latest payment against each invoice.

        The latest payment is looked up per selected invoice (``payments.invoice_id`` is indexed), so the
        cost is bounded by the period's invoices rather than by the scope's whole payment history. The
        invoice is already scoped, and a payment always belongs to its invoice's property/organization.
        """
        where, params = scope_clause(scope, org_column="i.organization_id", property_column="i.property_id")
        return self._fetch(
            f"""
            SELECT i.id, i.property_id, i.invoice_type, i.billing_month, i.due_date, i.status,
                   i.total_amount::float8 AS total_amount, i.amount_paid::float8 AS amount_paid,
                   lp.last_paid_at
            FROM analytics.invoices i
            LEFT JOIN LATERAL (
                SELECT MAX(pp.paid_at) AS last_paid_at
                FROM analytics.payments pp
                WHERE pp.invoice_id = i.id
            ) lp ON TRUE
            WHERE i.invoice_type = 'RENT' AND i.status <> 'VOID'
              AND i.billing_month >= :first_month AND i.billing_month <= :last_month
              AND {where}
            """,
            {**params, "first_month": first_month, "last_month": last_month},
        )

    def open_invoices(self, scope: AnalyticsScope) -> pd.DataFrame:
        """Current receivables: ISSUED / PARTIALLY_PAID / OVERDUE invoices of any type."""
        where, params = scope_clause(scope, org_column="i.organization_id", property_column="i.property_id")
        return self._fetch(
            f"""
            SELECT i.id, i.property_id, p.name AS property_name, i.invoice_type, i.billing_month,
                   i.due_date, i.status, i.total_amount::float8 AS total_amount,
                   i.amount_paid::float8 AS amount_paid
            FROM analytics.invoices i
            JOIN analytics.properties p ON p.id = i.property_id
            WHERE i.status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE')
              AND {where}
            """,
            params,
        )
