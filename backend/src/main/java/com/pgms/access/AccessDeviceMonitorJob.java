package com.pgms.access;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alerts managers when an ACTIVE device of a property with the module switched on has been silent for longer than
 * the property's {@code device_offline_minutes}. One alert per outage: {@code offline_alerted_at} is claimed
 * atomically and cleared by the next heartbeat. Each device is handled in its own transaction.
 */
@Component
public class AccessDeviceMonitorJob {

    private static final Logger log = LoggerFactory.getLogger(AccessDeviceMonitorJob.class);

    private final AccessDeviceRepository deviceRepository;
    private final AccessEventProcessor processor;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AccessDeviceMonitorJob(AccessDeviceRepository deviceRepository, AccessEventProcessor processor,
                                  ApplicationEventPublisher events, TransactionTemplate transactions, Clock clock) {
        this.deviceRepository = deviceRepository;
        this.processor = processor;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.access-device-check-interval:PT5M}", initialDelayString = "PT1M")
    @SchedulerLock(name = "accessDeviceOffline", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public int detectOfflineDevices() {
        Instant now = clock.instant();
        List<Long> ids = deviceRepository.findOfflineCandidateIds(now);
        int alerted = 0;
        for (Long id : ids) {
            try {
                Boolean sent = transactions.execute(status -> alert(id, now));
                if (Boolean.TRUE.equals(sent)) {
                    alerted++;
                }
            } catch (RuntimeException e) {
                log.error("Offline alert failed for access device {}", id, e);
            }
        }
        if (alerted > 0) {
            log.warn("{} access device(s) went offline", alerted);
        }
        return alerted;
    }

    private boolean alert(Long id, Instant now) {
        if (deviceRepository.markOfflineAlerted(id, now) == 0) {
            return false;
        }
        AccessDevice device = deviceRepository.findById(id).orElse(null);
        if (device == null) {
            return false;
        }
        int minutes = processor.settingsFor(device.getPropertyId()).getDeviceOfflineMinutes();
        events.publishEvent(new AccessEvents.DeviceOffline(device.getId(), device.getOrganizationId(), device.getPropertyId(),
                device.getName(), device.getSerialNumber(), device.getLastSeenAt(), minutes));
        return true;
    }
}
