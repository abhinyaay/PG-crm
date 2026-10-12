package com.pgms.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Type-safe, validated application configuration. Every secret is supplied via environment variables. */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @NotNull ZoneId businessTimezone,
        @Valid @NotNull Security security,
        @Valid @NotNull Booking booking,
        @Valid @NotNull Billing billing,
        @Valid @NotNull Notifications notifications,
        @Valid @NotNull Cache cache,
        @Valid @NotNull Bootstrap bootstrap) {

    public record Security(@Valid @NotNull Jwt jwt, @Valid @NotNull RateLimit rateLimit, @NotNull List<String> corsAllowedOrigins,
                           @Valid @NotNull PasswordReset passwordReset) {
    }

    /**
     * RS256 key material. {@code privateKey}/{@code publicKey} are PEM (PKCS#8 / X.509) strings from the environment
     * (Secrets Manager in AWS). {@code allowEphemeralKeys} lets local/dev runs generate a throwaway key pair.
     */
    public record Jwt(
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration refreshTokenTtl,
            String privateKey,
            String publicKey,
            @NotBlank String keyId,
            @DefaultValue("false") boolean allowEphemeralKeys) {
    }

    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @Min(1) int authRequestsPerWindow,
            @NotNull Duration authWindow) {
    }

    /**
     * One-time codes for "forgot password" ({@code codeTtl}) and the portal "set your password" invite
     * ({@code setupCodeTtl}). Requests are throttled per client IP and per identifier through Redis (fail-open).
     */
    public record PasswordReset(
            @NotNull Duration codeTtl,
            @NotNull Duration setupCodeTtl,
            @Min(1) int maxAttempts,
            @DefaultValue("true") boolean rateLimitEnabled,
            @Min(1) int requestsPerIdentifier,
            @Min(1) int requestsPerIp,
            @Min(1) int verificationsPerIp,
            @NotNull Duration window,
            @NotNull Duration retention) {
    }

    public record Booking(@NotNull Duration reservationHold) {
    }

    public record Billing(@Min(0) int dueReminderDaysBefore, @NotBlank String currency) {
    }

    /**
     * Outbox delivery. {@code dispatchLease} is how long a claimed row is hidden from other dispatchers while it is
     * being sent; it must comfortably exceed one batch worth of provider timeouts.
     */
    public record Notifications(
            @Min(1) int dispatchBatchSize,
            @Min(1) int maxAttempts,
            @NotNull Duration dispatchLease,
            String portalUrl,
            @Valid @NotNull Email email,
            @Valid @NotNull Sms sms,
            @Valid @NotNull QuietHours quietHours) {
    }

    /** {@code provider}: {@code log} (default) or {@code smtp} (uses the standard {@code spring.mail.*} settings). */
    public record Email(@NotBlank String provider, @NotBlank String from, String fromName, String replyTo,
                        @NotBlank String brandName) {
    }

    /**
     * {@code provider}: {@code log} (default), {@code twilio} or {@code msg91}. SMS text always comes from the fixed
     * {@code templates} (keyed by notification type), never from a free-form body - Indian DLT rules require it.
     */
    public record Sms(
            @NotBlank String provider,
            @NotBlank String defaultCountryCode,
            @DefaultValue("false") boolean logContent,
            @Min(1) int maxVariableLength,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout,
            @Valid @NotNull Twilio twilio,
            @Valid @NotNull Msg91 msg91,
            Map<String, SmsTemplate> templates) {
    }

    public record Twilio(@NotBlank String baseUrl, String accountSid, String authToken, String fromNumber,
                        String messagingServiceSid) {
    }

    public record Msg91(@NotBlank String baseUrl, String authKey, String senderId) {
    }

    /** A registered DLT template: its id and its text with {@code {variable}} placeholders. */
    public record SmsTemplate(String dltTemplateId, String text) {
    }

    /** Non-urgent SMS created or retried inside [start, end) in the business time zone wait until {@code end}. */
    public record QuietHours(@DefaultValue("true") boolean enabled, @NotNull LocalTime start, @NotNull LocalTime end) {
    }

    public record Cache(@NotNull Duration dashboardTtl, @NotNull Duration propertyTtl) {
    }

    public record Bootstrap(String superAdminEmail, String superAdminPassword) {
    }
}
