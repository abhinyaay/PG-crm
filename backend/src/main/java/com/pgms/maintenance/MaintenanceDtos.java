package com.pgms.maintenance;

import com.pgms.common.domain.Priority;
import com.pgms.common.lookup.DisplayNames;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class MaintenanceDtos {

    private MaintenanceDtos() {
    }

    /** propertyId/roomId are required for staff; for tenants they are derived from the active allocation. */
    public record CreateMaintenanceRequest(
            Long propertyId,
            Long roomId,
            @NotNull MaintenanceCategory category,
            @NotNull Priority priority,
            @NotBlank @Size(max = 150) String title,
            @NotBlank @Size(max = 4000) String description) {
    }

    public record AssignRequest(@NotNull Long assigneeUserId) {
    }

    public record StatusChangeRequest(@NotNull MaintenanceStatus status, @Size(max = 4000) String notes) {
    }

    public record MaintenanceResponse(Long id, String ticketNumber, Long propertyId, Long roomId, Long tenantId,
                                      Long raisedByUserId, MaintenanceCategory category, Priority priority, String title,
                                      String description, MaintenanceStatus status, Long assignedToUserId, Instant assignedAt,
                                      Instant startedAt, Instant resolvedAt, Instant closedAt, String resolutionNotes,
                                      Instant slaDueAt, boolean slaBreached, Instant createdAt, String tenantName,
                                      String raisedByName, String assignedToName) {
        static MaintenanceResponse of(MaintenanceRequest m, DisplayNames.Index names) {
            return new MaintenanceResponse(m.getId(), m.getTicketNumber(), m.getPropertyId(), m.getRoomId(), m.getTenantId(),
                    m.getRaisedByUserId(), m.getCategory(), m.getPriority(), m.getTitle(), m.getDescription(), m.getStatus(),
                    m.getAssignedToUserId(), m.getAssignedAt(), m.getStartedAt(), m.getResolvedAt(), m.getClosedAt(),
                    m.getResolutionNotes(), m.getSlaDueAt(), m.isSlaBreached(), m.getCreatedAt(), names.tenant(m.getTenantId()),
                    names.user(m.getRaisedByUserId()), names.user(m.getAssignedToUserId()));
        }
    }

    public record HistoryEntry(MaintenanceStatus fromStatus, MaintenanceStatus toStatus, Long changedBy, String note,
                               Instant changedAt) {
        static HistoryEntry of(MaintenanceStatusHistory h) {
            return new HistoryEntry(h.getFromStatus(), h.getToStatus(), h.getChangedBy(), h.getNote(), h.getChangedAt());
        }
    }
}
