from __future__ import annotations

import pandas as pd

from app.core.scope import AnalyticsScope
from app.repositories.base import BaseRepository, scope_clause


class PropertyRepository(BaseRepository):
    def properties(self, scope: AnalyticsScope) -> pd.DataFrame:
        where, params = scope_clause(scope, org_column="p.organization_id", property_column="p.id")
        return self._fetch(
            f"""
            SELECT p.id, p.organization_id, p.code, p.name, p.city, p.status
            FROM analytics.properties p
            WHERE {where}
            ORDER BY p.name
            """,
            params,
        )
