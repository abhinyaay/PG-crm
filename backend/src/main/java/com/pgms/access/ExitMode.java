package com.pgms.access;

/** How exits are recorded at a property (owner's choice). */
public enum ExitMode {
    /** Only exit punches from a biometric device. */
    DEVICE,
    /** Only the "I'm leaving" button in the app (resident, or staff on their behalf); device exits are ignored. */
    BUTTON,
    DEVICE_OR_BUTTON;

    public boolean allowsButton() {
        return this != DEVICE;
    }

    public boolean allowsDevice() {
        return this != BUTTON;
    }
}
