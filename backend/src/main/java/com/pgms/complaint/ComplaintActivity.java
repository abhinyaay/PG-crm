package com.pgms.complaint;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Immutable timeline entry of a complaint (table complaint_events). */
@Entity
@Table(name = "complaint_events")
public class ComplaintActivity {

    /** Column size of {@code details}; a resolution may be longer (the complaint keeps it in full as TEXT). */
    static final int DETAILS_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "complaint_id", nullable = false)
    private Long complaintId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private ComplaintActivityType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private ComplaintStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private ComplaintStatus toStatus;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(length = DETAILS_MAX_LENGTH)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ComplaintActivity() {
    }

    public ComplaintActivity(Long complaintId, ComplaintActivityType type, ComplaintStatus fromStatus, ComplaintStatus toStatus,
                             Long actorUserId, String details, Instant createdAt) {
        this.complaintId = complaintId;
        this.type = type;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUserId = actorUserId;
        this.details = details == null || details.length() <= DETAILS_MAX_LENGTH ? details
                : details.substring(0, DETAILS_MAX_LENGTH - 3) + "...";
        this.createdAt = createdAt;
    }

    public ComplaintActivityType getType() {
        return type;
    }

    public ComplaintStatus getFromStatus() {
        return fromStatus;
    }

    public ComplaintStatus getToStatus() {
        return toStatus;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
