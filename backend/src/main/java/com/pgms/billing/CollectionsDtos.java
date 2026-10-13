package com.pgms.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Request/response records of the collections features: billing policy, late fees, UPI pay links, claims, add-ons. */
public final class CollectionsDtos {

    static final String VPA_PATTERN = "^[A-Za-z0-9._-]{2,256}@[A-Za-z0-9]{2,64}$";

    private CollectionsDtos() {
    }

    // ---- billing policy ----

    public record LateFeeSettings(
            boolean enabled,
            @NotNull LateFeeType type,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal value,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal cap,
            @Min(0) @Max(31) int graceDays,
            @Min(0) @Max(31) Integer moveInGraceDays,
            boolean onMoveIn) {
    }

    public record ReminderSettings(@Min(0) @Max(15) int dueDaysBefore, @NotNull List<Integer> overdueOffsets) {
    }

    public record UpiSettings(@Pattern(regexp = VPA_PATTERN, message = "must be a UPI id like name@bank") String vpa,
                              @Size(max = 100) String payeeName) {
    }

    /** {@code version}: null when creating the first policy of the property, otherwise the version that was read. */
    public record UpdateBillingPolicyRequest(@Valid @NotNull LateFeeSettings lateFee,
                                             @Valid @NotNull ReminderSettings reminders,
                                             @Valid UpiSettings upi,
                                             Long version) {
    }

    public record BillingPolicyResponse(Long propertyId, boolean configured, LateFeeSettings lateFee, ReminderSettings reminders,
                                        UpiSettings upi, long version) {
        static BillingPolicyResponse of(BillingPolicy p) {
            return new BillingPolicyResponse(p.propertyId(), p.configured(),
                    new LateFeeSettings(p.lateFeeEnabled(), p.lateFeeType(), p.lateFeeValue(), p.lateFeeCap(), p.graceDays(),
                            p.moveInGraceDays(), p.lateFeeOnMoveIn()),
                    new ReminderSettings(p.dueReminderDaysBefore(), p.overdueReminderOffsets()),
                    new UpiSettings(p.upiVpa(), p.upiPayeeName()), p.version());
        }
    }

    // ---- late fees ----

    public record WaiveLateFeeRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                      @NotBlank @Size(max = 200) String reason) {
    }

    public record LateFeeEntryResponse(Long id, LateFeeEntryKind kind, LateFeeType rule, LocalDate periodKey, BigDecimal amount,
                                       String reason, Long lineItemId, Instant createdAt) {
        static LateFeeEntryResponse of(LateFeeEntry e) {
            return new LateFeeEntryResponse(e.getId(), e.getKind(), e.getRule(), e.getPeriodKey(), e.getAmount(), e.getReason(),
                    e.getLineItemId(), e.getCreatedAt());
        }
    }

    public record LateFeeHistoryResponse(Long invoiceId, BigDecimal charged, BigDecimal waived, BigDecimal net,
                                         LocalDate lateFeeFrom, List<LateFeeEntryResponse> entries) {
    }

    public record JobRunResult(LocalDate date, int applied, int skipped, int failed) {
    }

    // ---- UPI pay link ----

    /** {@code upiUri} is also the QR payload: the client renders the QR code from it. */
    public record PayLinkResponse(Long invoiceId, String invoiceNumber, BigDecimal amount, String currency, String payeeName,
                                  String vpa, String upiUri, String qrPayload) {
    }

    // ---- payment claims ----

    public record SubmitClaimRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 40) String utr,
            @NotNull @PastOrPresent LocalDate paidOn,
            @Size(max = 500) String note) {
    }

    public record ApproveClaimRequest(
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @PastOrPresent Instant paidAt,
            @Size(max = 500) String notes) {
    }

    public record RejectClaimRequest(@NotBlank @Size(max = 255) String reason) {
    }

    public record PaymentClaimResponse(
            Long id,
            String claimReference,
            Long propertyId,
            Long tenantId,
            String tenantName,
            Long invoiceId,
            String invoiceNumber,
            BigDecimal amount,
            BigDecimal approvedAmount,
            PaymentMethod method,
            String utr,
            LocalDate paidOn,
            String note,
            PaymentClaimStatus status,
            Long reviewedBy,
            Instant reviewedAt,
            String reviewNote,
            String rejectionReason,
            Long paymentId,
            Instant createdAt) {

        static PaymentClaimResponse of(PaymentClaim c, String tenantName, String invoiceNumber) {
            return new PaymentClaimResponse(c.getId(), c.getClaimReference(), c.getPropertyId(), c.getTenantId(), tenantName,
                    c.getInvoiceId(), invoiceNumber, c.getAmount(), c.getApprovedAmount(), c.getMethod(), c.getUtr(),
                    c.getPaidOn(), c.getTenantNote(), c.getStatus(), c.getReviewedBy(), c.getReviewedAt(), c.getReviewNote(),
                    c.getRejectionReason(), c.getPaymentId(), c.getCreatedAt());
        }
    }

    public record ClaimApprovalResponse(PaymentClaimResponse claim, BillingDtos.PaymentResponse payment) {
    }

    // ---- recurring charges ----

    public record CreateRecurringChargeRequest(
            @NotNull LineItemType type,
            @NotBlank @Size(max = 255) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "must be yyyy-MM") String startMonth,
            @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "must be yyyy-MM") String endMonth,
            Long allocationId,
            boolean prorateFirstMonth) {
    }

    public record UpdateRecurringChargeRequest(
            @NotBlank @Size(max = 255) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Pattern(regexp = "^\\d{4}-\\d{2}$", message = "must be yyyy-MM") String endMonth,
            boolean prorateFirstMonth,
            boolean active,
            @NotNull Long version) {
    }

    public record RecurringChargeResponse(Long id, Long tenantId, Long propertyId, Long allocationId, LineItemType type,
                                          String description, BigDecimal amount, String startMonth, String endMonth,
                                          boolean prorateFirstMonth, boolean active, long version) {
        static RecurringChargeResponse of(RecurringCharge c) {
            return new RecurringChargeResponse(c.getId(), c.getTenantId(), c.getPropertyId(), c.getAllocationId(), c.getLineType(),
                    c.getDescription(), c.getAmount(), c.getStartMonth().toString().substring(0, 7),
                    c.getEndMonth() == null ? null : c.getEndMonth().toString().substring(0, 7), c.isProrateFirstMonth(),
                    c.isActive(), c.getVersion());
        }
    }
}
