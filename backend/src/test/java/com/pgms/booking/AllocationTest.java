package com.pgms.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AllocationTest {

    private Allocation allocation() {
        return new Allocation(1L, 1L, 1L, 1L, null, null, LocalDate.of(2026, 9, 1), new BigDecimal("9000"),
                new BigDecimal("18000"));
    }

    @Test
    void checkoutAndTransferProduceDifferentTerminalStatuses() {
        Allocation a = allocation();
        a.end(LocalDate.of(2026, 9, 20), EndReason.CHECK_OUT);
        assertThat(a.getStatus()).isEqualTo(AllocationStatus.ENDED);
        Allocation b = allocation();
        b.end(LocalDate.of(2026, 9, 20), EndReason.TRANSFER);
        assertThat(b.getStatus()).isEqualTo(AllocationStatus.TRANSFERRED);
    }

    /** [d, d) is an empty range: it escapes the overlap constraints, so a same-day check-out is rejected. */
    @Test
    void sameDayCheckOutIsRejectedButSameDayTransferIsAllowed() {
        Allocation a = allocation();
        assertThatThrownBy(() -> a.end(LocalDate.of(2026, 9, 1), EndReason.CHECK_OUT))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("after the check-in date");
        Allocation b = allocation();
        b.end(LocalDate.of(2026, 9, 1), EndReason.TRANSFER);
        assertThat(b.getStatus()).isEqualTo(AllocationStatus.TRANSFERRED);
    }

    @Test
    void cannotEndTwiceOrBeforeStart() {
        Allocation a = allocation();
        assertThatThrownBy(() -> a.end(LocalDate.of(2026, 8, 31), EndReason.CHECK_OUT))
                .isInstanceOf(BusinessRuleException.class);
        a.end(LocalDate.of(2026, 9, 2), EndReason.CHECK_OUT);
        assertThatThrownBy(() -> a.end(LocalDate.of(2026, 9, 3), EndReason.CHECK_OUT)).isInstanceOf(ConflictException.class);
    }

    @Test
    void bookingHoldExpiresAtReservedUntil() {
        Instant until = Instant.parse("2026-10-02T00:00:00Z");
        Booking booking = new Booking("BK-1", 1L, 1L, 1L, 1L, LocalDate.of(2026, 10, 2), until, null);
        assertThat(booking.isLive(until.minusSeconds(1))).isTrue();
        assertThat(booking.isLive(until)).isFalse();
        booking.expire();
        assertThatThrownBy(booking::markCheckedIn).isInstanceOf(ConflictException.class);
    }
}
