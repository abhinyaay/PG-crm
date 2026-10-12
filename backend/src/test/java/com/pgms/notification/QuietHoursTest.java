package com.pgms.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuietHoursTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final QuietHours quietHours = new QuietHours(true, LocalTime.of(21, 0), LocalTime.of(8, 0), IST);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        // IST = UTC+05:30
        "2026-10-13T15:29:00Z, 2026-10-13T15:29:00Z",   // 20:59 IST: still allowed
        "2026-10-13T15:30:00Z, 2026-10-14T02:30:00Z",   // 21:00 IST -> next day 08:00 IST
        "2026-10-13T18:30:00Z, 2026-10-14T02:30:00Z",   // 00:00 IST -> same morning 08:00
        "2026-10-14T02:29:59Z, 2026-10-14T02:30:00Z",   // 07:59:59 IST -> 08:00
        "2026-10-14T02:30:00Z, 2026-10-14T02:30:00Z",   // 08:00 IST: window over
        "2026-10-14T06:30:00Z, 2026-10-14T06:30:00Z"    // midday
    })
    void nonUrgentSmsWaitsForTheMorning(Instant candidate, Instant expected) {
        assertThat(quietHours.defer(candidate)).isEqualTo(expected);
    }

    @Test
    void sameDayWindowAndDisabledWindow() {
        QuietHours lunch = new QuietHours(true, LocalTime.of(13, 0), LocalTime.of(14, 0), ZoneId.of("UTC"));
        assertThat(lunch.defer(Instant.parse("2026-10-13T13:30:00Z"))).isEqualTo(Instant.parse("2026-10-13T14:00:00Z"));
        assertThat(lunch.defer(Instant.parse("2026-10-13T12:59:00Z"))).isEqualTo(Instant.parse("2026-10-13T12:59:00Z"));

        Instant night = Instant.parse("2026-10-13T18:30:00Z");
        assertThat(new QuietHours(false, LocalTime.of(21, 0), LocalTime.of(8, 0), IST).defer(night)).isEqualTo(night);
        assertThat(new QuietHours(true, LocalTime.of(8, 0), LocalTime.of(8, 0), IST).defer(night)).isEqualTo(night);
    }
}
