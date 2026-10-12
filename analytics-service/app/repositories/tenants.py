from __future__ import annotations

from datetime import date

import pandas as pd

from app.core.scope import AnalyticsScope
from app.repositories.base import BaseRepository, scope_clause


class TenantRepository(BaseRepository):
    def tenants(self, scope: AnalyticsScope) -> pd.DataFrame:
        where, params = scope_clause(scope, org_column="t.organization_id", property_column="t.property_id")
        return self._fetch(
            f"""
            SELECT t.id, t.property_id, t.status, t.gender, t.joining_date, t.move_out_date
            FROM analytics.tenants t
            WHERE {where}
            """,
            params,
        )

    def allocations(self, scope: AnalyticsScope, since: date) -> pd.DataFrame:
        """Allocations in scope of every tenant that still matters for a period starting at ``since``.

        A tenant matters when one of their (in-scope) allocations is ongoing or ended on/after ``since``.
        All allocations of those tenants are returned so transfer chains can be stitched into stays; a
        tenant whose allocations all ended before ``since`` cannot affect any tenant metric of the
        period (move-ins/outs, active-at-month-start, completed or ongoing stays) and is skipped.
        """
        where, params = scope_clause(scope, org_column="a.organization_id", property_column="a.property_id")
        recent_where, recent_params = scope_clause(
            scope, org_column="r.organization_id", property_column="r.property_id"
        )
        return self._fetch(
            f"""
            SELECT a.id, a.tenant_id, a.property_id, a.status, a.start_date, a.end_date, a.end_reason
            FROM analytics.allocations a
            WHERE a.tenant_id IN (
                SELECT r.tenant_id FROM analytics.allocations r
                WHERE (r.end_date IS NULL OR r.end_date >= :since) AND {recent_where}
            )
              AND {where}
            """,
            {**params, **recent_params, "since": since},
        )
