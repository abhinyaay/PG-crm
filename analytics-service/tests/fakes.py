"""In-memory fakes for Redis, the database and repositories."""

from __future__ import annotations

from datetime import date

import pandas as pd
import redis
from sqlalchemy.exc import OperationalError

from tests.helpers import frame, ts


class FakeRedisClient:
    def __init__(self, *, fail: bool = False) -> None:
        self.store: dict[str, str] = {}
        self.fail = fail
        self.calls = 0

    def _check(self) -> None:
        self.calls += 1
        if self.fail:
            raise redis.ConnectionError("redis is down")

    def get(self, name):
        self._check()
        return self.store.get(name)

    def mget(self, keys):
        self._check()
        return [self.store.get(k) for k in keys]

    def setex(self, name, time, value):
        self._check()
        self.store[name] = value
        return True

    def ping(self):
        self._check()
        return True


class FakeDatabase:
    """Records queries; returns an empty frame (or a canned one keyed by a SQL substring)."""

    def __init__(self, *, healthy: bool = True, responses: dict[str, pd.DataFrame] | None = None) -> None:
        self.healthy = healthy
        self.responses = responses or {}
        self.queries: list[tuple[str, dict]] = []

    def fetch_df(self, sql, params=None):
        self.queries.append((sql, dict(params or {})))
        for needle, df in self.responses.items():
            if needle in sql:
                return df.copy()
        return pd.DataFrame()

    def ping(self) -> bool:
        return self.healthy


def db_down() -> OperationalError:
    return OperationalError("SELECT 1", {}, Exception("connection refused"))


class FakeAccessRepository:
    def __init__(self, manager_properties: dict[int, list[int]] | None = None, org_properties=None) -> None:
        self.manager_properties = manager_properties or {}
        self.org_properties = org_properties or {10: {1, 2, 3}, 20: {7}}

    def manager_property_ids(self, user_id: int, organization_id: int) -> list[int]:
        return [
            p
            for p in self.manager_properties.get(user_id, [])
            if p in self.org_properties.get(organization_id, set())
        ]

    def property_in_organization(self, property_id: int, organization_id: int) -> bool:
        return property_id in self.org_properties.get(organization_id, set())


class _Recorder:
    def __init__(self, *, empty: bool = False, fail: Exception | None = None) -> None:
        self.empty = empty
        self.fail = fail
        self.calls: list[tuple[str, object]] = []

    def _get(self, name: str, scope, df: pd.DataFrame) -> pd.DataFrame:
        self.calls.append((name, scope))
        if self.fail is not None:
            raise self.fail
        return df.iloc[0:0].copy() if self.empty else df.copy()


BEDS = frame(
    ["bed_id", "room_id", "property_id", "property_name", "status", "room_type", "capacity"],
    [
        (1, 10, 1, "Alpha", "OCCUPIED", "DOUBLE", 2),
        (2, 10, 1, "Alpha", "AVAILABLE", "DOUBLE", 2),
        (3, 11, 2, "Beta", "OCCUPIED", "SINGLE", 1),
        (4, 12, 2, "Beta", "MAINTENANCE", "SINGLE", 1),
    ],
)
ALLOCATIONS = frame(
    ["id", "bed_id", "property_id", "tenant_id", "status", "start_date", "end_date", "end_reason"],
    [
        (1, 1, 1, 1, "TRANSFERRED", date(2026, 1, 1), date(2026, 2, 1), "TRANSFER"),
        (2, 3, 2, 1, "ACTIVE", date(2026, 2, 1), None, None),
        (3, 2, 1, 2, "ENDED", date(2026, 3, 1), date(2026, 4, 1), "CHECK_OUT"),
    ],
)
TENANTS = frame(
    ["id", "property_id", "status", "gender", "joining_date", "move_out_date"],
    [
        (1, 2, "ACTIVE", "MALE", date(2026, 1, 1), None),
        (2, 1, "MOVED_OUT", "FEMALE", date(2026, 3, 1), date(2026, 4, 1)),
    ],
)
PAYMENTS = frame(
    [
        "id",
        "property_id",
        "invoice_id",
        "invoice_type",
        "invoice_status",
        "amount",
        "refunded_amount",
        "method",
        "status",
        "paid_at",
    ],
    [
        (1, 1, 1, "RENT", "PAID", 9000.0, 0.0, "UPI", "COMPLETED", ts("2026-09-03T05:00")),
        (2, 2, 2, "SECURITY_DEPOSIT", "PAID", 20000.0, 0.0, "CASH", "COMPLETED", ts("2026-10-02T05:00")),
        (3, 2, 3, "RENT", "PAID", 7000.0, 0.0, "=HYPERLINK", "COMPLETED", ts("2026-10-02T05:00")),
    ],
)
RENT_INVOICES = frame(
    [
        "id",
        "property_id",
        "invoice_type",
        "billing_month",
        "due_date",
        "status",
        "total_amount",
        "amount_paid",
        "last_paid_at",
    ],
    [
        (1, 1, "RENT", date(2026, 9, 1), date(2026, 9, 5), "PAID", 9000.0, 9000.0, ts("2026-09-03T05:00")),
        (3, 2, "RENT", date(2026, 10, 1), date(2026, 10, 5), "PAID", 7000.0, 7000.0, ts("2026-10-02T05:00")),
        (4, 1, "RENT", date(2026, 8, 1), date(2026, 8, 5), "OVERDUE", 9000.0, 0.0, None),
    ],
)
OPEN_INVOICES = frame(
    [
        "id",
        "property_id",
        "property_name",
        "invoice_type",
        "billing_month",
        "due_date",
        "status",
        "total_amount",
        "amount_paid",
    ],
    [(4, 1, "Alpha", "RENT", date(2026, 8, 1), date(2026, 8, 5), "OVERDUE", 9000.0, 0.0)],
)
MAINTENANCE = frame(
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
            "HIGH",
            "RESOLVED",
            ts("2026-09-10T00:00"),
            ts("2026-09-10T08:00"),
            None,
            ts("2026-09-11T00:00"),
            False,
        ),
        (
            2,
            2,
            "ELECTRICAL",
            "LOW",
            "OPEN",
            ts("2026-10-01T00:00"),
            None,
            None,
            ts("2026-10-02T00:00"),
            False,
        ),
    ],
)
COMPLAINTS = frame(
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
    [(1, 1, "NOISE", "LOW", "ESCALATED", 1, ts("2026-09-20T00:00"), None, None, ts("2026-09-22T00:00"))],
)
PROPERTIES = frame(
    ["id", "organization_id", "code", "name", "city", "status"],
    [(1, 10, "ALP", "Alpha", "Pune", "ACTIVE"), (2, 10, "BET", "Beta", "Pune", "ACTIVE")],
)


class FakeOccupancyRepository(_Recorder):
    def bed_inventory(self, scope):
        return self._get("bed_inventory", scope, BEDS)

    def allocations_overlapping(self, scope, start, end):
        return self._get("allocations_overlapping", scope, ALLOCATIONS)


class FakeFinanceRepository(_Recorder):
    def payments_between(self, scope, start, end):
        return self._get("payments_between", scope, PAYMENTS)

    def rent_invoices(self, scope, first_month, last_month):
        return self._get("rent_invoices", scope, RENT_INVOICES)

    def open_invoices(self, scope):
        return self._get("open_invoices", scope, OPEN_INVOICES)


class FakeTenantRepository(_Recorder):
    def tenants(self, scope):
        return self._get("tenants", scope, TENANTS)

    def allocations(self, scope, since):
        return self._get("allocations", scope, ALLOCATIONS)


class FakeMaintenanceRepository(_Recorder):
    def requests(self, scope, start, end):
        return self._get("requests", scope, MAINTENANCE)


class FakeComplaintRepository(_Recorder):
    def complaints(self, scope, start, end):
        return self._get("complaints", scope, COMPLAINTS)


class FakePropertyRepository(_Recorder):
    def properties(self, scope):
        return self._get("properties", scope, PROPERTIES)
