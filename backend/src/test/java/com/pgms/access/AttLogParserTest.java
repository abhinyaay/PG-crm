package com.pgms.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class AttLogParserTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void parsesTabSeparatedPunchesInTheDeviceZone() {
        String body = "1\t2026-10-13 08:15:30\t0\t1\t0\t0\t0\n"
                + "27\t2026-10-13 21:05:00\t1\t15\t0\t0\t0\r\n";

        AttLogParser.Result result = AttLogParser.parse(body, IST, 100);

        assertThat(result.skipped()).isZero();
        assertThat(result.punches()).hasSize(2);
        AttLogParser.Punch first = result.punches().get(0);
        assertThat(first.pin()).isEqualTo("1");
        assertThat(first.localTime()).isEqualTo(LocalDateTime.of(2026, 10, 13, 8, 15, 30));
        // 08:15:30 IST = 02:45:30 UTC
        assertThat(first.occurredAt()).isEqualTo(Instant.parse("2026-10-13T02:45:30Z"));
        assertThat(first.status()).isZero();
        assertThat(first.verify()).isEqualTo(1);
        assertThat(result.punches().get(1).status()).isEqualTo(1);
        assertThat(result.punches().get(1).verify()).isEqualTo(15);
    }

    @Test
    void sameLocalTimeMeansADifferentInstantForADeviceOnUtc() {
        AttLogParser.Punch punch = AttLogParser.parse("5\t2026-10-13 08:00:00\t0\t1", ZoneId.of("UTC"), 10).punches().get(0);
        assertThat(punch.occurredAt()).isEqualTo(Instant.parse("2026-10-13T08:00:00Z"));
    }

    @Test
    void malformedLinesAreSkippedAndTheRestIsKept() {
        String body = String.join("\n",
                "1\t2026-10-13 08:00:00\t0\t1",
                "garbage",
                "\t2026-10-13 08:00:00\t0\t1",          // missing PIN
                "2\t2026-13-45 99:00:00\t0\t1",          // impossible date
                "3\t2026-10-13 08:00:00\tx\t1",          // non-numeric status
                "bad pin!\t2026-10-13 08:00:00\t0\t1",   // invalid PIN characters
                "",
                "4\t2026-10-13 09:00:00");               // status/verify optional

        AttLogParser.Result result = AttLogParser.parse(body, IST, 100);

        assertThat(result.punches()).extracting(AttLogParser.Punch::pin).containsExactly("1", "4");
        assertThat(result.skipped()).isEqualTo(5);
        assertThat(result.errors()).hasSize(5);
        assertThat(result.punches().get(1).status()).isNull();
        assertThat(result.punches().get(1).verify()).isNull();
    }

    @Test
    void spaceSeparatedLinesFromOlderFirmwareAreAccepted() {
        AttLogParser.Punch punch = AttLogParser.parse("12 2026-10-13 07:00:00 1 1", IST, 10).punches().get(0);
        assertThat(punch.pin()).isEqualTo("12");
        assertThat(punch.localTime()).isEqualTo(LocalDateTime.of(2026, 10, 13, 7, 0));
        assertThat(punch.status()).isEqualTo(1);
    }

    @Test
    void emptyBodyAndLineCap() {
        assertThat(AttLogParser.parse("", IST, 10).punches()).isEmpty();
        assertThat(AttLogParser.parse(null, IST, 10).skipped()).isZero();

        AttLogParser.Result capped = AttLogParser.parse("1\t2026-10-13 08:00:00\n2\t2026-10-13 08:00:01\n3\t2026-10-13 08:00:02",
                IST, 2);
        assertThat(capped.punches()).hasSize(2);
        assertThat(capped.skipped()).isEqualTo(1);
    }
}
