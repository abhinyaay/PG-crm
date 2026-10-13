package com.pgms.booking;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Occupancy of one bed by one tenant over [startDate, endDate). Rent and deposit are snapshotted at check-in so
 * later room price changes never alter what an existing resident pays. A transfer ends this allocation
 * (TRANSFERRED) and links the successor through {@code previousAllocationId}, giving a full stay history.
 */
@Entity
@Table(name = "allocations")
public class Allocation extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "bed_id", nullable = false, updatable = false)
    private Long bedId;

    @Column(name = "booking_id", updatable = false)
    private Long bookingId;

    @Column(name = "previous_allocation_id", updatable = false)
    private Long previousAllocationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AllocationStatus status = AllocationStatus.ACTIVE;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "notice_date")
    private LocalDate noticeDate;

    @Column(name = "expected_end_date")
    private LocalDate expectedEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "end_reason")
    private EndReason endReason;

    @Column(name = "monthly_rent", nullable = false, updatable = false)
    private BigDecimal monthlyRent;

    @Column(name = "security_deposit", nullable = false, updatable = false)
    private BigDecimal securityDeposit;

    protected Allocation() {
    }

    public Allocation(Long organizationId, Long propertyId, Long tenantId, Long bedId, Long bookingId,
                      Long previousAllocationId, LocalDate startDate, BigDecimal monthlyRent, BigDecimal securityDeposit) {
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.bedId = bedId;
        this.bookingId = bookingId;
        this.previousAllocationId = previousAllocationId;
        this.startDate = startDate;
        this.monthlyRent = monthlyRent;
        this.securityDeposit = securityDeposit;
    }

    public boolean isActive() {
        return status == AllocationStatus.ACTIVE;
    }

    public void end(LocalDate date, EndReason reason) {
        if (!isActive()) {
            throw new ConflictException("ALLOCATION_NOT_ACTIVE", "Allocation " + getId() + " is already " + status);
        }
        if (date.isBefore(startDate)) {
            throw new BusinessRuleException("INVALID_END_DATE", "End date cannot be before the start date " + startDate);
        }
        if (reason == EndReason.CHECK_OUT && !date.isAfter(startDate)) {
            // [d, d) is an empty range: it would escape the overlap constraints and keep rent billed for 0 nights
            throw new BusinessRuleException("ZERO_LENGTH_STAY",
                    "Check-out date must be after the check-in date " + startDate);
        }
        endDate = date;
        endReason = reason;
        status = reason == EndReason.TRANSFER ? AllocationStatus.TRANSFERRED : AllocationStatus.ENDED;
    }

    public void recordNotice(LocalDate noticeDate, LocalDate expectedEndDate) {
        if (!isActive()) {
            throw new ConflictException("ALLOCATION_NOT_ACTIVE", "Allocation is not active");
        }
        this.noticeDate = noticeDate;
        this.expectedEndDate = expectedEndDate;
    }

    public void clearNotice() {
        this.noticeDate = null;
        this.expectedEndDate = null;
    }

    /** Carries an outstanding notice over to the successor allocation on transfer. */
    public void inheritNotice(Allocation previous) {
        this.noticeDate = previous.noticeDate;
        this.expectedEndDate = previous.expectedEndDate;
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

    public Long getBedId() {
        return bedId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public Long getPreviousAllocationId() {
        return previousAllocationId;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getNoticeDate() {
        return noticeDate;
    }

    public LocalDate getExpectedEndDate() {
        return expectedEndDate;
    }

    public EndReason getEndReason() {
        return endReason;
    }

    public BigDecimal getMonthlyRent() {
        return monthlyRent;
    }

    public BigDecimal getSecurityDeposit() {
        return securityDeposit;
    }
}
