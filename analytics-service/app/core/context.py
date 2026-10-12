"""Per-request context shared between middleware, auth and logging.

A *mutable* object is stored in the context variable so that values set from code running in
FastAPI's threadpool (sync dependencies copy the context) are still visible to the middleware.
"""

from __future__ import annotations

from contextvars import ContextVar
from dataclasses import dataclass


@dataclass
class RequestContext:
    correlation_id: str
    user_id: int | None = None


_current: ContextVar[RequestContext | None] = ContextVar("analytics_request_context", default=None)


def set_request_context(ctx: RequestContext) -> None:
    _current.set(ctx)


def get_request_context() -> RequestContext | None:
    return _current.get()


def current_correlation_id() -> str | None:
    ctx = _current.get()
    return ctx.correlation_id if ctx else None
