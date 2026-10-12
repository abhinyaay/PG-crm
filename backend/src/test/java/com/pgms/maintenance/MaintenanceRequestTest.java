package com.pgms.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.domain.Priority;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MaintenanceRequestTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T05:00:00Z");

    private MaintenanceRequest ticket(Priority priority) {
        return new MaintenanceRequest("MNT-1", 1L, 1L, null, null, 9L, MaintenanceCategory.PLUMBING, priority, "Leak", "Tap",
                CREATED);
    }

    @ParameterizedTest
    @EnumSource(Priority.class)
    void slaDeadlineDerivesFromPriority(Priority priority) {
        assertThat(ticket(priority).getSlaDueAt()).isEqualTo(CREATED.plus(priority.resolutionTarget()));
    }

    @Test
    void happyPathLifecycle() {
        MaintenanceRequest t = ticket(Priority.HIGH);
        t.assignTo(5L, CREATED.plusSeconds(60));
        t.transitionTo(MaintenanceStatus.IN_PROGRESS, null, CREATED.plusSeconds(120));
        t.transitionTo(MaintenanceStatus.RESOLVED, "Replaced washer", CREATED.plus(Duration.ofHours(3)));
        t.transitionTo(MaintenanceStatus.CLOSED, null, CREATED.plus(Duration.ofHours(4)));
        assertThat(t.getStatus()).isEqualTo(MaintenanceStatus.CLOSED);
        assertThat(t.isSlaBreached()).isFalse();
    }

    @Test
    void resolvingAfterDeadlineFlagsBreach() {
        MaintenanceRequest t = ticket(Priority.CRITICAL);
        t.assignTo(5L, CREATED);
        t.transitionTo(MaintenanceStatus.IN_PROGRESS, null, CREATED);
        t.transitionTo(MaintenanceStatus.RESOLVED, "Fixed", CREATED.plus(Duration.ofHours(5)));
        assertThat(t.isSlaBreached()).isTrue();
    }

    @Test
    void resolutionNotesAreMandatory() {
        MaintenanceRequest t = ticket(Priority.LOW);
        t.assignTo(5L, CREATED);
        t.transitionTo(MaintenanceStatus.IN_PROGRESS, null, CREATED);
        assertThatThrownBy(() -> t.transitionTo(MaintenanceStatus.RESOLVED, " ", CREATED))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void illegalTransitionsAreRejected() {
        MaintenanceRequest t = ticket(Priority.LOW);
        assertThatThrownBy(() -> t.transitionTo(MaintenanceStatus.RESOLVED, "x", CREATED))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.transitionTo(MaintenanceStatus.CLOSED, null, CREATED))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void slaMonitorFlagsOnlyOnceAndOnlyActiveTickets() {
        MaintenanceRequest t = ticket(Priority.HIGH);
        Instant late = CREATED.plus(Duration.ofHours(25));
        assertThat(t.markSlaBreachedIfOverdue(CREATED.plusSeconds(10))).isFalse();
        assertThat(t.markSlaBreachedIfOverdue(late)).isTrue();
        assertThat(t.markSlaBreachedIfOverdue(late)).isFalse();
    }

    @Test
    void terminalStatesAllowNothing() {
        assertThat(MaintenanceStatus.CLOSED.allowedNext()).isEmpty();
        assertThat(MaintenanceStatus.CANCELLED.allowedNext()).isEmpty();
    }
}
