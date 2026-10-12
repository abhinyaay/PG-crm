package com.pgms.auth;

import com.pgms.user.RoleName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class AuthDtos {

    /** bcrypt only uses the first 72 bytes, so cap length there; require letters and digits. */
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d).{8,72}$";
    public static final String PASSWORD_MESSAGE = "must be 8-72 characters and contain letters and digits";

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 72) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    public record LogoutRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String newPassword) {
    }

    /** {@code identifier}: the account's email address or phone number. */
    public record ForgotPasswordRequest(@NotBlank @Size(max = 255) String identifier) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 255) String identifier,
            @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be the 6-digit code") String code,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String newPassword) {
    }

    public record AcceptedResponse(String message) {
    }

    public record TokenResponse(
            String tokenType,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UserInfo user) {
    }

    public record UserInfo(Long id, String email, String fullName, RoleName role, Long organizationId) {
    }
}
