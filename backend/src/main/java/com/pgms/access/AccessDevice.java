package com.pgms.access;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import org.hibernate.annotations.SQLRestriction;

/**
 * A registered gate terminal. ADMS devices identify themselves by serial number; bridges and door controllers by
 * the device key, of which only the SHA-256 hash is stored.
 */
@Entity
@Table(name = "access_devices")
@SQLRestriction("deleted_at IS NULL")
public class AccessDevice extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private DeviceVendor vendor;

    @Column(name = "serial_number", nullable = false, updatable = false)
    private String serialNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceDirection direction;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status = DeviceStatus.ACTIVE;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_ip")
    private String lastIp;

    @Column(name = "attlog_stamp")
    private String attlogStamp;

    @Column(name = "offline_alerted_at")
    private Instant offlineAlertedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected AccessDevice() {
    }

    public AccessDevice(Long organizationId, Long propertyId, String name, DeviceVendor vendor, String serialNumber,
                        DeviceDirection direction, ZoneId timeZone, String apiKeyHash) {
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.name = name;
        this.vendor = vendor;
        this.serialNumber = normalizeSerial(serialNumber);
        this.direction = direction;
        this.timeZone = timeZone.getId();
        this.apiKeyHash = apiKeyHash;
    }

    public static String normalizeSerial(String serial) {
        return serial == null ? null : serial.trim().toUpperCase(Locale.ROOT);
    }

    public void update(String name, DeviceDirection direction, DeviceStatus status, ZoneId timeZone) {
        if (name != null) {
            this.name = name;
        }
        if (direction != null) {
            this.direction = direction;
        }
        if (status != null) {
            this.status = status;
        }
        if (timeZone != null) {
            this.timeZone = timeZone.getId();
        }
    }

    public void rotateKey(String apiKeyHash) {
        this.apiKeyHash = apiKeyHash;
    }

    public void softDelete(Instant at) {
        this.status = DeviceStatus.DISABLED;
        this.deletedAt = at;
    }

    public boolean isActive() {
        return status == DeviceStatus.ACTIVE;
    }

    public boolean isOnline(Instant now, int offlineMinutes) {
        return lastSeenAt != null && !lastSeenAt.isBefore(now.minus(Duration.ofMinutes(offlineMinutes)));
    }

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public String getName() {
        return name;
    }

    public DeviceVendor getVendor() {
        return vendor;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public DeviceDirection getDirection() {
        return direction;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getLastIp() {
        return lastIp;
    }

    public String getAttlogStamp() {
        return attlogStamp;
    }

    public Instant getOfflineAlertedAt() {
        return offlineAlertedAt;
    }
}
