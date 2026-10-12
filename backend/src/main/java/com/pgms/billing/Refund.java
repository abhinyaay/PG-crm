package com.pgms.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** Money returned against a payment (typically the security deposit at move-out). Idempotent like payments. */
@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_reference", nullable = false, updatable = false)
    private String refundReference;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private Long paymentId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "refunded_at", nullable = false, updatable = false)
    private Instant refundedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected Refund() {
    }

    public Refund(String refundReference, Payment payment, BigDecimal amount, String reason, String idempotencyKey,
                  String requestFingerprint, Instant refundedAt, Long createdBy) {
        this.refundReference = refundReference;
        this.organizationId = payment.getOrganizationId();
        this.paymentId = payment.getId();
        this.amount = amount;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.refundedAt = refundedAt;
        this.createdAt = refundedAt;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public String getRefundReference() {
        return refundReference;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }
}
