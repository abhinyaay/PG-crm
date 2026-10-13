package com.pgms.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Per-property switch for the biometric module. A property without a row behaves like {@link #defaults}: disabled.
 */
@Entity
@Table(name = "access_settings")
@EntityListeners(AuditingEntityListener.class)
public class AccessSettings {

    public static final int DEFAULT_OFFLINE_MINUTES = 15;

    @Id
    @Column(name = "property_id")
    private Long propertyId;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_mode", nullable = false)
    private ExitMode exitMode = ExitMode.DEVICE_OR_BUTTON;

    /** {@code null} = unlimited. */
    @Column(name = "max_entries_per_day")
    private Integer maxEntriesPerDay;

    @Column(name = "track_staff", nullable = false)
    private boolean trackStaff;

    @Column(name = "device_offline_minutes", nullable = false)
    private int deviceOfflineMinutes = DEFAULT_OFFLINE_MINUTES;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(nullable = false)
    private Long version;

    protected AccessSettings() {
    }

    public AccessSettings(Long propertyId) {
        this.propertyId = propertyId;
    }

    /** Transient defaults for a property that never configured the module. */
    public static AccessSettings defaults(Long propertyId) {
        return new AccessSettings(propertyId);
    }

    public void update(boolean enabled, ExitMode exitMode, Integer maxEntriesPerDay, boolean trackStaff, int deviceOfflineMinutes) {
        this.enabled = enabled;
        this.exitMode = Objects.requireNonNull(exitMode);
        this.maxEntriesPerDay = maxEntriesPerDay;
        this.trackStaff = trackStaff;
        this.deviceOfflineMinutes = deviceOfflineMinutes;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ExitMode getExitMode() {
        return exitMode;
    }

    public Integer getMaxEntriesPerDay() {
        return maxEntriesPerDay;
    }

    public boolean isTrackStaff() {
        return trackStaff;
    }

    public int getDeviceOfflineMinutes() {
        return deviceOfflineMinutes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
