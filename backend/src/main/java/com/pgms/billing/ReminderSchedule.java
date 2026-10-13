package com.pgms.billing;

import com.pgms.common.exception.BadRequestException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;

/** Pure reminder-schedule rules: T-N before the due date, D+n offsets after it, and the dedup key per offset. */
public final class ReminderSchedule {

    public static final int MAX_OFFSETS = 10;
    public static final int MAX_OFFSET_DAY = 90;

    private ReminderSchedule() {
    }

    public static long daysOverdue(LocalDate dueDate, LocalDate today) {
        return ChronoUnit.DAYS.between(dueDate, today);
    }

    /** True when {@code today} is exactly {@code daysBefore} days before the due date. */
    public static boolean isDueReminderDay(int daysBefore, LocalDate dueDate, LocalDate today) {
        return ChronoUnit.DAYS.between(today, dueDate) == daysBefore;
    }

    /** True when {@code today - dueDate} is one of the configured D+n offsets. */
    public static boolean isOverdueReminderDay(List<Integer> offsets, LocalDate dueDate, LocalDate today) {
        long days = daysOverdue(dueDate, today);
        return days > 0 && offsets.stream().anyMatch(o -> o == days);
    }

    /** One overdue notification per invoice per day-offset, whichever job produces it. */
    public static String overdueDedupKey(Long invoiceId, long daysOverdue) {
        return "PAYMENT_OVERDUE:" + invoiceId + ":D" + daysOverdue;
    }

    /** Validates (1..10 distinct values in 1..90) and sorts the offsets. */
    public static List<Integer> normalizeOffsets(List<Integer> offsets) {
        if (offsets == null || offsets.isEmpty() || offsets.size() > MAX_OFFSETS) {
            throw new BadRequestException("INVALID_REMINDER_OFFSETS",
                    "overdueOffsets must contain between 1 and " + MAX_OFFSETS + " values");
        }
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer offset : offsets) {
            if (offset == null || offset < 1 || offset > MAX_OFFSET_DAY) {
                throw new BadRequestException("INVALID_REMINDER_OFFSETS",
                        "overdueOffsets values must be between 1 and " + MAX_OFFSET_DAY);
            }
            if (!sorted.add(offset)) {
                throw new BadRequestException("INVALID_REMINDER_OFFSETS", "overdueOffsets must be distinct");
            }
        }
        return List.copyOf(sorted);
    }
}
