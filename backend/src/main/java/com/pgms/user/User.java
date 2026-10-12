package com.pgms.user;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "users")
@SQLRestriction("deleted_at IS NULL")
public class User extends AuditableEntity {

    @Column(name = "organization_id")
    private Long organizationId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "preferred_language", nullable = false)
    private String preferredLanguage = "en";

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    /** Set when the user opts out of SMS (incl. DLT STOP replies); suppresses the SMS channel. */
    @Column(name = "sms_opt_out_at")
    private Instant smsOptOutAt;

    /** Set by bounce handling; suppresses the EMAIL channel. */
    @Column(name = "email_bounced_at")
    private Instant emailBouncedAt;

    protected User() {
    }

    public User(Long organizationId, Role role, String email, String passwordHash, String fullName, String phone) {
        this.organizationId = organizationId;
        this.role = role;
        this.email = normalizeEmail(email);
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.phone = phone;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    public RoleName getRoleName() {
        return role.getName();
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = UserStatus.INACTIVE;
    }

    public void changePassword(String newHash, Instant at) {
        this.passwordHash = newHash;
        this.passwordChangedAt = at;
    }

    public void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    public void updateProfile(String fullName, String phone) {
        this.fullName = fullName;
        this.phone = phone;
    }

    public void softDelete(Instant at) {
        this.deletedAt = at;
        this.status = UserStatus.INACTIVE;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Role getRole() {
        return role;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    public Instant getSmsOptOutAt() {
        return smsOptOutAt;
    }

    public Instant getEmailBouncedAt() {
        return emailBouncedAt;
    }
}
