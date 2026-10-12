"""Reporting period (a closed range of calendar months in the business timezone)."""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, time
from zoneinfo import ZoneInfo

import pandas as pd

MONTH_PATTERN = r"^\d{4}-(0[1-9]|1[0-2])$"
_MONTH_RE = re.compile(MONTH_PATTERN)
DEFAULT_MONTHS = 12
# pandas Timestamps cover 1677-2262 and the services use 2200-01-01 as "open-ended"; anything outside
# this window is rejected up front instead of failing deep inside a computation (HTTP 500).
MIN_MONTH = pd.Period("1900-01", freq="M")
MAX_MONTH = pd.Period("2199-12", freq="M")


@dataclass(frozen=True)
class ReportPeriod:
    first_month: pd.Period
    last_month: pd.Period
    timezone: str = "Asia/Kolkata"

    @property
    def months(self) -> list[pd.Period]:
        return list(pd.period_range(self.first_month, self.last_month, freq="M"))

    @property
    def labels(self) -> list[str]:
        return [str(m) for m in self.months]

    @property
    def start_date(self) -> date:
        """First day of the first month (inclusive)."""
        return self.first_month.start_time.date()

    @property
    def end_date(self) -> date:
        """First day after the last month (exclusive)."""
        return (self.last_month + 1).start_time.date()

    @property
    def start_ts(self) -> datetime:
        """Local midnight at the start of the period, timezone-aware (for timestamptz filters)."""
        return datetime.combine(self.start_date, time.min, tzinfo=ZoneInfo(self.timezone))

    @property
    def end_ts(self) -> datetime:
        return datetime.combine(self.end_date, time.min, tzinfo=ZoneInfo(self.timezone))

    def as_params(self) -> dict[str, str]:
        return {"from": str(self.first_month), "to": str(self.last_month)}


def parse_month(value: str) -> pd.Period:
    if not _MONTH_RE.match(value):
        raise ValueError(f"'{value}' is not a valid month; expected YYYY-MM")
    year = int(value[:4])
    if not MIN_MONTH.year <= year <= MAX_MONTH.year:
        raise ValueError(f"'{value}' is outside the supported range {MIN_MONTH}..{MAX_MONTH}")
    return pd.Period(value, freq="M")


def build_period(
    from_month: str | None,
    to_month: str | None,
    *,
    today: date,
    timezone: str,
    max_months: int = 60,
    default_months: int = DEFAULT_MONTHS,
) -> ReportPeriod:
    """Resolve ``from``/``to`` query values. Defaults to the last ``default_months`` months including the
    current one. Raises ``ValueError`` with a user-facing message on invalid input."""
    current = pd.Period(today, freq="M")
    last = parse_month(to_month) if to_month else None
    first = parse_month(from_month) if from_month else None
    if last is None:
        last = current if first is None or first <= current else first
    if first is None:
        first = last - (default_months - 1)
    if first > last:
        raise ValueError("'from' must not be after 'to'")
    span = (last - first).n + 1
    if span > max_months:
        raise ValueError(f"period may span at most {max_months} months (requested {span})")
    return ReportPeriod(first_month=first, last_month=last, timezone=timezone)
