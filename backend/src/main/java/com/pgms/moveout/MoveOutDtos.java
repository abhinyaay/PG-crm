package com.pgms.moveout;

import com.pgms.billing.InvoiceStatus;
import com.pgms.billing.InvoiceType;
import com.pgms.billing.LineItemType;
import com.pgms.billing.PaymentMethod;
import com.pgms.deposit.DepositStatus;
import com.pgms.moveout.MoveOutEnums.Initiator;
import com.pgms.moveout.MoveOutEnums.ItemCondition;
import com.pgms.moveout.MoveOutEnums.Reason;
import com.pgms.moveout.MoveOutEnums.Status;
import com.pgms.moveout.MoveOutPolicy.FinalMonthBilling;
import com.pgms.moveout.MoveOutPolicy.LockInPenaltyRule;
import com.pgms.moveout.MoveOutPolicy.ShortfallRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MoveOutDtos {

    private MoveOutDtos() {
    }

    // ---- policy

    public record PolicyResponse(Long propertyId, int noticePeriodDays, ShortfallRule noticeShortfallRule, int lockInMonths,
                                 LockInPenaltyRule lockInPenaltyRule, BigDecimal lockInPenaltyAmount,
                                 FinalMonthBilling finalMonthBilling, int depositRefundDays, List<String> inspectionChecklist,
                                 boolean customized, Instant updatedAt) {
        static PolicyResponse of(MoveOutPolicy p, boolean customized) {
            return new PolicyResponse(p.getPropertyId(), p.getNoticePeriodDays(), p.getShortfallRule(), p.getLockInMonths(),
                    p.getLockInPenaltyRule(), p.getLockInPenaltyAmount(), p.getFinalMonthBilling(), p.getDepositRefundDays(),
                    List.copyOf(p.getInspectionChecklist()), customized, p.getUpdatedAt());
        }
    }

    public record UpdatePolicyRequest(
            @Min(0) @Max(90) int noticePeriodDays,
            @NotNull ShortfallRule noticeShortfallRule,
            @Min(0) @Max(24) int lockInMonths,
            @NotNull LockInPenaltyRule lockInPenaltyRule,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal lockInPenaltyAmount,
            @NotNull FinalMonthBilling finalMonthBilling,
            @Min(0) @Max(60) int depositRefundDays,
            @NotNull @Size(max = 50) List<@NotBlank @Size(max = 100) String> inspectionChecklist) {
    }

    // ---- notice

    /** What leaving on {@code moveOutDate} would cost, for the "give notice" screen. */
    public record PreviewResponse(Long allocationId, Long tenantId, LocalDate noticeDate, LocalDate moveOutDate,
                                  int noticePeriodDays, LocalDate earliestNoPenaltyDate, int shortfallDays,
                                  BigDecimal shortfallCharge, LocalDate lockInEndDate, BigDecimal lockInPenalty,
                                  BigDecimal monthlyRent, FinalMonthBilling finalMonthBilling, BigDecimal depositHeld,
                                  BigDecimal outstandingDues, BigDecimal estimatedRefund, BigDecimal estimatedPayable) {
    }

    /** {@code allocationId} is required for staff and ignored for residents (their own active stay is used). */
    public record CreateMoveOutRequest(
            Long allocationId,
            @NotNull LocalDate expectedMoveOutDate,
            @NotNull Reason reason,
            @Size(max = 500) String reasonText,
            @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._-]{2,64}@[A-Za-z]{2,64}$", message = "must be a UPI id like name@bank")
            String refundUpiVpa) {
    }

    public record ChangeDateRequest(
            @NotNull LocalDate expectedMoveOutDate,
            boolean waiveCharges,
            @NotBlank @Size(max = 255) String reason) {
    }

    // ---- inspection

    public record InspectionItemRequest(
            @NotBlank @Size(max = 100) String item,
            @NotNull ItemCondition condition,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal deductionAmount,
            @Size(max = 500) String note) {
    }

    public record InspectionRequest(
            @NotNull @Size(max = 50) @Valid List<InspectionItemRequest> items,
            @Size(max = 2000) String notes) {
    }

    public record InspectionItemResponse(Long id, String item, ItemCondition condition, BigDecimal deductionAmount,
                                         String note) {
        static InspectionItemResponse of(MoveOutInspectionItem i) {
            return new InspectionItemResponse(i.getId(), i.getItem(), i.getCondition(), i.getDeductionAmount(), i.getNote());
        }
    }

    // ---- settlement

    /** {@code type}: DEDUCTION (default), DAMAGE or OTHER. */
    public record ExtraDeduction(
            LineItemType type,
            @NotBlank @Size(max = 255) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /**
     * {@code waiveCharges} (optional) overrides the case's waiver of notice-shortfall / lock-in charges;
     * {@code skipInspection} lets admins settle a case that was never inspected.
     */
    public record CompleteRequest(
            @NotNull LocalDate actualMoveOutDate,
            boolean sendBedToMaintenance,
            @Valid @Size(max = 20) List<ExtraDeduction> extraDeductions,
            Boolean waiveCharges,
            boolean skipInspection) {
    }

    public record CloseRequest(boolean writeOffDues, @NotBlank @Size(max = 500) String note) {
    }

    public record RefundRequest(
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 100) String reference,
            @Size(max = 255) String note) {
    }

    public record ChargeLine(LineItemType type, String description, BigDecimal amount) {
    }

    public record OpenInvoice(Long id, String invoiceNumber, InvoiceType type, InvoiceStatus status, LocalDate dueDate,
                              BigDecimal balance) {
    }

    /** Settlement wizard preview (read-only): dues -> deductions -> deposit waterfall. */
    public record SettlementPreview(Long moveOutId, LocalDate actualMoveOutDate, List<OpenInvoice> openInvoices,
                                    BigDecimal outstandingDues, List<ChargeLine> charges, BigDecimal totalDeductions,
                                    BigDecimal depositHeld, BigDecimal estimatedRentCredit, BigDecimal duesCoveredByDeposit,
                                    BigDecimal deductionsCoveredByDeposit, BigDecimal estimatedRefund,
                                    BigDecimal estimatedPayable, FinalMonthBilling finalMonthBilling, int depositRefundDays) {
    }

    public record SettlementView(LocalDate actualMoveOutDate, BigDecimal depositHeld, BigDecimal outstandingDues,
                                 BigDecimal duesAdjusted, BigDecimal totalDeductions, BigDecimal rentCredit,
                                 BigDecimal refundDue, BigDecimal payableByTenant, LocalDate refundDueBy,
                                 Long settlementInvoiceId, Long depositAccountId, DepositStatus depositStatus,
                                 BigDecimal refundStillDue, Instant settledAt) {
    }

    public record MoveOutResponse(
            Long id,
            String reference,
            Long propertyId,
            Long tenantId,
            String tenantName,
            Long allocationId,
            Status status,
            Initiator initiatedBy,
            LocalDate noticeDate,
            LocalDate expectedMoveOutDate,
            Reason reason,
            String reasonText,
            String refundUpiVpa,
            int shortfallDays,
            BigDecimal shortfallCharge,
            BigDecimal lockInPenalty,
            boolean chargesWaived,
            Instant inspectedAt,
            Long inspectedBy,
            String inspectionNotes,
            List<InspectionItemResponse> inspectionItems,
            SettlementView settlement,
            Instant withdrawnAt,
            Instant closedAt,
            String closeNote,
            Instant createdAt) {
    }
}
