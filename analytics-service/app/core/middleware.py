"""Pure-ASGI middleware: correlation id, access log, last-resort error handling."""

from __future__ import annotations

import json
import logging
import re
import time
import uuid

from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.core.context import RequestContext, set_request_context
from app.core.errors import error_body

CORRELATION_HEADER = "X-Correlation-Id"
_HEADER_BYTES = CORRELATION_HEADER.lower().encode("latin-1")
_VALID_ID = re.compile(r"^[A-Za-z0-9._:\-]{1,64}$")
_QUIET_PATHS = ("/health/",)

access_logger = logging.getLogger("pgms.analytics.access")
error_logger = logging.getLogger("pgms.analytics.errors")


def _incoming_correlation_id(scope: Scope) -> str:
    for name, value in scope.get("headers", []):
        if name == _HEADER_BYTES:
            candidate = value.decode("latin-1").strip()
            if _VALID_ID.match(candidate):
                return candidate
            break
    return str(uuid.uuid4())


class CorrelationIdMiddleware:
    """Reads/creates ``X-Correlation-Id``, echoes it back, logs one line per request and converts
    any unhandled exception into the standard 500 error body."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        ctx = RequestContext(correlation_id=_incoming_correlation_id(scope))
        set_request_context(ctx)
        started = time.perf_counter()
        status_holder = {"status": 500, "started": False}

        async def send_wrapper(message: Message) -> None:
            if message["type"] == "http.response.start":
                status_holder["status"] = message["status"]
                status_holder["started"] = True
                headers = [(k, v) for k, v in message.get("headers", []) if k.lower() != _HEADER_BYTES]
                headers.append((_HEADER_BYTES, ctx.correlation_id.encode("latin-1")))
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_wrapper)
        except Exception as exc:
            error_logger.error("unhandled error", exc_info=exc)
            if status_holder["started"]:
                raise
            body = json.dumps(
                error_body(500, "INTERNAL_ERROR", "An unexpected error occurred", scope.get("path", ""))
            ).encode("utf-8")
            await send_wrapper(
                {
                    "type": "http.response.start",
                    "status": 500,
                    "headers": [
                        (b"content-type", b"application/json"),
                        (b"content-length", str(len(body)).encode("latin-1")),
                    ],
                }
            )
            await send_wrapper({"type": "http.response.body", "body": body})
        finally:
            path = scope.get("path", "")
            level = logging.DEBUG if path.startswith(_QUIET_PATHS) else logging.INFO
            access_logger.log(
                level,
                "request completed",
                extra={
                    "fields": {
                        "method": scope.get("method"),
                        "path": path,
                        "status": status_holder["status"],
                        "duration_ms": round((time.perf_counter() - started) * 1000, 2),
                        "correlation_id": ctx.correlation_id,
                        "user_id": ctx.user_id,
                    }
                },
            )
