"""Error model and global exception handlers.

The JSON error body deliberately mirrors the Spring Boot backend so clients can treat both services
uniformly::

    {"timestamp": "...", "status": 403, "error": "Forbidden", "code": "ACCESS_DENIED",
     "message": "...", "path": "/analytics/revenue", "correlationId": "..."}

Like the backend (``@JsonInclude(NON_EMPTY)``), ``fieldErrors`` is only present when it is not empty,
i.e. for request (query parameter) validation failures.
"""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from http import HTTPStatus
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy.exc import OperationalError, SQLAlchemyError
from sqlalchemy.exc import TimeoutError as PoolTimeoutError
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.context import current_correlation_id

logger = logging.getLogger("pgms.analytics.errors")

_DEFAULT_CODES = {
    400: "VALIDATION_FAILED",
    401: "UNAUTHORIZED",
    403: "ACCESS_DENIED",
    404: "NOT_FOUND",
    405: "METHOD_NOT_ALLOWED",
    406: "NOT_ACCEPTABLE",
    500: "INTERNAL_ERROR",
    503: "SERVICE_UNAVAILABLE",
}


class ApiError(Exception):
    """An error that maps 1:1 onto an HTTP error response."""

    def __init__(
        self, status: int, code: str, message: str, *, headers: dict[str, str] | None = None
    ) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.headers = headers


class UnauthorizedError(ApiError):
    def __init__(self, message: str = "Authentication is required") -> None:
        super().__init__(401, "UNAUTHORIZED", message, headers={"WWW-Authenticate": "Bearer"})


class ForbiddenError(ApiError):
    def __init__(self, message: str = "You do not have access to this resource") -> None:
        super().__init__(403, "ACCESS_DENIED", message)


class ValidationFailedError(ApiError):
    def __init__(self, message: str) -> None:
        super().__init__(400, "VALIDATION_FAILED", message)


class ServiceUnavailableError(ApiError):
    def __init__(self, message: str = "A required dependency is unavailable") -> None:
        super().__init__(503, "SERVICE_UNAVAILABLE", message)


def _reason(status: int) -> str:
    try:
        return HTTPStatus(status).phrase
    except ValueError:
        return "Error"


def error_body(
    status: int,
    code: str,
    message: str,
    path: str,
    field_errors: list[dict[str, Any]] | None = None,
) -> dict[str, object]:
    body: dict[str, object] = {
        "timestamp": datetime.now(tz=UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "status": status,
        "error": _reason(status),
        "code": code,
        "message": message,
        "path": path,
        "correlationId": current_correlation_id() or "",
    }
    if field_errors:
        body["fieldErrors"] = field_errors
    return body


def error_response(
    status: int,
    code: str,
    message: str,
    path: str,
    headers: dict[str, str] | None = None,
    field_errors: list[dict[str, Any]] | None = None,
) -> JSONResponse:
    return JSONResponse(
        status_code=status, content=error_body(status, code, message, path, field_errors), headers=headers
    )


def _safe_value(value: Any) -> Any:
    """Mirror of the backend's ``safeValue``: numbers/booleans as-is, other values as short text."""
    if value is None or isinstance(value, bool | int | float):
        return value
    text = str(value)
    return text[:100] + "..." if len(text) > 100 else text


def _field_violations(exc: RequestValidationError) -> list[dict[str, Any]]:
    violations = []
    for err in exc.errors():
        loc = [str(p) for p in err.get("loc", ()) if p not in ("query", "path", "body", "header")]
        violations.append(
            {
                "field": ".".join(loc) or "request",
                "message": err.get("msg", "invalid value"),
                "rejectedValue": _safe_value(err.get("input")),
            }
        )
    return violations


def _format_validation_errors(violations: list[dict[str, Any]]) -> str:
    parts = [f"{v['field']}: {v['message']}" for v in violations]
    return "; ".join(parts) or "Request validation failed"


def register_exception_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def _api_error(request: Request, exc: ApiError) -> JSONResponse:
        return error_response(exc.status, exc.code, exc.message, request.url.path, exc.headers)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError) -> JSONResponse:
        violations = _field_violations(exc)
        return error_response(
            400,
            "VALIDATION_FAILED",
            _format_validation_errors(violations),
            request.url.path,
            field_errors=violations,
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _DEFAULT_CODES.get(exc.status_code, "HTTP_ERROR")
        message = exc.detail if isinstance(exc.detail, str) else _reason(exc.status_code)
        return error_response(exc.status_code, code, message, request.url.path, getattr(exc, "headers", None))

    @app.exception_handler(SQLAlchemyError)
    async def _database(request: Request, exc: SQLAlchemyError) -> JSONResponse:
        if isinstance(exc, OperationalError | PoolTimeoutError):
            logger.warning("database unavailable", extra={"fields": {"error": type(exc).__name__}})
        else:
            logger.error("database error", exc_info=exc)
        return error_response(
            503, "SERVICE_UNAVAILABLE", "Analytics data source is unavailable", request.url.path
        )
