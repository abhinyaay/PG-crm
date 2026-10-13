package com.pgms.access;

public enum DeviceStatus {
    ACTIVE,
    /** Requests from a disabled device are rejected like those from an unknown serial number. */
    DISABLED
}
