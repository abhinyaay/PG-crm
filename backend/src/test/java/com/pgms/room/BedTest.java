package com.pgms.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BedTest {

    private Bed newBed() {
        Room room = new Room(1L, null, "101", RoomType.DOUBLE, 2, new BigDecimal("9000"), new BigDecimal("18000"), false, true);
        return new Bed(room, "A");
    }

    @Test
    void reservationLifecycle() {
        Bed bed = newBed();
        bed.reserve();
        assertThat(bed.getStatus()).isEqualTo(BedStatus.RESERVED);
        bed.occupy(true);
        assertThat(bed.getStatus()).isEqualTo(BedStatus.OCCUPIED);
        bed.vacate();
        assertThat(bed.getStatus()).isEqualTo(BedStatus.AVAILABLE);
    }

    @Test
    void cannotReserveAnOccupiedBed() {
        Bed bed = newBed();
        bed.occupy(false);
        assertThatThrownBy(bed::reserve).isInstanceOf(ConflictException.class).hasMessageContaining("OCCUPIED");
    }

    @Test
    void walkInRequiresAvailableBedAndReservedCheckInRequiresReservation() {
        Bed bed = newBed();
        assertThatThrownBy(() -> bed.occupy(true)).isInstanceOf(ConflictException.class);
        bed.reserve();
        assertThatThrownBy(() -> bed.occupy(false)).isInstanceOf(ConflictException.class);
    }

    @Test
    void maintenanceOnlyFromAvailable() {
        Bed bed = newBed();
        bed.startMaintenance();
        assertThatThrownBy(bed::reserve).isInstanceOf(ConflictException.class);
        bed.endMaintenance();
        assertThat(bed.getStatus()).isEqualTo(BedStatus.AVAILABLE);
    }

    @Test
    void occupiedBedCannotBeDeleted() {
        Bed bed = newBed();
        bed.occupy(false);
        assertThatThrownBy(() -> bed.softDelete(Instant.now())).isInstanceOf(ConflictException.class);
    }

    @Test
    void roomTypeCapacityRules() {
        assertThat(RoomType.SINGLE.allowsCapacity(1)).isTrue();
        assertThat(RoomType.DOUBLE.allowsCapacity(3)).isFalse();
        assertThat(RoomType.DORMITORY.allowsCapacity(12)).isTrue();
        assertThat(RoomType.DORMITORY.allowsCapacity(4)).isFalse();
    }
}
