package com.pgms.deposit;

import com.pgms.billing.LineItemType;
import com.pgms.billing.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DepositDtos {

    private DepositDtos() {
    }

    /**
     * One stay's deposit. {@code collected} = paid on the stay's deposit invoices; {@code held} = deposit money still
     * with the owner; {@code refundDue} = owed back after the settlement (deposit left + rent credit), payable by
     * {@code refundDueBy}.
     */
    public record DepositSummary(
            Long accountId,
            Long tenantId,
            String tenantName,
            Long propertyId,
            String propertyName,
            Long currentAllocationId,
            Long rootAllocationId,
            Long bedId,
            String allocationStatus,
            DepositStatus status,
            BigDecimal agreedDeposit,
            BigDecimal collected,
            BigDecimal pendingCollection,
            BigDecimal duesAdjusted,
            BigDecimal deductions,
            BigDecimal forfeited,
            BigDecimal refunded,
            BigDecimal rentCredit,
            BigDecimal held,
            BigDecimal refundDue,
            LocalDate moveOutDate,
            LocalDate refundDueBy,
            boolean refundOverdue,
            Instant settledAt,
            String forfeitReason) {
    }

    /**
     * Cards at the top of the Deposits tab (property/tenant filters apply, the status filter does not):
     * deposits held for running stays, refunds due, and refunds past their due-by date.
     */
    public record DepositTotals(BigDecimal totalHeld, long heldCount, BigDecimal totalRefundDue, long refundDueCount,
                                BigDecimal overdueRefundAmount, long overdueRefundCount) {
    }

    public record DepositListResponse(DepositTotals totals, List<DepositSummary> content, int page, int size,
                                      long totalElements, int totalPages, boolean last) {
    }

    /**
     * One ledger line. {@code type}: COLLECTED (deposit payment) or a {@link DepositTransactionType}. {@code amount}
     * is signed from the deposit's point of view (+ money in / owed to the resident, - money applied or paid out;
     * 0 for informational lines); {@code balanceAfter} is the money still held for the resident after the line.
     */
    public record LedgerLine(Instant at, String type, BigDecimal amount, BigDecimal balanceAfter, String description,
                             Long invoiceId, Long paymentId, PaymentMethod method, String reference, Long transactionId) {
    }

    public record DepositLedger(DepositSummary deposit, List<LedgerLine> lines) {
    }

    public record TenantDepositsResponse(Long tenantId, String tenantName, List<DepositLedger> stays) {
    }

    /** {@code amount} defaults to the full refund due. */
    public record RefundDepositRequest(
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull PaymentMethod method,
            @Size(max = 100) String reference,
            @Size(max = 255) String note) {
    }

    /** {@code type}: DEDUCTION (default) or DAMAGE. */
    public record DeductionRequest(
            @NotBlank @Size(max = 255) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            LineItemType type) {
    }

    public record ForfeitRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** {@code amount} defaults to agreed deposit of the current bed minus what is held or already invoiced. */
    public record TopUpRequest(
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 255) String reason) {
    }

    public record DepositActionResponse(DepositSummary deposit, LedgerLine transaction, Long invoiceId) {
    }

    /** Result of an idempotent deposit action: {@code replayed} when an earlier identical request is returned. */
    public record ActionResult(DepositActionResponse body, boolean replayed) {
    }
}
