package com.pgms.complaint;

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
@Table(name = "complaints")
public class Complaint extends AuditableEntity {

    public static final int MAX_ESCALATION_LEVEL = 3;

    @Column(name = "complaint_number", nullable = false, updatable = false)
    private String complaintNumber;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(name = "property_id", nullable = false, updatable = false)
    private Long propertyId;

    @Column(name = "tenant_id", updatable = false)
    private Long tenantId;

    @Column(name = "raised_by_user_id", nullable = false, updatable = false)
    private Long raisedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplaintStatus status = ComplaintStatus.OPEN;

    @Column(name = "assigned_to_user_id")
    private Long assignedToUserId;

    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    private String resolution;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected Complaint() {
    }

    public Complaint(String complaintNumber, Long organizationId, Long propertyId, Long tenantId, Long raisedByUserId,
                     ComplaintCategory category, Priority priority, String subject, String description, Instant createdAt) {
        this.complaintNumber = complaintNumber;
        this.organizationId = organizationId;
        this.propertyId = propertyId;
        this.tenantId = tenantId;
        this.raisedByUserId = raisedByUserId;
        this.category = category;
        this.priority = priority;
        this.subject = subject;
        this.description = description;
        this.dueAt = createdAt.plus(priority.resolutionTarget());
    }

    public void assignTo(Long userId) {
        if (!status.isActive()) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", "Cannot assign a complaint that is " + status);
        }
        assignedToUserId = userId;
    }

    public ComplaintStatus transitionTo(ComplaintStatus next, String resolutionText, Instant at) {
        if (next == ComplaintStatus.ESCALATED) {
            throw new BusinessRuleException("USE_ESCALATE", "Use the escalate operation to escalate");
        }
        if (!status.allowedNext().contains(next)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", "Cannot move complaint from " + status + " to " + next);
        }
        ComplaintStatus previous = status;
        switch (next) {
            case RESOLVED -> {
                if (resolutionText == null || resolutionText.isBlank()) {
                    throw new BusinessRuleException("RESOLUTION_REQUIRED", "A resolution summary is required");
                }
                resolution = resolutionText;
                resolvedAt = at;
            }
            case CLOSED -> closedAt = at;
            case OPEN -> {
                // reopened by the tenant: a fresh resolution window starts
                resolvedAt = null;
                dueAt = at.plus(priority.resolutionTarget());
            }
            default -> {
                // IN_PROGRESS: no extra state
            }
        }
        status = next;
        return previous;
    }

    /** Raises the escalation level and gives the next level a fresh resolution window. */
    public ComplaintStatus escalate(Instant at) {
        if (!status.isActive()) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", "Only open complaints can be escalated");
        }
        if (escalationLevel >= MAX_ESCALATION_LEVEL) {
            throw new BusinessRuleException("MAX_ESCALATION_REACHED", "Complaint is already at the highest escalation level");
        }
        ComplaintStatus previous = status;
        escalationLevel++;
        escalatedAt = at;
        dueAt = at.plus(priority.resolutionTarget());
        status = ComplaintStatus.ESCALATED;
        return previous;
    }

    public boolean isOverdue(Instant now) {
        return status.isActive() && now.isAfter(dueAt);
    }

    public String getComplaintNumber() {
        return complaintNumber;
    }

    public Long getOrganizationId() {
        return organizationId;
    }

    public Long getPropertyId() {
        return propertyId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Long getRaisedByUserId() {
        return raisedByUserId;
    }

    public ComplaintCategory getCategory() {
        return category;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public ComplaintStatus getStatus() {
        return status;
    }

    public Long getAssignedToUserId() {
        return assignedToUserId;
    }

    public int getEscalationLevel() {
        return escalationLevel;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public String getResolution() {
        return resolution;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
