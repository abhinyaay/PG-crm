from __future__ import annotations

from pydantic import BaseModel


class HealthStatus(BaseModel):
    status: str
    checks: dict[str, str] = {}
