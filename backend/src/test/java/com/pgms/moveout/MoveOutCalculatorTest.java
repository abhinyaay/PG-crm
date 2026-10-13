package com.pgms.moveout;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.moveout.MoveOutCalculator.NoticeTerms;
import com.pgms.moveout.MoveOutCalculator.Terms;
import com.pgms.moveout.MoveOutPolicy.LockInPenaltyRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MoveOutCalculatorTest {

    private static final BigDecimal RENT = new BigDecimal("9000");
    private static final LocalDate START = LocalDate.of(2026, 1, 15);

    private static Terms policy(int notice, boolean charge, int lockIn, LockInPenaltyRule rule, String flat) {
        return new Terms(notice, charge, lockIn, rule, flat == null ? null : new BigDecimal(flat));
    }

    @Test
    void dailyRentIsMonthlyRentOverThirtyHalfUp() {
        assertThat(MoveOutCalculator.dailyRent(RENT)).isEqualByComparingTo("300.00");
        assertThat(MoveOutCalculator.dailyRent(new BigDecimal("10000"))).isEqualByComparingTo("333.33");
        assertThat(MoveOutCalculator.dailyRent(new BigDecimal("10001"))).isEqualByComparingTo("333.37");
    }

    @Test
    void tenDaysNoticeOnAThirtyDayPolicyIsTwentyDaysShort() {
        LocalDate notice = LocalDate.of(2026, 10, 1);
        NoticeTerms t = MoveOutCalculator.noticeTerms(policy(30, true, 0, LockInPenaltyRule.NONE, null), notice,
                notice.plusDays(10), START, RENT);
        assertThat(t.earliestNoPenaltyDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(t.shortfallDays()).isEqualTo(20);
        assertThat(t.shortfallCharge()).isEqualByComparingTo("6000.00");
        assertThat(t.lockInEndDate()).isNull();
        assertThat(t.lockInPenalty()).isEqualByComparingTo("0");
        assertThat(t.totalCharges()).isEqualByComparingTo("6000.00");
    }

    @Test
    void enoughNoticeOrAZeroDayPolicyCostsNothing() {
        LocalDate notice = LocalDate.of(2026, 10, 1);
        assertThat(MoveOutCalculator.noticeTerms(policy(30, true, 0, LockInPenaltyRule.NONE, null), notice,
                notice.plusDays(45), START, RENT).shortfallDays()).isZero();
        NoticeTerms zero = MoveOutCalculator.noticeTerms(policy(0, true, 0, LockInPenaltyRule.NONE, null), notice, notice,
                START, RENT);
        assertThat(zero.shortfallDays()).isZero();
        assertThat(zero.earliestNoPenaltyDate()).isEqualTo(notice);
    }

    @Test
    void shortfallRuleNoneCountsDaysButChargesNothing() {
        LocalDate notice = LocalDate.of(2026, 10, 1);
        NoticeTerms t = MoveOutCalculator.noticeTerms(policy(30, false, 0, LockInPenaltyRule.NONE, null), notice, notice,
                START, RENT);
        assertThat(t.shortfallDays()).isEqualTo(30);
        assertThat(t.shortfallCharge()).isEqualByComparingTo("0");
    }

    @Test
    void lockInPenaltyAppliesOnlyBeforeTheLockInEnds() {
        Terms oneMonth = policy(0, true, 6, LockInPenaltyRule.ONE_MONTH_RENT, null);
        LocalDate notice = LocalDate.of(2026, 5, 1);
        NoticeTerms early = MoveOutCalculator.noticeTerms(oneMonth, notice, LocalDate.of(2026, 7, 14), START, RENT);
        assertThat(early.lockInEndDate()).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(early.lockInPenalty()).isEqualByComparingTo("9000.00");
        NoticeTerms onTheDay = MoveOutCalculator.noticeTerms(oneMonth, notice, LocalDate.of(2026, 7, 15), START, RENT);
        assertThat(onTheDay.lockInPenalty()).isEqualByComparingTo("0");

        Terms flat = policy(0, true, 6, LockInPenaltyRule.FLAT, "2500");
        assertThat(MoveOutCalculator.noticeTerms(flat, notice, LocalDate.of(2026, 6, 1), START, RENT).lockInPenalty())
                .isEqualByComparingTo("2500.00");
        Terms none = policy(0, true, 6, LockInPenaltyRule.NONE, null);
        assertThat(MoveOutCalculator.noticeTerms(none, notice, LocalDate.of(2026, 6, 1), START, RENT).lockInPenalty())
                .isEqualByComparingTo("0");
    }

    /** The lock-in counts from the first allocation of the stay chain: a transfer does not restart it. */
    @Test
    void lockInUsesTheStayStartPassedByTheCaller() {
        Terms oneMonth = policy(0, true, 3, LockInPenaltyRule.ONE_MONTH_RENT, null);
        LocalDate transferStart = LocalDate.of(2026, 4, 1);
        LocalDate moveOut = LocalDate.of(2026, 5, 1);
        assertThat(MoveOutCalculator.noticeTerms(oneMonth, moveOut, moveOut, START, RENT).lockInPenalty())
                .isEqualByComparingTo("0");
        assertThat(MoveOutCalculator.noticeTerms(oneMonth, moveOut, moveOut, transferStart, RENT).lockInPenalty())
                .isEqualByComparingTo("9000.00");
    }
}
