package com.pgms.billing.autopay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.payments.autopay}. {@code provider} selects the {@link RecurringPaymentGateway}: {@code sandbox}
 * (default; simulated bank, deterministic outcomes) or {@code razorpay} (keys and webhook secret from the environment).
 */
@Validated
@ConfigurationProperties(prefix = "app.payments.autopay")
public record AutopayProperties(
        @DefaultValue("sandbox") @NotBlank String provider,
        @DefaultValue("3") @Min(1) @Max(3) int maxAttempts,
        @DefaultValue("2") @Min(1) int confirmationTimeoutDays,
        @Valid @NotNull Sandbox sandbox,
        @Valid @NotNull Razorpay razorpay) {

    public record Sandbox(@NotBlank String authorisationBaseUrl, @NotBlank String webhookSecret) {
    }

    public record Razorpay(@NotBlank String baseUrl, String keyId, String keySecret, String webhookSecret,
                           @DefaultValue("3s") Duration connectTimeout, @DefaultValue("15s") Duration readTimeout) {
    }
}
