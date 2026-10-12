package com.pgms.notification;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Channel-independent content of a notification.
 * <ul>
 *   <li>{@code dedupKey} (optional) makes creation idempotent per channel and recipient.</li>
 *   <li>{@code params} are the variables of the fixed SMS template (and extras such as a one-time code for email).</li>
 *   <li>{@code important} switches on the conditional matrix cells ({@link ChannelRule#ON_IF_IMPORTANT}).</li>
 *   <li>{@code urgent} bypasses SMS quiet hours.</li>
 *   <li>{@code channels} (optional) restricts the channels considered, e.g. a reset code goes only to the channel the
 *       user asked for.</li>
 * </ul>
 */
public record NotificationMessage(NotificationType type, String title, String body, String referenceType, Long referenceId,
                                  String dedupKey, Map<String, String> params, boolean important, boolean urgent,
                                  Set<NotificationChannel> channels) {

    public NotificationMessage {
        Map<String, String> copy = new LinkedHashMap<>();
        if (params != null) {
            params.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(k, v);
                }
            });
        }
        params = Collections.unmodifiableMap(copy);
        channels = channels == null ? null : Collections.unmodifiableSet(EnumSet.copyOf(channels));
    }

    public static NotificationMessage of(NotificationType type, String title, String body, String referenceType,
                                         Long referenceId) {
        return new NotificationMessage(type, title, body, referenceType, referenceId, null, Map.of(), false, false, null);
    }

    public NotificationMessage withDedupKey(String key) {
        return new NotificationMessage(type, title, body, referenceType, referenceId, key, params, important, urgent, channels);
    }

    public NotificationMessage withParam(String name, Object value) {
        Map<String, String> merged = new LinkedHashMap<>(params);
        if (value != null) {
            merged.put(name, value.toString());
        }
        return new NotificationMessage(type, title, body, referenceType, referenceId, dedupKey, merged, important, urgent, channels);
    }

    public NotificationMessage important(boolean value) {
        return new NotificationMessage(type, title, body, referenceType, referenceId, dedupKey, params, value, urgent, channels);
    }

    public NotificationMessage urgent(boolean value) {
        return new NotificationMessage(type, title, body, referenceType, referenceId, dedupKey, params, important, value, channels);
    }

    public NotificationMessage onlyVia(NotificationChannel first, NotificationChannel... more) {
        return new NotificationMessage(type, title, body, referenceType, referenceId, dedupKey, params, important, urgent,
                EnumSet.of(first, more));
    }

    public boolean allows(NotificationChannel channel) {
        return channels == null || channels.contains(channel);
    }
}
