package com.pgms.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One message on one channel. Rows are inserted inside the business transaction that caused them (transactional
 * outbox), so a notification exists if and only if the change committed. External channels are delivered later by
 * {@link NotificationDispatcher}; IN_APP rows are "delivered" the moment they are stored.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "recipient_address")
    private String recipientAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationType type;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "reference_type")
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Urgent messages ignore SMS quiet hours (also when a retry is scheduled). */
    @Column(nullable = false)
    private boolean urgent;

    /** Template variables for SMS / extras for email. Cleared once delivery of a sensitive type is settled. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_params", columnDefinition = "jsonb")
    private Map<String, String> params;

    protected Notification() {
    }

    /** {@code firstAttemptAt} is later than {@code now} when quiet hours defer the first delivery attempt. */
    Notification(Long organizationId, Long recipientUserId, String recipientAddress, NotificationChannel channel,
                 NotificationMessage message, Instant now, Instant firstAttemptAt, boolean urgent) {
        this.organizationId = organizationId;
        this.recipientUserId = recipientUserId;
        this.recipientAddress = recipientAddress;
        this.channel = channel;
        this.type = message.type();
        this.title = message.title();
        this.body = message.body();
        this.referenceType = message.referenceType();
        this.referenceId = message.referenceId();
        this.dedupKey = message.dedupKey();
        this.createdAt = now;
        this.urgent = urgent;
        if (channel == NotificationChannel.IN_APP) {
            this.status = NotificationStatus.SENT;
            this.sentAt = now;
        } else {
            this.status = NotificationStatus.PENDING;
            this.nextAttemptAt = firstAttemptAt;
            this.params = message.params().isEmpty() ? null : Map.copyOf(message.params());
        }
    }

    /**
     * Hides a claimed row from other dispatchers until {@code until}. If the sender crashes before recording the
     * outcome the lease simply runs out and the row is retried (at-least-once delivery).
     */
    void lease(Instant until) {
        nextAttemptAt = until;
    }

    void markSent(Instant at) {
        status = NotificationStatus.SENT;
        sentAt = at;
        attempts++;
        lastError = null;
        nextAttemptAt = null;
    }

    /**
     * Exponential backoff (1, 2, 4, 8 ... minutes); gives up after {@code maxAttempts}. {@code schedule} may move the
     * retry time (quiet hours for non-urgent SMS).
     */
    void markFailedAttempt(String error, Instant at, int maxAttempts, UnaryOperator<Instant> schedule) {
        attempts++;
        lastError = truncate(error);
        if (attempts >= maxAttempts) {
            status = NotificationStatus.FAILED;
            nextAttemptAt = null;
        } else {
            nextAttemptAt = schedule.apply(at.plus(Duration.ofMinutes(1L << Math.min(attempts - 1, 20))));
        }
    }

    /** Retrying cannot help (rejected recipient, missing template, provider 4xx). */
    void markFailedPermanently(String error) {
        attempts++;
        lastError = truncate(error);
        status = NotificationStatus.FAILED;
        nextAttemptAt = null;
    }

    /** Drops one-time codes from the outbox once they are no longer needed for delivery. */
    void redactContent() {
        body = "[redacted]";
        params = null;
    }

    public boolean isPending() {
        return status == NotificationStatus.PENDING;
    }

    private static String truncate(String error) {
        return error == null ? "unknown error" : error.substring(0, Math.min(error.length(), 500));
    }

    void markRead(Instant at) {
        if (readAt == null) {
            readAt = at;
        }
    }

    public Long getId() {
        return id;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public String getRecipientAddress() {
        return recipientAddress;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public Long getReferenceId() {
        return referenceId;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isUrgent() {
        return urgent;
    }

    public Map<String, String> getParams() {
        return params == null ? Map.of() : params;
    }
}
