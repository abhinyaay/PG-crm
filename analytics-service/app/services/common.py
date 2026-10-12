"""Small, dependency-free helpers shared by the service computations.

All helpers are NaN-safe: anything that would be NaN/inf in JSON becomes ``None`` (for undefined
ratios/averages) or ``0`` (for sums and counts).
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from datetime import date

import numpy as np
import pandas as pd

FAR_FUTURE = pd.Timestamp("2200-01-01")


def ensure_columns(df: pd.DataFrame | None, columns: Sequence[str]) -> pd.DataFrame:
    """Return a copy of ``df`` that has at least ``columns`` (missing ones filled with None)."""
    out = pd.DataFrame(columns=list(columns)) if df is None else df.copy()
    for col in columns:
        if col not in out.columns:
            out[col] = None
    return out


def to_utc(series: pd.Series) -> pd.Series:
    """Coerce timestamps (aware or naive-as-UTC) to ``datetime64[ns, UTC]``."""
    return pd.to_datetime(series, utc=True, errors="coerce")


def to_day(series: pd.Series) -> pd.Series:
    """Coerce ``DATE`` values to naive midnight ``datetime64[ns]``."""
    converted = pd.to_datetime(series, errors="coerce")
    if isinstance(converted.dtype, pd.DatetimeTZDtype):
        converted = converted.dt.tz_localize(None)
    return converted.dt.normalize()


def to_money(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce").fillna(0.0).astype(float)


def to_local_day(series: pd.Series, timezone: str) -> pd.Series:
    """UTC timestamps -> naive local calendar day in the business timezone."""
    return to_utc(series).dt.tz_convert(timezone).dt.tz_localize(None).dt.normalize()


def to_local_month(series: pd.Series, timezone: str) -> pd.Series:
    """UTC timestamps -> monthly ``Period`` in the business timezone."""
    return to_utc(series).dt.tz_convert(timezone).dt.tz_localize(None).dt.to_period("M")


def to_month(series: pd.Series) -> pd.Series:
    """DATE values -> monthly ``Period``."""
    return to_day(series).dt.to_period("M")


def day(value: date) -> pd.Timestamp:
    return pd.Timestamp(value).normalize()


def money(value: float | int | None) -> float:
    if value is None:
        return 0.0
    value = float(value)
    if math.isnan(value) or math.isinf(value):
        return 0.0
    return round(value, 2)


def ratio_pct(numerator: float, denominator: float) -> float | None:
    """``numerator / denominator * 100`` rounded to 2 dp; ``None`` when undefined."""
    if denominator is None or denominator == 0 or pd.isna(denominator):
        return None
    value = float(numerator) / float(denominator) * 100.0
    if math.isnan(value) or math.isinf(value):
        return None
    return round(value, 2)


def round_or_none(value: float | None, digits: int = 2) -> float | None:
    if value is None:
        return None
    value = float(value)
    if math.isnan(value) or math.isinf(value):
        return None
    return round(value, digits)


def safe_mean(values: Iterable[float]) -> float | None:
    arr = np.asarray(list(values), dtype=float)
    arr = arr[~np.isnan(arr)]
    return round_or_none(float(arr.mean())) if arr.size else None


def safe_median(values: Iterable[float]) -> float | None:
    arr = np.asarray(list(values), dtype=float)
    arr = arr[~np.isnan(arr)]
    return round_or_none(float(np.median(arr))) if arr.size else None


def count_by(series: pd.Series, keys: Sequence[str], *, other_key: str | None = None) -> dict[str, int]:
    """Value counts for a fixed set of keys (zero-filled, stable order). Unknown values are added under
    ``other_key`` if given, otherwise appended under their own name."""
    counts = series.fillna("UNKNOWN").astype(str).value_counts()
    out = {k: int(counts.get(k, 0)) for k in keys}
    for value, n in counts.items():
        if value in out:
            continue
        target = other_key or str(value)
        out[target] = out.get(target, 0) + int(n)
    return out
