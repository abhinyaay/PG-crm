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

/**
 * A space on a floor. Historically only bedrooms existed, so the table (and API) are still called "rooms"; the
 * {@link SpaceKind} says whether it is a bedroom, a hall/dormitory or a bed-less area such as a kitchen. The optional
 * geometry places the space on its floor's grid for the layout editor (all four values set, or none).
 */
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
    @Column(name = "space_kind", nullable = false, updatable = false)
    private SpaceKind spaceKind = SpaceKind.ROOM;

    /** NULL for bed-less spaces; always DORMITORY for halls/dormitories. */
    @Enumerated(EnumType.STRING)
    @Column(name = "room_type")
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

    @Column(name = "layout_x")
    private Integer layoutX;

    @Column(name = "layout_y")
    private Integer layoutY;

    @Column(name = "layout_w")
    private Integer layoutW;

    @Column(name = "layout_h")
    private Integer layoutH;

    private String label;

    private String color;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Room() {
    }

    public Room(Long propertyId, Floor floor, String roomNumber, RoomType roomType, int capacity, BigDecimal monthlyRent,
                BigDecimal securityDeposit, boolean airConditioned, boolean attachedBathroom) {
        this(propertyId, floor, roomNumber, SpaceKind.ROOM, roomType, capacity, monthlyRent, securityDeposit, airConditioned,
                attachedBathroom);
    }

    public Room(Long propertyId, Floor floor, String roomNumber, SpaceKind spaceKind, RoomType roomType, int capacity,
                BigDecimal monthlyRent, BigDecimal securityDeposit, boolean airConditioned, boolean attachedBathroom) {
        this.propertyId = propertyId;
        this.floor = floor;
        this.roomNumber = roomNumber;
        this.spaceKind = spaceKind;
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

    /**
     * Keeps capacity equal to the number of active beds after an individual bed was added or removed. A ROOM's sharing
     * type follows its bed count (2 beds = DOUBLE, 5+ = DORMITORY); halls stay DORMITORY. Callers hold the room row lock.
     */
    public void syncCapacity(int activeBeds) {
        this.capacity = activeBeds;
        if (spaceKind == SpaceKind.ROOM) {
            this.roomType = RoomType.forCapacity(activeBeds);
        }
    }

    /** Places the space on its floor grid; all four values {@code null} removes it from the drawing. */
    public void place(Integer x, Integer y, Integer w, Integer h) {
        this.layoutX = x;
        this.layoutY = y;
        this.layoutW = w;
        this.layoutH = h;
    }

    public void decorate(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public void changeStatus(RoomStatus status) {
        this.status = status;
    }

    public boolean isActive() {
        return status == RoomStatus.ACTIVE;
    }

    public boolean isPlaced() {
        return layoutX != null;
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

    public SpaceKind getSpaceKind() {
        return spaceKind;
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

    public Integer getLayoutX() {
        return layoutX;
    }

    public Integer getLayoutY() {
        return layoutY;
    }

    public Integer getLayoutW() {
        return layoutW;
    }

    public Integer getLayoutH() {
        return layoutH;
    }

    public String getLabel() {
        return label;
    }

    public String getColor() {
        return color;
    }
}
