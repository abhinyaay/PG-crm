package com.pgms.billing.autopay;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class AutopayDtos {

    private AutopayDtos() {
    }

    /** {@code debitDayOffset}: debit N days before the due date (0 = on the due date). */
    public record StartMandateRequest(
            @NotNull MandateMethod method,
            @NotNull @DecimalMin("1.00") @Digits(integer = 10, fraction = 2) BigDecimal maxAmount,
            @Min(0) @Max(10) int debitDayOffset) {
    }

    public record CancelMandateRequest(@Size(max = 255) String reason) {
    }

    /** Sandbox only: the resident's decision on the simulated bank page. */
    public record SandboxAuthoriseRequest(boolean approve) {
    }

    public record MandateResponse(Long id, Long tenantId, Long propertyId, String provider, String providerMandateId,
                                  MandateMethod method, BigDecimal maxAmount, MandateStatus status, String statusReason,
                                  String authorisationUrl, int debitDayOffset, Instant authorisedAt, Instant pausedAt,
                                  Instant cancelledAt, Instant createdAt) {
        static MandateResponse of(PaymentMandate m) {
            return new MandateResponse(m.getId(), m.getTenantId(), m.getPropertyId(), m.getProvider(), m.getProviderMandateId(),
                    m.getMethod(), m.getMaxAmount(), m.getStatus(), m.getStatusReason(),
                    m.getStatus() == MandateStatus.PENDING_AUTHORISATION ? m.getAuthorisationUrl() : null, m.getDebitDayOffset(),
                    m.getAuthorisedAt(), m.getPausedAt(), m.getCancelledAt(), m.getCreatedAt());
        }
    }

    public record AttemptResponse(Long id, Long mandateId, Long invoiceId, Long tenantId, Long propertyId, BigDecimal amount,
                                  int attemptNo, LocalDate attemptDate, String providerPaymentId, AttemptStatus status,
                                  String failureReason, Long paymentId, Instant completedAt, Instant createdAt) {
        static AttemptResponse of(AutopayAttempt a) {
            return new AttemptResponse(a.getId(), a.getMandateId(), a.getInvoiceId(), a.getTenantId(), a.getPropertyId(),
                    a.getAmount(), a.getAttemptNo(), a.getAttemptDate(), a.getProviderPaymentId(), a.getStatus(),
                    a.getFailureReason(), a.getPaymentId(), a.getCompletedAt(), a.getCreatedAt());
        }
    }

    public record DebitRunResult(LocalDate date, int requested, int succeeded, int failed, int pending, int skipped) {
    }

    public record WebhookAck(String status, String eventId) {
    }
}
