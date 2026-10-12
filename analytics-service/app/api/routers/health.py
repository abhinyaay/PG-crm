"""Liveness / readiness probes (unauthenticated, not routed through nginx)."""

from __future__ import annotations

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from app.core.redis_gateway import RedisGateway
from app.schemas.health import HealthStatus

router = APIRouter(prefix="/health", tags=["health"])


@router.get("/live", response_model=HealthStatus)
def live() -> HealthStatus:
    return HealthStatus(status="UP")


async def _redis_status(redis: RedisGateway | None) -> str:
    """Redis is reported for visibility but never affects readiness (the service fails open)."""
    if redis is None:
        return "DISABLED"
    return "UP" if await run_in_threadpool(redis.ping) else "DOWN"


@router.get("/ready", response_model=HealthStatus, responses={503: {"model": HealthStatus}})
async def ready(request: Request) -> JSONResponse:
    state = request.app.state
    db_ok = await run_in_threadpool(state.database.ping)
    redis_status = await _redis_status(state.redis)
    body = HealthStatus(
        status="UP" if db_ok else "DOWN",
        checks={"database": "UP" if db_ok else "DOWN", "redis": redis_status},
    )
    return JSONResponse(status_code=200 if db_ok else 503, content=body.model_dump())
