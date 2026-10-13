package com.pgms.billing.autopay;

import java.math.BigDecimal;

public final class AutopayEvents {

    private AutopayEvents() {
    }

    public record MandateStatusChanged(Long mandateId, Long organizationId, Long propertyId, Long tenantId, MandateStatus status,
                                       String reason) {
    }

    public record DebitSucceeded(Long attemptId, Long organizationId, Long propertyId, Long tenantId, String invoiceNumber,
                                 BigDecimal amount) {
    }

    /** {@code finalAttempt}: no automatic retry follows (managers are told too). */
    public record DebitFailed(Long attemptId, Long organizationId, Long propertyId, Long tenantId, String invoiceNumber,
                              BigDecimal amount, String reason, int attemptNo, boolean finalAttempt) {
    }
}
