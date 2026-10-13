package com.pgms.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure late-fee arithmetic (no I/O). The job asks "how much late fee is owed on this invoice today that has not been
 * charged yet?", which makes every run idempotent and lets a missed day catch up:
 * <ul>
 *   <li>{@code effectiveDueDate}: the due date, or - for an invoice that was due the day it was issued (a move-in
 *       recorded after the rent due day) - the issue date plus {@code moveInGraceDays};</li>
 *   <li>{@code firstLateDay = effectiveDueDate + graceDays + 1}: nothing is charged before it;</li>
 *   <li>FLAT / PERCENT_OF_RENT: one charge per invoice, keyed by {@code firstLateDay};</li>
 *   <li>PER_DAY: {@code value x days late} accrued so far minus what was already charged, keyed by today;</li>
 *   <li>the optional cap bounds the cumulative charges (waivers do not re-open room under the cap).</li>
 * </ul>
 */
public final class LateFeeCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private LateFeeCalculator() {
    }

    /** What the calculator needs to know about one invoice. */
    public record InvoiceFacts(LocalDate issueDate, LocalDate dueDate, boolean moveIn, BigDecimal rentAmount,
                               BigDecimal alreadyCharged, boolean hasCharges) {
    }

    /** {@code amount == 0} means nothing to charge today. */
    public record Assessment(BigDecimal amount, LocalDate periodKey, LocalDate firstLateDay) {

        public boolean charges() {
            return amount.signum() > 0;
        }

        static Assessment none(LocalDate firstLateDay) {
            return new Assessment(BigDecimal.ZERO.setScale(2), null, firstLateDay);
        }
    }

    public static LocalDate effectiveDueDate(BillingPolicy policy, LocalDate issueDate, LocalDate dueDate) {
        if (issueDate != null && !dueDate.isAfter(issueDate)) {
            LocalDate withGrace = issueDate.plusDays(policy.moveInGraceDays());
            return withGrace.isAfter(dueDate) ? withGrace : dueDate;
        }
        return dueDate;
    }

    public static LocalDate firstLateDay(BillingPolicy policy, LocalDate issueDate, LocalDate dueDate) {
        return effectiveDueDate(policy, issueDate, dueDate).plusDays(policy.graceDays() + 1L);
    }

    public static Assessment assess(BillingPolicy policy, InvoiceFacts invoice, LocalDate today) {
        LocalDate firstLateDay = firstLateDay(policy, invoice.issueDate(), invoice.dueDate());
        if (!policy.lateFeeEnabled() || (invoice.moveIn() && !policy.lateFeeOnMoveIn()) || today.isBefore(firstLateDay)) {
            return Assessment.none(firstLateDay);
        }
        BigDecimal charged = invoice.alreadyCharged() == null ? BigDecimal.ZERO : invoice.alreadyCharged();
        BigDecimal value = policy.lateFeeValue() == null ? BigDecimal.ZERO : policy.lateFeeValue();
        BigDecimal amount;
        LocalDate key;
        switch (policy.lateFeeType()) {
            case FLAT -> {
                if (invoice.hasCharges()) {
                    return Assessment.none(firstLateDay);
                }
                amount = value;
                key = firstLateDay;
            }
            case PERCENT_OF_RENT -> {
                if (invoice.hasCharges()) {
                    return Assessment.none(firstLateDay);
                }
                BigDecimal rent = invoice.rentAmount() == null ? BigDecimal.ZERO : invoice.rentAmount();
                amount = rent.multiply(value).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                key = firstLateDay;
            }
            case PER_DAY -> {
                long days = ChronoUnit.DAYS.between(firstLateDay, today) + 1;
                amount = value.multiply(BigDecimal.valueOf(days)).subtract(charged);
                key = today;
            }
            default -> throw new IllegalStateException("Unknown late fee type " + policy.lateFeeType());
        }
        if (policy.lateFeeCap() != null) {
            amount = amount.min(policy.lateFeeCap().subtract(charged));
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        return amount.signum() > 0 ? new Assessment(amount, key, firstLateDay) : Assessment.none(firstLateDay);
    }
}
