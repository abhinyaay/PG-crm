from __future__ import annotations

import json
import logging
from datetime import UTC, datetime

import pytest
from pydantic import ValidationError

from app.core.cache import ResultCache
from app.core.clock import Clock, FixedClock
from app.core.config import Settings
from app.core.context import RequestContext, set_request_context
from app.core.logging import JsonFormatter, configure_logging
from app.core.redis_gateway import RedisGateway, RedisUnavailableError
from app.core.scope import AnalyticsScope
from app.core.security import RevocationChecker
from app.db.database import SqlDatabase
from app.reports.base import ExportedFile, ReportExporter, TabularReport
from app.reports.csv_exporter import CsvExporter
from app.reports.registry import ExporterRegistry
from app.reports.tabular import model_to_tabular
from app.schemas.common import ApiModel, KeyCount
from tests.conftest import principal
from tests.fakes import FakeRedisClient


class Sample(ApiModel):
    total_value: float
    labels: list[str]
    items: list[KeyCount]
    empty_items: list[KeyCount]


SAMPLE = Sample(total_value=1.5, labels=["a", "b"], items=[KeyCount(key="=cmd", count=2)], empty_items=[])


# --- cache ------------------------------------------------------------------------------------


def test_cache_hit_miss_and_key_shape():
    client = FakeRedisClient()
    cache = ResultCache(RedisGateway(client), ttl_seconds=60)
    scope = AnalyticsScope(organization_id=4, property_ids=(2, 1))
    calls = []

    def compute():
        calls.append(1)
        return SAMPLE

    assert cache.get_or_compute("x", scope, {"a": 1}, Sample, compute) == SAMPLE
    assert cache.get_or_compute("x", scope, {"a": 1}, Sample, compute) == SAMPLE
    assert len(calls) == 1
    (key,) = client.store
    assert key.startswith("analytics:cache:x:org-4:")
    assert ResultCache.build_key("x", AnalyticsScope(organization_id=4, property_ids=(1, 2)), {"a": 1}) == key
    assert ResultCache.build_key("x", scope, {"a": 2}) != key
    assert ResultCache.build_key("x", AnalyticsScope(), {}).startswith("analytics:cache:x:org-all:")


def test_cache_ignores_corrupt_entries_and_disabled_modes():
    client = FakeRedisClient()
    cache = ResultCache(RedisGateway(client), ttl_seconds=60)
    key = ResultCache.build_key("x", AnalyticsScope(), {})
    client.store[key] = "{not json"
    assert cache.get_or_compute("x", AnalyticsScope(), {}, Sample, lambda: SAMPLE) == SAMPLE
    assert json.loads(client.store[key])["totalValue"] == 1.5
    assert not ResultCache(None, 60).enabled
    assert not ResultCache(RedisGateway(client), 0).enabled
    assert ResultCache(None, 60).get_or_compute("x", AnalyticsScope(), {}, Sample, lambda: SAMPLE) == SAMPLE


# --- redis gateway ----------------------------------------------------------------------------


def test_gateway_cooldown_after_failure():
    now = [100.0]
    client = FakeRedisClient(fail=True)
    gw = RedisGateway(client, cooldown_seconds=5, monotonic=lambda: now[0])
    with pytest.raises(RedisUnavailableError):
        gw.get("k")
    assert gw.in_cooldown
    client.fail = False
    with pytest.raises(RedisUnavailableError):
        gw.get("k")  # still cooling down -> redis not even called
    assert client.calls == 1
    now[0] = 106.0
    assert gw.get("k") is None
    gw.setex("k", 5, "v")
    assert gw.mget(["k", "z"]) == ["v", None]
    assert gw.ping() is True
    client.fail = True
    assert gw.ping() is False


def test_gateway_decodes_bytes():
    client = FakeRedisClient()
    client.store["k"] = b"v"  # type: ignore[assignment]
    assert RedisGateway(client).get("k") == "v"


def test_revocation_without_redis_and_malformed_marker():
    assert RevocationChecker(None).is_revoked(principal()) is False
    client = FakeRedisClient()
    client.store["auth:invalid-before:5"] = "garbage"
    assert RevocationChecker(RedisGateway(client)).is_revoked(principal()) is False
    client.store["auth:invalid-before:5"] = "1.0"
    assert RevocationChecker(RedisGateway(client)).is_revoked(principal()) is True  # iat 0 < 1


# --- reports ----------------------------------------------------------------------------------


def test_model_to_tabular_and_csv():
    report = model_to_tabular("sample", SAMPLE)
    names = [s.name for s in report.sections]
    assert names == ["summary", "items", "emptyItems"]
    assert report.sections[0].rows == [["totalValue", 1.5], ["labels", "a; b"]]
    assert report.sections[1].columns == ["key", "count", "sharePct"]
    exported = CsvExporter().export(report)
    text = exported.content.decode()
    assert text == (
        "# summary\nmetric,value\ntotalValue,1.5\nlabels,a; b\n\n"
        "# items\nkey,count,sharePct\n'=cmd,2,\n\n# emptyItems\n"
    )
    assert exported.media_type.startswith("text/csv")


def test_tabular_flattens_nested_objects_in_lists():
    class Inner(ApiModel):
        a: int

    class Row(ApiModel):
        inner: Inner
        tags: list[str]

    class Outer(ApiModel):
        rows: list[Row]

    section = model_to_tabular("t", Outer(rows=[Row(inner=Inner(a=1), tags=["x"])])).sections[1]
    assert section.columns == ["inner.a", "tags"]
    assert section.rows == [[1, "x"]]


def test_registry_is_extensible():
    class FakeExcel(ReportExporter):
        format = "xlsx"
        media_type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        extension = "xlsx"

        def export(self, report: TabularReport) -> ExportedFile:
            return ExportedFile(b"", self.media_type, self.extension)

    registry = ExporterRegistry([CsvExporter()])
    registry.register(FakeExcel())
    assert registry.formats() == ["csv", "xlsx"]
    with pytest.raises(KeyError, match="pdf"):
        registry.get("pdf")


# --- config / clock / logging / db -------------------------------------------------------------


def test_settings_parsing():
    s = Settings(cors_origins=" http://a , ,http://b ", log_level="debug")
    assert s.cors_origin_list == ["http://a", "http://b"]
    assert s.log_level == "DEBUG"
    with pytest.raises(ValidationError):
        Settings(business_timezone="Mars/Olympus")


def test_clocks():
    fixed = FixedClock(datetime(2026, 1, 31, 20, 0, tzinfo=UTC), "Asia/Kolkata")
    assert fixed.today().isoformat() == "2026-02-01"
    assert fixed.now_local().utcoffset().total_seconds() == 19800
    assert Clock("UTC").now_utc().tzinfo is UTC
    with pytest.raises(ValueError, match="timezone-aware"):
        FixedClock(datetime(2026, 1, 1))


def test_json_formatter_includes_context_and_fields():
    set_request_context(RequestContext(correlation_id="cid-1", user_id=7))
    record = logging.LogRecord("t", logging.INFO, __file__, 1, "hello %s", ("x",), None)
    record.fields = {"status": 200}
    payload = json.loads(JsonFormatter().format(record))
    assert payload["message"] == "hello x"
    assert (payload["correlation_id"], payload["user_id"], payload["status"]) == ("cid-1", 7, 200)
    try:
        raise ValueError("bad")
    except ValueError:
        import sys

        record = logging.LogRecord("t", logging.ERROR, __file__, 1, "err", (), sys.exc_info())
    assert "ValueError" in json.loads(JsonFormatter().format(record))["exception"]


def test_configure_logging_installs_json_handler():
    configure_logging("INFO")
    root = logging.getLogger()
    assert isinstance(root.handlers[0].formatter, JsonFormatter)
    assert logging.getLogger("uvicorn.access").disabled


def test_sql_database_ping_failure_is_reported_not_raised():
    db = SqlDatabase(
        "postgresql+psycopg://nobody@127.0.0.1:1/none", connect_timeout_seconds=1, statement_timeout_ms=500
    )
    try:
        assert db.ping() is False
    finally:
        db.dispose()
