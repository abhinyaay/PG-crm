package com.pgms.booking;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** A time-boxed hold on a bed for a prospective tenant. Expires automatically at {@code reservedUntil}. */
@Entity
@Table(name = "bookings")
public class Booking extends AuditableEntity {

    @Column(name = "booking_reference", nullable = false, updatable = false)
    private String bookingReference;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    @Column(name = "bed_id", nullable = false, updatable = false)
    private Long bedId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.RESERVED;

    @Column(name = "expected_check_in_date", nullable = false)
    private LocalDate expectedCheckInDate;

    @Column(name = "reserved_until", nullable = false)
    private Instant reservedUntil;

    private String notes;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    protected Booking() {
    }

    public Booking(String bookingReference, Long organizationId, Long propertyId, Long tenantId, Long bedId,
                   LocalDate expectedCheckInDate, Instant reservedUntil, String notes) {
        this.bookingReference = bookingReference;
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.bedId = bedId;
        this.expectedCheckInDate = expectedCheckInDate;
        this.reservedUntil = reservedUntil;
        this.notes = notes;
    }

    public boolean isLive(Instant now) {
        return status == BookingStatus.RESERVED && now.isBefore(reservedUntil);
    }

    public void cancel(String reason, Instant at) {
        requireReserved();
        status = BookingStatus.CANCELLED;
        cancelledAt = at;
        cancellationReason = reason;
    }

    public void expire() {
        requireReserved();
        status = BookingStatus.EXPIRED;
    }

    public void markCheckedIn() {
        requireReserved();
        status = BookingStatus.CHECKED_IN;
    }

    private void requireReserved() {
        if (status != BookingStatus.RESERVED) {
            throw new ConflictException("BOOKING_NOT_RESERVED", "Booking " + bookingReference + " is " + status);
        }
    }

    public String getBookingReference() {
        return bookingReference;
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

    public BookingStatus getStatus() {
        return status;
    }

    public LocalDate getExpectedCheckInDate() {
        return expectedCheckInDate;
    }

    public Instant getReservedUntil() {
        return reservedUntil;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }
}
