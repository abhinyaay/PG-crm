package com.pgms.property;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "properties")
@SQLRestriction("deleted_at IS NULL")
public class Property extends AuditableEntity {

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false)
    private PropertyType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PropertyStatus status = PropertyStatus.ACTIVE;

    @Embedded
    private Address address;

    @Column(name = "contact_phone")
    private String contactPhone;

    @Column(name = "rent_due_day", nullable = false)
    private int rentDueDay;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Property() {
    }

    public Property(Long organizationId, String code, String name, String description, PropertyType type,
                    Address address, String contactPhone, int rentDueDay) {
        this.organizationId = organizationId;
        this.code = code;
        this.name = name;
        this.description = description;
        this.type = type;
        this.address = address;
        this.contactPhone = contactPhone;
        this.rentDueDay = rentDueDay;
    }

    public void update(String name, String description, PropertyType type, Address address, String contactPhone,
                       int rentDueDay) {
        this.name = name;
        this.description = description;
        this.type = type;
        this.address = address;
        this.contactPhone = contactPhone;
        this.rentDueDay = rentDueDay;
    }

    public void changeStatus(PropertyStatus status) {
        this.status = status;
    }

    public boolean isOperational() {
        return status == PropertyStatus.ACTIVE;
    }

    public void softDelete(Instant at) {
        this.deletedAt = at;
        this.status = PropertyStatus.INACTIVE;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public PropertyType getType() {
        return type;
    }

    public PropertyStatus getStatus() {
        return status;
    }

    public Address getAddress() {
        return address;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public int getRentDueDay() {
        return rentDueDay;
    }
}
