package com.pgms.maintenance;

import java.time.Clock;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceSlaJob {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceSlaJob.class);

    private final MaintenanceRepository repository;
    private final MaintenanceService service;
    private final Clock clock;

    public MaintenanceSlaJob(MaintenanceRepository repository, MaintenanceService service, Clock clock) {
        this.repository = repository;
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.sla-check-interval:PT10M}")
    @SchedulerLock(name = "maintenanceSlaCheck", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void flagBreaches() {
        List<Long> ids = repository.findIdsBreachingSla(clock.instant());
        int flagged = 0;
        for (Long id : ids) {
            try {
                if (service.flagSlaBreach(id)) {
                    flagged++;
                }
            } catch (RuntimeException e) {
                log.error("SLA check failed for maintenance request {}", id, e);
            }
        }
        if (flagged > 0) {
            log.warn("Maintenance SLA breached for {} request(s)", flagged);
        }
    }
}
