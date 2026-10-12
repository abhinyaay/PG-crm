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
        if (status == BedStatus.OCCUPIED || status == BedStatus.RESERVED) {
            throw new ConflictException("BED_IN_USE", "Bed " + bedCode + " is " + status + " and cannot be removed");
        }
        deletedAt = at;
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
}
