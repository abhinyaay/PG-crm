package com.pgms.moveout;

import com.pgms.common.exception.BusinessRuleException;
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
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** A property's move-out rules (row in {@code property_moveout_policies}; absent = {@link #defaults}). */
@Entity
@Table(name = "property_moveout_policies")
@EntityListeners(AuditingEntityListener.class)
public class MoveOutPolicy {

    public static final List<String> DEFAULT_CHECKLIST = List.of("Room keys returned", "Cupboard key returned",
            "Access card returned", "Mattress & bed", "Furniture", "Walls & paint", "Bathroom fittings",
            "Electricals & AC remote");

    public enum ShortfallRule { NONE, CHARGE_DAYS }

    public enum LockInPenaltyRule { NONE, ONE_MONTH_RENT, FLAT }

    public enum FinalMonthBilling { FULL, PRORATED }

    @Id
    @Column(name = "property_id")
    private Long propertyId;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "notice_period_days", nullable = false)
    private int noticePeriodDays = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "notice_shortfall_rule", nullable = false)
    private ShortfallRule shortfallRule = ShortfallRule.CHARGE_DAYS;

    @Column(name = "lock_in_months", nullable = false)
    private int lockInMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_in_penalty_rule", nullable = false)
    private LockInPenaltyRule lockInPenaltyRule = LockInPenaltyRule.NONE;

    @Column(name = "lock_in_penalty_amount")
    private BigDecimal lockInPenaltyAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "final_month_billing", nullable = false)
    private FinalMonthBilling finalMonthBilling = FinalMonthBilling.FULL;

    @Column(name = "deposit_refund_days", nullable = false)
    private int depositRefundDays = 7;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inspection_checklist", nullable = false, columnDefinition = "jsonb")
    private List<String> inspectionChecklist = new ArrayList<>(DEFAULT_CHECKLIST);

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

    @Version
    @Column(nullable = false)
    private Long version;

    protected MoveOutPolicy() {
    }

    public MoveOutPolicy(Long propertyId, Long organizationId) {
        this.propertyId = propertyId;
        this.organizationId = organizationId;
    }

    /** Not persisted: the policy of a property without a row. */
    public static MoveOutPolicy defaults(Long propertyId, Long organizationId) {
        return new MoveOutPolicy(propertyId, organizationId);
    }

    public void update(int notice, ShortfallRule shortfall, int lockIn, LockInPenaltyRule penaltyRule, BigDecimal penaltyAmount,
                       FinalMonthBilling finalMonth, int refundDays, List<String> checklist) {
        if (penaltyRule == LockInPenaltyRule.FLAT && (penaltyAmount == null || penaltyAmount.signum() <= 0)) {
            throw new BusinessRuleException("INVALID_POLICY", "A FLAT lock-in penalty needs a positive lockInPenaltyAmount");
        }
        this.noticePeriodDays = notice;
        this.shortfallRule = shortfall;
        this.lockInMonths = lockIn;
        this.lockInPenaltyRule = penaltyRule;
        this.lockInPenaltyAmount = penaltyRule == LockInPenaltyRule.FLAT ? penaltyAmount : null;
        this.finalMonthBilling = finalMonth;
        this.depositRefundDays = refundDays;
        this.inspectionChecklist = new ArrayList<>(checklist);
    }

    /** The pure calculation view of this policy. */
    public MoveOutCalculator.Terms terms() {
        return new MoveOutCalculator.Terms(noticePeriodDays, shortfallRule == ShortfallRule.CHARGE_DAYS, lockInMonths,
                lockInPenaltyRule, lockInPenaltyAmount);
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public int getNoticePeriodDays() {
        return noticePeriodDays;
    }

    public ShortfallRule getShortfallRule() {
        return shortfallRule;
    }

    public int getLockInMonths() {
        return lockInMonths;
    }

    public LockInPenaltyRule getLockInPenaltyRule() {
        return lockInPenaltyRule;
    }

    public BigDecimal getLockInPenaltyAmount() {
        return lockInPenaltyAmount;
    }

    public FinalMonthBilling getFinalMonthBilling() {
        return finalMonthBilling;
    }

    public int getDepositRefundDays() {
        return depositRefundDays;
    }

    public List<String> getInspectionChecklist() {
        return inspectionChecklist;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
