package com.pgms.notification;

import com.pgms.config.AppProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SMS quiet hours (default 21:00-08:00 in the business time zone, i.e. IST). A non-urgent SMS whose delivery time
 * falls inside the window is pushed to the end of the window; the outbox row simply gets a later
 * {@code next_attempt_at}, so nothing else in the dispatcher changes.
 */
@Component
public class QuietHours {

    private final boolean enabled;
    private final LocalTime start;
    private final LocalTime end;
    private final ZoneId zone;

    @Autowired
    public QuietHours(AppProperties properties) {
        this(properties.notifications().quietHours().enabled(), properties.notifications().quietHours().start(),
                properties.notifications().quietHours().end(), properties.businessTimezone());
    }

    public QuietHours(boolean enabled, LocalTime start, LocalTime end, ZoneId zone) {
        this.enabled = enabled && !start.equals(end);
        this.start = start;
        this.end = end;
        this.zone = zone;
    }

    /** {@code candidate} itself when it is outside the window, otherwise the instant the window ends. */
    public Instant defer(Instant candidate) {
        if (!enabled) {
            return candidate;
        }
        ZonedDateTime local = candidate.atZone(zone);
        LocalTime time = local.toLocalTime();
        boolean overnight = start.isAfter(end);
        boolean quiet = overnight
                ? !time.isBefore(start) || time.isBefore(end)
                : !time.isBefore(start) && time.isBefore(end);
        if (!quiet) {
            return candidate;
        }
        LocalDate day = local.toLocalDate();
        if (overnight && !time.isBefore(start)) {
            day = day.plusDays(1);
        }
        return ZonedDateTime.of(day, end, zone).toInstant();
    }
}
