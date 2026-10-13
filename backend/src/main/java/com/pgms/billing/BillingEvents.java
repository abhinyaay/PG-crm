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

    // ---- collections (V5) ----

    /**
     * An overdue reminder on day D+{@code daysOverdue}; one per invoice per offset (dedup key
     * {@link ReminderSchedule#overdueDedupKey}). {@code lateFeeFrom} is set when a late fee is (or will be) charged.
     */
    public record OverdueReminder(Long invoiceId, String invoiceNumber, Long organizationId, Long propertyId, Long tenantId,
                                  BigDecimal balance, LocalDate dueDate, long daysOverdue, LocalDate lateFeeFrom) {
    }

    public record LateFeeApplied(Long invoiceId, String invoiceNumber, Long organizationId, Long propertyId, Long tenantId,
                                 BigDecimal amount, BigDecimal newBalance) {
    }

    public record LateFeeWaived(Long invoiceId, String invoiceNumber, Long organizationId, Long propertyId, Long tenantId,
                                BigDecimal amount, BigDecimal newBalance, String reason) {
    }

    /** Daily per-property overdue summary for managers ({@code includeAdmins}: the weekly copy for admins). */
    public record OverdueDigest(Long propertyId, String propertyName, Long organizationId, LocalDate date, int count,
                                BigDecimal total, String top, boolean includeAdmins) {
    }

    public record PaymentClaimSubmitted(Long claimId, String claimReference, Long organizationId, Long propertyId, Long tenantId,
                                        String tenantName, String invoiceNumber, BigDecimal amount, PaymentMethod method,
                                        String utr) {
    }

    public record PaymentClaimRejected(Long claimId, String claimReference, Long organizationId, Long propertyId, Long tenantId,
                                       String invoiceNumber, BigDecimal amount, String utr, String reason) {
    }

    /** Claims waiting longer than the SLA at one property (daily digest to managers and admins). */
    public record PaymentClaimsPending(Long propertyId, String propertyName, Long organizationId, LocalDate date, int count,
                                       BigDecimal total) {
    }
}
