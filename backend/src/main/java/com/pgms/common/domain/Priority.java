package com.pgms.common.domain;

import java.time.Duration;

/** Shared by maintenance and complaints. Resolution targets are deliberately simple and documented. */
public enum Priority {
    LOW(Duration.ofDays(7)),
    MEDIUM(Duration.ofDays(3)),
    HIGH(Duration.ofHours(24)),
    CRITICAL(Duration.ofHours(4));

    private final Duration resolutionTarget;

    Priority(Duration resolutionTarget) {
        this.resolutionTarget = resolutionTarget;
    }

    public Duration resolutionTarget() {
        return resolutionTarget;
    }
}
