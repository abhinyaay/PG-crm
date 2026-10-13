package com.pgms.access;

/** How an event was produced. Only the first three come from devices. */
public enum AccessMethod {
    FINGERPRINT,
    FACE,
    CARD,
    BUTTON,
    MANUAL_OVERRIDE;

    public boolean isDevice() {
        return this == FINGERPRINT || this == FACE || this == CARD;
    }
}
