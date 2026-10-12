package com.pgms.property;

import com.pgms.user.RoleName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class PropertyDtos {

    private PropertyDtos() {
    }

    public record AddressDto(
            @NotBlank @Size(max = 255) String line1,
            @Size(max = 255) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9 -]{3,12}$", message = "invalid postal code") String postalCode,
            @Size(max = 60) String country) {

        Address toEntity() {
            return new Address(line1, line2, city, state, postalCode, country == null || country.isBlank() ? "India" : country);
        }

        static AddressDto of(Address a) {
            return new AddressDto(a.getLine1(), a.getLine2(), a.getCity(), a.getState(), a.getPostalCode(), a.getCountry());
        }
    }

    public record CreatePropertyRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{2,30}$", message = "uppercase letters, digits and hyphens") String code,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 2000) String description,
            @NotNull PropertyType type,
            @NotNull @Valid AddressDto address,
            @Size(max = 20) String contactPhone,
            @Min(1) @Max(28) Integer rentDueDay,
            Long organizationId) {
    }

    public record UpdatePropertyRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 2000) String description,
            @NotNull PropertyType type,
            @NotNull @Valid AddressDto address,
            @Size(max = 20) String contactPhone,
            @NotNull @Min(1) @Max(28) Integer rentDueDay) {
    }

    public record ChangePropertyStatusRequest(@NotNull PropertyStatus status) {
    }

    public record PropertyResponse(
            Long id,
            Long organizationId,
            String code,
            String name,
            String description,
            PropertyType type,
            PropertyStatus status,
            AddressDto address,
            String contactPhone,
            int rentDueDay,
            Instant createdAt,
            Instant updatedAt) {

        static PropertyResponse of(Property p) {
            return new PropertyResponse(p.getId(), p.getOrganizationId(), p.getCode(), p.getName(), p.getDescription(),
                    p.getType(), p.getStatus(), AddressDto.of(p.getAddress()), p.getContactPhone(), p.getRentDueDay(),
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record OccupancyResponse(
            Long propertyId,
            long totalBeds,
            long occupiedBeds,
            long reservedBeds,
            long availableBeds,
            long maintenanceBeds,
            BigDecimal occupancyRate) {
    }

    public record CreateBuildingRequest(
            @NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,20}$", message = "uppercase letters, digits and hyphens") String code,
            @NotBlank @Size(max = 100) String name) {
    }

    public record BuildingResponse(Long id, Long propertyId, String code, String name) {
        static BuildingResponse of(Building b) {
            return new BuildingResponse(b.getId(), b.getPropertyId(), b.getCode(), b.getName());
        }
    }

    public record CreateFloorRequest(@NotNull @Min(-5) @Max(200) Integer floorNumber, @Size(max = 50) String label) {
    }

    public record FloorResponse(Long id, Long buildingId, int floorNumber, String label) {
        static FloorResponse of(Floor f) {
            return new FloorResponse(f.getId(), f.getBuilding().getId(), f.getFloorNumber(), f.getLabel());
        }
    }

    public record AssignUserRequest(@NotNull Long userId) {
    }

    public record AssignmentResponse(Long userId, String fullName, String email, RoleName role, Instant assignedAt) {
    }
}
