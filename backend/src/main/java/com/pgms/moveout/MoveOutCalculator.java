package com.pgms.moveout;

import com.pgms.moveout.MoveOutPolicy.LockInPenaltyRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure notice-period and lock-in arithmetic (docs/FEATURE_ROADMAP.md, Spec 4 "Calculation rules"). */
public final class MoveOutCalculator {

    private static final BigDecimal DAYS_PER_MONTH = BigDecimal.valueOf(30);

    private MoveOutCalculator() {
    }

    /** The policy values the calculation needs. */
    public record Terms(int noticePeriodDays, boolean chargeShortfall, int lockInMonths, LockInPenaltyRule lockInPenaltyRule,
                        BigDecimal lockInPenaltyAmount) {
    }

    /**
     * Outcome for leaving on {@code moveOutDate} after notice on {@code noticeDate}. {@code lockInEndDate} is null when
     * the policy has no lock-in.
     */
    public record NoticeTerms(int noticePeriodDays, LocalDate earliestNoPenaltyDate, int shortfallDays,
                              BigDecimal shortfallCharge, LocalDate lockInEndDate, BigDecimal lockInPenalty) {

        public BigDecimal totalCharges() {
            return shortfallCharge.add(lockInPenalty);
        }
    }

    /** {@code monthlyRent / 30}, half-up to paise. */
    public static BigDecimal dailyRent(BigDecimal monthlyRent) {
        return monthlyRent.divide(DAYS_PER_MONTH, 2, RoundingMode.HALF_UP);
    }

    /**
     * @param stayStart start of the first allocation of the stay chain (a transfer does not restart the lock-in)
     */
    public static NoticeTerms noticeTerms(Terms terms, LocalDate noticeDate, LocalDate moveOutDate, LocalDate stayStart,
                                          BigDecimal monthlyRent) {
        LocalDate earliest = noticeDate.plusDays(terms.noticePeriodDays());
        int shortfallDays = (int) Math.max(0, ChronoUnit.DAYS.between(moveOutDate, earliest));
        BigDecimal shortfallCharge = terms.chargeShortfall()
                ? dailyRent(monthlyRent).multiply(BigDecimal.valueOf(shortfallDays)).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        LocalDate lockInEnd = terms.lockInMonths() > 0 ? stayStart.plusMonths(terms.lockInMonths()) : null;
        BigDecimal penalty = BigDecimal.ZERO.setScale(2);
        if (lockInEnd != null && moveOutDate.isBefore(lockInEnd)) {
            penalty = switch (terms.lockInPenaltyRule()) {
                case NONE -> penalty;
                case ONE_MONTH_RENT -> monthlyRent.setScale(2, RoundingMode.HALF_UP);
                case FLAT -> terms.lockInPenaltyAmount() == null ? penalty
                        : terms.lockInPenaltyAmount().setScale(2, RoundingMode.HALF_UP);
            };
        }
        return new NoticeTerms(terms.noticePeriodDays(), earliest, shortfallDays, shortfallCharge, lockInEnd, penalty);
    }
}
