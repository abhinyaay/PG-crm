package com.pgms.deposit;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.BusinessRuleException;
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
 * The security deposit of one stay chain (the first allocation and every allocation reached from it by transfers).
 * Collected / held amounts are derived from the payments on the chain's deposit invoices; this aggregate keeps the
 * settlement outcome: what is still owed to the resident ({@code refundDue}) and by when.
 */
@Entity
@Table(name = "deposit_accounts")
public class DepositAccount extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "root_allocation_id", nullable = false, updatable = false)
    private Long rootAllocationId;

    @Column(name = "current_allocation_id", nullable = false)
    private Long currentAllocationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepositStatus status = DepositStatus.HELD;

    @Column(name = "move_out_date")
    private LocalDate moveOutDate;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "refund_due", nullable = false)
    private BigDecimal refundDue = BigDecimal.ZERO;

    @Column(name = "refund_due_by")
    private LocalDate refundDueBy;

    @Column(name = "forfeit_reason")
    private String forfeitReason;

    @Column(name = "overdue_notified_on")
    private LocalDate overdueNotifiedOn;

    protected DepositAccount() {
    }

    public DepositAccount(Long organizationId, Long propertyId, Long tenantId, Long rootAllocationId, Long currentAllocationId) {
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.rootAllocationId = rootAllocationId;
        this.currentAllocationId = currentAllocationId;
    }

    public boolean isSettled() {
        return status != DepositStatus.HELD;
    }

    /** The stay moved to another bed (possibly another property of the organization). */
    void followTransfer(Long allocationId, Long newPropertyId) {
        this.currentAllocationId = allocationId;
        this.propertyId = newPropertyId;
    }

    void settle(LocalDate date, BigDecimal remainingRefund, LocalDate dueBy, Instant at) {
        if (isSettled()) {
            throw new ConflictException("DEPOSIT_ALREADY_SETTLED", "The deposit of this stay was already settled (" + status + ")");
        }
        this.moveOutDate = date;
        this.settledAt = at;
        this.refundDue = remainingRefund;
        this.refundDueBy = remainingRefund.signum() > 0 ? dueBy : null;
        this.status = remainingRefund.signum() > 0 ? DepositStatus.REFUND_DUE : DepositStatus.SETTLED;
    }

    void recordRefund(BigDecimal amount) {
        if (!status.refundPending()) {
            throw new ConflictException("NO_REFUND_DUE", "No refund is due on this deposit (" + status + ")");
        }
        if (amount.compareTo(refundDue) > 0) {
            throw new BusinessRuleException("REFUND_EXCEEDS_DUE", "Refund " + amount + " exceeds the refund due " + refundDue);
        }
        refundDue = refundDue.subtract(amount);
        status = refundDue.signum() == 0 ? DepositStatus.REFUNDED : DepositStatus.PARTIALLY_REFUNDED;
        if (refundDue.signum() == 0) {
            refundDueBy = null;
        }
    }

    /** A deduction (or a refund made outside the Deposits tab) after the settlement lowers what is still owed. */
    void reduceRefundDue(BigDecimal amount, boolean paidOut) {
        if (!status.refundPending()) {
            return;
        }
        refundDue = refundDue.subtract(amount.min(refundDue));
        if (refundDue.signum() == 0) {
            status = paidOut || status == DepositStatus.PARTIALLY_REFUNDED ? DepositStatus.REFUNDED : DepositStatus.SETTLED;
            refundDueBy = null;
        } else if (paidOut) {
            status = DepositStatus.PARTIALLY_REFUNDED;
        }
    }

    void forfeit(String reason, LocalDate date, Instant at) {
        if (status == DepositStatus.FORFEITED || status == DepositStatus.REFUNDED || status == DepositStatus.SETTLED) {
            throw new ConflictException("DEPOSIT_NOT_FORFEITABLE", "Nothing left to forfeit on this deposit (" + status + ")");
        }
        if (moveOutDate == null) {
            moveOutDate = date;
        }
        if (settledAt == null) {
            settledAt = at;
        }
        this.forfeitReason = reason;
        this.refundDue = BigDecimal.ZERO;
        this.refundDueBy = null;
        this.status = DepositStatus.FORFEITED;
    }

    void markOverdueNotified(LocalDate date) {
        this.overdueNotifiedOn = date;
    }

    public boolean isRefundOverdue(LocalDate today) {
        return status.refundPending() && refundDueBy != null && refundDueBy.isBefore(today);
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

    public Long getRootAllocationId() {
        return rootAllocationId;
    }

    public Long getCurrentAllocationId() {
        return currentAllocationId;
    }

    public DepositStatus getStatus() {
        return status;
    }

    public LocalDate getMoveOutDate() {
        return moveOutDate;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public BigDecimal getRefundDue() {
        return refundDue;
    }

    public LocalDate getRefundDueBy() {
        return refundDueBy;
    }

    public String getForfeitReason() {
        return forfeitReason;
    }

    public LocalDate getOverdueNotifiedOn() {
        return overdueNotifiedOn;
    }
}
