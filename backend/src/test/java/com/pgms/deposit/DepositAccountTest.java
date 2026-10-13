package com.pgms.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DepositAccountTest {

    private static final LocalDate MOVE_OUT = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T06:00:00Z");

    private static DepositAccount account() {
        return new DepositAccount(1L, 2L, 3L, 4L, 4L);
    }

    @Test
    void settlementWithMoneyLeftIsRefundDueUntilPaidOut() {
        DepositAccount a = account();
        assertThat(a.isSettled()).isFalse();
        a.settle(MOVE_OUT, new BigDecimal("13500"), MOVE_OUT.plusDays(7), NOW);
        assertThat(a.getStatus()).isEqualTo(DepositStatus.REFUND_DUE);
        assertThat(a.getRefundDueBy()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(a.isRefundOverdue(LocalDate.of(2026, 10, 17))).isFalse();
        assertThat(a.isRefundOverdue(LocalDate.of(2026, 10, 18))).isTrue();

        a.recordRefund(new BigDecimal("3500"));
        assertThat(a.getStatus()).isEqualTo(DepositStatus.PARTIALLY_REFUNDED);
        assertThatThrownBy(() -> a.recordRefund(new BigDecimal("10000.01")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("exceeds the refund due");
        a.recordRefund(new BigDecimal("10000"));
        assertThat(a.getStatus()).isEqualTo(DepositStatus.REFUNDED);
        assertThat(a.getRefundDue()).isEqualByComparingTo("0");
        assertThat(a.getRefundDueBy()).isNull();
        assertThatThrownBy(() -> a.recordRefund(BigDecimal.ONE)).isInstanceOf(ConflictException.class);
    }

    @Test
    void settlementWithNothingLeftIsSettledAndCannotBeSettledTwice() {
        DepositAccount a = account();
        a.settle(MOVE_OUT, BigDecimal.ZERO, MOVE_OUT.plusDays(7), NOW);
        assertThat(a.getStatus()).isEqualTo(DepositStatus.SETTLED);
        assertThat(a.getRefundDueBy()).isNull();
        assertThatThrownBy(() -> a.settle(MOVE_OUT, BigDecimal.ONE, MOVE_OUT, NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> a.forfeit("x", MOVE_OUT, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void laterDeductionsAndManualRefundsLowerTheRefundDue() {
        DepositAccount deducted = account();
        deducted.settle(MOVE_OUT, new BigDecimal("1000"), MOVE_OUT, NOW);
        deducted.reduceRefundDue(new BigDecimal("400"), false);
        assertThat(deducted.getStatus()).isEqualTo(DepositStatus.REFUND_DUE);
        deducted.reduceRefundDue(new BigDecimal("600"), false);
        assertThat(deducted.getStatus()).isEqualTo(DepositStatus.SETTLED);

        DepositAccount manual = account();
        manual.settle(MOVE_OUT, new BigDecimal("1000"), MOVE_OUT, NOW);
        manual.reduceRefundDue(new BigDecimal("400"), true);
        assertThat(manual.getStatus()).isEqualTo(DepositStatus.PARTIALLY_REFUNDED);
        manual.reduceRefundDue(new BigDecimal("5000"), true);
        assertThat(manual.getStatus()).isEqualTo(DepositStatus.REFUNDED);
        assertThat(manual.getRefundDue()).isEqualByComparingTo("0");

        DepositAccount held = account();
        held.reduceRefundDue(new BigDecimal("400"), true);
        assertThat(held.getStatus()).isEqualTo(DepositStatus.HELD);
    }

    @Test
    void forfeitKeepsTheRemainder() {
        DepositAccount a = account();
        a.settle(MOVE_OUT, new BigDecimal("1000"), MOVE_OUT, NOW);
        a.forfeit("Left without notice", MOVE_OUT, NOW);
        assertThat(a.getStatus()).isEqualTo(DepositStatus.FORFEITED);
        assertThat(a.getRefundDue()).isEqualByComparingTo("0");
        assertThat(a.getForfeitReason()).isEqualTo("Left without notice");

        DepositAccount legacy = account();
        legacy.forfeit("Absconded", MOVE_OUT, NOW);
        assertThat(legacy.getMoveOutDate()).isEqualTo(MOVE_OUT);
        assertThat(legacy.getSettledAt()).isEqualTo(NOW);
    }

    @Test
    void followsTransfersAndRemembersOverdueAlerts() {
        DepositAccount a = account();
        a.followTransfer(9L, 8L);
        assertThat(a.getCurrentAllocationId()).isEqualTo(9L);
        assertThat(a.getPropertyId()).isEqualTo(8L);
        a.markOverdueNotified(MOVE_OUT);
        assertThat(a.getOverdueNotifiedOn()).isEqualTo(MOVE_OUT);
    }
}
