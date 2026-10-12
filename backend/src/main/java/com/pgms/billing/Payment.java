package com.pgms.billing;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.BusinessRuleException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Money received against an invoice. {@code (organizationId, idempotencyKey)} is unique: a retried request with the
 * same key replays the original result instead of charging twice; {@code requestFingerprint} detects a key being
 * reused for a <em>different</em> request.
 */
@Entity
@Table(name = "payments")
public class Payment extends AuditableEntity {

    @Column(name = "receipt_number", nullable = false, updatable = false)
    private String receiptNumber;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "refunded_amount", nullable = false)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.COMPLETED;

    @Column(name = "external_reference", updatable = false)
    private String externalReference;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    private String notes;

    protected Payment() {
    }

    public Payment(String receiptNumber, Invoice invoice, String idempotencyKey, String requestFingerprint, BigDecimal amount,
                   PaymentMethod method, String externalReference, Instant paidAt, String notes) {
        this.receiptNumber = receiptNumber;
        this.organizationId = invoice.getOrganizationId();
        this.propertyId = invoice.getPropertyId();
        this.tenantId = invoice.getTenantId();
        this.invoiceId = invoice.getId();
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.amount = amount;
        this.method = method;
        this.externalReference = externalReference;
        this.paidAt = paidAt;
        this.notes = notes;
    }

    public BigDecimal refundableAmount() {
        return amount.subtract(refundedAmount);
    }

    public void refund(BigDecimal refundAmount) {
        if (refundAmount.signum() <= 0) {
            throw new BusinessRuleException("INVALID_AMOUNT", "Refund amount must be positive");
        }
        if (refundAmount.compareTo(refundableAmount()) > 0) {
            throw new BusinessRuleException("REFUND_EXCEEDS_PAYMENT",
                    "Refund " + refundAmount + " exceeds the refundable amount " + refundableAmount());
        }
        refundedAmount = refundedAmount.add(refundAmount);
        status = refundedAmount.compareTo(amount) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getNotes() {
        return notes;
    }
}
