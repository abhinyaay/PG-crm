package com.pgms.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.exception.ConflictException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpaceAndBedRulesTest {

    private static Room room(SpaceKind kind, RoomType type, int capacity) {
        return new Room(1L, null, "101", kind, type, capacity, new BigDecimal("9000"), new BigDecimal("18000"), false, false);
    }

    @Test
    void generatedBedCodesSkipCodesInUse() {
        assertThat(BedCodes.next(List.of(), 3)).containsExactly("A", "B", "C");
        assertThat(BedCodes.next(List.of("A", "C", "W1"), 3)).containsExactly("B", "D", "E");
        assertThat(BedCodes.code(25)).isEqualTo("Z");
        assertThat(BedCodes.code(26)).isEqualTo("AA");
        assertThat(BedCodes.code(27)).isEqualTo("AB");
        assertThat(BedCodes.code(51)).isEqualTo("AZ");
        assertThat(BedCodes.code(52)).isEqualTo("BA");
    }

    @Test
    void onlyRoomsHallsAndDormitoriesHoldBeds() {
        assertThat(SpaceKind.ROOM.holdsBeds()).isTrue();
        assertThat(SpaceKind.HALL.isOpenPlan()).isTrue();
        assertThat(SpaceKind.DORMITORY.maxBeds()).isEqualTo(50);
        assertThat(SpaceKind.ROOM.maxBeds()).isEqualTo(20);
        assertThat(SpaceKind.KITCHEN.holdsBeds()).isFalse();
        assertThat(SpaceKind.KITCHEN.maxBeds()).isZero();
    }

    @Test
    void roomTypeFollowsTheBedCount() {
        assertThat(RoomType.forCapacity(1)).isEqualTo(RoomType.SINGLE);
        assertThat(RoomType.forCapacity(4)).isEqualTo(RoomType.QUAD);
        assertThat(RoomType.forCapacity(12)).isEqualTo(RoomType.DORMITORY);
        assertThatThrownBy(() -> RoomType.forCapacity(21)).isInstanceOf(IllegalArgumentException.class);

        Room room = room(SpaceKind.ROOM, RoomType.DOUBLE, 2);
        room.syncCapacity(3);
        assertThat(room.getCapacity()).isEqualTo(3);
        assertThat(room.getRoomType()).isEqualTo(RoomType.TRIPLE);

        Room hall = room(SpaceKind.HALL, RoomType.DORMITORY, 0);
        hall.syncCapacity(2);
        assertThat(hall.getRoomType()).isEqualTo(RoomType.DORMITORY);
        assertThat(hall.getCapacity()).isEqualTo(2);
    }

    @Test
    void placementIsAllOrNothing() {
        Room room = room(SpaceKind.KITCHEN, null, 0);
        assertThat(room.isPlaced()).isFalse();
        room.place(1, 2, 3, 4);
        assertThat(room.isPlaced()).isTrue();
        assertThat(List.of(room.getLayoutX(), room.getLayoutY(), room.getLayoutW(), room.getLayoutH())).containsExactly(1, 2, 3, 4);
        room.decorate("Kitchen", "#AABBCC");
        assertThat(room.getLabel()).isEqualTo("Kitchen");
        assertThat(room.getColor()).isEqualTo("#AABBCC");
        room.place(null, null, null, null);
        assertThat(room.isPlaced()).isFalse();
    }

    @Test
    void bedPriceOverrideReplacesTheRoomPrice() {
        Bed bed = new Bed(room(SpaceKind.ROOM, RoomType.DOUBLE, 2), "W1");
        assertThat(bed.effectiveMonthlyRent()).isEqualByComparingTo("9000");
        assertThat(bed.effectiveSecurityDeposit()).isEqualByComparingTo("18000");
        bed.overridePrice(new BigDecimal("7500"), null);
        assertThat(bed.effectiveMonthlyRent()).isEqualByComparingTo("7500");
        assertThat(bed.effectiveSecurityDeposit()).isEqualByComparingTo("18000");
        bed.overridePrice(null, new BigDecimal("10000"));
        assertThat(bed.effectiveMonthlyRent()).isEqualByComparingTo("9000");
        assertThat(bed.effectiveSecurityDeposit()).isEqualByComparingTo("10000");

        bed.rename("Window-2");
        bed.relabel("By the window");
        bed.position(3, 4);
        assertThat(bed.getBedCode()).isEqualTo("Window-2");
        assertThat(bed.getLabel()).isEqualTo("By the window");
        assertThat(bed.getPosX()).isEqualTo(3);
        assertThat(bed.getPosY()).isEqualTo(4);
    }

    @Test
    void occupiedOrReservedBedsCannotBeRemoved() {
        Bed bed = new Bed(room(SpaceKind.HALL, RoomType.DORMITORY, 1), "A");
        bed.reserve();
        assertThat(bed.isInUse()).isTrue();
        assertThatThrownBy(() -> bed.softDelete(Instant.now())).isInstanceOf(ConflictException.class);
        bed.releaseReservation();
        bed.softDelete(Instant.now());
        assertThat(bed.isInUse()).isFalse();
    }
}
