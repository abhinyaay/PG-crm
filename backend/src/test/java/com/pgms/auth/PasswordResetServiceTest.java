package com.pgms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.TooManyRequestsException;
import com.pgms.config.AppProperties;
import com.pgms.security.RedisRateLimiter;
import com.pgms.support.TestData;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordResetServiceTest {

    private UserRepository users;
    private PasswordResetTokenRepository tokens;
    private RedisRateLimiter limiter;
    private ApplicationEventPublisher events;
    private AppProperties properties;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(PasswordResetTokenRepository.class);
        limiter = mock(RedisRateLimiter.class);
        events = mock(ApplicationEventPublisher.class);
        properties = mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.notifications().sms().defaultCountryCode()).thenReturn("91");
        AppProperties.PasswordReset config = new AppProperties.PasswordReset(Duration.ofMinutes(10), Duration.ofHours(72), 5, true,
                3, 10, 20, Duration.ofMinutes(15), Duration.ofDays(7));
        when(properties.security().passwordReset()).thenReturn(config);
        when(limiter.hit(anyString(), anyString(), anyInt(), any())).thenReturn(new RedisRateLimiter.Decision(true, 0));
        service = new PasswordResetService(users, tokens, mock(PasswordEncoder.class), mock(AuthService.class), limiter, events,
                mock(AuditService.class), properties, Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void perIpThrottleRejectsBeforeAnyLookup() {
        when(limiter.hit(eq("pwd-forgot-ip"), anyString(), eq(10), any())).thenReturn(new RedisRateLimiter.Decision(false, 42));
        assertThatThrownBy(() -> service.requestReset("someone@x.test"))
                .isInstanceOf(TooManyRequestsException.class).hasMessageContaining("42s");
        verify(users, never()).findByEmail(any());
    }

    @Test
    void phoneSharedByTwoAccountsMatchesNothing() {
        User a = TestData.user(1L, 3L, RoleName.TENANT, "x");
        User b = TestData.user(2L, 4L, RoleName.TENANT, "x");
        when(users.findActiveByPhoneIn(any())).thenReturn(List.of(a, b));
        service.requestReset("98765 43210");
        verify(tokens, never()).save(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void phoneFallsBackToTheResidentProfileAndSendsBySms() {
        User resident = TestData.user(7L, 3L, RoleName.TENANT, "x");
        when(users.findActiveByPhoneIn(any())).thenReturn(List.of());
        when(users.findActiveByTenantPhoneIn(any())).thenReturn(List.of(resident));

        service.requestReset("+91 98765 43210");

        ArgumentCaptor<PasswordResetToken> token = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(token.capture());
        assertThat(token.getValue().getUserId()).isEqualTo(7L);
        assertThat(token.getValue().getPurpose()).isEqualTo(AuthEvents.OtpPurpose.RESET);
        assertThat(token.getValue().getCodeHash()).hasSize(64);
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(event.capture());
        AuthEvents.OtpIssued issued = (AuthEvents.OtpIssued) event.getValue();
        assertThat(issued.delivery()).isEqualTo(AuthEvents.OtpDelivery.SMS);
        assertThat(issued.code()).matches("\\d{6}");
        assertThat(issued.validity()).isEqualTo(Duration.ofMinutes(10));
        assertThat(issued.toString()).doesNotContain(issued.code());
    }

    @Test
    void throttlingCanBeSwitchedOff() {
        when(properties.security().passwordReset()).thenReturn(new AppProperties.PasswordReset(Duration.ofMinutes(10),
                Duration.ofHours(72), 5, false, 3, 10, 20, Duration.ofMinutes(15), Duration.ofDays(7)));
        PasswordResetService unthrottled = new PasswordResetService(users, tokens, mock(PasswordEncoder.class),
                mock(AuthService.class), limiter, events, mock(AuditService.class), properties, Clock.systemUTC());
        unthrottled.requestReset("nobody@x.test");
        verify(limiter, never()).hit(anyString(), anyString(), anyInt(), any());
    }
}
