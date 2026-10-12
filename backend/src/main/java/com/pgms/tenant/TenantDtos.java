package com.pgms.tenant;

import com.pgms.auth.AuthDtos;
import com.pgms.user.UserDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class TenantDtos {

    private TenantDtos() {
    }

    public record EmergencyContactDto(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 50) String relationship,
            @NotBlank @Pattern(regexp = UserDtos.PHONE_REGEX, message = "must be 10-15 digits") String phone,
            @Email String email,
            boolean primary) {

        static EmergencyContactDto of(EmergencyContact c) {
            return new EmergencyContactDto(c.getName(), c.getRelationship(), c.getPhone(), c.getEmail(), c.isPrimary());
        }
    }

    public record CreateTenantRequest(
            @NotNull Long propertyId,
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = UserDtos.PHONE_REGEX, message = "must be 10-15 digits") String phone,
            Gender gender,
            @Past LocalDate dateOfBirth,
            @Size(max = 100) String occupation,
            @Size(max = 150) String organizationName,
            @Size(max = 1000) String permanentAddress,
            @Valid @Size(max = 3) List<EmergencyContactDto> emergencyContacts) {
    }

    public record UpdateTenantRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = UserDtos.PHONE_REGEX, message = "must be 10-15 digits") String phone,
            Gender gender,
            @Past LocalDate dateOfBirth,
            @Size(max = 100) String occupation,
            @Size(max = 150) String organizationName,
            @Size(max = 1000) String permanentAddress) {
    }

    public record EmergencyContactsRequest(@NotNull @Valid @Size(max = 3) List<EmergencyContactDto> contacts) {
    }

    public record AddDocumentRequest(
            @NotNull DocumentType documentType,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9 -]{4,30}$", message = "4-30 letters/digits") String documentNumber,
            @Size(max = 500) String storageKey) {
    }

    public record ReviewDocumentRequest(@NotNull VerificationStatus status) {
    }

    public record DocumentResponse(Long id, DocumentType documentType, String documentNumberMasked, String storageKey,
                                   VerificationStatus verificationStatus, Instant verifiedAt, Instant createdAt) {
        static DocumentResponse of(TenantDocument d) {
            return new DocumentResponse(d.getId(), d.getDocumentType(), d.getDocumentNumberMasked(), d.getStorageKey(),
                    d.getVerificationStatus(), d.getVerifiedAt(), d.getCreatedAt());
        }
    }

    /** {@code initialPassword} is optional (legacy): without it the resident sets their own password with a code. */
    public record PortalAccountRequest(
            @Pattern(regexp = AuthDtos.PASSWORD_REGEX, message = AuthDtos.PASSWORD_MESSAGE) String initialPassword) {
    }

    public record TenantResponse(
            Long id,
            Long organizationId,
            Long propertyId,
            Long userId,
            String fullName,
            String email,
            String phone,
            Gender gender,
            LocalDate dateOfBirth,
            String occupation,
            String organizationName,
            String permanentAddress,
            TenantStatus status,
            LocalDate joiningDate,
            LocalDate moveOutDate,
            List<EmergencyContactDto> emergencyContacts,
            Instant createdAt) {

        static TenantResponse of(Tenant t) {
            return new TenantResponse(t.getId(), t.getOrganizationId(), t.getPropertyId(), t.getUserId(), t.getFullName(),
                    t.getEmail(), t.getPhone(), t.getGender(), t.getDateOfBirth(), t.getOccupation(), t.getOrganizationName(),
                    t.getPermanentAddress(), t.getStatus(), t.getJoiningDate(), t.getMoveOutDate(),
                    t.getEmergencyContacts().stream().map(EmergencyContactDto::of).toList(), t.getCreatedAt());
        }
    }

    public record TenantSummary(Long id, Long propertyId, String fullName, String email, String phone, TenantStatus status,
                                LocalDate joiningDate, LocalDate moveOutDate) {
        static TenantSummary of(Tenant t) {
            return new TenantSummary(t.getId(), t.getPropertyId(), t.getFullName(), t.getEmail(), t.getPhone(), t.getStatus(),
                    t.getJoiningDate(), t.getMoveOutDate());
        }
    }
}
