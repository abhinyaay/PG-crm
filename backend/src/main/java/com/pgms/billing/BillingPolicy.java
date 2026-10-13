package com.pgms.billing;

import java.math.BigDecimal;
import java.util.List;

/**
 * Effective collections policy of a property: the stored row, or the configured defaults ({@code configured == false}).
 * {@code overdueReminderOffsets} are days after the due date (D+n) on which an overdue reminder goes out.
 */
public record BillingPolicy(
        Long propertyId,
        boolean configured,
        boolean lateFeeEnabled,
        LateFeeType lateFeeType,
        BigDecimal lateFeeValue,
        BigDecimal lateFeeCap,
        int graceDays,
        int moveInGraceDays,
        boolean lateFeeOnMoveIn,
        int dueReminderDaysBefore,
        List<Integer> overdueReminderOffsets,
        String upiVpa,
        String upiPayeeName,
        long version) {

    public BillingPolicy {
        overdueReminderOffsets = overdueReminderOffsets == null ? List.of() : List.copyOf(overdueReminderOffsets);
    }
}
