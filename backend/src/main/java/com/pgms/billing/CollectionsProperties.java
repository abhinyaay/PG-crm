package com.pgms.billing;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.collections}: defaults for properties without a stored billing policy, and payment-claim settings.
 * The T-N due reminder default stays {@code app.billing.due-reminder-days-before}.
 */
@Validated
@ConfigurationProperties(prefix = "app.collections")
public record CollectionsProperties(@Valid @NotNull Defaults defaults, @Valid @NotNull Claims claims) {

    public record Defaults(
            @DefaultValue("false") boolean lateFeeEnabled,
            @DefaultValue("FLAT") LateFeeType lateFeeType,
            @DefaultValue("0") BigDecimal lateFeeValue,
            BigDecimal lateFeeCap,
            @DefaultValue("0") @Min(0) @Max(31) int graceDays,
            @DefaultValue("7") @Min(0) @Max(31) int moveInGraceDays,
            @DefaultValue("false") boolean lateFeeOnMoveIn,
            @DefaultValue({"1", "5", "10"}) List<Integer> overdueReminderOffsets) {
    }

    /** {@code pendingSla}: a SUBMITTED claim older than this is included in the pending-claims digest. */
    public record Claims(@DefaultValue("24h") @NotNull Duration pendingSla) {
    }
}
