package com.pgms.notification;

import static com.pgms.notification.Audience.RESIDENT;
import static com.pgms.notification.Audience.STAFF;
import static com.pgms.notification.ChannelRule.MANDATORY;
import static com.pgms.notification.ChannelRule.NONE;
import static com.pgms.notification.ChannelRule.OFF;
import static com.pgms.notification.ChannelRule.ON;
import static com.pgms.notification.ChannelRule.ON_IF_IMPORTANT;
import static com.pgms.notification.NotificationType.BOOKING_CONFIRMED;
import static com.pgms.notification.NotificationType.BOOKING_RELEASED;
import static com.pgms.notification.NotificationType.COMPLAINT_ESCALATED;
import static com.pgms.notification.NotificationType.COMPLAINT_UPDATE;
import static com.pgms.notification.NotificationType.INVOICE_ISSUED;
import static com.pgms.notification.NotificationType.MAINTENANCE_ASSIGNED;
import static com.pgms.notification.NotificationType.MAINTENANCE_CREATED;
import static com.pgms.notification.NotificationType.MAINTENANCE_SLA_BREACH;
import static com.pgms.notification.NotificationType.MAINTENANCE_UPDATE;
import static com.pgms.notification.NotificationType.MOVE_IN;
import static com.pgms.notification.NotificationType.MOVE_OUT;
import static com.pgms.notification.NotificationType.PASSWORD_CHANGED;
import static com.pgms.notification.NotificationType.PASSWORD_RESET_OTP;
import static com.pgms.notification.NotificationType.PAYMENT_DUE;
import static com.pgms.notification.NotificationType.PAYMENT_OVERDUE;
import static com.pgms.notification.NotificationType.PAYMENT_RECEIVED;
import static com.pgms.notification.NotificationType.PORTAL_ACCOUNT_CREATED;
import static com.pgms.notification.NotificationType.REFUND_ISSUED;
import static com.pgms.notification.NotificationType.ROOM_TRANSFER;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The notification matrix (docs/FEATURE_ROADMAP.md section 3) in one place: which channels each notification type uses
 * by default for each audience, which are mandatory, and how a user preference / organization setting is applied.
 *
 * <p>Resolution for one channel ({@link #shouldSend}): not offered → never; mandatory → always; organization switched
 * it off → skip; the user's explicit preference → use it; otherwise the matrix default. Whether an address exists and
 * whether it bounced / opted out is checked afterwards by {@link NotificationService}.
 */
@Component
public class NotificationPolicy {

    /** Channels that preferences and settings can address. PUSH has no sender yet. */
    public static final List<NotificationChannel> CHANNELS =
            List.of(NotificationChannel.IN_APP, NotificationChannel.EMAIL, NotificationChannel.SMS);

    /** Used when a type is sent to an audience the matrix has no row for: in-app only, never a paid channel. */
    static final Rule FALLBACK = new Rule(ON, NONE, NONE);

    /** Bypass SMS quiet hours: the user is waiting for these right now. */
    private static final Set<NotificationType> URGENT = EnumSet.of(PORTAL_ACCOUNT_CREATED, PASSWORD_RESET_OTP, PASSWORD_CHANGED);

    /** Carry a one-time code: content is redacted from the outbox once delivery is settled. */
    private static final Set<NotificationType> SENSITIVE = EnumSet.of(PORTAL_ACCOUNT_CREATED, PASSWORD_RESET_OTP);

    private static final Map<Audience, Map<NotificationType, Rule>> MATRIX = new EnumMap<>(Audience.class);

    static {
        //   type                     audience   IN_APP     EMAIL              SMS
        row(BOOKING_CONFIRMED,        RESIDENT,  ON,        ON,                ON);
        row(BOOKING_RELEASED,         RESIDENT,  ON,        ON,                ON);
        row(MOVE_IN,                  RESIDENT,  ON,        ON,                ON);
        row(MOVE_IN,                  STAFF,     ON,        OFF,               NONE);
        row(MOVE_OUT,                 RESIDENT,  ON,        ON,                OFF);
        row(MOVE_OUT,                 STAFF,     ON,        OFF,               NONE);
        row(ROOM_TRANSFER,            RESIDENT,  ON,        ON,                OFF);
        // SMS only for rent invoices (important), not for deposits
        row(INVOICE_ISSUED,           RESIDENT,  ON,        ON,                ON_IF_IMPORTANT);
        row(PAYMENT_DUE,              RESIDENT,  ON,        ON,                ON);
        row(PAYMENT_OVERDUE,          RESIDENT,  ON,        ON,                ON);
        row(PAYMENT_RECEIVED,         RESIDENT,  MANDATORY, ON,                ON);
        row(REFUND_ISSUED,            RESIDENT,  MANDATORY, ON,                ON);
        row(MAINTENANCE_CREATED,      STAFF,     ON,        OFF,               NONE);
        // SMS by default for HIGH/CRITICAL tickets only
        row(MAINTENANCE_ASSIGNED,     STAFF,     ON,        OFF,               ON_IF_IMPORTANT);
        // email by default when the ticket is RESOLVED
        row(MAINTENANCE_UPDATE,       RESIDENT,  ON,        ON_IF_IMPORTANT,   OFF);
        row(MAINTENANCE_UPDATE,       STAFF,     ON,        ON_IF_IMPORTANT,   OFF);
        // SMS (and quiet-hours bypass) only for CRITICAL tickets
        row(MAINTENANCE_SLA_BREACH,   STAFF,     ON,        ON,                ON_IF_IMPORTANT);
        // email to the resident when RESOLVED; assignees and managers in-app
        row(COMPLAINT_UPDATE,         RESIDENT,  ON,        ON_IF_IMPORTANT,   OFF);
        row(COMPLAINT_UPDATE,         STAFF,     ON,        OFF,               OFF);
        // SMS from escalation level 2
        row(COMPLAINT_ESCALATED,      STAFF,     ON,        ON,                ON_IF_IMPORTANT);
        row(PORTAL_ACCOUNT_CREATED,   RESIDENT,  NONE,      MANDATORY,         MANDATORY);
        row(PORTAL_ACCOUNT_CREATED,   STAFF,     NONE,      MANDATORY,         MANDATORY);
        // the caller restricts the message to the channel of the identifier the user typed
        row(PASSWORD_RESET_OTP,       RESIDENT,  NONE,      MANDATORY,         MANDATORY);
        row(PASSWORD_RESET_OTP,       STAFF,     NONE,      MANDATORY,         MANDATORY);
        row(PASSWORD_CHANGED,         RESIDENT,  ON,        MANDATORY,         OFF);
        row(PASSWORD_CHANGED,         STAFF,     ON,        MANDATORY,         OFF);
    }

    /** The three channel cells of one matrix row. */
    public record Rule(ChannelRule inApp, ChannelRule email, ChannelRule sms) {

        public ChannelRule of(NotificationChannel channel) {
            return switch (channel) {
                case IN_APP -> inApp;
                case EMAIL -> email;
                case SMS -> sms;
                case PUSH -> NONE;
            };
        }
    }

    /** One preference-addressable cell. */
    public record TypeChannel(NotificationType type, NotificationChannel channel) {
    }

    private static void row(NotificationType type, Audience audience, ChannelRule inApp, ChannelRule email, ChannelRule sms) {
        MATRIX.computeIfAbsent(audience, a -> new EnumMap<>(NotificationType.class)).put(type, new Rule(inApp, email, sms));
    }

    public Rule rule(NotificationType type, Audience audience) {
        return MATRIX.getOrDefault(audience, Map.of()).getOrDefault(type, FALLBACK);
    }

    public ChannelRule channelRule(NotificationType type, Audience audience, NotificationChannel channel) {
        return rule(type, audience).of(channel);
    }

    /**
     * Decides whether {@code channel} is used. {@code organizationEnabled} / {@code userPreference} are {@code null}
     * when no explicit row exists.
     */
    public boolean shouldSend(NotificationType type, Audience audience, NotificationChannel channel, boolean important,
                              Boolean organizationEnabled, Boolean userPreference) {
        ChannelRule rule = channelRule(type, audience, channel);
        if (rule == NONE) {
            return false;
        }
        if (rule == MANDATORY) {
            return true;
        }
        if (Boolean.FALSE.equals(organizationEnabled)) {
            return false;
        }
        if (userPreference != null) {
            return userPreference;
        }
        return rule == ON || (rule == ON_IF_IMPORTANT && important);
    }

    /** The default shown in the preferences screen when the user has not chosen (important = false). */
    public boolean defaultEnabled(NotificationType type, Audience audience, NotificationChannel channel) {
        ChannelRule rule = channelRule(type, audience, channel);
        return rule == ON || rule == MANDATORY;
    }

    /** Cells a user of this audience can see and (unless mandatory) change, in a stable order. */
    public List<TypeChannel> offered(Audience audience) {
        List<TypeChannel> cells = new ArrayList<>();
        MATRIX.getOrDefault(audience, Map.of()).forEach((type, rule) -> CHANNELS.forEach(channel -> {
            if (rule.of(channel) != NONE) {
                cells.add(new TypeChannel(type, channel));
            }
        }));
        return cells;
    }

    /** Cells an organization can switch: offered to at least one audience. */
    public List<TypeChannel> offeredToAnyAudience() {
        Set<TypeChannel> seen = new LinkedHashSet<>();
        for (NotificationType type : NotificationType.values()) {
            for (NotificationChannel channel : CHANNELS) {
                for (Audience audience : Audience.values()) {
                    if (MATRIX.getOrDefault(audience, Map.of()).containsKey(type) && channelRule(type, audience, channel) != NONE) {
                        seen.add(new TypeChannel(type, channel));
                    }
                }
            }
        }
        return List.copyOf(seen);
    }

    public boolean isMandatory(NotificationType type, Audience audience, NotificationChannel channel) {
        return channelRule(type, audience, channel) == MANDATORY;
    }

    /** Mandatory for some audience: organizations cannot switch such a cell off. */
    public boolean isMandatoryForAnyAudience(NotificationType type, NotificationChannel channel) {
        for (Audience audience : Audience.values()) {
            if (MATRIX.getOrDefault(audience, Map.of()).containsKey(type) && isMandatory(type, audience, channel)) {
                return true;
            }
        }
        return false;
    }

    public boolean isUrgent(NotificationType type) {
        return URGENT.contains(type);
    }

    public boolean isSensitive(NotificationType type) {
        return SENSITIVE.contains(type);
    }
}
