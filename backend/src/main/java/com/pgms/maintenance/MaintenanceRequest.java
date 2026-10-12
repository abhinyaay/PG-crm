package com.pgms.maintenance;

import com.pgms.common.domain.AuditableEntity;
import com.pgms.common.domain.Priority;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "maintenance_requests")
public class MaintenanceRequest extends AuditableEntity {

    @Column(name = "ticket_number", nullable = false, updatable = false)
    private String ticketNumber;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "room_id")
    private Long roomId;

    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    @Column(name = "raised_by_user_id", nullable = false, updatable = false)
    private Long raisedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenanceCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MaintenanceStatus status = MaintenanceStatus.OPEN;

    @Column(name = "assigned_to_user_id")
    private Long assignedToUserId;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "resolution_notes")
    private String resolutionNotes;

    @Column(name = "sla_due_at", nullable = false)
    private Instant slaDueAt;

    @Column(name = "sla_breached", nullable = false)
    private boolean slaBreached;

    protected MaintenanceRequest() {
    }

    public MaintenanceRequest(String ticketNumber, Long organizationId, Long propertyId, Long roomId, Long tenantId,
                              Long raisedByUserId, MaintenanceCategory category, Priority priority, String title,
                              String description, Instant createdAt) {
        this.ticketNumber = ticketNumber;
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.roomId = roomId;
        this.tenantId = tenantId;
        this.raisedByUserId = raisedByUserId;
        this.category = category;
        this.priority = priority;
        this.title = title;
        this.description = description;
        this.slaDueAt = createdAt.plus(priority.resolutionTarget());
    }

    /** Returns the previous status so callers can record history. */
    public MaintenanceStatus assignTo(Long userId, Instant at) {
        if (status != MaintenanceStatus.OPEN && status != MaintenanceStatus.ASSIGNED) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", "Cannot (re)assign a ticket that is " + status);
        }
        MaintenanceStatus previous = status;
        assignedToUserId = userId;
        assignedAt = at;
        status = MaintenanceStatus.ASSIGNED;
        return previous;
    }

    public MaintenanceStatus transitionTo(MaintenanceStatus next, String notes, Instant at) {
        if (!status.canMoveTo(next)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", "Cannot move ticket from " + status + " to " + next
                    + " (allowed: " + status.allowedNext() + ")");
        }
        MaintenanceStatus previous = status;
        switch (next) {
            case IN_PROGRESS -> {
                if (previous == MaintenanceStatus.RESOLVED) {
                    resolvedAt = null; // reopened: work continues
                } else {
                    startedAt = at;
                }
            }
            case RESOLVED -> {
                if (notes == null || notes.isBlank()) {
                    throw new BusinessRuleException("RESOLUTION_NOTES_REQUIRED", "Resolution notes are required");
                }
                resolutionNotes = notes;
                resolvedAt = at;
                if (at.isAfter(slaDueAt)) {
                    slaBreached = true;
                }
            }
            case CLOSED, CANCELLED -> closedAt = at;
            case OPEN -> {
                assignedToUserId = null;
                assignedAt = null;
            }
            default -> {
                // ASSIGNED goes through assignTo()
            }
        }
        status = next;
        return previous;
    }

    public boolean markSlaBreachedIfOverdue(Instant now) {
        if (!slaBreached && status.isActive() && now.isAfter(slaDueAt)) {
            slaBreached = true;
            return true;
        }
        return false;
    }

    public String getTicketNumber() {
        return ticketNumber;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getRoomId() {
        return roomId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getRaisedByUserId() {
        return raisedByUserId;
    }

    public MaintenanceCategory getCategory() {
        return category;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public MaintenanceStatus getStatus() {
        return status;
    }

    public Long getAssignedToUserId() {
        return assignedToUserId;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public String getResolutionNotes() {
        return resolutionNotes;
    }

    public Instant getSlaDueAt() {
        return slaDueAt;
    }

    public boolean isSlaBreached() {
        return slaBreached;
    }
}
