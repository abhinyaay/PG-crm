package com.pgms.maintenance;

import java.util.EnumSet;
import java.util.Set;

/** Ticket lifecycle with explicit allowed transitions (anything else is rejected). */
public enum MaintenanceStatus {
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    CLOSED,
    CANCELLED;

    public Set<MaintenanceStatus> allowedNext() {
        return switch (this) {
            case OPEN -> EnumSet.of(ASSIGNED, CANCELLED);
            case ASSIGNED -> EnumSet.of(IN_PROGRESS, OPEN, CANCELLED);
            case IN_PROGRESS -> EnumSet.of(RESOLVED);
            case RESOLVED -> EnumSet.of(CLOSED, IN_PROGRESS);
            case CLOSED, CANCELLED -> EnumSet.noneOf(MaintenanceStatus.class);
        };
    }

    public boolean canMoveTo(MaintenanceStatus next) {
        return allowedNext().contains(next);
    }

    /** Statuses in which the SLA clock is still running. */
    public boolean isActive() {
        return this == OPEN || this == ASSIGNED || this == IN_PROGRESS;
    }
}
