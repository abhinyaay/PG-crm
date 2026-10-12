package com.pgms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/** Organization-wide switch for one type and channel (e.g. no SMS for room transfers, to save cost). */
@Entity
@Table(name = "organization_notification_settings")
public class OrganizationNotificationSetting {

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected OrganizationNotificationSetting() {
    }

    OrganizationNotificationSetting(Key id, boolean enabled, Instant at, Long by) {
        this.id = id;
        this.enabled = enabled;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    void change(boolean value, Instant at, Long by) {
        this.enabled = value;
        this.updatedAt = at;
        this.updatedBy = by;
    }

    public Key getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "organization_id", nullable = false)
        private Long organizationId;

        @Enumerated(EnumType.STRING)
        @Column(name = "notification_type", nullable = false)
        private NotificationType notificationType;

        @Enumerated(EnumType.STRING)
        @Column(nullable = false)
        private NotificationChannel channel;

        protected Key() {
        }

        public Key(Long organizationId, NotificationType notificationType, NotificationChannel channel) {
            this.organizationId = organizationId;
            this.notificationType = notificationType;
            this.channel = channel;
        }

        public Long getOrganizationId() {
            return organizationId;
        }

        public NotificationType getNotificationType() {
            return notificationType;
        }

        public NotificationChannel getChannel() {
            return channel;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(organizationId, k.organizationId)
                    && notificationType == k.notificationType && channel == k.channel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(organizationId, notificationType, channel);
        }
    }
}
