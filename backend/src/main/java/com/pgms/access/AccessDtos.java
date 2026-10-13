package com.pgms.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AccessDtos {

    public static final String PIN_REGEX = "^[A-Za-z0-9]{1,24}$";
    public static final String SERIAL_REGEX = "^[A-Za-z0-9._-]{3,64}$";
    public static final int MAX_DAILY_LIMIT = 100;

    private AccessDtos() {
    }

    // ---- property settings ----

    /** {@code maxEntriesPerDay = null} means unlimited. */
    public record AccessSettingsRequest(
            @NotNull Boolean enabled,
            @NotNull ExitMode exitMode,
            @Min(1) @Max(MAX_DAILY_LIMIT) Integer maxEntriesPerDay,
            Boolean trackStaff,
            @Min(1) @Max(10080) Integer deviceOfflineMinutes) {
    }

    public record AccessSettingsResponse(Long propertyId, boolean enabled, ExitMode exitMode, Integer maxEntriesPerDay,
                                         boolean trackStaff, int deviceOfflineMinutes, boolean configured, Instant updatedAt,
                                         long version) {
        static AccessSettingsResponse of(AccessSettings s, boolean configured) {
            return new AccessSettingsResponse(s.getPropertyId(), s.isEnabled(), s.getExitMode(), s.getMaxEntriesPerDay(),
                    s.isTrackStaff(), s.getDeviceOfflineMinutes(), configured, s.getUpdatedAt(), s.getVersion());
        }
    }

    // ---- devices ----

    /** {@code timeZone}: IANA zone of the device clock, default Asia/Kolkata. */
    public record CreateDeviceRequest(
            @NotNull Long propertyId,
            @NotBlank @Size(max = 100) String name,
            @NotNull DeviceVendor vendor,
            @NotBlank @Pattern(regexp = SERIAL_REGEX, message = "3-64 letters, digits, '.', '_' or '-'") String serialNumber,
            @NotNull DeviceDirection direction,
            @Size(max = 40) String timeZone) {
    }

    public record UpdateDeviceRequest(
            @Size(min = 1, max = 100) String name,
            DeviceDirection direction,
            DeviceStatus status,
            @Size(max = 40) String timeZone) {
    }

    public record DeviceResponse(Long id, Long propertyId, String name, DeviceVendor vendor, String serialNumber,
                                 DeviceDirection direction, String timeZone, DeviceStatus status, boolean online,
                                 Instant lastSeenAt, String lastIp, Instant createdAt) {
        static DeviceResponse of(AccessDevice d, boolean online) {
            return new DeviceResponse(d.getId(), d.getPropertyId(), d.getName(), d.getVendor(), d.getSerialNumber(),
                    d.getDirection(), d.getTimeZone(), d.getStatus(), online, d.getLastSeenAt(), d.getLastIp(), d.getCreatedAt());
        }
    }

    /** Paths the device / bridge must call; the host is the site's own address (LAN IP or domain). */
    public record DeviceSetup(String admsPath, String deviceEventsPath, String verifyPath, String keyHeader,
                              String serialNumber, String deviceTimeZone) {
    }

    /** Returned on registration and key rotation only: {@code apiKey} is never shown again. */
    public record DeviceKeyResponse(DeviceResponse device, String apiKey, DeviceSetup setup) {
    }

    // ---- credentials ----

    /** Exactly one of {@code tenantId} / {@code userId}. Without {@code deviceUserId} the next free PIN is assigned. */
    public record CreateCredentialRequest(
            @NotNull Long propertyId,
            @Pattern(regexp = PIN_REGEX, message = "1-24 letters or digits") String deviceUserId,
            Long tenantId,
            Long userId,
            CredentialType credentialType) {
    }

    public record CredentialResponse(Long id, Long propertyId, String deviceUserId, Long tenantId, Long userId, String personName,
                                     CredentialType credentialType, CredentialStatus status, Instant enrolledAt,
                                     Instant revokedAt) {
        static CredentialResponse of(AccessCredential c, String personName) {
            return new CredentialResponse(c.getId(), c.getPropertyId(), c.getDeviceUserId(), c.getTenantId(), c.getUserId(),
                    personName, c.getCredentialType(), c.getStatus(), c.getEnrolledAt(), c.getRevokedAt());
        }
    }

    public record NextPinResponse(Long propertyId, String deviceUserId) {
    }

    // ---- per-resident limit ----

    /**
     * {@code unlimited = true}: no limit for this resident. Otherwise {@code maxEntriesPerDay} is the personal limit,
     * or {@code null} to fall back to the property default.
     */
    public record MemberLimitRequest(@Min(1) @Max(MAX_DAILY_LIMIT) Integer maxEntriesPerDay, boolean unlimited) {
    }

    public record MemberLimitResponse(Long tenantId, boolean override, boolean unlimited, Integer maxEntriesPerDay,
                                      Integer propertyDefault, Integer effectiveLimit) {
    }

    // ---- events, presence, resident status ----

    public record AccessEventResponse(Long id, Long propertyId, Long deviceId, String deviceName, Long tenantId, Long userId,
                                      String personName, String deviceUserId, AccessDirection direction, AccessMethod method,
                                      Instant occurredAt, Instant receivedAt, AccessResult result, String reason,
                                      Long recordedBy) {
        static AccessEventResponse of(AccessEvent e, String deviceName, String personName) {
            return new AccessEventResponse(e.getId(), e.getPropertyId(), e.getDeviceId(), deviceName, e.getTenantId(),
                    e.getUserId(), personName, e.getDeviceUserId(), e.getDirection(), e.getMethod(), e.getOccurredAt(),
                    e.getReceivedAt(), e.getResult(), e.getReason(), e.getRecordedBy());
        }
    }

    public record PresenceEntry(String personType, Long tenantId, Long userId, String name, Instant since, AccessMethod method,
                                Long deviceId, String deviceName, Long eventId) {
    }

    public record PresenceResponse(Long propertyId, boolean enabled, int insideCount, List<PresenceEntry> inside) {
    }

    /** Body of {@code POST /access/exit}: residents send nothing, staff name the resident. */
    public record ExitRequest(Long tenantId) {
    }

    public record ManualEntryRequest(
            @NotNull Long tenantId,
            @NotBlank @Size(min = 5, max = 400) String reason,
            @PastOrPresent Instant occurredAt) {
    }

    public record MyAccessResponse(boolean enabled, Long propertyId, Presence presence, Instant since, long entriesToday,
                                   Integer limit, Integer remainingEntries, ExitMode exitMode, boolean exitButtonAvailable,
                                   AccessEventResponse lastEvent) {
    }

    // ---- device-key endpoints (bridge / door controller) ----

    /**
     * {@code occurredAt}: ISO-8601 instant or offset date-time, or a local date-time in the device's time zone.
     * {@code method} defaults to FINGERPRINT; only FINGERPRINT, FACE and CARD are accepted from devices.
     */
    public record DeviceEventItem(
            @NotBlank @Pattern(regexp = PIN_REGEX, message = "1-24 letters or digits") String deviceUserId,
            @NotBlank @Size(max = 40) String occurredAt,
            AccessDirection direction,
            AccessMethod method) {
    }

    public record DeviceEventsRequest(@NotNull @Size(min = 1, max = 500) List<@Valid @NotNull DeviceEventItem> events) {
    }

    /** {@code status}: STORED, DUPLICATE (already received; idempotent re-send) or REJECTED (see {@code error}). */
    public record DeviceEventResult(int index, String deviceUserId, String status, Long eventId, AccessResult result,
                                    AccessDirection direction, Instant occurredAt, String error) {
    }

    public record DeviceEventsResponse(Long deviceId, int stored, int duplicates, int rejected, List<DeviceEventResult> results) {
    }

    /** {@code record = true} also stores the event (verify-and-record); by default verify is a dry run. */
    public record VerifyRequest(
            @NotBlank @Pattern(regexp = PIN_REGEX, message = "1-24 letters or digits") String deviceUserId,
            AccessDirection direction,
            Boolean record) {
    }

    public record VerifyResponse(boolean allowed, AccessResult result, String reason, String residentName, long entriesToday,
                                 Integer limit, AccessDirection direction, Long eventId) {
    }
}
