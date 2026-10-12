package com.pgms.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A one-time code for setting a new password. Only a SHA-256 hash of the code is stored; a code is valid until
 * {@code expiresAt}, for at most a configured number of wrong guesses, and can be used once. Only a user's most
 * recent code counts, so requesting a new one silently retires the previous one.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    /** Delivery channel recorded for support/audit; the database allows EMAIL and SMS. */
    public enum Channel {
        EMAIL,
        SMS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private AuthEvents.OtpPurpose purpose;

    @Column(name = "code_hash", nullable = false, updatable = false)
    private String codeHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by_ip", updatable = false)
    private String createdByIp;

    protected PasswordResetToken() {
    }

    PasswordResetToken(Long userId, Channel channel, AuthEvents.OtpPurpose purpose, String codeHash, Instant createdAt,
                       Instant expiresAt, String createdByIp) {
        this.userId = userId;
        this.channel = channel;
        this.purpose = purpose;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.createdByIp = createdByIp;
    }

    boolean isUsable(Instant now, int maxAttempts) {
        return consumedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
    }

    void recordFailedAttempt() {
        attempts++;
    }

    void consume(Instant at) {
        consumedAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public AuthEvents.OtpPurpose getPurpose() {
        return purpose;
    }

    public int getAttempts() {
        return attempts;
    }
}
