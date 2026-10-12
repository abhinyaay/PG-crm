from datetime import date

import pandas as pd

from app.services.tenants import build_stays, build_tenant_report
from tests.helpers import frame, meta, period

ALLOC_COLS = ["id", "tenant_id", "property_id", "status", "start_date", "end_date"]
TENANT_COLS = ["id", "property_id", "status", "gender"]
TODAY = date(2026, 4, 10)


def _allocations():
    return frame(
        ALLOC_COLS,
        [
            # T1: room transfer on 2026-02-15 -> one stay [2026-01-10, 2026-04-01) = 81 days
            (1, 1, 1, "TRANSFERRED", date(2026, 1, 10), date(2026, 2, 15)),
            (2, 1, 1, "ENDED", date(2026, 2, 15), date(2026, 4, 1)),
            # T2: ongoing since 2025-12-01
            (3, 2, 1, "ACTIVE", date(2025, 12, 1), None),
            # T3: left and came back -> two stays (47 days, then ongoing)
            (4, 3, 1, "ENDED", date(2025, 12, 15), date(2026, 1, 31)),
            (5, 3, 1, "ACTIVE", date(2026, 3, 1), None),
            # T4: short stay of 10 days
            (6, 4, 1, "ENDED", date(2026, 2, 1), date(2026, 2, 11)),
        ],
    )


def _tenants():
    return frame(
        TENANT_COLS,
        [
            (1, 1, "MOVED_OUT", "MALE"),
            (2, 1, "ACTIVE", "FEMALE"),
            (3, 1, "ON_NOTICE", "MALE"),
            (4, 1, "MOVED_OUT", "FEMALE"),
            (5, 1, "PROSPECTIVE", None),
            (6, 1, "ACTIVE", None),
        ],
    )


def test_transfer_chain_is_one_stay_and_gap_starts_new_stay():
    stays = build_stays(_allocations())
    assert len(stays) == 5
    t1 = stays[stays["tenant_id"] == 1].iloc[0]
    assert (t1["stay_start"], t1["stay_end"]) == (pd.Timestamp("2026-01-10"), pd.Timestamp("2026-04-01"))
    assert t1["allocation_count"] == 2
    assert not t1["ongoing"]
    t3 = stays[stays["tenant_id"] == 3].sort_values("stay_start")
    assert t3["ongoing"].tolist() == [False, True]


def test_unsorted_and_overlapping_rows_are_merged():
    allocations = frame(
        ALLOC_COLS,
        [
            (2, 9, 1, "ENDED", date(2026, 1, 20), date(2026, 2, 1)),
            (1, 9, 1, "TRANSFERRED", date(2026, 1, 1), date(2026, 1, 25)),
        ],
    )
    stays = build_stays(allocations)
    assert len(stays) == 1
    assert stays.iloc[0]["stay_end"] == pd.Timestamp("2026-02-01")


def test_monthly_move_ins_outs_and_churn():
    report = build_tenant_report(
        _tenants(), _allocations(), period=period("2026-01", "2026-04"), today=TODAY, meta=meta()
    )
    by_month = {m.month: m for m in report.monthly}
    assert [by_month[m].move_ins for m in ("2026-01", "2026-02", "2026-03", "2026-04")] == [1, 1, 1, 0]
    assert [by_month[m].move_outs for m in ("2026-01", "2026-02", "2026-03", "2026-04")] == [1, 1, 0, 1]
    assert [by_month[m].active_at_month_start for m in ("2026-01", "2026-02", "2026-03", "2026-04")] == [
        2,
        2,
        2,
        3,
    ]
    assert by_month["2026-01"].churn_rate_pct == 50.0
    assert by_month["2026-03"].churn_rate_pct == 0.0
    assert by_month["2026-04"].churn_rate_pct == 33.33
    assert (report.summary.move_ins_in_period, report.summary.move_outs_in_period) == (3, 3)


def test_stay_duration_statistics():
    report = build_tenant_report(
        _tenants(), _allocations(), period=period("2026-01", "2026-04"), today=TODAY, meta=meta()
    )
    sd = report.stay_duration
    assert sd.completed_stays == 3  # 81, 47, 10 (transfer chain counted once)
    assert sd.average_completed_stay_days == 46.0
    assert sd.median_completed_stay_days == 47.0
    assert sd.stays_including_ongoing == 5  # + 130 (T2) + 40 (T3 second stay)
    assert sd.average_stay_days_including_ongoing == 61.6
    assert sd.median_stay_days_including_ongoing == 47.0


def test_status_and_gender_mix():
    report = build_tenant_report(
        _tenants(), _allocations(), period=period("2026-01", "2026-04"), today=TODAY, meta=meta()
    )
    assert {k.key: k.count for k in report.by_status} == {
        "PROSPECTIVE": 1,
        "ACTIVE": 2,
        "ON_NOTICE": 1,
        "MOVED_OUT": 2,
    }
    assert report.summary.active_tenants == 3
    assert report.summary.total_tenants == 6
    assert report.summary.currently_allocated_tenants == 2
    gender = {k.key: (k.count, k.share_pct) for k in report.gender_mix}
    assert gender == {"MALE": (1, 33.33), "FEMALE": (1, 33.33), "OTHER": (0, 0.0), "UNKNOWN": (1, 33.33)}


def test_future_month_has_no_active_base():
    report = build_tenant_report(
        _tenants(), _allocations(), period=period("2026-05", "2026-05"), today=TODAY, meta=meta()
    )
    assert report.monthly[0].active_at_month_start == 0
    assert report.monthly[0].churn_rate_pct is None


def test_empty_tenants():
    report = build_tenant_report(
        pd.DataFrame(columns=TENANT_COLS),
        pd.DataFrame(columns=ALLOC_COLS),
        period=period("2026-01", "2026-02"),
        today=TODAY,
        meta=meta(),
    )
    assert report.summary.active_tenants == 0
    assert report.stay_duration.average_completed_stay_days is None
    assert all(m.churn_rate_pct is None for m in report.monthly)
    assert all(k.share_pct is None for k in report.gender_mix)


def test_bounding_allocations_to_relevant_tenants_does_not_change_the_report():
    """Mirrors the repository predicate: tenants whose every allocation ended before the period start
    contribute nothing to any tenant metric, so dropping them is lossless."""
    since = pd.Timestamp("2026-01-01")
    extra = pd.DataFrame(
        [
            (90, 50, 1, "ENDED", date(2025, 3, 1), date(2025, 6, 1)),
            (91, 50, 1, "TRANSFERRED", date(2025, 6, 1), date(2025, 12, 31)),
            # old transfer chain of a tenant who is still relevant: must be kept to stitch the stay
            (92, 51, 1, "TRANSFERRED", date(2025, 5, 1), date(2025, 9, 1)),
            (93, 51, 1, "ENDED", date(2025, 9, 1), date(2026, 1, 1)),
        ],
        columns=ALLOC_COLS,
    )
    full = pd.concat([_allocations(), extra], ignore_index=True)
    ends = pd.to_datetime(full["end_date"])
    relevant = set(full.loc[ends.isna() | (ends >= since), "tenant_id"])
    bounded = full[full["tenant_id"].isin(relevant)]
    assert 50 not in relevant
    assert 51 in relevant
    kwargs = {"period": period("2026-01", "2026-04"), "today": TODAY, "meta": meta()}
    expected = build_tenant_report(_tenants(), full, **kwargs)
    assert build_tenant_report(_tenants(), bounded, **kwargs) == expected
