package com.pgms.billing.autopay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One debit request against a mandate for one invoice: at most one per invoice per day, at most three per invoice. */
@Entity
@Table(name = "autopay_attempts")
public class AutopayAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "mandate_id", nullable = false, updatable = false)
    private Long mandateId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "attempt_no", nullable = false, updatable = false)
    private int attemptNo;

    @Column(name = "attempt_date", nullable = false, updatable = false)
    private LocalDate attemptDate;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptStatus status = AttemptStatus.SCHEDULED;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "payment_id")
    private Long paymentId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected AutopayAttempt() {
    }

    public AutopayAttempt(PaymentMandate mandate, Long invoiceId, Long propertyId, BigDecimal amount, int attemptNo,
                          LocalDate attemptDate, Instant now) {
        this.organizationId = mandate.getOrganizationId();
        this.propertyId = propertyId;
        this.mandateId = mandate.getId();
        this.invoiceId = invoiceId;
        this.tenantId = mandate.getTenantId();
        this.provider = mandate.getProvider();
        this.amount = amount;
        this.attemptNo = attemptNo;
        this.attemptDate = attemptDate;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Provider accepted the request (outcome pending): remember its payment id for the webhook. */
    public void requested(String providerPaymentId, Instant now) {
        if (providerPaymentId != null) {
            this.providerPaymentId = providerPaymentId;
        }
        this.updatedAt = now;
    }

    /** Returns false if already succeeded (replayed confirmation). A late success overrides an earlier failure. */
    public boolean succeed(String providerPaymentId, Long paymentId, String note, Instant now) {
        if (status == AttemptStatus.SUCCEEDED) {
            return false;
        }
        requested(providerPaymentId, now);
        this.status = AttemptStatus.SUCCEEDED;
        this.paymentId = paymentId;
        this.failureReason = note;
        this.completedAt = now;
        return true;
    }

    /** Returns false unless the attempt was still SCHEDULED (a failure never overrides a recorded success). */
    public boolean fail(String providerPaymentId, String reason, Instant now) {
        if (status != AttemptStatus.SCHEDULED) {
            return false;
        }
        requested(providerPaymentId, now);
        this.status = AttemptStatus.FAILED;
        this.failureReason = reason == null ? "Declined" : reason.length() > 255 ? reason.substring(0, 255) : reason;
        this.completedAt = now;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getMandateId() {
        return mandateId;
    }

    public Long getInvoiceId() {
        return invoiceId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getProvider() {
        return provider;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public LocalDate getAttemptDate() {
        return attemptDate;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public AttemptStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
