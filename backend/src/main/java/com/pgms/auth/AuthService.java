package com.pgms.auth;

import com.pgms.audit.AuditService;
import com.pgms.auth.AuthDtos.TokenResponse;
import com.pgms.auth.AuthDtos.UserInfo;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.NotFoundException;
import com.pgms.common.exception.UnauthorizedException;
import com.pgms.common.util.Hashing;
import com.pgms.common.web.RequestContext;
import com.pgms.config.AppProperties;
import com.pgms.organization.OrganizationRepository;
import com.pgms.organization.OrganizationStatus;
import com.pgms.security.JwtTokenService;
import com.pgms.security.TokenRevocationService;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final TokenRevocationService revocationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final AppProperties.Jwt jwtProperties;
    private final Clock clock;
    /** Hash compared against when the email is unknown, so response time does not reveal which emails exist. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository, OrganizationRepository organizationRepository,
                       RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService, TokenRevocationService revocationService, AuditService auditService,
                       ApplicationEventPublisher events, AppProperties properties, Clock clock) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.revocationService = revocationService;
        this.auditService = auditService;
        this.events = events;
        this.jwtProperties = properties.security().jwt();
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenResponse login(String email, String password) {
        User user = userRepository.findByEmail(User.normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.warn("Failed login for userId={}", user.getId());
            throw invalidCredentials();
        }
        // status checks only after the password is verified -> no account-state disclosure to guessers
        ensureMayAuthenticate(user);
        Instant now = clock.instant();
        user.recordLogin(now);
        TokenResponse response = issueTokens(user, UUID.randomUUID(), now);
        auditService.record(user.getOrganizationId(), "LOGIN", "USER", user.getId());
        return response;
    }

    /**
     * Rotating refresh. Presenting an already-rotated/revoked token means it was copied: the whole token family is
     * revoked and all live access tokens of the user are invalidated. That state change must survive the 401 we
     * return, hence {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokenRepository.findByHashForUpdate(Hashing.sha256Hex(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefreshToken);
        if (current.isRevoked()) {
            log.warn("Refresh token reuse detected for userId={} family={} (reason={})",
                    current.getUserId(), current.getFamilyId(), current.getRevokedReason());
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now, "REUSE_DETECTED");
            revocationService.revokeAllTokensOf(current.getUserId());
            userRepository.findById(current.getUserId()).ifPresent(u ->
                    auditService.record(u.getOrganizationId(), "REFRESH_TOKEN_REUSE", "USER", u.getId(),
                            Map.of("familyId", current.getFamilyId().toString())));
            throw invalidRefreshToken();
        }
        if (current.isExpired(now)) {
            throw invalidRefreshToken();
        }
        User user = userRepository.findById(current.getUserId()).orElse(null);
        if (user == null || !user.isActive() || isOrganizationSuspended(user)) {
            refreshTokenRepository.revokeFamily(current.getFamilyId(), now, "ACCOUNT_INACTIVE");
            throw invalidRefreshToken();
        }
        RotatedTokens rotated = createRefreshToken(user, current.getFamilyId(), now);
        current.rotateTo(rotated.entity(), now);
        JwtTokenService.IssuedToken access = jwtTokenService.issueAccessToken(user);
        return new TokenResponse("Bearer", access.value(), access.expiresAt(), rotated.rawToken(),
                rotated.entity().getExpiresAt(), toUserInfo(user));
    }

    /** Ends the session: revokes the refresh-token family and blacklists the presented access token. */
    @Transactional
    public void logout(String rawRefreshToken, Jwt accessToken) {
        Long userId = Long.valueOf(accessToken.getSubject());
        refreshTokenRepository.findByTokenHash(Hashing.sha256Hex(rawRefreshToken))
                .filter(token -> token.getUserId().equals(userId))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), clock.instant(), "LOGOUT"));
        revocationService.revokeToken(accessToken.getId(), accessToken.getExpiresAt());
    }

    @Transactional
    public void logoutEverywhere(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, clock.instant(), "LOGOUT_ALL");
        revocationService.revokeAllTokensOf(userId);
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User", userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            // 422, not 401: the access token is fine, only the submitted field is wrong (a 401 makes clients log out)
            throw new BusinessRuleException("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessRuleException("PASSWORD_REUSED", "New password must differ from the current password");
        }
        user.changePassword(passwordEncoder.encode(newPassword), clock.instant());
        passwordChanged(user, "CHANGED");
        auditService.record(user.getOrganizationId(), "PASSWORD_CHANGED", "USER", userId);
    }

    /**
     * Follow-up of every password change (own change, reset with a code, admin reset): every session is revoked and a
     * mandatory PASSWORD_CHANGED notification is queued. Joins the caller's transaction.
     */
    @Transactional
    public void passwordChanged(User user, String reason) {
        logoutEverywhere(user.getId());
        events.publishEvent(new AuthEvents.PasswordChanged(user.getId(), user.getOrganizationId(), clock.instant(), reason));
    }

    @Transactional(readOnly = true)
    public UserInfo me(Long userId) {
        return userRepository.findById(userId).map(AuthService::toUserInfo)
                .orElseThrow(() -> new NotFoundException("User", userId));
    }

    private void ensureMayAuthenticate(User user) {
        if (!user.isActive()) {
            throw new UnauthorizedException("ACCOUNT_INACTIVE", "This account has been deactivated");
        }
        if (isOrganizationSuspended(user)) {
            throw new UnauthorizedException("ORGANIZATION_SUSPENDED", "The organization of this account is suspended");
        }
    }

    private boolean isOrganizationSuspended(User user) {
        return user.getOrganizationId() != null && organizationRepository.findById(user.getOrganizationId())
                .map(org -> org.getStatus() == OrganizationStatus.SUSPENDED)
                .orElse(true);
    }

    private TokenResponse issueTokens(User user, UUID familyId, Instant now) {
        RotatedTokens refresh = createRefreshToken(user, familyId, now);
        JwtTokenService.IssuedToken access = jwtTokenService.issueAccessToken(user);
        return new TokenResponse("Bearer", access.value(), access.expiresAt(), refresh.rawToken(),
                refresh.entity().getExpiresAt(), toUserInfo(user));
    }

    private RotatedTokens createRefreshToken(User user, UUID familyId, Instant now) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken entity = refreshTokenRepository.save(new RefreshToken(user.getId(), Hashing.sha256Hex(raw), familyId,
                now, now.plus(jwtProperties.refreshTokenTtl()), RequestContext.clientIp(), RequestContext.userAgent()));
        return new RotatedTokens(raw, entity);
    }

    private static UserInfo toUserInfo(User user) {
        return new UserInfo(user.getId(), user.getEmail(), user.getFullName(), user.getRoleName(), user.getOrganizationId());
    }

    private static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("INVALID_CREDENTIALS", "Invalid email or password");
    }

    private static UnauthorizedException invalidRefreshToken() {
        return new UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid, expired or revoked");
    }

    private record RotatedTokens(String rawToken, RefreshToken entity) {
    }
}
