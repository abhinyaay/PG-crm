package com.pgms.security;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis, shared by all replicas. Fail-open: when Redis is unavailable every request is
 * allowed (availability of auth beats strict throttling for this threat model) and a warning is logged.
 */
@Component
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /** INCR + set expiry on first hit, atomically (no key can be left without a TTL). */
    private static final RedisScript<Long> INCREMENT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** {@code retryAfterSeconds} is only meaningful when not allowed. */
    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    /**
     * Counts one hit for {@code subject} in {@code bucket}. Callers must not pass raw personal data as the subject
     * (hash it): keys are visible to anyone with Redis access.
     */
    public Decision hit(String bucket, String subject, int limit, Duration window) {
        long windowMillis = window.toMillis();
        long current = clock.millis() / windowMillis;
        String key = "ratelimit:" + bucket + ":" + subject + ":" + current;
        try {
            Long count = redis.execute(INCREMENT_SCRIPT, List.of(key), String.valueOf(windowMillis));
            if (count != null && count > limit) {
                long retryAfter = Math.max(1, ((current + 1) * windowMillis - clock.millis()) / 1000);
                return new Decision(false, retryAfter);
            }
        } catch (DataAccessException e) {
            log.warn("Redis unavailable - rate limiting for {} skipped (fail-open)", bucket);
        }
        return new Decision(true, 0);
    }
}
