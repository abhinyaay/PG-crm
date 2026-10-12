package com.pgms.complaint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.common.domain.Priority;
import com.pgms.common.exception.BusinessRuleException;
import com.pgms.common.exception.ConflictException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ComplaintTest {

    private static final Instant CREATED = Instant.parse("2026-10-01T05:00:00Z");

    private Complaint complaint() {
        return new Complaint("CMP-1", 1L, 1L, 3L, 9L, ComplaintCategory.NOISE, Priority.MEDIUM, "Noise", "Loud music", CREATED);
    }

    @Test
    void escalationRaisesLevelAndRestartsTheClock() {
        Complaint c = complaint();
        Instant at = CREATED.plus(Duration.ofDays(4));
        assertThat(c.isOverdue(at)).isTrue();
        c.escalate(at);
        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.ESCALATED);
        assertThat(c.getEscalationLevel()).isEqualTo(1);
        assertThat(c.getDueAt()).isEqualTo(at.plus(Priority.MEDIUM.resolutionTarget()));
        assertThat(c.isOverdue(at.plusSeconds(1))).isFalse();
    }

    @Test
    void escalationIsCappedAtTheMaximumLevel() {
        Complaint c = complaint();
        for (int i = 0; i < Complaint.MAX_ESCALATION_LEVEL; i++) {
            c.escalate(CREATED);
        }
        assertThatThrownBy(() -> c.escalate(CREATED)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void reopeningAResolvedComplaint() {
        Complaint c = complaint();
        c.transitionTo(ComplaintStatus.IN_PROGRESS, null, CREATED);
        c.transitionTo(ComplaintStatus.RESOLVED, "Spoke to neighbour", CREATED.plusSeconds(60));
        c.transitionTo(ComplaintStatus.OPEN, null, CREATED.plusSeconds(120));
        assertThat(c.getStatus()).isEqualTo(ComplaintStatus.OPEN);
        assertThat(c.getResolvedAt()).isNull();
    }

    @Test
    void closedComplaintsCannotBeEscalatedOrChanged() {
        Complaint c = complaint();
        c.transitionTo(ComplaintStatus.CLOSED, null, CREATED);
        assertThatThrownBy(() -> c.escalate(CREATED)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> c.transitionTo(ComplaintStatus.OPEN, null, CREATED)).isInstanceOf(ConflictException.class);
    }
}
