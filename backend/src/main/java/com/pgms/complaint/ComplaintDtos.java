package com.pgms.complaint;

import com.pgms.common.domain.Priority;
import com.pgms.common.lookup.DisplayNames;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ComplaintDtos {

    private ComplaintDtos() {
    }

    public record CreateComplaintRequest(
            Long propertyId,
            @NotNull ComplaintCategory category,
            @NotNull Priority priority,
            @NotBlank @Size(max = 150) String subject,
            @NotBlank @Size(max = 4000) String description) {
    }

    public record AssignComplaintRequest(@NotNull Long assigneeUserId) {
    }

    public record ComplaintStatusRequest(@NotNull ComplaintStatus status, @Size(max = 4000) String resolution) {
    }

    public record EscalateRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record CommentRequest(@NotBlank @Size(max = 4000) String body, boolean internal) {
    }

    public record ComplaintResponse(Long id, String complaintNumber, Long propertyId, Long tenantId, Long raisedByUserId,
                                    ComplaintCategory category, Priority priority, String subject, String description,
                                    ComplaintStatus status, Long assignedToUserId, int escalationLevel, Instant escalatedAt,
                                    Instant dueAt, String resolution, Instant resolvedAt, Instant closedAt, Instant createdAt,
                                    String tenantName, String raisedByName, String assignedToName) {
        static ComplaintResponse of(Complaint c, DisplayNames.Index names) {
            return new ComplaintResponse(c.getId(), c.getComplaintNumber(), c.getPropertyId(), c.getTenantId(),
                    c.getRaisedByUserId(), c.getCategory(), c.getPriority(), c.getSubject(), c.getDescription(), c.getStatus(),
                    c.getAssignedToUserId(), c.getEscalationLevel(), c.getEscalatedAt(), c.getDueAt(), c.getResolution(),
                    c.getResolvedAt(), c.getClosedAt(), c.getCreatedAt(), names.tenant(c.getTenantId()),
                    names.user(c.getRaisedByUserId()), names.user(c.getAssignedToUserId()));
        }
    }

    public record CommentResponse(Long id, Long authorUserId, String body, boolean internal, Instant createdAt) {
        static CommentResponse of(ComplaintComment c) {
            return new CommentResponse(c.getId(), c.getAuthorUserId(), c.getBody(), c.isInternal(), c.getCreatedAt());
        }
    }

    public record ActivityResponse(ComplaintActivityType type, ComplaintStatus fromStatus, ComplaintStatus toStatus,
                                   Long actorUserId, String details, Instant createdAt) {
        static ActivityResponse of(ComplaintActivity a) {
            return new ActivityResponse(a.getType(), a.getFromStatus(), a.getToStatus(), a.getActorUserId(), a.getDetails(),
                    a.getCreatedAt());
        }
    }
}
