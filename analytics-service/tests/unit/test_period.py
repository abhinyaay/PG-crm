from datetime import date

import pandas as pd
import pytest

from app.services.period import build_period, parse_month


def test_default_is_last_twelve_months_including_current():
    p = build_period(None, None, today=date(2026, 10, 5), timezone="Asia/Kolkata")
    assert str(p.first_month) == "2025-11"
    assert str(p.last_month) == "2026-10"
    assert len(p.months) == 12
    assert p.start_date == date(2025, 11, 1)
    assert p.end_date == date(2026, 11, 1)
    assert p.start_ts.isoformat() == "2025-11-01T00:00:00+05:30"


def test_only_from_extends_to_current_month():
    p = build_period("2026-07", None, today=date(2026, 10, 5), timezone="UTC")
    assert p.labels == ["2026-07", "2026-08", "2026-09", "2026-10"]


def test_only_from_in_future_is_single_month():
    p = build_period("2027-02", None, today=date(2026, 10, 5), timezone="UTC")
    assert p.labels == ["2027-02"]


def test_only_to_goes_back_twelve_months():
    p = build_period(None, "2026-03", today=date(2026, 10, 5), timezone="UTC")
    assert str(p.first_month) == "2025-04"


@pytest.mark.parametrize(
    ("from_month", "to_month", "message"),
    [
        ("2026-05", "2026-04", "must not be after"),
        ("2020-01", "2026-04", "at most 60 months"),
        ("2026-13", None, "not a valid month"),
    ],
)
def test_invalid_periods(from_month, to_month, message):
    with pytest.raises(ValueError, match=message):
        build_period(from_month, to_month, today=date(2026, 10, 5), timezone="UTC")


def test_parse_month():
    assert parse_month("2026-02") == pd.Period("2026-02", freq="M")
    with pytest.raises(ValueError, match="valid month"):
        parse_month("26-2")


@pytest.mark.parametrize("value", ["0000-01", "1500-01", "1899-12", "2200-01", "9999-12"])
def test_months_outside_supported_years_are_rejected(value):
    # pandas Timestamps only cover 1677-2262; such months used to blow up later with a 500
    with pytest.raises(ValueError, match="supported range"):
        parse_month(value)


def test_supported_year_bounds_are_accepted():
    assert str(parse_month("1900-01")) == "1900-01"
    p = build_period("2199-01", "2199-12", today=date(2026, 10, 5), timezone="Asia/Kolkata")
    assert p.end_date == date(2200, 1, 1)
