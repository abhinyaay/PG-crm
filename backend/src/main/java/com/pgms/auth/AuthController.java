package com.pgms.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.pgms.auth.AuthDtos.AcceptedResponse;
import com.pgms.auth.AuthDtos.ChangePasswordRequest;
import com.pgms.auth.AuthDtos.ForgotPasswordRequest;
import com.pgms.auth.AuthDtos.LoginRequest;
import com.pgms.auth.AuthDtos.LogoutRequest;
import com.pgms.auth.AuthDtos.RefreshRequest;
import com.pgms.auth.AuthDtos.ResetPasswordRequest;
import com.pgms.auth.AuthDtos.TokenResponse;
import com.pgms.auth.AuthDtos.UserInfo;
import com.pgms.security.JwtKeyProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final JwtKeyProvider keyProvider;

    public AuthController(AuthService authService, PasswordResetService passwordResetService, JwtKeyProvider keyProvider) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.keyProvider = keyProvider;
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Exchange credentials for an access token (15 min) and a rotating refresh token")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    @SecurityRequirements
    @Operation(summary = "Rotate the refresh token and obtain a new access token")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke the current session (refresh token family + current access token)")
    public void logout(@Valid @RequestBody LogoutRequest request, @AuthenticationPrincipal Jwt jwt) {
        authService.logout(request.refreshToken(), jwt);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke every session of the current user on all devices")
    public void logoutAll(@AuthenticationPrincipal Jwt jwt) {
        authService.logoutEverywhere(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Change own password; all sessions are revoked and the user must log in again")
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request, @AuthenticationPrincipal Jwt jwt) {
        authService.changePassword(Long.valueOf(jwt.getSubject()), request.currentPassword(), request.newPassword());
    }

    @PostMapping("/password/forgot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @SecurityRequirements
    @Operation(summary = "Send a 6-digit reset code to the email or phone (always 202, whether or not an account exists)")
    public AcceptedResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.identifier());
        return new AcceptedResponse("If an account matches, a code has been sent. It is valid for a few minutes.");
    }

    @PostMapping("/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirements
    @Operation(summary = "Set a new password with the code (also the first password of a new portal account); "
            + "revokes all sessions")
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.identifier(), request.code(), request.newPassword());
    }

    @GetMapping("/me")
    public UserInfo me(@AuthenticationPrincipal Jwt jwt) {
        return authService.me(Long.valueOf(jwt.getSubject()));
    }

    /** Public signing keys (JWKS) so other services can verify tokens without sharing a secret. */
    @GetMapping("/jwks")
    @SecurityRequirements
    @Operation(summary = "JSON Web Key Set used to verify access tokens")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .body(new JWKSet(keyProvider.rsaKey().toPublicJWK()).toJSONObject());
    }
}
