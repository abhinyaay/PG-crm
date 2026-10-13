package com.pgms.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One line of the gate log. Append-only: events are never updated or deleted. */
@Entity
@Table(name = "access_events")
public class AccessEvent {

    public static final int MAX_REASON = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "device_id", updatable = false)
    private Long deviceId;

    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Column(name = "device_user_id", updatable = false)
    private String deviceUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccessDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccessMethod method;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AccessResult result;

    @Column(updatable = false)
    private String reason;

    @Column(name = "recorded_by", updatable = false)
    private Long recordedBy;

    protected AccessEvent() {
    }

    /** Who / where / what of a new event; the outcome is decided by {@link AccessEventProcessor}. */
    public record Draft(Long organizationId, Long propertyId, Long deviceId, Long tenantId, Long userId, String deviceUserId,
                        AccessDirection direction, AccessMethod method, Instant occurredAt, Long recordedBy) {
    }

    public AccessEvent(Draft draft, Instant receivedAt, AccessResult result, String reason) {
        this.organizationId = draft.organizationId();
        this.propertyId = draft.propertyId();
        this.deviceId = draft.deviceId();
        this.tenantId = draft.tenantId();
        this.userId = draft.userId();
        this.deviceUserId = draft.deviceUserId();
        this.direction = draft.direction();
        this.method = draft.method();
        this.occurredAt = draft.occurredAt();
        this.recordedBy = draft.recordedBy();
        this.receivedAt = receivedAt;
        this.result = result;
        this.reason = reason == null || reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public AccessDirection getDirection() {
        return direction;
    }

    public AccessMethod getMethod() {
        return method;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public AccessResult getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public Long getRecordedBy() {
        return recordedBy;
    }
}
