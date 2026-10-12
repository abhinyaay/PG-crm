package com.pgms.tenant;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.BusinessRuleException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.hibernate.annotations.SQLRestriction;

/**
 * A resident. Lifecycle: PROSPECTIVE -> ACTIVE (check-in) -> ON_NOTICE (optional) -> MOVED_OUT (check-out);
 * a returning resident can be checked in again from MOVED_OUT.
 */
@Entity
@Table(name = "tenants")
@SQLRestriction("deleted_at IS NULL")
public class Tenant extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    /** Current (or onboarding) property; moves with inter-property transfers. */
    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String email;

    @Column(nullable = false)
    private String phone;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    private String occupation;

    @Column(name = "organization_name")
    private String organizationName;

    @Column(name = "permanent_address")
    private String permanentAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status = TenantStatus.PROSPECTIVE;

    @Column(name = "joining_date")
    private LocalDate joiningDate;

    @Column(name = "move_out_date")
    private LocalDate moveOutDate;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Opt-out / bounce state for residents without a portal login (users carry their own). */
    @Column(name = "sms_opt_out_at")
    private Instant smsOptOutAt;

    @Column(name = "email_bounced_at")
    private Instant emailBouncedAt;

    @OneToMany(mappedBy = "tenant", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("primary DESC, id ASC")
    private List<EmergencyContact> emergencyContacts = new ArrayList<>();

    protected Tenant() {
    }

    public Tenant(Long organizationId, Long propertyId, String fullName, String email, String phone) {
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.fullName = fullName;
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        this.phone = phone;
    }

    public void updateProfile(String fullName, String email, String phone, Gender gender, LocalDate dateOfBirth,
                              String occupation, String organizationName, String permanentAddress) {
        this.fullName = fullName;
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        this.phone = phone;
        this.gender = gender;
        this.dateOfBirth = dateOfBirth;
        this.occupation = occupation;
        this.organizationName = organizationName;
        this.permanentAddress = permanentAddress;
    }

    public void replaceEmergencyContacts(List<EmergencyContact> contacts) {
        emergencyContacts.clear();
        emergencyContacts.addAll(contacts);
    }

    public void checkIn(Long propertyId, LocalDate date) {
        if (status == TenantStatus.ACTIVE || status == TenantStatus.ON_NOTICE) {
            throw new BusinessRuleException("TENANT_ALREADY_CHECKED_IN", "Tenant is already checked in");
        }
        this.propertyId = propertyId;
        this.status = TenantStatus.ACTIVE;
        if (joiningDate == null) {
            joiningDate = date;
        }
        moveOutDate = null;
    }

    public void giveNotice() {
        if (status != TenantStatus.ACTIVE) {
            throw new BusinessRuleException("TENANT_NOT_ACTIVE", "Only active tenants can give notice");
        }
        status = TenantStatus.ON_NOTICE;
    }

    public void withdrawNotice() {
        if (status != TenantStatus.ON_NOTICE) {
            throw new BusinessRuleException("TENANT_NOT_ON_NOTICE", "Tenant has not given notice");
        }
        status = TenantStatus.ACTIVE;
    }

    public void relocateTo(Long propertyId) {
        this.propertyId = propertyId;
    }

    public void moveOut(LocalDate date) {
        if (status != TenantStatus.ACTIVE && status != TenantStatus.ON_NOTICE) {
            throw new BusinessRuleException("TENANT_NOT_ACTIVE", "Tenant is not checked in");
        }
        status = TenantStatus.MOVED_OUT;
        moveOutDate = date;
    }

    public void linkUser(Long userId) {
        this.userId = userId;
    }

    public boolean isResident() {
        return status == TenantStatus.ACTIVE || status == TenantStatus.ON_NOTICE;
    }

    public void softDelete(Instant at) {
        if (isResident()) {
            throw new BusinessRuleException("TENANT_IS_RESIDENT", "Check the tenant out before deleting the profile");
        }
        deletedAt = at;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public Gender getGender() {
        return gender;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getOccupation() {
        return occupation;
    }

    public String getOrganizationName() {
        return organizationName;
    }

    public String getPermanentAddress() {
        return permanentAddress;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public LocalDate getJoiningDate() {
        return joiningDate;
    }

    public LocalDate getMoveOutDate() {
        return moveOutDate;
    }

    public List<EmergencyContact> getEmergencyContacts() {
        return emergencyContacts;
    }

    public Instant getSmsOptOutAt() {
        return smsOptOutAt;
    }

    public Instant getEmailBouncedAt() {
        return emailBouncedAt;
    }
}
