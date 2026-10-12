package com.pgms.billing;

import java.math.BigDecimal;
import java.time.LocalDate;

public final class BillingEvents {

    private BillingEvents() {
    }

    public record InvoiceIssued(Long invoiceId, String invoiceNumber, Long organizationId, Long tenantId, InvoiceType type,
                                BigDecimal amount, LocalDate dueDate) {
    }

    public record PaymentReceived(Long paymentId, String receiptNumber, Long organizationId, Long tenantId,
                                  String invoiceNumber, BigDecimal amount, BigDecimal remainingBalance) {
    }

    public record RefundIssued(Long refundId, String refundReference, Long organizationId, Long tenantId, BigDecimal amount) {
    }

    /** Reminder before the due date ({@code overdue == false}) or once it has passed ({@code overdue == true}). */
    public record PaymentDue(Long invoiceId, String invoiceNumber, Long organizationId, Long tenantId, BigDecimal balance,
                             LocalDate dueDate, boolean overdue) {
    }
}
