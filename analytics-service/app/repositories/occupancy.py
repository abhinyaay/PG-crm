from __future__ import annotations

from datetime import date

import pandas as pd

from app.core.scope import AnalyticsScope
from app.repositories.base import BaseRepository, scope_clause


class OccupancyRepository(BaseRepository):
    def bed_inventory(self, scope: AnalyticsScope) -> pd.DataFrame:
        """Current (non-deleted) beds in ACTIVE rooms of non-deleted properties."""
        where, params = scope_clause(scope, org_column="p.organization_id", property_column="b.property_id")
        return self._fetch(
            f"""
            SELECT b.id AS bed_id, b.room_id, b.property_id, p.name AS property_name, b.status,
                   r.room_type, r.capacity
            FROM analytics.beds b
            JOIN analytics.rooms r ON r.id = b.room_id
            JOIN analytics.properties p ON p.id = b.property_id
            WHERE r.status = 'ACTIVE' AND {where}
            """,
            params,
        )

    def allocations_overlapping(self, scope: AnalyticsScope, start: date, end: date) -> pd.DataFrame:
        """Allocations whose [start_date, end_date) overlaps [start, end)."""
        where, params = scope_clause(scope, org_column="a.organization_id", property_column="a.property_id")
        return self._fetch(
            f"""
            SELECT a.id, a.bed_id, a.property_id, a.tenant_id, a.status, a.start_date, a.end_date
            FROM analytics.allocations a
            WHERE a.start_date < :period_end
              AND (a.end_date IS NULL OR a.end_date > :period_start)
              AND {where}
            """,
            {**params, "period_start": start, "period_end": end},
        )
