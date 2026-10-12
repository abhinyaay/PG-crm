package com.pgms.security;

import com.pgms.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Access tokens are stateless, so revocation needs a shared, fast store: Redis.
 * <ul>
 *   <li>{@code auth:revoked-jti:{jti}} - a single token revoked at logout, kept only until it would expire anyway.</li>
 *   <li>{@code auth:invalid-before:{userId}} - every token issued before this epoch second is rejected
 *       (deactivation, password change, "log out everywhere", refresh-token reuse detection).</li>
 * </ul>
 * Fail-open: if Redis is down we accept the token (it expires within minutes) and log loudly. Refresh tokens live in
 * PostgreSQL, so a revoked session still cannot be extended while Redis is unavailable.
 */
@Service
public class TokenRevocationService {

    static final String REVOKED_JTI_PREFIX = "auth:revoked-jti:";
    static final String INVALID_BEFORE_PREFIX = "auth:invalid-before:";

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final Duration maxAccessTokenTtl;

    public TokenRevocationService(StringRedisTemplate redis, Clock clock, AppProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.maxAccessTokenTtl = properties.security().jwt().accessTokenTtl();
    }

    public void revokeToken(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(clock.instant(), expiresAt);
        if (jti == null || ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redis.opsForValue().set(REVOKED_JTI_PREFIX + jti, "1", ttl);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable - could not revoke access token jti={}; it stays valid until expiry", jti, e);
        }
    }

    public void revokeAllTokensOf(Long userId) {
        try {
            // keep the marker for one access-token lifetime: older tokens are expired after that anyway
            redis.opsForValue().set(INVALID_BEFORE_PREFIX + userId, String.valueOf(clock.instant().getEpochSecond()),
                    maxAccessTokenTtl.plusMinutes(1));
        } catch (DataAccessException e) {
            log.warn("Redis unavailable - could not invalidate access tokens of user {}", userId, e);
        }
    }

    public boolean isRevoked(String jti, String userId, Instant issuedAt) {
        try {
            if (jti != null && Boolean.TRUE.equals(redis.hasKey(REVOKED_JTI_PREFIX + jti))) {
                return true;
            }
            String invalidBefore = redis.opsForValue().get(INVALID_BEFORE_PREFIX + userId);
            return invalidBefore != null && issuedAt != null && issuedAt.getEpochSecond() < Long.parseLong(invalidBefore);
        } catch (DataAccessException e) {
            log.warn("Redis unavailable - skipping access token revocation check (fail-open)");
            return false;
        }
    }
}
