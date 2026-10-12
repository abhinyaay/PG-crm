"""Lookups used to resolve a caller's data scope."""

from __future__ import annotations

from app.repositories.base import BaseRepository


class AccessRepository(BaseRepository):
    def manager_property_ids(self, user_id: int, organization_id: int) -> list[int]:
        """Properties assigned to a manager, restricted to the manager's own organization."""
        df = self._fetch(
            """
            SELECT upa.property_id
            FROM analytics.user_property_access upa
            JOIN analytics.properties p ON p.id = upa.property_id
            WHERE upa.user_id = :user_id AND p.organization_id = :org_id
            """,
            {"user_id": user_id, "org_id": organization_id},
        )
        return sorted(int(v) for v in df["property_id"].tolist()) if not df.empty else []

    def property_in_organization(self, property_id: int, organization_id: int) -> bool:
        df = self._fetch(
            "SELECT 1 AS found FROM analytics.properties WHERE id = :pid AND organization_id = :org_id",
            {"pid": property_id, "org_id": organization_id},
        )
        return not df.empty
