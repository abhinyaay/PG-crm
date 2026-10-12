package com.pgms.auth;

import com.pgms.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping for credential tables: expired refresh tokens (kept one extra day so a replayed token is still
 * recognised as expired rather than unknown) and password-reset codes older than the retention period.
 */
@Component
public class AuthMaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(AuthMaintenanceJobs.class);
    private static final Duration REFRESH_TOKEN_GRACE = Duration.ofDays(1);

    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final AppProperties.PasswordReset passwordReset;
    private final Clock clock;

    public AuthMaintenanceJobs(RefreshTokenRepository refreshTokenRepository,
                               PasswordResetTokenRepository passwordResetTokenRepository, AppProperties properties, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordReset = properties.security().passwordReset();
        this.clock = clock;
    }

    @Scheduled(cron = "${app.jobs.auth-cleanup-cron:0 15 3 * * *}", zone = "${app.business-timezone}")
    @SchedulerLock(name = "authTokenCleanup", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @Transactional
    public CleanupResult purgeExpired() {
        Instant now = clock.instant();
        int refreshTokens = refreshTokenRepository.deleteExpiredBefore(now.minus(REFRESH_TOKEN_GRACE));
        int resetCodes = passwordResetTokenRepository.deleteCreatedBefore(now.minus(passwordReset.retention()));
        log.info("Auth cleanup: removed {} expired refresh tokens and {} old password reset codes", refreshTokens, resetCodes);
        return new CleanupResult(refreshTokens, resetCodes);
    }

    public record CleanupResult(int refreshTokens, int passwordResetCodes) {
    }
}
