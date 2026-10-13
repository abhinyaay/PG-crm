package com.pgms.billing;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A tenant's "I've paid" report (UPI / bank transfer / cash / cheque with its reference), waiting for a manager to
 * verify it. Approval turns it into a real payment; only SUBMITTED claims can change state.
 */
@Entity
@Table(name = "payment_claims")
public class PaymentClaim extends AuditableEntity {

    @Column(name = "claim_reference", nullable = false, updatable = false)
    private String claimReference;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "submitted_by_user_id", nullable = false, updatable = false)
    private Long submittedByUserId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Column(updatable = false)
    private String utr;

    @Column(name = "paid_on", nullable = false, updatable = false)
    private LocalDate paidOn;

    @Column(name = "tenant_note", updatable = false)
    private String tenantNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentClaimStatus status = PaymentClaimStatus.SUBMITTED;

    @Column(name = "reviewed_by")
    private Long reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "payment_id")
    private Long paymentId;

    protected PaymentClaim() {
    }

    public PaymentClaim(String claimReference, Invoice invoice, Long submittedByUserId, BigDecimal amount, PaymentMethod method,
                        String utr, LocalDate paidOn, String tenantNote) {
        this.claimReference = claimReference;
        this.organizationId = invoice.getOrganizationId();
        this.propertyId = invoice.getPropertyId();
        this.tenantId = invoice.getTenantId();
        this.invoiceId = invoice.getId();
        this.submittedByUserId = submittedByUserId;
        this.amount = amount;
        this.method = method;
        this.utr = utr;
        this.paidOn = paidOn;
        this.tenantNote = tenantNote;
    }

    public void approve(Long paymentId, BigDecimal approvedAmount, Long reviewer, Instant at, String note) {
        requirePending();
        this.status = PaymentClaimStatus.APPROVED;
        this.paymentId = paymentId;
        this.approvedAmount = approvedAmount;
        this.reviewedBy = reviewer;
        this.reviewedAt = at;
        this.reviewNote = note;
    }

    public void reject(String reason, Long reviewer, Instant at) {
        requirePending();
        this.status = PaymentClaimStatus.REJECTED;
        this.rejectionReason = reason;
        this.reviewedBy = reviewer;
        this.reviewedAt = at;
    }

    public void cancel(Instant at) {
        requirePending();
        this.status = PaymentClaimStatus.CANCELLED;
        this.reviewedAt = at;
    }

    public void requirePending() {
        if (status != PaymentClaimStatus.SUBMITTED) {
            throw new ConflictException("CLAIM_NOT_PENDING", "Payment claim " + claimReference + " is " + status);
        }
    }

    public String getClaimReference() {
        return claimReference;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getApprovedAmount() {
        return approvedAmount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getUtr() {
        return utr;
    }

    public LocalDate getPaidOn() {
        return paidOn;
    }

    public String getTenantNote() {
        return tenantNote;
    }

    public PaymentClaimStatus getStatus() {
        return status;
    }

    public Long getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Long getPaymentId() {
        return paymentId;
    }
}
