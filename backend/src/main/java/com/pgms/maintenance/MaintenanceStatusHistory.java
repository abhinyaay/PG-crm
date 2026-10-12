package com.pgms.maintenance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "maintenance_status_history")
public class MaintenanceStatusHistory {

    /** Column size of {@code note}; the full resolution text lives on the ticket (TEXT), this is only the timeline copy. */
    static final int NOTE_MAX_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private MaintenanceStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private MaintenanceStatus toStatus;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(length = NOTE_MAX_LENGTH)
    private String note;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected MaintenanceStatusHistory() {
    }

    public MaintenanceStatusHistory(Long requestId, MaintenanceStatus fromStatus, MaintenanceStatus toStatus, Long changedBy,
                                    String note, Instant changedAt) {
        this.requestId = requestId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.changedBy = changedBy;
        this.note = note == null || note.length() <= NOTE_MAX_LENGTH ? note : note.substring(0, NOTE_MAX_LENGTH - 3) + "...";
        this.changedAt = changedAt;
    }

    public Long getId() {
        return id;
    }

    public MaintenanceStatus getFromStatus() {
        return fromStatus;
    }

    public MaintenanceStatus getToStatus() {
        return toStatus;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public String getNote() {
        return note;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
