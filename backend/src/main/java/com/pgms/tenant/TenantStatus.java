package com.pgms.tenant;

public enum TenantStatus {
    /** Registered / reserved, not yet checked in. */
    PROSPECTIVE,
    ACTIVE,
    /** Has given notice; expected to check out. */
    ON_NOTICE,
    MOVED_OUT
}
