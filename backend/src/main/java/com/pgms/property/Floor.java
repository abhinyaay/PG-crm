package com.pgms.property;

import com.pgms.common.domain.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "floors")
public class Floor extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "building_id", nullable = false, updatable = false)
    private Building building;

    @Column(name = "floor_number", nullable = false)
    private int floorNumber;

    private String label;

    protected Floor() {
    }

    public Floor(Building building, int floorNumber, String label) {
        this.building = building;
        this.floorNumber = floorNumber;
        this.label = label;
    }

    public Building getBuilding() {
        return building;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public String getLabel() {
        return label;
    }
}
