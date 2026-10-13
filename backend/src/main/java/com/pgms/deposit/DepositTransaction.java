package com.pgms.deposit;

import com.pgms.billing.PaymentMethod;
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

/** One line of a deposit ledger. Immutable except for {@code remaining} of a RENT_CREDIT entry. */
@Entity
@Table(name = "deposit_transactions")
public class DepositTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deposit_account_id", nullable = false, updatable = false)
    private Long depositAccountId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "move_out_id", updatable = false)
    private Long moveOutId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DepositTransactionType type;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "invoice_id", updatable = false)
    private Long invoiceId;

    /** RENT_CREDIT: the rent payment the credit is refunded from. */
    @Column(name = "payment_id", updatable = false)
    private Long paymentId;

    /** REFUND recorded through the payments API: the billing refund it mirrors. */
    @Column(name = "refund_id", updatable = false)
    private Long refundId;

    @Column(name = "remaining")
    private BigDecimal remaining;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private PaymentMethod method;

    @Column(updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", updatable = false)
    private String requestFingerprint;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected DepositTransaction() {
    }

    DepositTransaction(DepositAccount account, DepositTransactionType type, BigDecimal amount, String description,
                       Instant createdAt, Long createdBy) {
        this.depositAccountId = account.getId();
        this.organizationId = account.getOrganizationId();
        this.tenantId = account.getTenantId();
        this.type = type;
        this.amount = amount;
        this.description = description.length() > 500 ? description.substring(0, 500) : description;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        if (type == DepositTransactionType.RENT_CREDIT) {
            this.remaining = amount;
        }
    }

    DepositTransaction withMoveOut(Long id) {
        this.moveOutId = id;
        return this;
    }

    DepositTransaction withInvoice(Long id) {
        this.invoiceId = id;
        return this;
    }

    DepositTransaction withPayment(Long id) {
        this.paymentId = id;
        return this;
    }

    DepositTransaction withRefund(Long id) {
        this.refundId = id;
        return this;
    }

    DepositTransaction withPayout(PaymentMethod payoutMethod, String payoutReference) {
        this.method = payoutMethod;
        this.reference = payoutReference;
        return this;
    }

    DepositTransaction withIdempotency(String key, String fingerprint) {
        this.idempotencyKey = key;
        this.requestFingerprint = fingerprint;
        return this;
    }

    /** Uses part of a RENT_CREDIT entry (applied to dues or paid out). */
    void consume(BigDecimal value) {
        remaining = remaining.subtract(value);
    }

    public Long getId() {
        return id;
    }

    public Long getDepositAccountId() {
        return depositAccountId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getMoveOutId() {
        return moveOutId;
    }

    public DepositTransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public Long getRefundId() {
        return refundId;
    }

    public BigDecimal getRemaining() {
        return remaining;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public String getDescription() {
        return description;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }
}
