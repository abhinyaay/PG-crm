package com.pgms.security;

/** Custom claim names - part of the contract with the analytics service. */
public final class JwtClaimNames {

    public static final String ROLE = "role";
    public static final String ORGANIZATION = "org";
    public static final String EMAIL = "email";
    public static final String TOKEN_TYPE = "token_type";
    public static final String ACCESS_TOKEN_TYPE = "access";

    private JwtClaimNames() {
    }
}
