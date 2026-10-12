package com.pgms.notification;

import com.pgms.user.RoleName;

/** Which column of the notification matrix applies: residents and operator staff get different defaults. */
public enum Audience {
    RESIDENT,
    STAFF;

    public static Audience of(RoleName role) {
        return role == RoleName.TENANT ? RESIDENT : STAFF;
    }
}
