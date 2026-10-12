package com.pgms.room;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.property.Floor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "rooms")
@SQLRestriction("deleted_at IS NULL")
public class Room extends AuditableEntity {

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "floor_id", nullable = false)
    private Floor floor;

    @Column(name = "room_number", nullable = false)
    private String roomNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "room_type", nullable = false)
    private RoomType roomType;

    @Column(nullable = false)
    private int capacity;

    @Column(name = "monthly_rent", nullable = false)
    private BigDecimal monthlyRent;

    @Column(name = "security_deposit", nullable = false)
    private BigDecimal securityDeposit;

    @Column(name = "air_conditioned", nullable = false)
    private boolean airConditioned;

    @Column(name = "attached_bathroom", nullable = false)
    private boolean attachedBathroom;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RoomStatus status = RoomStatus.ACTIVE;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Room() {
    }

    public Room(Long propertyId, Floor floor, String roomNumber, RoomType roomType, int capacity, BigDecimal monthlyRent,
                BigDecimal securityDeposit, boolean airConditioned, boolean attachedBathroom) {
        this.propertyId = propertyId;
        this.floor = floor;
        this.roomNumber = roomNumber;
        this.roomType = roomType;
        this.capacity = capacity;
        this.monthlyRent = monthlyRent;
        this.securityDeposit = securityDeposit;
        this.airConditioned = airConditioned;
        this.attachedBathroom = attachedBathroom;
    }

    public void updatePricing(BigDecimal monthlyRent, BigDecimal securityDeposit) {
        this.monthlyRent = monthlyRent;
        this.securityDeposit = securityDeposit;
    }

    public void updateAmenities(boolean airConditioned, boolean attachedBathroom) {
        this.airConditioned = airConditioned;
        this.attachedBathroom = attachedBathroom;
    }

    public void changeLayout(RoomType roomType, int capacity) {
        this.roomType = roomType;
        this.capacity = capacity;
    }

    public void changeStatus(RoomStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == RoomStatus.ACTIVE;
    }

    public void softDelete(Instant at) {
        this.deletedAt = at;
        this.status = RoomStatus.INACTIVE;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Floor getFloor() {
        return floor;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public RoomType getRoomType() {
        return roomType;
    }

    public int getCapacity() {
        return capacity;
    }

    public BigDecimal getMonthlyRent() {
        return monthlyRent;
    }

    public BigDecimal getSecurityDeposit() {
        return securityDeposit;
    }

    public boolean isAirConditioned() {
        return airConditioned;
    }

    public boolean isAttachedBathroom() {
        return attachedBathroom;
    }

    public RoomStatus getStatus() {
        return status;
    }
}
