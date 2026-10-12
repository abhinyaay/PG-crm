from __future__ import annotations

from datetime import datetime

import pandas as pd

from app.core.scope import AnalyticsScope
from app.repositories.base import BaseRepository, scope_clause


class ComplaintRepository(BaseRepository):
    def complaints(self, scope: AnalyticsScope, start: datetime, end: datetime) -> pd.DataFrame:
        """Complaints created or resolved in [start, end), plus every complaint that is open right now."""
        where, params = scope_clause(scope, org_column="c.organization_id", property_column="c.property_id")
        return self._fetch(
            f"""
            SELECT c.id, c.property_id, c.category, c.priority, c.status, c.escalation_level,
                   c.created_at, c.resolved_at, c.closed_at, c.due_at
            FROM analytics.complaints c
            WHERE ((c.created_at >= :start_ts AND c.created_at < :end_ts)
                   OR (COALESCE(c.resolved_at, c.closed_at) >= :start_ts
                       AND COALESCE(c.resolved_at, c.closed_at) < :end_ts)
                   OR c.status IN ('OPEN', 'IN_PROGRESS', 'ESCALATED'))
              AND {where}
            """,
            {**params, "start_ts": start, "end_ts": end},
        )
