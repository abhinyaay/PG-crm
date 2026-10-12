import pandas as pd

from app.services.common import (
    count_by,
    ensure_columns,
    money,
    ratio_pct,
    round_or_none,
    safe_mean,
    safe_median,
    to_day,
    to_local_month,
)


def test_ratio_pct_handles_zero_and_nan():
    assert ratio_pct(1, 3) == 33.33
    assert ratio_pct(5, 0) is None
    assert ratio_pct(5, float("nan")) is None


def test_money_never_returns_nan():
    assert money(float("nan")) == 0.0
    assert money(None) == 0.0
    assert money(float("inf")) == 0.0
    assert money(1234.5678) == 1234.57


def test_round_or_none():
    assert round_or_none(None) is None
    assert round_or_none(float("nan")) is None
    assert round_or_none(2.345678) == 2.35


def test_safe_mean_and_median_empty():
    assert safe_mean([]) is None
    assert safe_median([]) is None
    assert safe_mean([1.0, float("nan"), 3.0]) == 2.0
    assert safe_median([5.0, 1.0, 3.0]) == 3.0


def test_count_by_zero_fills_and_buckets_unknown():
    s = pd.Series(["A", "A", None, "Z"])
    assert count_by(s, ("A", "B")) == {"A": 2, "B": 0, "UNKNOWN": 1, "Z": 1}
    assert count_by(s, ("A", "B", "UNKNOWN"), other_key="UNKNOWN") == {"A": 2, "B": 0, "UNKNOWN": 2}


def test_local_month_uses_business_timezone():
    s = pd.Series([pd.Timestamp("2026-01-31T19:00:00Z")])  # 2026-02-01 00:30 IST
    assert str(to_local_month(s, "Asia/Kolkata").iloc[0]) == "2026-02"
    assert str(to_local_month(s, "UTC").iloc[0]) == "2026-01"


def test_to_day_from_python_dates_and_none():
    from datetime import date

    s = to_day(pd.Series([date(2026, 1, 2), None]))
    assert s.iloc[0] == pd.Timestamp("2026-01-02")
    assert pd.isna(s.iloc[1])


def test_ensure_columns_on_none():
    df = ensure_columns(None, ["a", "b"])
    assert list(df.columns) == ["a", "b"]
    assert df.empty
