package com.pgms.room;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.List;

public final class RoomDtos {

    private RoomDtos() {
    }

    public record CreateRoomRequest(
            @NotNull Long floorId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,20}$", message = "letters, digits and hyphens") String roomNumber,
            @NotNull RoomType roomType,
            @NotNull @Min(1) @Max(20) Integer capacity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom) {
    }

    public record UpdateRoomRequest(
            @NotNull RoomType roomType,
            @NotNull @Min(1) @Max(20) Integer capacity,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal monthlyRent,
            @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal securityDeposit,
            boolean airConditioned,
            boolean attachedBathroom) {
    }

    public record ChangeRoomStatusRequest(@NotNull RoomStatus status) {
    }

    public record ChangeBedStatusRequest(@NotNull BedStatus status) {
    }

    public record BedSummary(Long id, String bedCode, BedStatus status) {
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
            List<BedSummary> beds) {
    }

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
            boolean attachedBathroom) {

        public static BedResponse of(Bed bed) {
            Room room = bed.getRoom();
            return new BedResponse(bed.getId(), bed.getPropertyId(), room.getId(), room.getRoomNumber(), bed.getBedCode(),
                    bed.getStatus(), room.getRoomType(), room.getMonthlyRent(), room.getSecurityDeposit(),
                    room.isAirConditioned(), room.isAttachedBathroom());
        }
    }
}
