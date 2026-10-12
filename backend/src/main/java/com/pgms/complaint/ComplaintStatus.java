package com.pgms.complaint;

import java.util.EnumSet;
import java.util.Set;

public enum ComplaintStatus {
    OPEN,
    IN_PROGRESS,
    ESCALATED,
    RESOLVED,
    CLOSED;

    public Set<ComplaintStatus> allowedNext() {
        return switch (this) {
            case OPEN -> EnumSet.of(IN_PROGRESS, ESCALATED, RESOLVED, CLOSED);
            case IN_PROGRESS -> EnumSet.of(ESCALATED, RESOLVED);
            case ESCALATED -> EnumSet.of(IN_PROGRESS, RESOLVED);
            case RESOLVED -> EnumSet.of(CLOSED, OPEN);
            case CLOSED -> EnumSet.noneOf(ComplaintStatus.class);
        };
    }

    public boolean isActive() {
        return this == OPEN || this == IN_PROGRESS || this == ESCALATED;
    }
}
