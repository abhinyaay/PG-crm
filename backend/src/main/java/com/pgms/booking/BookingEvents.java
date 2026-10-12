package com.pgms.booking;

import java.time.Instant;
import java.time.LocalDate;

/** Domain events published by the booking module; consumed by notifications (and future integrations). */
public final class BookingEvents {

    private BookingEvents() {
    }

    public record BedReserved(Long bookingId, String reference, Long organizationId, Long propertyId, Long tenantId,
                              Long bedId, LocalDate expectedCheckInDate, Instant reservedUntil) {
    }

    public record ReservationReleased(Long bookingId, String reference, Long organizationId, Long tenantId, Long bedId,
                                      BookingStatus outcome) {
    }

    public record TenantCheckedIn(Long allocationId, Long organizationId, Long propertyId, Long tenantId, Long bedId,
                                  LocalDate checkInDate) {
    }

    public record TenantCheckedOut(Long allocationId, Long organizationId, Long propertyId, Long tenantId, Long bedId,
                                   LocalDate checkOutDate) {
    }

    public record TenantTransferred(Long fromAllocationId, Long toAllocationId, Long organizationId, Long tenantId,
                                    Long fromBedId, Long toBedId, LocalDate transferDate) {
    }
}
