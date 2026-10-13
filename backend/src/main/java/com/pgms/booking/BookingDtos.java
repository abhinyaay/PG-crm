package com.pgms.booking;

import com.pgms.billing.BillingDtos.Deduction;
import com.pgms.billing.BillingDtos.InvoiceSummary;
import com.pgms.billing.BillingDtos.SettlementSummary;
import com.pgms.common.lookup.DisplayNames;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {
    }

    public record ReserveBedRequest(
            @NotNull Long tenantId,
            @NotNull Long bedId,
            @NotNull @FutureOrPresent LocalDate expectedCheckInDate,
            @Min(1) @Max(168) Integer holdHours,
            @Size(max = 500) String notes) {
    }

    public record CancelBookingRequest(@NotBlank @Size(max = 255) String reason) {
    }

    /** {@code quotedMonthlyRent}/{@code quotedSecurityDeposit}: price locked in at reservation (bed override or room price). */
    public record BookingResponse(Long id, String bookingReference, Long propertyId, Long tenantId, Long bedId,
                                  BookingStatus status, LocalDate expectedCheckInDate, Instant reservedUntil, String notes,
                                  Instant cancelledAt, String cancellationReason, Instant createdAt, String tenantName,
                                  String roomNumber, String bedCode, BigDecimal quotedMonthlyRent,
                                  BigDecimal quotedSecurityDeposit) {
        static BookingResponse of(Booking b, DisplayNames.Index names) {
            return new BookingResponse(b.getId(), b.getBookingReference(), b.getPropertyId(), b.getTenantId(), b.getBedId(),
                    b.getStatus(), b.getExpectedCheckInDate(), b.getReservedUntil(), b.getNotes(), b.getCancelledAt(),
                    b.getCancellationReason(), b.getCreatedAt(), names.tenant(b.getTenantId()), names.roomNumber(b.getBedId()),
                    names.bedCode(b.getBedId()), b.getQuotedMonthlyRent(), b.getQuotedSecurityDeposit());
        }
    }

    /** Either {@code bookingId} (check-in against a reservation) or {@code bedId} (walk-in) must be given. */
    public record CheckInRequest(
            @NotNull Long tenantId,
            Long bookingId,
            Long bedId,
            @NotNull LocalDate checkInDate,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit) {
    }

    public record CheckOutRequest(
            @NotNull LocalDate checkOutDate,
            @Valid @Size(max = 20) List<Deduction> deductions,
            boolean sendBedToMaintenance) {
    }

    public record TransferRequest(
            @NotNull Long targetBedId,
            @NotNull LocalDate transferDate,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal newMonthlyRent) {
    }

    public record NoticeRequest(@NotNull LocalDate expectedCheckOutDate) {
    }

    public record AllocationResponse(Long id, Long propertyId, Long tenantId, Long bedId, Long bookingId,
                                     Long previousAllocationId, AllocationStatus status, LocalDate startDate,
                                     LocalDate endDate, LocalDate noticeDate, LocalDate expectedEndDate, EndReason endReason,
                                     BigDecimal monthlyRent, BigDecimal securityDeposit, String tenantName, String roomNumber,
                                     String bedCode) {
        static AllocationResponse of(Allocation a, DisplayNames.Index names) {
            return new AllocationResponse(a.getId(), a.getPropertyId(), a.getTenantId(), a.getBedId(), a.getBookingId(),
                    a.getPreviousAllocationId(), a.getStatus(), a.getStartDate(), a.getEndDate(), a.getNoticeDate(),
                    a.getExpectedEndDate(), a.getEndReason(), a.getMonthlyRent(), a.getSecurityDeposit(),
                    names.tenant(a.getTenantId()), names.roomNumber(a.getBedId()), names.bedCode(a.getBedId()));
        }
    }

    public record CheckInResponse(AllocationResponse allocation, List<InvoiceSummary> invoices) {
    }

    public record CheckOutResponse(AllocationResponse allocation, SettlementSummary settlement) {
    }

    public record TransferResponse(AllocationResponse previous, AllocationResponse current) {
    }
}
