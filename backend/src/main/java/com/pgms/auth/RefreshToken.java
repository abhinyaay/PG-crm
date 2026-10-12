package com.pgms.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of an opaque refresh token. Only its SHA-256 hash is stored, so a database leak does not
 * yield usable tokens. Tokens rotate on every use; all tokens of one login share a {@code familyId}, which lets us
 * detect replay of an already-rotated token and kill the whole session.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason")
    private String revokedReason;

    @Column(name = "replaced_by_token_id")
    private Long replacedByTokenId;

    @Column(name = "created_by_ip")
    private String createdByIp;

    @Column(name = "user_agent")
    private String userAgent;

    protected RefreshToken() {
    }

    public RefreshToken(Long userId, String tokenHash, UUID familyId, Instant createdAt, Instant expiresAt,
                        String createdByIp, String userAgent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.createdByIp = createdByIp;
        this.userAgent = userAgent;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void revoke(Instant at, String reason) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokedReason = reason;
        }
    }

    public void rotateTo(RefreshToken successor, Instant at) {
        revoke(at, "ROTATED");
        this.replacedByTokenId = successor.getId();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }
}
