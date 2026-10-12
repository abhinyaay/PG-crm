"""Data-access scope derived from the caller's identity."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class AnalyticsScope:
    """Which rows a request may see.

    * ``organization_id`` - restrict to one tenant organization (``None`` = all, SUPER_ADMIN only).
    * ``property_ids``    - ``None`` = every property in the organization; a tuple (possibly empty)
      restricts to exactly those properties. An empty tuple matches nothing.
    """

    organization_id: int | None = None
    property_ids: tuple[int, ...] | None = None

    def __post_init__(self) -> None:
        if self.property_ids is not None:
            object.__setattr__(self, "property_ids", tuple(sorted(set(self.property_ids))))

    def as_dict(self) -> dict[str, Any]:
        return {
            "organizationId": self.organization_id,
            "propertyIds": list(self.property_ids) if self.property_ids is not None else None,
        }
