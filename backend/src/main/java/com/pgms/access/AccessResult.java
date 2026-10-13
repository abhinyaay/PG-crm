package com.pgms.access;

/** Outcome stored with every event; presence and entry limits only count {@link #ALLOWED}. */
public enum AccessResult {
    ALLOWED,
    DENIED_LIMIT_EXCEEDED,
    DENIED_NOT_RESIDENT,
    /** The module is switched off for the property: stored so nothing is lost, but no alerts. */
    DENIED_DISABLED,
    UNKNOWN_CREDENTIAL,
    /** A device exit at a property whose exit mode is BUTTON. */
    IGNORED_EXIT_MODE
}
