package com.pgms.support;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * "Today" as the application sees it. The app validates business dates in its configured zone (Asia/Kolkata), so
 * tests must not use the JVM's zone: CI runners are on UTC, where the date differs for 5.5 hours every day.
 */
public final class BusinessDates {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private BusinessDates() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static YearMonth thisMonth() {
        return YearMonth.now(ZONE);
    }
}
