package com.pgms.notification;

/** One cell of the notification matrix. */
public enum ChannelRule {
    /** Not offered for this type/audience; preferences cannot turn it on. */
    NONE,
    /** Available but off unless the user opts in. */
    OFF,
    /** On unless the user or the organization opts out. */
    ON,
    /** Like {@link #OFF}, but on by default for messages flagged important (e.g. CRITICAL tickets, rent invoices). */
    ON_IF_IMPORTANT,
    /** Always sent (security, money received); bypasses preferences and organization settings. */
    MANDATORY
}
