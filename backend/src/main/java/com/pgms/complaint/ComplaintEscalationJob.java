package com.pgms.complaint;

import java.time.Clock;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Escalates complaints that missed their resolution deadline (one level per missed window, max level 3). */
@Component
public class ComplaintEscalationJob {

    private static final Logger log = LoggerFactory.getLogger(ComplaintEscalationJob.class);

    private final ComplaintRepository repository;
    private final ComplaintService service;
    private final Clock clock;

    public ComplaintEscalationJob(ComplaintRepository repository, ComplaintService service, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.escalation-interval:PT15M}")
    @SchedulerLock(name = "complaintEscalation", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void escalateOverdue() {
        List<Long> ids = repository.findIdsDueForEscalation(clock.instant(), Complaint.MAX_ESCALATION_LEVEL);
        int escalated = 0;
        for (Long id : ids) {
            try {
                if (service.autoEscalateIfOverdue(id)) {
                    escalated++;
                }
            } catch (RuntimeException e) {
                log.error("Auto-escalation failed for complaint {}", id, e);
            }
        }
        if (escalated > 0) {
            log.warn("Auto-escalated {} overdue complaint(s)", escalated);
        }
    }
}
