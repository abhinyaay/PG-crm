package com.pgms.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BillingDtos {

    private BillingDtos() {
    }

    public record AddChargeRequest(
            @NotNull LineItemType type,
            @NotBlank @Size(max = 255) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    public record DiscountRequest(
            @NotBlank @Size(max = 255) String reason,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    public record VoidInvoiceRequest(@NotBlank @Size(max = 255) String reason) {
    }

    public record LineItemResponse(Long id, LineItemType type, String description, BigDecimal amount, Instant createdAt) {
        static LineItemResponse of(InvoiceLineItem l) {
            return new LineItemResponse(l.getId(), l.getLineType(), l.getDescription(), l.getAmount(), l.getCreatedAt());
        }
    }

    public record InvoiceResponse(
            Long id,
            String invoiceNumber,
            Long propertyId,
            Long tenantId,
            Long allocationId,
            InvoiceType type,
            LocalDate billingMonth,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate issueDate,
            LocalDate dueDate,
            InvoiceStatus status,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal totalAmount,
            BigDecimal amountPaid,
            BigDecimal balance,
            String currency,
            List<LineItemResponse> lineItems,
            String tenantName) {

        static InvoiceResponse of(Invoice i, boolean withLines, String tenantName) {
            return new InvoiceResponse(i.getId(), i.getInvoiceNumber(), i.getPropertyId(), i.getTenantId(), i.getAllocationId(),
                    i.getInvoiceType(), i.getBillingMonth(), i.getPeriodStart(), i.getPeriodEnd(), i.getIssueDate(),
                    i.getDueDate(), i.getStatus(), i.getSubtotal(), i.getDiscountTotal(), i.getTotalAmount(),
                    i.getAmountPaid(), i.balance(), i.getCurrency(),
                    withLines ? i.getLineItems().stream().map(LineItemResponse::of).toList() : null, tenantName);
        }
    }

    public record InvoiceSummary(Long id, String invoiceNumber, InvoiceType type, BigDecimal totalAmount, LocalDate dueDate,
                                 InvoiceStatus status) {
        public static InvoiceSummary of(Invoice i) {
            return new InvoiceSummary(i.getId(), i.getInvoiceNumber(), i.getInvoiceType(), i.getTotalAmount(), i.getDueDate(),
                    i.getStatus());
        }
    }

    public record TenantBalanceResponse(Long tenantId, BigDecimal outstanding, BigDecimal depositHeld,
                                        List<InvoiceResponse> openInvoices) {
    }

    public record RecordPaymentRequest(
            @NotNull Long invoiceId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 100) String externalReference,
            @PastOrPresent Instant paidAt,
            @Size(max = 500) String notes) {
    }

    public record RefundRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 255) String reason) {
    }

    public record PaymentResponse(
            Long id,
            String receiptNumber,
            Long propertyId,
            Long tenantId,
            Long invoiceId,
            BigDecimal amount,
            BigDecimal refundedAmount,
            PaymentMethod method,
            PaymentStatus status,
            String externalReference,
            Instant paidAt,
            String notes,
            String tenantName) {

        static PaymentResponse of(Payment p, String tenantName) {
            return new PaymentResponse(p.getId(), p.getReceiptNumber(), p.getPropertyId(), p.getTenantId(), p.getInvoiceId(),
                    p.getAmount(), p.getRefundedAmount(), p.getMethod(), p.getStatus(), p.getExternalReference(), p.getPaidAt(),
                    p.getNotes(), tenantName);
        }
    }

    public record RefundResponse(Long id, String refundReference, Long paymentId, BigDecimal amount, String reason,
                                 Instant refundedAt) {
        static RefundResponse of(Refund r) {
            return new RefundResponse(r.getId(), r.getRefundReference(), r.getPaymentId(), r.getAmount(), r.getReason(),
                    r.getRefundedAt());
        }
    }

    /** Result of an idempotent command: {@code replayed} is true when the response comes from an earlier identical request. */
    public record IdempotentResult<T>(T body, boolean replayed) {
    }

    public record Deduction(@NotBlank @Size(max = 255) String description,
                            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /** Move-out financial summary: what is held, what is owed, and what should be refunded or collected. */
    public record SettlementSummary(
            BigDecimal depositHeld,
            BigDecimal outstandingDues,
            BigDecimal deductions,
            BigDecimal refundDue,
            BigDecimal amountPayableByTenant,
            @Valid List<Deduction> deductionItems) {
    }

    public record GenerationResult(LocalDate billingMonth, int generated, int skipped, int failed) {
    }
}
