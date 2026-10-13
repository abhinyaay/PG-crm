package com.pgms.billing;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * A fixed monthly add-on (food, laundry, parking, internet ...) of a resident. Rent generation and move-in invoicing
 * copy it into the rent invoice as a line item, so editing or ending a charge never changes invoices already issued.
 * {@code allocationId == null} means the charge follows the resident across transfers.
 */
@Entity
@Table(name = "recurring_charges")
public class RecurringCharge extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "allocation_id", updatable = false)
    private Long allocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false, updatable = false)
    private LineItemType lineType;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "start_month", nullable = false, updatable = false)
    private LocalDate startMonth;

    @Column(name = "end_month")
    private LocalDate endMonth;

    @Column(name = "prorate_first_month", nullable = false)
    private boolean prorateFirstMonth;

    @Column(nullable = false)
    private boolean active = true;

    protected RecurringCharge() {
    }

    public RecurringCharge(Long organizationId, Long propertyId, Long tenantId, Long allocationId, LineItemType lineType,
                           String description, BigDecimal amount, YearMonth startMonth, YearMonth endMonth,
                           boolean prorateFirstMonth) {
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.allocationId = allocationId;
        this.lineType = lineType;
        this.description = description;
        this.amount = amount;
        this.startMonth = startMonth.atDay(1);
        this.endMonth = endMonth == null ? null : endMonth.atDay(1);
        this.prorateFirstMonth = prorateFirstMonth;
    }

    public void update(String description, BigDecimal amount, YearMonth endMonth, boolean prorateFirstMonth, boolean active) {
        this.description = description;
        this.amount = amount;
        this.endMonth = endMonth == null ? null : endMonth.atDay(1);
        this.prorateFirstMonth = prorateFirstMonth;
        this.active = active;
    }

    public void deactivate() {
        this.active = false;
    }

    /** Active and the month lies in [startMonth, endMonth]. */
    public boolean covers(YearMonth month) {
        LocalDate first = month.atDay(1);
        return active && !first.isBefore(startMonth) && (endMonth == null || !first.isAfter(endMonth));
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

    public Long getAllocationId() {
        return allocationId;
    }

    public LineItemType getLineType() {
        return lineType;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDate getStartMonth() {
        return startMonth;
    }

    public LocalDate getEndMonth() {
        return endMonth;
    }

    public boolean isProrateFirstMonth() {
        return prorateFirstMonth;
    }

    public boolean isActive() {
        return active;
    }
}
