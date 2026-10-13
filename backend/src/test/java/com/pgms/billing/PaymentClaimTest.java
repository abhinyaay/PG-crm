package com.pgms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PaymentClaimTest {

    private static PaymentClaim claim() {
        Invoice invoice = new Invoice("INV-1", 1L, 2L, 3L, 4L, InvoiceType.RENT, LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5), "INR");
        return new PaymentClaim("PCL-1", invoice, 9L, new BigDecimal("1000"), PaymentMethod.UPI, "412345678901",
                LocalDate.of(2026, 10, 4), "GPay");
    }

    @Test
    void approvalRecordsThePaymentAndReviewer() {
        PaymentClaim claim = claim();
        Instant at = Instant.parse("2026-10-05T06:00:00Z");
        claim.approve(77L, new BigDecimal("900"), 5L, at, "partial");
        assertThat(claim.getStatus()).isEqualTo(PaymentClaimStatus.APPROVED);
        assertThat(claim.getPaymentId()).isEqualTo(77L);
        assertThat(claim.getApprovedAmount()).isEqualByComparingTo("900");
        assertThat(claim.getAmount()).isEqualByComparingTo("1000");
        assertThat(claim.getReviewedBy()).isEqualTo(5L);
        assertThat(claim.getReviewedAt()).isEqualTo(at);
        assertThat(claim.getReviewNote()).isEqualTo("partial");
        assertThat(claim.getPropertyId()).isEqualTo(2L);
        assertThat(claim.getTenantId()).isEqualTo(3L);
    }

    @Test
    void onlySubmittedClaimsChangeState() {
        PaymentClaim rejected = claim();
        rejected.reject("No credit", 5L, Instant.now());
        assertThat(rejected.getStatus()).isEqualTo(PaymentClaimStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("No credit");
        assertThatThrownBy(() -> rejected.approve(1L, BigDecimal.ONE, 5L, Instant.now(), null))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "CLAIM_NOT_PENDING");
        assertThatThrownBy(() -> rejected.cancel(Instant.now())).isInstanceOf(ConflictException.class);

        PaymentClaim cancelled = claim();
        cancelled.cancel(Instant.now());
        assertThat(cancelled.getStatus()).isEqualTo(PaymentClaimStatus.CANCELLED);
        assertThatThrownBy(() -> cancelled.reject("x", 5L, Instant.now())).isInstanceOf(ConflictException.class);
    }
}
