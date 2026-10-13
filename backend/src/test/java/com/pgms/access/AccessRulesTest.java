package com.pgms.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccessRulesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Test
    void businessDayRunsFromIstMidnightToIstMidnight() {
        // 18:29:59Z is 23:59:59 IST on the 13th; 18:30:00Z is already the 14th in India
        AccessRules.Day late = AccessRules.businessDay(Instant.parse("2026-10-13T18:29:59Z"), IST);
        AccessRules.Day next = AccessRules.businessDay(Instant.parse("2026-10-13T18:30:00Z"), IST);

        assertThat(late.date()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(late.start()).isEqualTo(Instant.parse("2026-10-12T18:30:00Z"));
        assertThat(late.end()).isEqualTo(Instant.parse("2026-10-13T18:30:00Z"));
        assertThat(next.date()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(next.start()).isEqualTo(late.end());
    }

    @Test
    void earlyMorningUtcIsStillTheSameIstDay() {
        // 00:30Z = 06:00 IST, the UTC date and the IST date agree; 20:00Z the previous UTC day = 01:30 IST next day
        assertThat(AccessRules.businessDay(Instant.parse("2026-10-13T00:30:00Z"), IST).date()).isEqualTo(LocalDate.of(2026, 10, 13));
        assertThat(AccessRules.businessDay(Instant.parse("2026-10-12T20:00:00Z"), IST).date()).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @ParameterizedTest(name = "device {0}, requested {1}, punch state {2} -> {3}")
    @CsvSource(nullValues = "null", value = {
        "ENTRY, null,  1,    ENTRY",
        "ENTRY, EXIT,  1,    ENTRY",
        "EXIT,  null,  0,    EXIT",
        "EXIT,  ENTRY, null, EXIT",
        "BOTH,  null,  0,    ENTRY",
        "BOTH,  null,  1,    EXIT",
        "BOTH,  null,  2,    ENTRY",
        "BOTH,  null,  5,    ENTRY",
        "BOTH,  null,  null, ENTRY",
        "BOTH,  EXIT,  0,    EXIT"
    })
    void directionComesFromTheMountingOrThePunchState(DeviceDirection device, AccessDirection requested, Integer state,
                                                      AccessDirection expected) {
        assertThat(AccessRules.resolveDirection(device, requested, state)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"1, FINGERPRINT", "15, FACE", "2, CARD", "4, CARD", "0, FINGERPRINT",
        "null, FINGERPRINT"})
    void verifyModeMapsToMethod(Integer verify, AccessMethod expected) {
        assertThat(AccessRules.fromVerifyMode(verify)).isEqualTo(expected);
    }

    @Test
    void personalOverrideBeatsPropertyDefaultAndNullMeansUnlimited() {
        assertThat(AccessRules.effectiveLimit(false, null, 3)).isEqualTo(3);
        assertThat(AccessRules.effectiveLimit(true, 5, 3)).isEqualTo(5);
        assertThat(AccessRules.effectiveLimit(true, null, 3)).isNull();
        assertThat(AccessRules.effectiveLimit(false, null, null)).isNull();
    }

    @Test
    void limitCounting() {
        assertThat(AccessRules.limitReached(1, 2)).isFalse();
        assertThat(AccessRules.limitReached(2, 2)).isTrue();
        assertThat(AccessRules.limitReached(3, 2)).isTrue();
        assertThat(AccessRules.limitReached(1000, null)).isFalse();
        assertThat(AccessRules.remaining(1, 3)).isEqualTo(2);
        assertThat(AccessRules.remaining(5, 3)).isZero();
        assertThat(AccessRules.remaining(5, null)).isNull();
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @CsvSource({
        "DEVICE,           FINGERPRINT, ALLOWED",
        "DEVICE,           BUTTON,      IGNORED_EXIT_MODE",
        "BUTTON,           FINGERPRINT, IGNORED_EXIT_MODE",
        "BUTTON,           FACE,        IGNORED_EXIT_MODE",
        "BUTTON,           BUTTON,      ALLOWED",
        "DEVICE_OR_BUTTON, CARD,        ALLOWED",
        "DEVICE_OR_BUTTON, BUTTON,      ALLOWED"
    })
    void exitModeRules(ExitMode mode, AccessMethod method, AccessResult expected) {
        assertThat(AccessRules.exitResult(mode, method)).isEqualTo(expected);
    }

    @Test
    void exitModeFlags() {
        assertThat(ExitMode.DEVICE.allowsButton()).isFalse();
        assertThat(ExitMode.BUTTON.allowsDevice()).isFalse();
        assertThat(ExitMode.DEVICE_OR_BUTTON.allowsButton()).isTrue();
        assertThat(ExitMode.DEVICE_OR_BUTTON.allowsDevice()).isTrue();
    }

    @Test
    void bridgeTimestampsAcceptInstantsOffsetsAndLocalDeviceTime() {
        assertThat(DeviceIngestionService.parseTime("2026-10-13T02:45:30Z", IST)).isEqualTo(Instant.parse("2026-10-13T02:45:30Z"));
        assertThat(DeviceIngestionService.parseTime("2026-10-13T08:15:30+05:30", IST))
                .isEqualTo(Instant.parse("2026-10-13T02:45:30Z"));
        assertThat(DeviceIngestionService.parseTime("2026-10-13 08:15:30", IST)).isEqualTo(Instant.parse("2026-10-13T02:45:30Z"));
        // sub-second precision is dropped so re-sent punches hit the idempotency key
        assertThat(DeviceIngestionService.parseTime("2026-10-13T02:45:30.987Z", IST))
                .isEqualTo(Instant.parse("2026-10-13T02:45:30Z"));
    }
}
