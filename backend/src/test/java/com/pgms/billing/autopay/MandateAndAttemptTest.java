package com.pgms.billing.autopay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MandateAndAttemptTest {

    private static final Instant NOW = Instant.parse("2026-10-13T05:00:00Z");

    private static PaymentMandate mandate() {
        return new PaymentMandate(1L, 2L, 3L, "sandbox", "sbx_mdt_1", "cust", MandateMethod.UPI_AUTOPAY, new BigDecimal("20000"),
                "https://bank/authorise", 0);
    }

    @Test
    void mandateLifecycleIsIdempotentForReplays() {
        PaymentMandate mandate = mandate();
        assertThat(mandate.getStatus().isLive()).isTrue();
        assertThat(mandate.activate("tok_1", NOW)).isTrue();
        assertThat(mandate.activate("tok_1", NOW)).isFalse();
        assertThat(mandate.getProviderTokenId()).isEqualTo("tok_1");
        assertThat(mandate.getAuthorisedAt()).isEqualTo(NOW);
        assertThat(mandate.pause(NOW)).isTrue();
        assertThat(mandate.pause(NOW)).isFalse();
        assertThat(mandate.getPausedAt()).isEqualTo(NOW);
        assertThat(mandate.resume()).isTrue();
        assertThat(mandate.resume()).isFalse();
        assertThat(mandate.cancel("bye", NOW)).isTrue();
        assertThat(mandate.cancel("bye", NOW)).isFalse();
        assertThat(mandate.getStatus()).isEqualTo(MandateStatus.CANCELLED);
        assertThat(mandate.getStatus().isLive()).isFalse();
        assertThatThrownBy(() -> mandate.resume()).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "MANDATE_INVALID_STATE");
        assertThatThrownBy(() -> mandate.activate(null, NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void pendingMandatesCanFailButNotPause() {
        PaymentMandate mandate = mandate();
        assertThatThrownBy(() -> mandate.pause(NOW)).isInstanceOf(ConflictException.class);
        assertThat(mandate.fail("Rejected")).isTrue();
        assertThat(mandate.fail("Rejected")).isFalse();
        assertThat(mandate.getStatusReason()).isEqualTo("Rejected");
        assertThatThrownBy(() -> mandate.cancel(null, NOW)).isInstanceOf(ConflictException.class);
        mandate.relocate(9L);
        assertThat(mandate.getPropertyId()).isEqualTo(9L);
    }

    @Test
    void attemptOutcomesNeverRegress() {
        AutopayAttempt attempt = new AutopayAttempt(mandate(), 5L, 2L, new BigDecimal("9000"), 1, LocalDate.of(2026, 10, 13), NOW);
        attempt.requested("pay_1", NOW);
        assertThat(attempt.getProviderPaymentId()).isEqualTo("pay_1");
        assertThat(attempt.fail(null, "x".repeat(300), NOW)).isTrue();
        assertThat(attempt.getFailureReason()).hasSize(255);
        assertThat(attempt.fail(null, "again", NOW)).isFalse();
        // a late capture still wins over an earlier failure, but never the other way round
        assertThat(attempt.succeed(null, 77L, null, NOW)).isTrue();
        assertThat(attempt.succeed(null, 78L, null, NOW)).isFalse();
        assertThat(attempt.fail("pay_1", "late", NOW)).isFalse();
        assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(attempt.getPaymentId()).isEqualTo(77L);
        assertThat(attempt.getCompletedAt()).isEqualTo(NOW);
        AutopayAttempt declined = new AutopayAttempt(mandate(), 5L, 2L, BigDecimal.TEN, 2, LocalDate.of(2026, 10, 14), NOW);
        declined.fail("pay_2", null, NOW);
        assertThat(declined.getFailureReason()).isEqualTo("Declined");
    }
}
