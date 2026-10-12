package com.pgms.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Row lock so two concurrent refreshes with the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshToken> findByHashForUpdate(@Param("hash") String hash);

    Optional<RefreshToken> findByTokenHash(String hash);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :at, t.revokedReason = :reason
            where t.familyId = :familyId and t.revokedAt is null
            """)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("at") Instant at, @Param("reason") String reason);

    @Modifying
    @Query("""
            update RefreshToken t set t.revokedAt = :at, t.revokedReason = :reason
            where t.userId = :userId and t.revokedAt is null
            """)
    int revokeAllForUser(@Param("userId") Long userId, @Param("at") Instant at, @Param("reason") String reason);

    /**
     * Housekeeping. A row still referenced as the successor of a not-yet-deletable token stays (FK
     * {@code replaced_by_token_id}); that only happens if the refresh TTL was shortened and resolves on a later run.
     */
    @Modifying
    @Query("""
            delete from RefreshToken t where t.expiresAt < :cutoff
              and not exists (select 1 from RefreshToken r where r.replacedByTokenId = t.id and r.expiresAt >= :cutoff)
            """)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
