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

/**
 * Maps the user id (PIN) a person was enrolled under on the property's devices to a resident or staff member.
 * The biometric template itself stays on the device; nothing about it is stored here.
 */
@Entity
@Table(name = "access_credentials")
public class AccessCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "device_user_id", nullable = false, updatable = false)
    private String deviceUserId;

    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    @Column(name = "user_id", updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credential_type", nullable = false, updatable = false)
    private CredentialType credentialType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CredentialStatus status = CredentialStatus.ACTIVE;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private Long revokedBy;

    protected AccessCredential() {
    }

    public AccessCredential(Long propertyId, String deviceUserId, Long tenantId, Long userId, CredentialType credentialType,
                            Instant enrolledAt, Long createdBy) {
        this.propertyId = propertyId;
        this.deviceUserId = deviceUserId;
        this.tenantId = tenantId;
        this.userId = userId;
        this.credentialType = credentialType;
        this.enrolledAt = enrolledAt;
        this.createdBy = createdBy;
    }

    /** @return false when it was already revoked */
    public boolean revoke(Instant at, Long by) {
        if (status == CredentialStatus.REVOKED) {
            return false;
        }
        status = CredentialStatus.REVOKED;
        revokedAt = at;
        revokedBy = by;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public String getDeviceUserId() {
        return deviceUserId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getUserId() {
        return userId;
    }

    public CredentialType getCredentialType() {
        return credentialType;
    }

    public CredentialStatus getStatus() {
        return status;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
