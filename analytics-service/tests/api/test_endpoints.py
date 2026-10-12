"""API tests with repository fakes and an overridden principal."""

from __future__ import annotations

import json

import pytest

from app.core.security import Role
from tests.conftest import principal
from tests.fakes import FakeDatabase, FakeRedisClient, db_down

ENDPOINTS = [
    "/analytics/occupancy",
    "/analytics/revenue",
    "/analytics/payments",
    "/analytics/tenants",
    "/analytics/maintenance",
    "/analytics/complaints",
    "/analytics/properties",
]


@pytest.mark.parametrize("url", ENDPOINTS)
def test_every_endpoint_returns_json(harness_factory, url):
    h = harness_factory(principal=principal())
    response = h.client.get(url)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["meta"]["organizationId"] == 10
    assert body["meta"]["fromMonth"] == "2025-11"
    assert body["meta"]["toMonth"] == "2026-10"
    assert body["meta"]["timezone"] == "Asia/Kolkata"
    assert "NaN" not in response.text


@pytest.mark.parametrize("url", ENDPOINTS)
def test_every_endpoint_handles_empty_data(harness_factory, url):
    h = harness_factory(principal=principal(), empty=True)
    response = h.client.get(url)
    assert response.status_code == 200, response.text
    assert "NaN" not in response.text


@pytest.mark.parametrize("url", ENDPOINTS)
def test_every_endpoint_exports_csv(harness_factory, url):
    h = harness_factory(principal=principal())
    response = h.client.get(url, params={"format": "csv"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/csv")
    name = url.rsplit("/", 1)[1]
    assert response.headers["content-disposition"] == f'attachment; filename="{name}-2026-10-05.csv"'
    assert response.text.startswith("# summary\nmetric,value\n")


def test_occupancy_payload(harness_factory):
    h = harness_factory(principal=principal())
    body = h.client.get("/analytics/occupancy", params={"from": "2026-01", "to": "2026-03"}).json()
    snap = body["snapshot"]
    assert (snap["totalBeds"], snap["occupiedBeds"], snap["maintenanceBeds"]) == (4, 2, 1)
    assert snap["occupancyRatePct"] == 66.67
    assert snap["rawOccupancyRatePct"] == 50.0
    assert [p["month"] for p in body["monthlyTrend"]] == ["2026-01", "2026-02", "2026-03"]
    assert body["monthlyTrend"][0]["occupiedBedDays"] == 31


def test_revenue_payload_excludes_deposits(harness_factory):
    h = harness_factory(principal=principal())
    body = h.client.get("/analytics/revenue", params={"from": "2026-09", "to": "2026-10"}).json()
    assert body["totals"]["netRentRevenue"] == 16000.0
    assert body["totals"]["depositsCollected"] == 20000.0
    assert [m["netRentRevenue"] for m in body["monthly"]] == [9000.0, 7000.0]


def test_revenue_period_into_the_future_uses_service_clock(harness_factory):
    h = harness_factory(principal=principal())  # clock: 2026-10-05
    body = h.client.get("/analytics/revenue", params={"from": "2026-09", "to": "2026-12"}).json()
    assert body["totals"]["averageMonthlyNetRentRevenue"] == 8000.0  # Sep + Oct only
    assert [m["momGrowthPct"] for m in body["monthly"]] == [None, -22.22, None, None]


def test_properties_sorting_and_validation(harness_factory):
    h = harness_factory(principal=principal())
    body = h.client.get("/analytics/properties", params={"sortBy": "name", "order": "asc"}).json()
    assert [p["propertyName"] for p in body["properties"]] == ["Alpha", "Beta"]
    assert [p["rank"] for p in body["properties"]] == [1, 2]
    assert body["sortBy"] == "name"
    assert body["currentMonth"] == "2026-10"
    response = h.client.get("/analytics/properties", params={"sortBy": "password; DROP TABLE"})
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_FAILED"
    assert "sortBy" in response.json()["message"]


def test_csv_neutralises_formula_injection(harness_factory):
    h = harness_factory(principal=principal())
    text = h.client.get("/analytics/revenue", params={"format": "csv"}).text
    assert "'=HYPERLINK" in text
    assert ",=HYPERLINK" not in text


@pytest.mark.parametrize(
    ("params", "fragment"),
    [
        ({"from": "2026-13"}, "from"),
        ({"from": "2026-05", "to": "2026-01"}, "must not be after"),
        ({"from": "2019-01", "to": "2026-01"}, "at most 60 months"),
        ({"format": "xml"}, "format"),
        ({"propertyId": "abc"}, "propertyId"),
        ({"propertyId": "0"}, "propertyId"),
        ({"from": "1500-01", "to": "1500-03"}, "supported range"),
        ({"from": "9999-01"}, "supported range"),
        ({"propertyId": str(2**63)}, "propertyId"),
        ({"organizationId": str(2**63)}, "organizationId"),
    ],
)
def test_validation_errors_use_backend_error_shape(harness_factory, params, fragment):
    h = harness_factory(principal=principal())
    response = h.client.get("/analytics/revenue", params=params)
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_FAILED"
    assert body["error"] == "Bad Request"
    assert fragment in body["message"]
    assert body["path"] == "/analytics/revenue"


def test_super_admin_out_of_range_ids_are_400_not_503(harness_factory):
    """A bigint overflow used to reach PostgreSQL (CAST AS bigint[]) and surface as a 503."""
    h = harness_factory(principal=principal(Role.SUPER_ADMIN, org=None), override_repositories=False)
    for params in ({"propertyId": str(2**63)}, {"organizationId": str(2**63)}):
        response = h.client.get("/analytics/occupancy", params=params)
        assert response.status_code == 400, response.text
    assert h.db.queries == []
    assert h.client.get("/analytics/occupancy", params={"propertyId": str(2**63 - 1)}).status_code == 200


def test_query_validation_errors_carry_field_errors_like_the_backend(harness_factory):
    h = harness_factory(principal=principal())
    body = h.client.get("/analytics/revenue", params={"propertyId": "abc", "format": "xml"}).json()
    assert body["code"] == "VALIDATION_FAILED"
    fields = {fe["field"]: fe for fe in body["fieldErrors"]}
    assert set(fields) == {"propertyId", "format"}
    assert fields["propertyId"]["rejectedValue"] == "abc"
    assert fields["format"]["message"]
    # non-field errors keep the backend's NON_EMPTY behaviour: no fieldErrors key at all
    other = h.client.get("/analytics/revenue", params={"from": "2026-05", "to": "2026-01"}).json()
    assert "fieldErrors" not in other


def test_database_failure_is_503(harness_factory):
    h = harness_factory(principal=principal(), fail=db_down())
    response = h.client.get("/analytics/occupancy")
    assert response.status_code == 503
    assert response.json()["code"] == "SERVICE_UNAVAILABLE"


def test_unexpected_error_is_500_with_correlation_id(harness_factory):
    h = harness_factory(principal=principal(), fail=RuntimeError("boom"))
    response = h.client.get("/analytics/tenants", headers={"X-Correlation-Id": "abc-123"})
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == "INTERNAL_ERROR"
    assert body["correlationId"] == "abc-123"
    assert "boom" not in body["message"]
    assert response.headers["X-Correlation-Id"] == "abc-123"


def test_correlation_id_echoed_generated_and_sanitised(harness_factory):
    h = harness_factory(principal=principal())
    assert (
        h.client.get("/health/live", headers={"X-Correlation-Id": "req-42"}).headers["X-Correlation-Id"]
        == "req-42"
    )
    generated = h.client.get("/health/live").headers["X-Correlation-Id"]
    assert len(generated) == 36
    bad = h.client.get("/health/live", headers={"X-Correlation-Id": "x" * 200}).headers["X-Correlation-Id"]
    assert bad != "x" * 200


def test_results_are_cached_per_scope_and_params(harness_factory):
    redis_client = FakeRedisClient()
    h = harness_factory(principal=principal(), redis_client=redis_client)
    first = h.client.get("/analytics/maintenance").json()
    second = h.client.get("/analytics/maintenance").json()
    assert first == second
    assert len(h.repos["maintenance"].calls) == 1
    keys = list(redis_client.store)
    assert len(keys) == 1
    assert keys[0].startswith("analytics:cache:maintenance:org-10:")
    h.client.get("/analytics/maintenance", params={"from": "2026-01"})
    assert len(h.repos["maintenance"].calls) == 2
    h.client.get("/analytics/maintenance", params={"format": "csv"})  # same data, served from cache
    assert len(h.repos["maintenance"].calls) == 2


def test_cache_failure_falls_back_to_computation(harness_factory):
    h = harness_factory(principal=principal(), redis_client=FakeRedisClient(fail=True))
    assert h.client.get("/analytics/complaints").status_code == 200
    assert h.client.get("/analytics/complaints").status_code == 200
    assert len(h.repos["complaints"].calls) == 2


def test_manager_scope_is_passed_to_repositories(harness_factory):
    h = harness_factory(principal=principal(Role.MANAGER, user_id=8), manager_properties={8: [2, 1]})
    h.client.get("/analytics/occupancy")
    scopes = {scope for _, scope in h.repos["occupancy"].calls}
    assert len(scopes) == 1
    (scope,) = scopes
    assert (scope.organization_id, scope.property_ids) == (10, (1, 2))


def test_health_ready_reports_dependencies(harness_factory):
    h = harness_factory(principal=principal())
    response = h.client.get("/health/ready")
    assert response.status_code == 200
    assert response.json() == {"status": "UP", "checks": {"database": "UP", "redis": "UP"}}


def test_health_ready_db_down_redis_down(harness_factory):
    h = harness_factory(db=FakeDatabase(healthy=False), redis_client=FakeRedisClient(fail=True))
    response = h.client.get("/health/ready")
    assert response.status_code == 503
    assert response.json()["checks"] == {"database": "DOWN", "redis": "DOWN"}


def test_health_ready_ignores_redis(harness_factory):
    h = harness_factory(redis_client=FakeRedisClient(fail=True))
    assert h.client.get("/health/ready").status_code == 200


def test_openapi_and_docs_are_served(harness_factory):
    h = harness_factory()
    spec = h.client.get("/analytics/openapi.json").json()
    assert "/analytics/properties" in spec["paths"]
    assert "HTTPBearer" in json.dumps(spec["components"]["securitySchemes"])
    assert h.client.get("/analytics/docs").status_code == 200


def test_unknown_route_uses_error_shape(harness_factory):
    h = harness_factory()
    response = h.client.get("/analytics/nope")
    assert response.status_code == 404
    assert response.json()["code"] == "NOT_FOUND"


def test_method_not_allowed(harness_factory):
    h = harness_factory(principal=principal())
    response = h.client.post("/analytics/occupancy")
    assert response.status_code == 405
    assert response.json()["code"] == "METHOD_NOT_ALLOWED"


def test_cors_preflight(harness_factory):
    h = harness_factory()
    response = h.client.options(
        "/analytics/occupancy",
        headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "GET"},
    )
    assert response.status_code == 200
    assert response.headers["access-control-allow-origin"] == "http://localhost:5173"


def test_real_repositories_against_fake_database(harness_factory):
    """Wire the real repositories to a recording database to prove the SQL path end-to-end."""
    db = FakeDatabase()
    h = harness_factory(principal=principal(), db=db, override_repositories=False)
    for url in ENDPOINTS:
        assert h.client.get(url).status_code == 200, url
    assert db.queries
    assert all(params.get("scope_org_id") == 10 for _, params in db.queries)
