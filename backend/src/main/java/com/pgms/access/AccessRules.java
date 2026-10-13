package com.pgms.access;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** The side-effect-free rules of the module (spec "Rules" section), kept apart so they can be unit tested. */
public final class AccessRules {

    /** ZKTeco ATTLOG punch state meaning "check-out"; everything else counts as an entry (rule 5). */
    static final int PUNCH_STATE_CHECK_OUT = 1;
    /** ZKTeco verify modes. */
    static final int VERIFY_CARD = 2;
    static final int VERIFY_CARD_ALT = 4;
    static final int VERIFY_FACE = 15;

    private AccessRules() {
    }

    /** {@code [start, end)} of the business day (e.g. Asia/Kolkata midnight to midnight) containing {@code at}. */
    public record Day(LocalDate date, Instant start, Instant end) {
    }

    public static Day businessDay(Instant at, ZoneId zone) {
        LocalDate date = at.atZone(zone).toLocalDate();
        return new Day(date, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /**
     * A terminal mounted for one direction always records that direction; a {@code BOTH} terminal uses the
     * direction sent by a bridge, else the ATTLOG punch state (0 = check-in, 1 = check-out, others = entry).
     */
    public static AccessDirection resolveDirection(DeviceDirection device, AccessDirection requested, Integer punchState) {
        return switch (device) {
            case ENTRY -> AccessDirection.ENTRY;
            case EXIT -> AccessDirection.EXIT;
            case BOTH -> requested != null ? requested : fromPunchState(punchState);
        };
    }

    public static AccessDirection fromPunchState(Integer punchState) {
        return punchState != null && punchState == PUNCH_STATE_CHECK_OUT ? AccessDirection.EXIT : AccessDirection.ENTRY;
    }

    /** ZKTeco verify mode → method: 15 face, 2/4 card, anything else (1 = fingerprint) fingerprint. */
    public static AccessMethod fromVerifyMode(Integer verify) {
        if (verify == null) {
            return AccessMethod.FINGERPRINT;
        }
        return switch (verify) {
            case VERIFY_FACE -> AccessMethod.FACE;
            case VERIFY_CARD, VERIFY_CARD_ALT -> AccessMethod.CARD;
            default -> AccessMethod.FINGERPRINT;
        };
    }

    /**
     * Daily entry limit of a resident: the personal override when one exists (whose {@code null} value means
     * unlimited), else the property default (also {@code null} = unlimited).
     */
    public static Integer effectiveLimit(boolean hasOverride, Integer overrideValue, Integer propertyDefault) {
        return hasOverride ? overrideValue : propertyDefault;
    }

    /** True when {@code entriesSoFar} allowed entries already use up the limit, so one more must be denied. */
    public static boolean limitReached(long entriesSoFar, Integer limit) {
        return limit != null && entriesSoFar >= limit;
    }

    /** Entries still available today; {@code null} = unlimited. */
    public static Integer remaining(long entriesSoFar, Integer limit) {
        return limit == null ? null : (int) Math.max(0, limit - entriesSoFar);
    }

    /** Rule 4: whether an exit produced by {@code method} counts under the property's exit mode. */
    public static AccessResult exitResult(ExitMode mode, AccessMethod method) {
        if (method.isDevice() && !mode.allowsDevice()) {
            return AccessResult.IGNORED_EXIT_MODE;
        }
        if (method == AccessMethod.BUTTON && !mode.allowsButton()) {
            return AccessResult.IGNORED_EXIT_MODE;
        }
        return AccessResult.ALLOWED;
    }
}
