package com.pgms.booking;

import java.time.Clock;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Releases beds whose reservation hold has lapsed. Each booking is expired in its own short transaction. */
@Component
public class ReservationExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryJob.class);
    private static final int BATCH = 200;

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final Clock clock;

    public ReservationExpiryJob(BookingRepository bookingRepository, BookingService bookingService, Clock clock) {
        this.bookingRepository = bookingRepository;
        this.bookingService = bookingService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.jobs.reservation-expiry-interval:PT5M}")
    @SchedulerLock(name = "reservationExpiry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void expireReservations() {
        List<Long> due = bookingRepository.findExpiredReservationIds(clock.instant(), PageRequest.of(0, BATCH));
        int expired = 0;
        for (Long id : due) {
            try {
                if (bookingService.expireIfDue(id)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Failed to expire booking {}", id, e);
            }
        }
        if (!due.isEmpty()) {
            log.info("Reservation expiry: candidates={} expired={}", due.size(), expired);
        }
    }
}
