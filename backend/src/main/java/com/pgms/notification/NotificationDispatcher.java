package com.pgms.notification;

import com.pgms.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers pending outbox rows to external channels with retry/backoff, in three steps so that no transaction or row
 * lock is held during network calls:
 * <ol>
 *   <li><b>Claim</b> (short transaction): select due rows with {@code FOR UPDATE SKIP LOCKED} and lease them by moving
 *       {@code next_attempt_at} forward by {@code app.notifications.dispatch-lease}; commit. Other replicas skip the
 *       locked rows during the claim and the leased rows afterwards, so every replica can dispatch in parallel
 *       without ShedLock.</li>
 *   <li><b>Send</b> each row outside any transaction.</li>
 *   <li><b>Record</b> each outcome in its own short transaction: one failing provider or row never rolls back the
 *       others.</li>
 * </ol>
 * Delivery is <b>at-least-once</b>: a crash (or an expired lease) between sending and recording means the row is
 * claimed again and re-sent. Rows whose lease is about to run out are not sent but left for the next claim, which
 * keeps duplicates to genuine crashes. Permanent provider errors fail a row at once; transient ones are retried with
 * exponential backoff (non-urgent SMS retries also respect quiet hours).
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final Map<NotificationChannel, NotificationSender> senders = new EnumMap<>(NotificationChannel.class);
    private final AppProperties.Notifications config;
    private final NotificationPolicy policy;
    private final QuietHours quietHours;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationDispatcher(NotificationRepository repository, List<NotificationSender> senders,
                                  AppProperties properties, NotificationPolicy policy, QuietHours quietHours,
                                  PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        senders.forEach(s -> this.senders.put(s.channel(), s));
        this.config = properties.notifications();
        this.policy = policy;
        this.quietHours = quietHours;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.notification-dispatch-interval:PT30S}")
    public void dispatchDue() {
        int processed = dispatchBatch();
        if (processed > 0) {
            log.debug("Dispatched {} notifications", processed);
        }
    }

    /** One claim-send-record round; returns how many rows were attempted. */
    public int dispatchBatch() {
        Instant claimedAt = clock.instant();
        Duration lease = config.dispatchLease();
        Instant leaseUntil = claimedAt.plus(lease);
        List<Notification> batch = transactions.execute(status -> {
            List<Notification> due = repository.claimDueBatch(claimedAt, config.dispatchBatchSize());
            due.forEach(n -> n.lease(leaseUntil));
            return due;
        });
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        // stop sending while a safe margin of the lease remains, so another replica never sends the same row
        Instant sendDeadline = leaseUntil.minus(lease.dividedBy(5));
        int attempted = 0;
        for (Notification notification : batch) {
            if (clock.instant().isAfter(sendDeadline)) {
                log.warn("Lease nearly expired; {} claimed notifications left for the next round", batch.size() - attempted);
                break;
            }
            Outcome outcome = send(notification);
            attempted++;
            try {
                transactions.executeWithoutResult(status -> record(notification.getId(), outcome));
            } catch (RuntimeException e) {
                // the lease expires and the row is retried: at-least-once
                log.error("Could not record delivery outcome of notification {}", notification.getId(), e);
            }
        }
        return attempted;
    }

    private record Outcome(boolean sent, boolean permanent, String error) {
    }

    private Outcome send(Notification notification) {
        NotificationSender sender = senders.get(notification.getChannel());
        if (sender == null) {
            return new Outcome(false, true, "No provider configured for channel " + notification.getChannel());
        }
        try {
            sender.send(notification);
            return new Outcome(true, false, null);
        } catch (DeliveryException e) {
            log.warn("Delivery failed for notification {} (attempt {}, {}): {}", notification.getId(),
                    notification.getAttempts() + 1, e.isPermanent() ? "permanent" : "will retry", e.getMessage());
            return new Outcome(false, e.isPermanent(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Delivery failed for notification {} (attempt {}): {}", notification.getId(),
                    notification.getAttempts() + 1, e.getMessage());
            return new Outcome(false, false, e.getMessage());
        }
    }

    private void record(Long id, Outcome outcome) {
        Notification notification = repository.findById(id).orElse(null);
        if (notification == null || !notification.isPending()) {
            return;
        }
        Instant now = clock.instant();
        if (outcome.sent()) {
            notification.markSent(now);
        } else if (outcome.permanent()) {
            notification.markFailedPermanently(outcome.error());
        } else {
            UnaryOperator<Instant> schedule = notification.getChannel() == NotificationChannel.SMS && !notification.isUrgent()
                    ? quietHours::defer : UnaryOperator.identity();
            notification.markFailedAttempt(outcome.error(), now, config.maxAttempts(), schedule);
        }
        if (!notification.isPending() && policy.isSensitive(notification.getType())) {
            notification.redactContent();
        }
    }
}
