package com.pgms.security;

import com.pgms.user.RoleName;

/** The authenticated caller, reconstructed from verified JWT claims (no DB hit per request). */
public record CurrentUser(Long id, Long organizationId, RoleName role, String email) {

    public boolean is(RoleName roleName) {
        return role == roleName;
    }

    public boolean isSuperAdmin() {
        return role == RoleName.SUPER_ADMIN;
    }
}
