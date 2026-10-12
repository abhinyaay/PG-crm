from __future__ import annotations

from datetime import datetime

import pandas as pd

from app.core.scope import AnalyticsScope
from app.repositories.base import BaseRepository, scope_clause


class MaintenanceRepository(BaseRepository):
    def requests(self, scope: AnalyticsScope, start: datetime, end: datetime) -> pd.DataFrame:
        """Requests created or resolved in [start, end), plus every request that is open right now."""
        where, params = scope_clause(scope, org_column="m.organization_id", property_column="m.property_id")
        return self._fetch(
            f"""
            SELECT m.id, m.property_id, m.category, m.priority, m.status, m.created_at, m.resolved_at,
                   m.closed_at, m.sla_due_at, m.sla_breached
            FROM analytics.maintenance_requests m
            WHERE ((m.created_at >= :start_ts AND m.created_at < :end_ts)
                   OR (COALESCE(m.resolved_at, m.closed_at) >= :start_ts
                       AND COALESCE(m.resolved_at, m.closed_at) < :end_ts)
                   OR m.status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS'))
              AND {where}
            """,
            {**params, "start_ts": start, "end_ts": end},
        )
