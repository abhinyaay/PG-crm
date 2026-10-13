package com.pgms.moveout;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.ConflictException;
import com.pgms.moveout.MoveOutCalculator.NoticeTerms;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEnums.ItemCondition;
import com.pgms.moveout.MoveOutEnums.Reason;
import com.pgms.moveout.MoveOutEnums.Status;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A resident's move-out case: notice (with the shortfall / lock-in charges computed from the property policy),
 * inspection checklist, and the persisted settlement. It follows the resident across transfers while open.
 */
@Entity
@Table(name = "move_outs")
public class MoveOut extends AuditableEntity {

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "allocation_id", nullable = false)
    private Long allocationId;

    @Column(name = "deposit_account_id")
    private Long depositAccountId;

    @Column(name = "requested_by_user_id", updatable = false)
    private Long requestedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiated_by", nullable = false, updatable = false)
    private Initiator initiatedBy;

    @Column(name = "notice_date", nullable = false)
    private LocalDate noticeDate;

    @Column(name = "expected_move_out_date", nullable = false)
    private LocalDate expectedMoveOutDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    @Column(name = "reason_text")
    private String reasonText;

    @Column(name = "refund_upi_vpa")
    private String refundUpiVpa;

    @Column(name = "shortfall_days", nullable = false)
    private int shortfallDays;

    @Column(name = "shortfall_charge", nullable = false)
    private BigDecimal shortfallCharge = BigDecimal.ZERO;

    @Column(name = "lock_in_penalty", nullable = false)
    private BigDecimal lockInPenalty = BigDecimal.ZERO;

    @Column(name = "charges_waived", nullable = false)
    private boolean chargesWaived;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.NOTICE_GIVEN;

    @Column(name = "inspected_by")
    private Long inspectedBy;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @Column(name = "inspection_notes")
    private String inspectionNotes;

    @Column(name = "actual_move_out_date")
    private LocalDate actualMoveOutDate;

    @Column(name = "settlement_invoice_id")
    private Long settlementInvoiceId;

    @Column(name = "deposit_held")
    private BigDecimal depositHeld;

    @Column(name = "outstanding_dues")
    private BigDecimal outstandingDues;

    @Column(name = "dues_adjusted")
    private BigDecimal duesAdjusted;

    @Column(name = "total_deductions")
    private BigDecimal totalDeductions;

    @Column(name = "rent_credit")
    private BigDecimal rentCredit;

    @Column(name = "refund_due")
    private BigDecimal refundDue;

    @Column(name = "payable_by_tenant")
    private BigDecimal payableByTenant;

    @Column(name = "refund_due_by")
    private LocalDate refundDueBy;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "close_note")
    private String closeNote;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @OneToMany(mappedBy = "moveOut", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<MoveOutInspectionItem> items = new ArrayList<>();

    protected MoveOut() {
    }

    public MoveOut(String reference, Long organizationId, Long propertyId, Long tenantId, Long allocationId,
                   Long requestedByUserId, Initiator initiatedBy, LocalDate noticeDate, LocalDate expectedMoveOutDate,
                   Reason reason, String reasonText, String refundUpiVpa) {
        this.reference = reference;
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.allocationId = allocationId;
        this.requestedByUserId = requestedByUserId;
        this.initiatedBy = initiatedBy;
        this.noticeDate = noticeDate;
        this.expectedMoveOutDate = expectedMoveOutDate;
        this.reason = reason;
        this.reasonText = reasonText;
        this.refundUpiVpa = refundUpiVpa;
    }

    public void applyTerms(NoticeTerms terms) {
        this.shortfallDays = terms.shortfallDays();
        this.shortfallCharge = terms.shortfallCharge();
        this.lockInPenalty = terms.lockInPenalty();
    }

    public void reschedule(LocalDate expected, NoticeTerms terms, boolean waive) {
        requireOpen();
        this.expectedMoveOutDate = expected;
        applyTerms(terms);
        this.chargesWaived = waive;
    }

    public void waiveCharges() {
        this.chargesWaived = true;
    }

    public void followAllocation(Long newAllocationId, Long newPropertyId) {
        this.allocationId = newAllocationId;
        this.propertyId = newPropertyId;
    }

    /** One checklist entry for {@link #recordInspection}. */
    public record Item(String item, ItemCondition condition, BigDecimal deductionAmount, String note) {
    }

    public void recordInspection(List<Item> newItems, String notes, Long userId, Instant at) {
        requireOpen();
        items.clear();
        newItems.forEach(i -> items.add(new MoveOutInspectionItem(this, i.item(), i.condition(), i.deductionAmount(), i.note())));
        this.inspectionNotes = notes;
        this.inspectedBy = userId;
        this.inspectedAt = at;
        this.status = Status.INSPECTED;
    }

    public void withdraw(Instant at) {
        if (status != Status.NOTICE_GIVEN) {
            throw invalid("withdrawn");
        }
        this.status = Status.WITHDRAWN;
        this.withdrawnAt = at;
    }

    /** Figures of the settlement, persisted when the case is completed. */
    public record Settlement(LocalDate actualMoveOutDate, Long settlementInvoiceId, Long depositAccountId,
                             BigDecimal depositHeld, BigDecimal outstandingDues, BigDecimal duesAdjusted,
                             BigDecimal totalDeductions, BigDecimal rentCredit, BigDecimal refundDue,
                             BigDecimal payableByTenant, LocalDate refundDueBy) {
    }

    public void settle(Settlement s, Instant at) {
        if (status != Status.NOTICE_GIVEN && status != Status.INSPECTED) {
            throw invalid("settled");
        }
        this.actualMoveOutDate = s.actualMoveOutDate();
        this.settlementInvoiceId = s.settlementInvoiceId();
        this.depositAccountId = s.depositAccountId();
        this.depositHeld = s.depositHeld();
        this.outstandingDues = s.outstandingDues();
        this.duesAdjusted = s.duesAdjusted();
        this.totalDeductions = s.totalDeductions();
        this.rentCredit = s.rentCredit();
        this.refundDue = s.refundDue();
        this.payableByTenant = s.payableByTenant();
        this.refundDueBy = s.refundDueBy();
        this.settledAt = at;
        this.status = Status.SETTLED;
    }

    public void close(String note, Instant at) {
        if (status != Status.SETTLED) {
            throw invalid("closed");
        }
        this.status = Status.CLOSED;
        this.closedAt = at;
        this.closeNote = note;
    }

    /** Charges applied at settlement (0 when waived). */
    public BigDecimal effectiveShortfall() {
        return chargesWaived ? BigDecimal.ZERO : shortfallCharge;
    }

    public BigDecimal effectiveLockInPenalty() {
        return chargesWaived ? BigDecimal.ZERO : lockInPenalty;
    }

    private void requireOpen() {
        if (!status.isOpen()) {
            throw invalid("changed");
        }
    }

    private ConflictException invalid(String action) {
        return new ConflictException("MOVE_OUT_INVALID_STATE", "Move-out " + reference + " is " + status + " and cannot be "
                + action);
    }

    public String getReference() {
        return reference;
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

    public Long getDepositAccountId() {
        return depositAccountId;
    }

    public Long getRequestedByUserId() {
        return requestedByUserId;
    }

    public Initiator getInitiatedBy() {
        return initiatedBy;
    }

    public LocalDate getNoticeDate() {
        return noticeDate;
    }

    public LocalDate getExpectedMoveOutDate() {
        return expectedMoveOutDate;
    }

    public Reason getReason() {
        return reason;
    }

    public String getReasonText() {
        return reasonText;
    }

    public String getRefundUpiVpa() {
        return refundUpiVpa;
    }

    public int getShortfallDays() {
        return shortfallDays;
    }

    public BigDecimal getShortfallCharge() {
        return shortfallCharge;
    }

    public BigDecimal getLockInPenalty() {
        return lockInPenalty;
    }

    public boolean isChargesWaived() {
        return chargesWaived;
    }

    public Status getStatus() {
        return status;
    }

    public Long getInspectedBy() {
        return inspectedBy;
    }

    public Instant getInspectedAt() {
        return inspectedAt;
    }

    public String getInspectionNotes() {
        return inspectionNotes;
    }

    public LocalDate getActualMoveOutDate() {
        return actualMoveOutDate;
    }

    public Long getSettlementInvoiceId() {
        return settlementInvoiceId;
    }

    public BigDecimal getDepositHeld() {
        return depositHeld;
    }

    public BigDecimal getOutstandingDues() {
        return outstandingDues;
    }

    public BigDecimal getDuesAdjusted() {
        return duesAdjusted;
    }

    public BigDecimal getTotalDeductions() {
        return totalDeductions;
    }

    public BigDecimal getRentCredit() {
        return rentCredit;
    }

    public BigDecimal getRefundDue() {
        return refundDue;
    }

    public BigDecimal getPayableByTenant() {
        return payableByTenant;
    }

    public LocalDate getRefundDueBy() {
        return refundDueBy;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getCloseNote() {
        return closeNote;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public List<MoveOutInspectionItem> getItems() {
        return items;
    }
}
