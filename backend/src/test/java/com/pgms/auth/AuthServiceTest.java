package com.pgms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.UnauthorizedException;
import com.pgms.common.util.Hashing;
import com.pgms.config.AppProperties;
import com.pgms.organization.Organization;
import com.pgms.organization.OrganizationRepository;
import com.pgms.organization.OrganizationStatus;
import com.pgms.security.JwtTokenService;
import com.pgms.security.TokenRevocationService;
import com.pgms.support.TestData;
import com.pgms.user.RoleName;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private final PasswordEncoder encoder = new PlainTextEncoderForTests();
    private UserRepository users;
    private OrganizationRepository organizations;
    private RefreshTokenRepository refreshTokens;
    private TokenRevocationService revocation;
    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        organizations = mock(OrganizationRepository.class);
        refreshTokens = mock(RefreshTokenRepository.class);
        revocation = mock(TokenRevocationService.class);
        JwtTokenService jwt = mock(JwtTokenService.class);
        when(jwt.issueAccessToken(any())).thenReturn(new JwtTokenService.IssuedToken("access-token", NOW.plusSeconds(900)));
        AtomicLong ids = new AtomicLong(1);
        when(refreshTokens.save(any())).thenAnswer(inv -> TestData.withId(inv.getArgument(0, RefreshToken.class),
                ids.getAndIncrement()));
        AppProperties properties = mock(AppProperties.class, Answers.RETURNS_DEEP_STUBS);
        when(properties.security().jwt().refreshTokenTtl()).thenReturn(Duration.ofDays(14));

        service = new AuthService(users, organizations, refreshTokens, encoder, jwt, revocation, mock(AuditService.class),
                mock(ApplicationEventPublisher.class), properties, Clock.fixed(NOW, ZoneOffset.UTC));
        user = TestData.user(7L, 3L, RoleName.MANAGER, encoder.encode("Secret123"));
        when(users.findByEmail("user7@example.com")).thenReturn(Optional.of(user));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(organizations.findById(3L)).thenReturn(Optional.of(new Organization("Org", "org", null, null)));
    }

    @Test
    void loginIssuesAccessAndRefreshTokens() {
        AuthDtos.TokenResponse response = service.login("USER7@example.com ", "Secret123");
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).hasSizeGreaterThan(40);
        assertThat(response.refreshTokenExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() {
        assertThatThrownBy(() -> service.login("user7@example.com", "nope"))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid email or password");
        assertThatThrownBy(() -> service.login("ghost@example.com", "nope"))
                .isInstanceOf(UnauthorizedException.class).hasMessage("Invalid email or password");
    }

    @Test
    void deactivatedAccountCannotLogIn() {
        user.deactivate();
        assertThatThrownBy(() -> service.login("user7@example.com", "Secret123"))
                .isInstanceOf(UnauthorizedException.class).extracting("code").isEqualTo("ACCOUNT_INACTIVE");
    }

    @Test
    void suspendedOrganizationBlocksLogin() {
        Organization org = new Organization("Org", "org", null, null);
        org.changeStatus(OrganizationStatus.SUSPENDED);
        when(organizations.findById(3L)).thenReturn(Optional.of(org));
        assertThatThrownBy(() -> service.login("user7@example.com", "Secret123"))
                .isInstanceOf(UnauthorizedException.class).extracting("code").isEqualTo("ORGANIZATION_SUSPENDED");
    }

    @Test
    void refreshRotatesTheToken() {
        RefreshToken current = TestData.withId(new RefreshToken(7L, Hashing.sha256Hex("raw"), UUID.randomUUID(), NOW,
                NOW.plusSeconds(3600), null, null), 50L);
        when(refreshTokens.findByHashForUpdate(Hashing.sha256Hex("raw"))).thenReturn(Optional.of(current));

        AuthDtos.TokenResponse response = service.refresh("raw");

        assertThat(response.refreshToken()).isNotEqualTo("raw");
        assertThat(current.isRevoked()).isTrue();
        assertThat(current.getRevokedReason()).isEqualTo("ROTATED");
    }

    @Test
    void replayOfARotatedTokenRevokesTheWholeFamilyAndLiveAccessTokens() {
        UUID family = UUID.randomUUID();
        RefreshToken rotated = TestData.withId(new RefreshToken(7L, Hashing.sha256Hex("old"), family, NOW,
                NOW.plusSeconds(3600), null, null), 51L);
        rotated.revoke(NOW, "ROTATED");
        when(refreshTokens.findByHashForUpdate(Hashing.sha256Hex("old"))).thenReturn(Optional.of(rotated));

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(UnauthorizedException.class);

        verify(refreshTokens).revokeFamily(eq(family), eq(NOW), eq("REUSE_DETECTED"));
        verify(revocation).revokeAllTokensOf(7L);
    }

    @Test
    void expiredRefreshTokenIsRejectedWithoutRotation() {
        RefreshToken expired = TestData.withId(new RefreshToken(7L, Hashing.sha256Hex("exp"), UUID.randomUUID(),
                NOW.minusSeconds(7200), NOW.minusSeconds(1), null, null), 52L);
        when(refreshTokens.findByHashForUpdate(Hashing.sha256Hex("exp"))).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.refresh("exp")).isInstanceOf(UnauthorizedException.class);
        verify(refreshTokens, never()).save(any());
    }

    @Test
    void changingPasswordRevokesAllSessions() {
        service.changePassword(7L, "Secret123", "NewSecret456");
        assertThat(encoder.matches("NewSecret456", user.getPasswordHash())).isTrue();
        verify(refreshTokens).revokeAllForUser(eq(7L), eq(NOW), anyString());
        verify(revocation).revokeAllTokensOf(7L);
    }

    /** Fast deterministic encoder: hashing strength is not what these tests are about. */
    private static final class PlainTextEncoderForTests implements PasswordEncoder {
        @Override
        public String encode(CharSequence raw) {
            return "plain:" + raw;
        }

        @Override
        public boolean matches(CharSequence raw, String encoded) {
            return encoded.equals("plain:" + raw);
        }
    }
}
