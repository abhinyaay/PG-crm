"""Shared repository plumbing: scope filtering in SQL.

Column names interpolated into SQL come exclusively from constants in this package; every value
(organization id, property ids, dates) is a bound parameter.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

import pandas as pd

from app.core.scope import AnalyticsScope
from app.db.database import Database


def scope_clause(
    scope: AnalyticsScope, *, org_column: str | None, property_column: str
) -> tuple[str, dict[str, Any]]:
    """SQL predicate restricting rows to ``scope``.

    ``org_column`` may be ``None`` for views without an ``organization_id`` column; the organization is
    then resolved through ``analytics.properties``.
    """
    clauses: list[str] = []
    params: dict[str, Any] = {}
    if scope.organization_id is not None:
        if org_column is not None:
            clauses.append(f"{org_column} = :scope_org_id")
        else:
            clauses.append(
                f"{property_column} IN (SELECT sp.id FROM analytics.properties sp "
                "WHERE sp.organization_id = :scope_org_id)"
            )
        params["scope_org_id"] = scope.organization_id
    if scope.property_ids is not None:
        if not scope.property_ids:
            clauses.append("FALSE")
        else:
            clauses.append(f"{property_column} = ANY(CAST(:scope_property_ids AS bigint[]))")
            params["scope_property_ids"] = list(scope.property_ids)
    return (" AND ".join(clauses) if clauses else "TRUE"), params


class BaseRepository:
    def __init__(self, db: Database) -> None:
        self._db = db

    def _fetch(self, sql: str, params: Mapping[str, Any]) -> pd.DataFrame:
        return self._db.fetch_df(sql, params)
