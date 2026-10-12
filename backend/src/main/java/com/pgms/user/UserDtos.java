package com.pgms.user;

import com.pgms.auth.AuthDtos;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Set;

public final class UserDtos {

    public static final String PHONE_REGEX = "^\\+?[0-9]{10,15}$";

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = AuthDtos.PASSWORD_REGEX, message = AuthDtos.PASSWORD_MESSAGE) String password,
            @NotBlank @Size(max = 150) String fullName,
            @Pattern(regexp = PHONE_REGEX, message = "must be 10-15 digits, optionally prefixed with +") String phone,
            @NotNull RoleName role,
            Long organizationId,
            Set<Long> propertyIds) {
    }

    public record UpdateUserRequest(
            @NotBlank @Size(max = 150) String fullName,
            @Pattern(regexp = PHONE_REGEX, message = "must be 10-15 digits, optionally prefixed with +") String phone) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Pattern(regexp = AuthDtos.PASSWORD_REGEX, message = AuthDtos.PASSWORD_MESSAGE) String newPassword) {
    }

    public record UserResponse(
            Long id,
            Long organizationId,
            String email,
            String fullName,
            String phone,
            RoleName role,
            UserStatus status,
            Instant lastLoginAt,
            Instant createdAt,
            Set<Long> propertyIds) {
    }
}
