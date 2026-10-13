package com.pgms.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Collections policy of one property: late fee rule, grace, reminder schedule and the UPI payee. Keyed by the property
 * (one row per property, absent = configured defaults). Other policy areas (move-out, utilities) live in their own
 * tables so that each feature owns its schema.
 */
@Entity
@Table(name = "property_billing_policies")
@EntityListeners(AuditingEntityListener.class)
public class PropertyBillingPolicy {

    @Id
    @Column(name = "property_id")
    private Long propertyId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "late_fee_enabled", nullable = false)
    private boolean lateFeeEnabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "late_fee_type", nullable = false)
    private LateFeeType lateFeeType = LateFeeType.FLAT;

    @Column(name = "late_fee_value", nullable = false)
    private BigDecimal lateFeeValue = BigDecimal.ZERO;

    @Column(name = "late_fee_cap")
    private BigDecimal lateFeeCap;

    @Column(name = "grace_days", nullable = false)
    private int graceDays;

    @Column(name = "move_in_grace_days", nullable = false)
    private int moveInGraceDays;

    @Column(name = "late_fee_on_move_in", nullable = false)
    private boolean lateFeeOnMoveIn;

    @Column(name = "due_reminder_days_before", nullable = false)
    private int dueReminderDaysBefore;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "overdue_reminder_offsets", nullable = false, columnDefinition = "integer[]")
    private Integer[] overdueReminderOffsets;

    @Column(name = "upi_vpa")
    private String upiVpa;

    @Column(name = "upi_payee_name")
    private String upiPayeeName;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    /** Wrapper type: null marks a not-yet-persisted row (the id is assigned, so it cannot tell). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PropertyBillingPolicy() {
    }

    public PropertyBillingPolicy(Long propertyId, Long organizationId) {
        this.propertyId = propertyId;
        this.organizationId = organizationId;
    }

    /** Replaces every setting; validation of combinations happens in the service. */
    public void apply(BillingPolicy policy) {
        this.lateFeeEnabled = policy.lateFeeEnabled();
        this.lateFeeType = policy.lateFeeType();
        this.lateFeeValue = policy.lateFeeValue();
        this.lateFeeCap = policy.lateFeeCap();
        this.graceDays = policy.graceDays();
        this.moveInGraceDays = policy.moveInGraceDays();
        this.lateFeeOnMoveIn = policy.lateFeeOnMoveIn();
        this.dueReminderDaysBefore = policy.dueReminderDaysBefore();
        this.overdueReminderOffsets = policy.overdueReminderOffsets().toArray(Integer[]::new);
        this.upiVpa = policy.upiVpa();
        this.upiPayeeName = policy.upiPayeeName();
    }

    /** Immutable view used by calculators, jobs and responses. */
    public BillingPolicy toPolicy() {
        return new BillingPolicy(propertyId, true, lateFeeEnabled, lateFeeType, lateFeeValue, lateFeeCap, graceDays,
                moveInGraceDays, lateFeeOnMoveIn, dueReminderDaysBefore,
                overdueReminderOffsets == null ? List.of() : Arrays.asList(overdueReminderOffsets), upiVpa, upiPayeeName,
                getVersion());
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
