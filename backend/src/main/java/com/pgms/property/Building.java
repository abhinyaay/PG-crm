package com.pgms.property;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "buildings")
public class Building extends AuditableEntity {

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    protected Building() {
    }

    public Building(Long propertyId, String code, String name) {
        this.propertyId = propertyId;
        this.code = code;
        this.name = name;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
