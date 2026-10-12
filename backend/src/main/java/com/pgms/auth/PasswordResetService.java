package com.pgms.auth;

import com.pgms.audit.AuditService;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.exception.TooManyRequestsException;
import com.pgms.common.util.Hashing;
import com.pgms.common.util.PhoneNumbers;
import com.pgms.common.web.RequestContext;
import com.pgms.config.AppProperties;
import com.pgms.security.RedisRateLimiter;
import com.pgms.user.User;
import com.pgms.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service password reset with 6-digit one-time codes (and the "set your password" code of new portal accounts).
 *
 * <ul>
 *   <li>No account enumeration: "forgot" behaves identically whether or not the identifier exists, and "reset" gives
 *       the same {@code INVALID_OR_EXPIRED_CODE} for an unknown identifier, a wrong, expired, used or locked code.</li>
 *   <li>Codes: SHA-256 stored, short TTL, at most {@code maxAttempts} wrong guesses, single use, newest code only.</li>
 *   <li>Throttled per client IP and per identifier (Redis, fail-open); identifiers are hashed in Redis keys.</li>
 *   <li>A successful reset revokes every session, exactly like "log out everywhere".</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;
    private final RedisRateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final AuditService auditService;
    private final AppProperties.PasswordReset config;
    private final String defaultCountryCode;
    private final Clock clock;

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder, AuthService authService, RedisRateLimiter rateLimiter,
                                ApplicationEventPublisher events, AuditService auditService, AppProperties properties,
                                Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.auditService = auditService;
        this.config = properties.security().passwordReset();
        this.defaultCountryCode = properties.notifications().sms().defaultCountryCode();
        this.clock = clock;
    }

    /** What the user typed, normalised: an email address or an E.164 phone number (or neither). */
    record Identifier(String normalized, boolean email) {

        static Identifier parse(String raw, String defaultCountryCode) {
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.contains("@")) {
                return new Identifier(User.normalizeEmail(trimmed), true);
            }
            return new Identifier(PhoneNumbers.toE164(trimmed, defaultCountryCode).orElse(null), false);
        }

        /** Rate-limit subject: hashed so Redis never holds emails or phone numbers. */
        String throttleKey(String raw) {
            return Hashing.sha256Hex(normalized != null ? normalized : raw.trim().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * "Forgot password": if exactly one active account matches, a code is sent to the channel of the identifier
     * (email → EMAIL, phone → SMS). The caller always answers 202.
     */
    @Transactional
    public void requestReset(String rawIdentifier) {
        Identifier identifier = Identifier.parse(rawIdentifier, defaultCountryCode);
        throttle("pwd-forgot-ip", Hashing.sha256Hex(String.valueOf(RequestContext.clientIp())), config.requestsPerIp());
        throttle("pwd-forgot-id", identifier.throttleKey(rawIdentifier), config.requestsPerIdentifier());
        Optional<User> user = find(identifier);
        if (user.isEmpty()) {
            log.info("Password reset requested for an identifier without a (unique) active account");
            return;
        }
        issue(user.get(), AuthEvents.OtpPurpose.RESET,
                identifier.email() ? AuthEvents.OtpDelivery.EMAIL : AuthEvents.OtpDelivery.SMS, config.codeTtl());
    }

    /** First password of a portal account created without one: a code by email and SMS, valid longer. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issueSetupCode(User user) {
        issue(user, AuthEvents.OtpPurpose.SETUP, AuthEvents.OtpDelivery.EMAIL_AND_SMS, config.setupCodeTtl());
    }

    /**
     * Sets a new password with a code. A wrong guess is counted even though the request fails, hence
     * {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = BadRequestException.class)
    public void resetPassword(String rawIdentifier, String code, String newPassword) {
        throttle("pwd-reset-ip", Hashing.sha256Hex(String.valueOf(RequestContext.clientIp())), config.verificationsPerIp());
        Identifier identifier = Identifier.parse(rawIdentifier, defaultCountryCode);
        User user = find(identifier).orElseThrow(PasswordResetService::invalidCode);
        Instant now = clock.instant();
        PasswordResetToken token = tokenRepository.findFirstByUserIdOrderByCreatedAtDescIdDesc(user.getId())
                .filter(t -> t.isUsable(now, config.maxAttempts()))
                .orElseThrow(PasswordResetService::invalidCode);
        if (!matches(token.getCodeHash(), hash(user.getId(), code))) {
            token.recordFailedAttempt();
            log.warn("Wrong password reset code for userId={} (attempt {})", user.getId(), token.getAttempts());
            throw invalidCode();
        }
        token.consume(now);
        user.changePassword(passwordEncoder.encode(newPassword), now);
        authService.passwordChanged(user, token.getPurpose() == AuthEvents.OtpPurpose.SETUP ? "SETUP" : "RESET");
        auditService.record(user.getOrganizationId(), "PASSWORD_RESET_WITH_CODE", "USER", user.getId(),
                Map.of("purpose", token.getPurpose().name()));
    }

    private void issue(User user, AuthEvents.OtpPurpose purpose, AuthEvents.OtpDelivery delivery, Duration validity) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant now = clock.instant();
        PasswordResetToken.Channel channel = delivery == AuthEvents.OtpDelivery.SMS
                ? PasswordResetToken.Channel.SMS : PasswordResetToken.Channel.EMAIL;
        tokenRepository.save(new PasswordResetToken(user.getId(), channel, purpose, hash(user.getId(), code), now,
                now.plus(validity), RequestContext.clientIp()));
        events.publishEvent(new AuthEvents.OtpIssued(user.getId(), user.getOrganizationId(), purpose, delivery, code, validity));
        auditService.record(user.getOrganizationId(), "PASSWORD_CODE_ISSUED", "USER", user.getId(),
                Map.of("purpose", purpose.name(), "delivery", delivery.name()));
    }

    /**
     * Exactly one active account, or none: an email is unique; a phone is matched on {@code users.phone} first and
     * then on the resident profile ({@code tenants.phone} via {@code tenants.user_id}). A phone shared by several
     * accounts is ambiguous and matches nothing - those users reset by email.
     */
    private Optional<User> find(Identifier identifier) {
        if (identifier.normalized() == null) {
            return Optional.empty();
        }
        if (identifier.email()) {
            return userRepository.findByEmail(identifier.normalized()).filter(User::isActive);
        }
        Set<String> forms = PhoneNumbers.storedForms(identifier.normalized(), defaultCountryCode);
        List<User> matches = userRepository.findActiveByPhoneIn(forms);
        if (matches.isEmpty()) {
            matches = userRepository.findActiveByTenantPhoneIn(forms);
        }
        if (matches.size() > 1) {
            log.warn("Password reset by phone matched {} accounts; ignored", matches.size());
        }
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private void throttle(String bucket, String subject, int limit) {
        if (!config.rateLimitEnabled()) {
            return;
        }
        RedisRateLimiter.Decision decision = rateLimiter.hit(bucket, subject, limit, config.window());
        if (!decision.allowed()) {
            throw new TooManyRequestsException("Too many attempts. Try again in " + decision.retryAfterSeconds() + "s");
        }
    }

    private static String hash(Long userId, String code) {
        return Hashing.sha256Hex(userId + ":" + code);
    }

    private static boolean matches(String expectedHash, String actualHash) {
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.US_ASCII), actualHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static BadRequestException invalidCode() {
        return new BadRequestException("INVALID_OR_EXPIRED_CODE", "The code is invalid or has expired");
    }
}
