package com.pgms.complaint;

/** Domain events of the complaint module (not to be confused with the persisted {@link ComplaintActivity} timeline). */
public final class ComplaintEvents {

    private ComplaintEvents() {
    }

    public record ComplaintUpdated(Long complaintId, String complaintNumber, Long organizationId, Long propertyId,
                                   Long raisedByUserId, Long assigneeUserId, ComplaintActivityType activity,
                                   ComplaintStatus status, boolean visibleToTenant) {
    }

    public record ComplaintEscalated(Long complaintId, String complaintNumber, Long organizationId, Long propertyId,
                                     int escalationLevel, boolean automatic) {
    }
}
