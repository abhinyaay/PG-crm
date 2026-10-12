from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ApiModel(BaseModel):
    """Base for all response models: camelCase JSON, snake_case Python."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class ReportMeta(ApiModel):
    generated_at: datetime
    timezone: str
    organization_id: int | None = None
    property_ids: list[int] | None = None
    from_month: str | None = None
    to_month: str | None = None


class KeyCount(ApiModel):
    key: str
    count: int
    share_pct: float | None = None
