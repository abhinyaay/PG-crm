package com.pgms.billing.autopay;

public enum MandateStatus {
    /** Created; waiting for the resident to approve it at the bank / UPI app. */
    PENDING_AUTHORISATION,
    ACTIVE,
    PAUSED,
    CANCELLED,
    /** Authorisation was rejected or expired. */
    FAILED;

    public boolean isLive() {
        return this == PENDING_AUTHORISATION || this == ACTIVE || this == PAUSED;
    }
}
