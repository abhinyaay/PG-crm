"""Fail-open wrapper around redis-py.

Redis is an optimisation (result cache) and a best-effort security control (token revocation) for
this read-only service; it must never take the service down. Every call has a short socket
timeout, errors are logged and swallowed, and after a failure Redis is skipped for a cooldown period
so a dead Redis does not add a timeout to every request.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from typing import Any, Protocol, TypeVar

from redis.exceptions import RedisError

logger = logging.getLogger("pgms.analytics.redis")

T = TypeVar("T")


class RedisLike(Protocol):
    def get(self, name: str) -> Any: ...
    def mget(self, keys: list[str]) -> list[Any]: ...
    def setex(self, name: str, time: int, value: str) -> Any: ...
    def ping(self) -> Any: ...


class RedisUnavailableError(Exception):
    """Raised internally when Redis could not be used."""


class RedisGateway:
    def __init__(
        self,
        client: RedisLike,
        *,
        cooldown_seconds: float = 5.0,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        self._client = client
        self._cooldown = cooldown_seconds
        self._monotonic = monotonic
        self._down_until = 0.0

    @property
    def in_cooldown(self) -> bool:
        return self._monotonic() < self._down_until

    def _call(self, operation: str, fn: Callable[[], T]) -> T:
        if self.in_cooldown:
            raise RedisUnavailableError(f"redis in cooldown ({operation})")
        try:
            return fn()
        except (RedisError, OSError) as exc:
            self._down_until = self._monotonic() + self._cooldown
            logger.warning(
                "redis operation failed; continuing without redis",
                extra={"fields": {"operation": operation, "error": type(exc).__name__}},
            )
            raise RedisUnavailableError(str(exc)) from exc

    def get(self, key: str) -> str | None:
        value = self._call("get", lambda: self._client.get(key))
        return _as_text(value)

    def mget(self, keys: list[str]) -> list[str | None]:
        values = self._call("mget", lambda: self._client.mget(keys))
        return [_as_text(v) for v in values]

    def setex(self, key: str, ttl_seconds: int, value: str) -> None:
        self._call("setex", lambda: self._client.setex(key, ttl_seconds, value))

    def ping(self) -> bool:
        """Health probe: ignores the cooldown so readiness always reflects the real state."""
        try:
            return bool(self._client.ping())
        except (RedisError, OSError):
            return False


def _as_text(value: Any) -> str | None:
    if value is None:
        return None
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return str(value)
