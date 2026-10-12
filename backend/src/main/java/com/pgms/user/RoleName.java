package com.pgms.user;

public enum RoleName {
    SUPER_ADMIN,
    PROPERTY_ADMIN,
    MANAGER,
    STAFF,
    TENANT;

    public String authority() {
        return "ROLE_" + name();
    }

    /** Roles whose visibility is limited to explicitly assigned properties. */
    public boolean isPropertyScoped() {
        return this == MANAGER || this == STAFF;
    }
}
