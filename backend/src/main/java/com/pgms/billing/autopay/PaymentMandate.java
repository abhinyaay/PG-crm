package com.pgms.billing.autopay;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A resident's standing authorisation for automatic rent collection. State machine:
 * PENDING_AUTHORISATION → ACTIVE | FAILED; ACTIVE ⇄ PAUSED; any live state → CANCELLED.
 */
@Entity
@Table(name = "payment_mandates")
public class PaymentMandate extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_mandate_id", nullable = false, updatable = false)
    private String providerMandateId;

    @Column(name = "provider_customer_id")
    private String providerCustomerId;

    @Column(name = "provider_token_id")
    private String providerTokenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MandateMethod method;

    @Column(name = "max_amount", nullable = false, updatable = false)
    private BigDecimal maxAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MandateStatus status = MandateStatus.PENDING_AUTHORISATION;

    @Column(name = "authorisation_url")
    private String authorisationUrl;

    @Column(name = "debit_day_offset", nullable = false, updatable = false)
    private int debitDayOffset;

    @Column(name = "status_reason")
    private String statusReason;

    @Column(name = "authorised_at")
    private Instant authorisedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected PaymentMandate() {
    }

    public PaymentMandate(Long organizationId, Long propertyId, Long tenantId, String provider, String providerMandateId,
                          String providerCustomerId, MandateMethod method, BigDecimal maxAmount, String authorisationUrl,
                          int debitDayOffset) {
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.provider = provider;
        this.providerMandateId = providerMandateId;
        this.providerCustomerId = providerCustomerId;
        this.method = method;
        this.maxAmount = maxAmount;
        this.authorisationUrl = authorisationUrl;
        this.debitDayOffset = debitDayOffset;
    }

    /** Returns false when already ACTIVE (idempotent replay of the activation event). */
    public boolean activate(String tokenId, Instant at) {
        if (status == MandateStatus.ACTIVE) {
            return false;
        }
        require(status == MandateStatus.PENDING_AUTHORISATION, "activate");
        status = MandateStatus.ACTIVE;
        if (tokenId != null) {
            providerTokenId = tokenId;
        }
        authorisedAt = at;
        statusReason = null;
        return true;
    }

    public boolean fail(String reason) {
        if (status == MandateStatus.FAILED) {
            return false;
        }
        require(status == MandateStatus.PENDING_AUTHORISATION, "fail");
        status = MandateStatus.FAILED;
        statusReason = reason;
        return true;
    }

    public boolean pause(Instant at) {
        if (status == MandateStatus.PAUSED) {
            return false;
        }
        require(status == MandateStatus.ACTIVE, "pause");
        status = MandateStatus.PAUSED;
        pausedAt = at;
        return true;
    }

    public boolean resume() {
        if (status == MandateStatus.ACTIVE) {
            return false;
        }
        require(status == MandateStatus.PAUSED, "resume");
        status = MandateStatus.ACTIVE;
        pausedAt = null;
        return true;
    }

    public boolean cancel(String reason, Instant at) {
        if (status == MandateStatus.CANCELLED) {
            return false;
        }
        require(status.isLive(), "cancel");
        status = MandateStatus.CANCELLED;
        statusReason = reason;
        cancelledAt = at;
        return true;
    }

    public void relocate(Long newPropertyId) {
        this.propertyId = newPropertyId;
    }

    private void require(boolean allowed, String action) {
        if (!allowed) {
            throw new ConflictException("MANDATE_INVALID_STATE", "Cannot " + action + " a mandate that is " + status);
        }
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

    public String getProvider() {
        return provider;
    }

    public String getProviderMandateId() {
        return providerMandateId;
    }

    public String getProviderCustomerId() {
        return providerCustomerId;
    }

    public String getProviderTokenId() {
        return providerTokenId;
    }

    public MandateMethod getMethod() {
        return method;
    }

    public BigDecimal getMaxAmount() {
        return maxAmount;
    }

    public MandateStatus getStatus() {
        return status;
    }

    public String getAuthorisationUrl() {
        return authorisationUrl;
    }

    public int getDebitDayOffset() {
        return debitDayOffset;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getAuthorisedAt() {
        return authorisedAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
