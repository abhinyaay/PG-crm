package com.pgms.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Plugs Redis-backed revocation into the standard JWT validation chain. */
public class TokenRevocationValidator implements OAuth2TokenValidator<Jwt> {

    private final TokenRevocationService revocationService;

    public TokenRevocationValidator(TokenRevocationService revocationService) {
        this.revocationService = revocationService;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (revocationService.isRevoked(jwt.getId(), jwt.getSubject(), jwt.getIssuedAt())) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token has been revoked", null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
