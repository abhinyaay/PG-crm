package com.pgms.organization;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** A PG operator - the tenancy boundary of the platform. All business data hangs off an organization. */
@Entity
@Table(name = "organizations")
public class Organization extends AuditableEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrganizationStatus status = OrganizationStatus.ACTIVE;

    protected Organization() {
    }

    public Organization(String name, String slug, String contactEmail, String contactPhone) {
        this.name = name;
        this.slug = slug;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
    }

    public void update(String name, String contactEmail, String contactPhone) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
    }

    public void changeStatus(OrganizationStatus status) {
        this.status = status;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public OrganizationStatus getStatus() {
        return status;
    }
}
