package com.pgms.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RefundKind kind = RefundKind.CASH;

    /** How a CASH refund was paid out (deposit refunds); null for manual refunds and adjustments. */
    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private PaymentMethod method;

    @Column(name = "payout_reference", updatable = false)
    private String payoutReference;

    /** The deposit ledger entry this refund belongs to (deposit refunds / adjustments made by the deposit module). */
    @Column(name = "deposit_transaction_id", updatable = false)
    private Long depositTransactionId;

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

    /** Refund created by the deposit module: a payout (CASH) or an application to other dues (ADJUSTMENT). */
    Refund(String refundReference, Payment payment, BigDecimal amount, String reason, String idempotencyKey,
           Instant refundedAt, Long createdBy, RefundKind kind, PaymentMethod method, String payoutReference,
           Long depositTransactionId) {
        this(refundReference, payment, amount, reason, idempotencyKey, "internal", refundedAt, createdBy);
        this.kind = kind;
        this.method = method;
        this.payoutReference = payoutReference;
        this.depositTransactionId = depositTransactionId;
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

    public RefundKind getKind() {
        return kind;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getPayoutReference() {
        return payoutReference;
    }

    public Long getDepositTransactionId() {
        return depositTransactionId;
    }
}
