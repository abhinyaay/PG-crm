package com.pgms.layout;

import com.pgms.room.Bed;
import com.pgms.room.BedStatus;
import com.pgms.room.Room;
import com.pgms.room.RoomStatus;
import com.pgms.room.RoomType;
import com.pgms.room.SpaceKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class LayoutDtos {

    private LayoutDtos() {
    }

    /** A bed as drawn inside its space; {@code monthlyRent}/{@code securityDeposit} are the effective prices. */
    public record LayoutBed(
            Long id,
            String bedCode,
            String label,
            BedStatus status,
            Integer posX,
            Integer posY,
            BigDecimal monthlyRentOverride,
            BigDecimal securityDepositOverride,
            BigDecimal monthlyRent,
            BigDecimal securityDeposit) {

        static LayoutBed of(Bed b) {
            return new LayoutBed(b.getId(), b.getBedCode(), b.getLabel(), b.getStatus(), b.getPosX(), b.getPosY(),
                    b.getMonthlyRentOverride(), b.getSecurityDepositOverride(), b.effectiveMonthlyRent(),
                    b.effectiveSecurityDeposit());
        }
    }

    /**
     * A space on the floor. {@code placed=false} spaces have no layout* fields and belong in an "unplaced" tray;
     * {@code version} must be echoed back in {@link SpacePlacement#version()} when moving the space.
     */
    public record LayoutSpace(
            Long id,
            long version,
            SpaceKind spaceKind,
            String roomNumber,
            String label,
            String color,
            RoomType roomType,
            RoomStatus status,
            int capacity,
            BigDecimal monthlyRent,
            BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom,
            boolean placed,
            Integer layoutX,
            Integer layoutY,
            Integer layoutW,
            Integer layoutH,
            List<LayoutBed> beds) {

        static LayoutSpace of(Room r, List<Bed> beds) {
            return new LayoutSpace(r.getId(), r.getVersion(), r.getSpaceKind(), r.getRoomNumber(), r.getLabel(), r.getColor(),
                    r.getRoomType(), r.getStatus(), r.getCapacity(), r.getMonthlyRent(), r.getSecurityDeposit(),
                    r.isAirConditioned(), r.isAttachedBathroom(), r.isPlaced(), r.getLayoutX(), r.getLayoutY(), r.getLayoutW(),
                    r.getLayoutH(), beds.stream().map(LayoutBed::of).toList());
        }
    }

    public record FloorLayoutResponse(
            Long floorId,
            Long propertyId,
            Long buildingId,
            String buildingCode,
            int floorNumber,
            String label,
            int gridCols,
            int gridRows,
            List<LayoutSpace> spaces) {
    }

    /**
     * New geometry of one space: all four layout values, or none of them to take the space off the drawing.
     * {@code label}/{@code color}: {@code null} keeps the current value, an empty string clears it.
     */
    public record SpacePlacement(
            @NotNull Long id,
            @NotNull Long version,
            Integer layoutX,
            Integer layoutY,
            Integer layoutW,
            Integer layoutH,
            @Size(max = 60) String label,
            @Pattern(regexp = "^(#[0-9A-Fa-f]{6})?$", message = "hex colour like #A1B2C3 (empty to clear)") String color) {
    }

    /** Bulk move/resize: applied atomically, validated against every space of the floor after all moves. */
    public record UpdateLayoutRequest(@NotNull @Size(min = 1, max = 500) List<@Valid @NotNull SpacePlacement> spaces) {
    }

    /** Partial floor update; {@code null} keeps a value, an empty label clears it. */
    public record UpdateFloorRequest(
            @Size(max = 50) String label,
            @Min(1) @Max(200) Integer gridCols,
            @Min(1) @Max(200) Integer gridRows) {
    }
}
