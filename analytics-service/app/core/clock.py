"""Injectable clock so that "today" / "now" are deterministic in tests."""

from __future__ import annotations

from datetime import UTC, date, datetime
from zoneinfo import ZoneInfo


class Clock:
    def __init__(self, timezone: str) -> None:
        self.timezone = timezone
        self._tz = ZoneInfo(timezone)

    def now_utc(self) -> datetime:
        return datetime.now(tz=UTC)

    def now_local(self) -> datetime:
        return self.now_utc().astimezone(self._tz)

    def today(self) -> date:
        return self.now_local().date()


class FixedClock(Clock):
    """Clock frozen at a given instant (used by tests)."""

    def __init__(self, instant: datetime, timezone: str = "Asia/Kolkata") -> None:
        super().__init__(timezone)
        if instant.tzinfo is None:
            raise ValueError("FixedClock requires a timezone-aware datetime")
        self._instant = instant.astimezone(UTC)

    def now_utc(self) -> datetime:
        return self._instant
