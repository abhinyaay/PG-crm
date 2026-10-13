package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BadRequestException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReminderScheduleTest {

    private static final LocalDate DUE = LocalDate.of(2026, 10, 5);

    @Test
    void dueReminderFiresExactlyNDaysBefore() {
        assertThat(ReminderSchedule.isDueReminderDay(3, DUE, LocalDate.of(2026, 10, 2))).isTrue();
        assertThat(ReminderSchedule.isDueReminderDay(3, DUE, LocalDate.of(2026, 10, 3))).isFalse();
        assertThat(ReminderSchedule.isDueReminderDay(0, DUE, DUE)).isTrue();
    }

    @Test
    void overdueRemindersFollowTheOffsets() {
        List<Integer> offsets = List.of(1, 5, 10);
        assertThat(ReminderSchedule.isOverdueReminderDay(offsets, DUE, DUE)).isFalse();
        assertThat(ReminderSchedule.isOverdueReminderDay(offsets, DUE, DUE.plusDays(1))).isTrue();
        assertThat(ReminderSchedule.isOverdueReminderDay(offsets, DUE, DUE.plusDays(4))).isFalse();
        assertThat(ReminderSchedule.isOverdueReminderDay(offsets, DUE, DUE.plusDays(5))).isTrue();
        assertThat(ReminderSchedule.isOverdueReminderDay(offsets, DUE, DUE.plusDays(10))).isTrue();
        assertThat(ReminderSchedule.daysOverdue(DUE, DUE.plusDays(10))).isEqualTo(10);
        assertThat(ReminderSchedule.overdueDedupKey(42L, 5)).isEqualTo("PAYMENT_OVERDUE:42:D5");
    }

    @Test
    void offsetsAreValidatedAndSorted() {
        assertThat(ReminderSchedule.normalizeOffsets(List.of(10, 1, 5))).containsExactly(1, 5, 10);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(List.of())).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(List.of(1, 1))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(List.of(0))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(List.of(91))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(Arrays.asList(1, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> ReminderSchedule.normalizeOffsets(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)))
                .isInstanceOf(BadRequestException.class);
    }
}
