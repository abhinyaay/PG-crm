package com.pgms.maintenance;

import com.pgms.common.domain.Priority;

public final class MaintenanceEvents {

    private MaintenanceEvents() {
    }

    public record StatusChanged(Long requestId, String ticketNumber, Long organizationId, Long propertyId, Long tenantId,
                                Long raisedByUserId, Long assigneeUserId, MaintenanceStatus from, MaintenanceStatus to,
                                Priority priority) {
    }

    public record SlaBreached(Long requestId, String ticketNumber, Long organizationId, Long propertyId, Priority priority) {
    }
}
