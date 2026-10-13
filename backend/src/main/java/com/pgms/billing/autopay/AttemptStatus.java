package com.pgms.billing.autopay;

public enum AttemptStatus {
    /** Requested from the provider; the outcome arrives synchronously (sandbox) or by webhook. */
    SCHEDULED,
    SUCCEEDED,
    FAILED
}
