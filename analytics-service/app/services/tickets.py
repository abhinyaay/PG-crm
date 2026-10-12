"""Helpers shared by the maintenance and complaint analytics."""

from __future__ import annotations

import pandas as pd

from app.schemas.common import KeyCount
from app.services.common import count_by, ratio_pct, to_local_month, to_utc


def add_ticket_columns(df: pd.DataFrame, timezone: str) -> pd.DataFrame:
    """Normalise timestamps and add ``created_month``, ``resolved_ts`` (resolved_at, falling back to
    closed_at), ``resolved_month`` and ``resolution_hours``."""
    out = df.copy()
    out["created_ts"] = to_utc(out["created_at"])
    resolved = to_utc(out["resolved_at"])
    if "closed_at" in out.columns:
        resolved = resolved.fillna(to_utc(out["closed_at"]))
    out["resolved_ts"] = resolved
    out["created_month"] = to_local_month(out["created_ts"], timezone)
    out["resolved_month"] = to_local_month(out["resolved_ts"], timezone)
    hours = (out["resolved_ts"] - out["created_ts"]).dt.total_seconds() / 3600.0
    out["resolution_hours"] = hours.clip(lower=0.0)
    return out


def key_counts(series: pd.Series, keys: tuple[str, ...]) -> list[KeyCount]:
    counts = count_by(series, keys)
    total = sum(counts.values())
    return [KeyCount(key=k, count=v, share_pct=ratio_pct(v, total)) for k, v in counts.items()]
