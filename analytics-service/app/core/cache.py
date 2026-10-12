"""Redis-backed cache of computed analytics results (JSON)."""

from __future__ import annotations

import contextlib
import hashlib
import json
import logging
from collections.abc import Callable, Mapping
from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

from app.core.redis_gateway import RedisGateway, RedisUnavailableError
from app.core.scope import AnalyticsScope

logger = logging.getLogger("pgms.analytics.cache")

CACHE_PREFIX = "analytics:cache:"

M = TypeVar("M", bound=BaseModel)


class ResultCache:
    def __init__(self, redis: RedisGateway | None, ttl_seconds: int) -> None:
        self._redis = redis
        self._ttl = ttl_seconds

    @property
    def enabled(self) -> bool:
        return self._redis is not None and self._ttl > 0

    @staticmethod
    def build_key(endpoint: str, scope: AnalyticsScope, params: Mapping[str, Any]) -> str:
        raw = json.dumps({"scope": scope.as_dict(), "params": dict(params)}, sort_keys=True, default=str)
        digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:40]
        org = scope.organization_id if scope.organization_id is not None else "all"
        return f"{CACHE_PREFIX}{endpoint}:org-{org}:{digest}"

    def get_or_compute(
        self,
        endpoint: str,
        scope: AnalyticsScope,
        params: Mapping[str, Any],
        model: type[M],
        compute: Callable[[], M],
    ) -> M:
        redis = self._redis
        if redis is None or self._ttl <= 0:
            return compute()
        key = self.build_key(endpoint, scope, params)
        try:
            cached = redis.get(key)
        except RedisUnavailableError:
            cached = None
        if cached is not None:
            try:
                return model.model_validate_json(cached)
            except ValidationError:
                logger.warning("discarding unreadable cache entry", extra={"fields": {"key": key}})
        result = compute()
        with contextlib.suppress(RedisUnavailableError):
            redis.setex(key, self._ttl, result.model_dump_json(by_alias=True))
        return result
