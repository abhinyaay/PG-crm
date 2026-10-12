package com.pgms.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/** Pure rent arithmetic, kept free of I/O so it is trivially unit-testable. */
public final class RentCalculator {

    private RentCalculator() {
    }

    /**
     * Rent for the remainder of the month starting on {@code from} (inclusive):
     * {@code monthlyRent * remainingDays / daysInMonth}, rounded half-up to paise.
     */
    public static BigDecimal proratedRent(BigDecimal monthlyRent, LocalDate from) {
        YearMonth month = YearMonth.from(from);
        int daysInMonth = month.lengthOfMonth();
        int remainingDays = daysInMonth - from.getDayOfMonth() + 1;
        if (remainingDays == daysInMonth) {
            return monthlyRent.setScale(2, RoundingMode.HALF_UP);
        }
        return monthlyRent.multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(daysInMonth), 2, RoundingMode.HALF_UP);
    }

    /** Due date inside the billed month, clamped to the issue date (an invoice is never due before it is issued). */
    public static LocalDate dueDate(YearMonth month, int rentDueDay, LocalDate issueDate) {
        LocalDate due = month.atDay(Math.min(rentDueDay, month.lengthOfMonth()));
        return due.isBefore(issueDate) ? issueDate : due;
    }
}
