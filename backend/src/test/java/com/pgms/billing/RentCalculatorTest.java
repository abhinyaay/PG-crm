package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RentCalculatorTest {

    @Test
    void fullMonthWhenStartingOnTheFirst() {
        assertThat(RentCalculator.proratedRent(new BigDecimal("12000"), LocalDate.of(2026, 10, 1)))
                .isEqualByComparingTo("12000.00");
    }

    @ParameterizedTest(name = "{0} from {1} -> {2}")
    @CsvSource({
        "12000, 2026-10-16, 6193.55",   // 16 of 31 days
        "9000,  2026-11-30, 300.00",    // last day of a 30-day month
        "10000, 2028-02-15, 5172.41",   // leap February: 15 of 29 days
        "10000, 2026-02-15, 5000.00"    // 14 of 28 days
    })
    void proratesByRemainingDaysIncludingStartDay(String rent, String from, String expected) {
        assertThat(RentCalculator.proratedRent(new BigDecimal(rent), LocalDate.parse(from)))
                .isEqualByComparingTo(expected);
    }

    @Test
    void dueDateIsTheConfiguredDayOfTheMonth() {
        assertThat(RentCalculator.dueDate(YearMonth.of(2026, 10), 5, LocalDate.of(2026, 10, 1)))
                .isEqualTo(LocalDate.of(2026, 10, 5));
    }

    @Test
    void dueDateNeverPrecedesTheIssueDate() {
        assertThat(RentCalculator.dueDate(YearMonth.of(2026, 10), 5, LocalDate.of(2026, 10, 20)))
                .isEqualTo(LocalDate.of(2026, 10, 20));
    }
}
