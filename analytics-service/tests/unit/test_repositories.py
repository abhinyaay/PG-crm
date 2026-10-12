"""Repositories: scope applied in SQL, only analytics.* views referenced, values always bound."""

from __future__ import annotations

import re
from datetime import UTC, date, datetime

import pandas as pd
import pytest

from app.core.scope import AnalyticsScope
from app.repositories.access import AccessRepository
from app.repositories.base import scope_clause
from app.repositories.complaints import ComplaintRepository
from app.repositories.finance import FinanceRepository
from app.repositories.maintenance import MaintenanceRepository
from app.repositories.occupancy import OccupancyRepository
from app.repositories.properties import PropertyRepository
from app.repositories.tenants import TenantRepository
from tests.fakes import FakeDatabase

START = datetime(2026, 1, 1, tzinfo=UTC)
END = datetime(2026, 2, 1, tzinfo=UTC)
TABLE_REF = re.compile(r"\b(?:FROM|JOIN)\s+([\w.]+)", re.IGNORECASE)


def test_scope_clause_variants():
    assert scope_clause(
        AnalyticsScope(), org_column="x.organization_id", property_column="x.property_id"
    ) == (
        "TRUE",
        {},
    )
    sql, params = scope_clause(
        AnalyticsScope(organization_id=3, property_ids=(9, 4, 9)),
        org_column="x.organization_id",
        property_column="x.property_id",
    )
    assert (
        sql
        == "x.organization_id = :scope_org_id AND x.property_id = ANY(CAST(:scope_property_ids AS bigint[]))"
    )
    assert params == {"scope_org_id": 3, "scope_property_ids": [4, 9]}
    sql, _ = scope_clause(
        AnalyticsScope(organization_id=3, property_ids=()), org_column=None, property_column="b.pid"
    )
    assert "SELECT sp.id FROM analytics.properties sp" in sql
    assert sql.endswith("AND FALSE")


def _all_calls(db: FakeDatabase, scope: AnalyticsScope) -> None:
    OccupancyRepository(db).bed_inventory(scope)
    OccupancyRepository(db).allocations_overlapping(scope, date(2026, 1, 1), date(2026, 2, 1))
    fin = FinanceRepository(db)
    fin.payments_between(scope, START, END)
    fin.rent_invoices(scope, date(2026, 1, 1), date(2026, 1, 1))
    fin.open_invoices(scope)
    TenantRepository(db).tenants(scope)
    TenantRepository(db).allocations(scope, date(2026, 1, 1))
    MaintenanceRepository(db).requests(scope, START, END)
    ComplaintRepository(db).complaints(scope, START, END)
    PropertyRepository(db).properties(scope)


@pytest.mark.parametrize(
    "scope",
    [
        AnalyticsScope(organization_id=10),
        AnalyticsScope(organization_id=10, property_ids=(1, 2)),
        AnalyticsScope(),
    ],
)
def test_every_query_reads_only_analytics_views_and_is_scoped(scope):
    db = FakeDatabase()
    _all_calls(db, scope)
    assert len(db.queries) == 10
    for sql, params in db.queries:
        refs = TABLE_REF.findall(sql)
        assert refs, sql
        assert all(ref.startswith("analytics.") or ref in ("(", "LATERAL") for ref in refs), refs
        if scope.organization_id is not None:
            assert params["scope_org_id"] == 10
            assert ":scope_org_id" in sql
        if scope.property_ids:
            assert params["scope_property_ids"] == [1, 2]
        assert "10" not in re.sub(r":\w+", "", sql)  # values are bound, never interpolated


def test_finance_queries_exclude_void():
    db = FakeDatabase()
    fin = FinanceRepository(db)
    fin.payments_between(AnalyticsScope(organization_id=1), START, END)
    fin.rent_invoices(AnalyticsScope(organization_id=1), date(2026, 1, 1), date(2026, 3, 1))
    assert all("<> 'VOID'" in sql for sql, _ in db.queries)


def test_access_repository():
    db = FakeDatabase(
        responses={
            "user_property_access": pd.DataFrame({"property_id": [3, 1]}),
            "SELECT 1 AS found": pd.DataFrame({"found": [1]}),
        }
    )
    repo = AccessRepository(db)
    assert repo.manager_property_ids(5, 10) == [1, 3]
    assert repo.property_in_organization(1, 10) is True
    assert AccessRepository(FakeDatabase()).manager_property_ids(5, 10) == []
    assert AccessRepository(FakeDatabase()).property_in_organization(1, 10) is False


def test_rent_invoices_look_up_last_payment_per_invoice_not_for_the_whole_scope():
    """The latest-payment lookup must be bounded by the period's invoices (index on payments.invoice_id),
    not aggregate every RENT payment the scope has ever received."""
    db = FakeDatabase()
    FinanceRepository(db).rent_invoices(AnalyticsScope(organization_id=1), date(2026, 1, 1), date(2026, 3, 1))
    sql, params = db.queries[0]
    normalised = " ".join(sql.split())
    assert "LEFT JOIN LATERAL" in normalised
    assert "WHERE pp.invoice_id = i.id" in normalised
    assert "GROUP BY pp.invoice_id" not in normalised
    assert params["first_month"] == date(2026, 1, 1)


def test_tenant_allocations_are_bounded_to_tenants_still_relevant_for_the_period():
    """Only tenants with an allocation ending on/after the period start (or ongoing) are loaded - with
    their full history, so transfer chains are still stitched - instead of every allocation ever."""
    db = FakeDatabase()
    TenantRepository(db).allocations(AnalyticsScope(organization_id=1, property_ids=(4,)), date(2026, 1, 1))
    sql, params = db.queries[0]
    normalised = " ".join(sql.split())
    assert "a.tenant_id IN ( SELECT r.tenant_id FROM analytics.allocations r" in normalised
    assert "(r.end_date IS NULL OR r.end_date >= :since)" in normalised
    assert "r.organization_id = :scope_org_id" in normalised
    assert "r.property_id = ANY(CAST(:scope_property_ids AS bigint[]))" in normalised
    assert params["since"] == date(2026, 1, 1)
