package com.pgms.access;

import java.time.Instant;

/** Domain events published by the access module; turned into notifications by the notification module. */
public final class AccessEvents {

    private AccessEvents() {
    }

    /** A resident tried to enter after using up today's entries (stored as DENIED_LIMIT_EXCEEDED). */
    public record EntryLimitExceeded(Long eventId, Long organizationId, Long propertyId, Long tenantId, String tenantName,
                                     Instant occurredAt, long entriesToday, int limit, AccessMethod method) {
    }

    /** An ACTIVE device has been silent longer than the property's offline threshold (once per outage). */
    public record DeviceOffline(Long deviceId, Long organizationId, Long propertyId, String deviceName, String serialNumber,
                                Instant lastSeenAt, int offlineMinutes) {
    }
}
