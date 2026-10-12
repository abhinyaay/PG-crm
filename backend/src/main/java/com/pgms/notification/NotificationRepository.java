package com.pgms.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserIdAndChannel(Long userId, NotificationChannel channel, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndChannelAndReadAtIsNull(Long userId, NotificationChannel channel,
                                                                      Pageable pageable);

    long countByRecipientUserIdAndChannelAndReadAtIsNull(Long userId, NotificationChannel channel);

    Optional<Notification> findByIdAndRecipientUserId(Long id, Long userId);

    boolean existsByDedupKeyAndChannel(String dedupKey, NotificationChannel channel);

    @Modifying
    @Query("""
            update Notification n set n.readAt = :at
            where n.recipientUserId = :userId and n.channel = com.pgms.notification.NotificationChannel.IN_APP
              and n.readAt is null
            """)
    int markAllRead(@Param("userId") Long userId, @Param("at") Instant at);

    /**
     * Claims a batch of due outbox rows. {@code FOR UPDATE SKIP LOCKED} lets several dispatcher instances work the
     * same table concurrently without blocking each other or double-sending.
     */
    @Query(value = """
            SELECT * FROM notifications
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Notification> claimDueBatch(@Param("now") Instant now, @Param("limit") int limit);
}
