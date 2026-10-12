package com.pgms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pgms.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class TokenRevocationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private TokenRevocationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        AppProperties properties = mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.security().jwt().accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        service = new TokenRevocationService(redis, Clock.fixed(NOW, ZoneOffset.UTC), properties);
    }

    @Test
    void revokedJtiIsRejected() {
        when(redis.hasKey("auth:revoked-jti:abc")).thenReturn(true);
        assertThat(service.isRevoked("abc", "7", NOW)).isTrue();
    }

    @Test
    void tokensIssuedBeforeUserWideCutoffAreRejected() {
        when(redis.hasKey(anyString())).thenReturn(false);
        when(values.get("auth:invalid-before:7")).thenReturn(String.valueOf(NOW.getEpochSecond()));
        assertThat(service.isRevoked("x", "7", NOW.minusSeconds(1))).isTrue();
        assertThat(service.isRevoked("x", "7", NOW)).isFalse();
    }

    @Test
    void failsOpenWhenRedisIsDown() {
        when(redis.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        assertThat(service.isRevoked("abc", "7", NOW)).isFalse();
    }
}
