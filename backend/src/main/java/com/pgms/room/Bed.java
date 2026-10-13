package com.pgms.room;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.exception.ConflictException;
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
 * The unit of occupancy. Status transitions are guarded here; RESERVED/OCCUPIED can only be entered through the
 * booking/allocation workflows, which hold a row lock on the bed while transitioning.
 *
 * <pre>
 *  AVAILABLE --reserve--> RESERVED --occupy--> OCCUPIED --release--> AVAILABLE
 *  AVAILABLE --occupy (walk-in)--> OCCUPIED
 *  RESERVED --release (cancel/expire)--> AVAILABLE
 *  AVAILABLE <--> MAINTENANCE (manual)
 * </pre>
 */
@Entity
@Table(name = "beds")
@SQLRestriction("deleted_at IS NULL")
public class Bed extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false, updatable = false)
    private Room room;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "bed_code", nullable = false)
    private String bedCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BedStatus status = BedStatus.AVAILABLE;

    /** Free-text name shown on the layout (e.g. "Window side"); the short {@code bedCode} stays the identifier. */
    private String label;

    /** Per-bed price; when set it replaces the room's price for new reservations, check-ins and transfers. */
    @Column(name = "monthly_rent_override")
    private BigDecimal monthlyRentOverride;

    @Column(name = "security_deposit_override")
    private BigDecimal securityDepositOverride;

    /** Optional drawing position inside the space (both set or both null). */
    @Column(name = "pos_x")
    private Integer posX;

    @Column(name = "pos_y")
    private Integer posY;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Bed() {
    }

    public Bed(Room room, String bedCode) {
        this.room = room;
        this.propertyId = room.getPropertyId();
        this.bedCode = bedCode;
    }

    public void reserve() {
        requireStatus(BedStatus.AVAILABLE, "reserve");
        status = BedStatus.RESERVED;
    }

    /** Check-in: from a reservation, or directly for a walk-in. */
    public void occupy(boolean fromReservation) {
        requireStatus(fromReservation ? BedStatus.RESERVED : BedStatus.AVAILABLE, "occupy");
        status = BedStatus.OCCUPIED;
    }

    public void releaseReservation() {
        requireStatus(BedStatus.RESERVED, "release reservation on");
        status = BedStatus.AVAILABLE;
    }

    public void vacate() {
        requireStatus(BedStatus.OCCUPIED, "vacate");
        status = BedStatus.AVAILABLE;
    }

    public void startMaintenance() {
        requireStatus(BedStatus.AVAILABLE, "put under maintenance");
        status = BedStatus.MAINTENANCE;
    }

    public void endMaintenance() {
        requireStatus(BedStatus.MAINTENANCE, "end maintenance on");
        status = BedStatus.AVAILABLE;
    }

    public void softDelete(Instant at) {
        if (isInUse()) {
            throw new ConflictException("BED_IN_USE", "Bed " + bedCode + " is " + status + " and cannot be removed");
        }
        deletedAt = at;
    }

    public void rename(String bedCode) {
        this.bedCode = bedCode;
    }

    public void relabel(String label) {
        this.label = label;
    }

    public void overridePrice(BigDecimal monthlyRentOverride, BigDecimal securityDepositOverride) {
        this.monthlyRentOverride = monthlyRentOverride;
        this.securityDepositOverride = securityDepositOverride;
    }

    public void position(Integer posX, Integer posY) {
        this.posX = posX;
        this.posY = posY;
    }

    /** Rent a new occupant of this bed is charged unless the operator overrides it at check-in. */
    public BigDecimal effectiveMonthlyRent() {
        return monthlyRentOverride != null ? monthlyRentOverride : room.getMonthlyRent();
    }

    public BigDecimal effectiveSecurityDeposit() {
        return securityDepositOverride != null ? securityDepositOverride : room.getSecurityDeposit();
    }

    public boolean isInUse() {
        return status == BedStatus.OCCUPIED || status == BedStatus.RESERVED;
    }

    private void requireStatus(BedStatus expected, String action) {
        if (status != expected) {
            throw new ConflictException("BED_NOT_" + expected,
                    "Cannot " + action + " bed " + bedCode + ": bed is " + status + " (expected " + expected + ")");
        }
    }

    public Room getRoom() {
        return room;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public String getBedCode() {
        return bedCode;
    }

    public BedStatus getStatus() {
        return status;
    }

    public String getLabel() {
        return label;
    }

    public BigDecimal getMonthlyRentOverride() {
        return monthlyRentOverride;
    }

    public BigDecimal getSecurityDepositOverride() {
        return securityDepositOverride;
    }

    public Integer getPosX() {
        return posX;
    }

    public Integer getPosY() {
        return posY;
    }
}
