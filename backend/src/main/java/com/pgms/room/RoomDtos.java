package com.pgms.room;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public final class RoomDtos {

    static final String BED_CODE_PATTERN = "^[A-Za-z0-9-]{1,10}$";
    static final String COLOR_PATTERN = "^#[0-9A-Fa-f]{6}$";

    private RoomDtos() {
    }

    /**
     * Creates a space. Without {@code spaceKind} this is the original "create a room" request (roomType, capacity and
     * prices required, beds generated from the capacity). HALL/DORMITORY take 0-50 initial beds; other kinds hold no
     * beds. The optional geometry places the space on the floor grid right away.
     */
    public record CreateRoomRequest(
            @NotNull Long floorId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "letters, digits and hyphens") String roomNumber,
            RoomType roomType,
            @Min(0) @Max(SpaceKind.MAX_OPEN_BEDS) Integer capacity,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom,
            SpaceKind spaceKind,
            Integer layoutX,
            Integer layoutY,
            Integer layoutW,
            Integer layoutH,
            @Size(max = 60) String label,
            @Pattern(regexp = COLOR_PATTERN, message = "hex colour like #A1B2C3") String color) {

        public SpaceKind kind() {
            return spaceKind == null ? SpaceKind.ROOM : spaceKind;
        }

        @AssertTrue(message = "roomType and a capacity of 1-20 are required for ROOM spaces")
        public boolean isRoomLayoutComplete() {
            return kind() != SpaceKind.ROOM
                    || roomType != null && capacity != null && capacity >= 1 && capacity <= SpaceKind.MAX_ROOM_BEDS;
        }

        @AssertTrue(message = "monthlyRent and securityDeposit are required for spaces that hold beds")
        public boolean isPricingComplete() {
            return !kind().holdsBeds() || monthlyRent != null && securityDeposit != null;
        }

        @AssertTrue(message = "spaces of this kind hold no beds: capacity must be 0 or omitted")
        public boolean isBedlessCapacityValid() {
            return kind().holdsBeds() || capacity == null || capacity == 0;
        }
    }

    /** Pricing/amenities/capacity. roomType and capacity are required for ROOM spaces (checked in the service). */
    public record UpdateRoomRequest(
            RoomType roomType,
            @Min(0) @Max(SpaceKind.MAX_OPEN_BEDS) Integer capacity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom) {
    }

    public record ChangeRoomStatusRequest(@NotNull RoomStatus status) {
    }

    public record ChangeBedStatusRequest(@NotNull BedStatus status) {
    }

    /** Adds one bed to a ROOM/HALL/DORMITORY; without {@code bedCode} the next free code (A, B, ... AA) is used. */
    public record AddBedRequest(
            @Pattern(regexp = BED_CODE_PATTERN, message = "1-10 letters, digits or hyphens") String bedCode,
            @Size(max = 60) String label,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRentOverride,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDepositOverride,
            @Min(0) @Max(1000) Integer posX,
            @Min(0) @Max(1000) Integer posY) {
    }

    /** Attributes of a bed that {@link UpdateBedRequest#clear()} can reset to "not set". */
    public enum BedAttribute {
        LABEL,
        MONTHLY_RENT_OVERRIDE,
        SECURITY_DEPOSIT_OVERRIDE,
        POSITION
    }

    /**
     * Partial update: a {@code null} field is left unchanged; attributes listed in {@code clear} are reset (e.g. drop a
     * price override so the room price applies again). Setting and clearing the same attribute is rejected.
     */
    public record UpdateBedRequest(
            @Pattern(regexp = BED_CODE_PATTERN, message = "1-10 letters, digits or hyphens") String bedCode,
            @Size(max = 60) String label,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRentOverride,
            @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDepositOverride,
            @Min(0) @Max(1000) Integer posX,
            @Min(0) @Max(1000) Integer posY,
            Set<BedAttribute> clear) {

        public boolean clears(BedAttribute attribute) {
            return clear != null && clear.contains(attribute);
        }
    }

    public record BedSummary(Long id, String bedCode, BedStatus status, String label) {
        static BedSummary of(Bed b) {
            return new BedSummary(b.getId(), b.getBedCode(), b.getStatus(), b.getLabel());
        }
    }

    public record RoomResponse(
            Long id,
            Long propertyId,
            Long floorId,
            int floorNumber,
            String buildingCode,
            String roomNumber,
            RoomType roomType,
            int capacity,
            BigDecimal monthlyRent,
            BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom,
            RoomStatus status,
            List<BedSummary> beds,
            SpaceKind spaceKind,
            Integer layoutX,
            Integer layoutY,
            Integer layoutW,
            Integer layoutH,
            String label,
            String color,
            long version) {
    }

    /**
     * {@code monthlyRent}/{@code securityDeposit} are what a new occupant pays: the bed's override when set, otherwise
     * the room's price. The raw overrides are exposed separately so editors can show/clear them.
     */
    public record BedResponse(
            Long id,
            Long propertyId,
            Long roomId,
            String roomNumber,
            String bedCode,
            BedStatus status,
            RoomType roomType,
            BigDecimal monthlyRent,
            BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom,
            String label,
            BigDecimal monthlyRentOverride,
            BigDecimal securityDepositOverride,
            Integer posX,
            Integer posY,
            SpaceKind spaceKind) {

        public static BedResponse of(Bed bed) {
            Room room = bed.getRoom();
            return new BedResponse(bed.getId(), bed.getPropertyId(), room.getId(), room.getRoomNumber(), bed.getBedCode(),
                    bed.getStatus(), room.getRoomType(), bed.effectiveMonthlyRent(), bed.effectiveSecurityDeposit(),
                    room.isAirConditioned(), room.isAttachedBathroom(), bed.getLabel(), bed.getMonthlyRentOverride(),
                    bed.getSecurityDepositOverride(), bed.getPosX(), bed.getPosY(), room.getSpaceKind());
        }
    }
}
