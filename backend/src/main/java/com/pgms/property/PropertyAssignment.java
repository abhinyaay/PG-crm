package com.pgms.property;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Grants a MANAGER or STAFF user access to one property. */
@Entity
@Table(name = "property_assignments")
public class PropertyAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false)
    private Long propertyId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    protected PropertyAssignment() {
    }

    public PropertyAssignment(Long propertyId, Long userId, Instant assignedAt, Long assignedBy) {
        this.propertyId = propertyId;
        this.userId = userId;
        this.assignedAt = assignedAt;
        this.assignedBy = assignedBy;
    }

    public Long getId() {
        return id;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }
}
