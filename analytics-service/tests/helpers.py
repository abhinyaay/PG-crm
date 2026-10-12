from __future__ import annotations

from datetime import UTC, datetime

import pandas as pd

from app.schemas.common import ReportMeta
from app.services.period import ReportPeriod

TZ = "Asia/Kolkata"


def period(first: str, last: str, tz: str = TZ) -> ReportPeriod:
    return ReportPeriod(pd.Period(first, freq="M"), pd.Period(last, freq="M"), tz)


def meta() -> ReportMeta:
    return ReportMeta(generated_at=datetime(2026, 1, 1, tzinfo=UTC), timezone=TZ)


def ts(value: str) -> pd.Timestamp:
    return pd.Timestamp(value, tz="UTC")


def frame(columns: list[str], rows: list[tuple]) -> pd.DataFrame:
    return pd.DataFrame(rows, columns=columns)
