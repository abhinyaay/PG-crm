package com.pgms.security;

import com.pgms.config.AppProperties;
import com.pgms.user.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Mints short-lived RS256 access tokens. Refresh tokens are opaque and handled by the auth module. */
@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AppProperties.Jwt jwtProperties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AppProperties properties, Clock clock) {
        this.encoder = encoder;
        this.jwtProperties = properties.security().jwt();
        this.clock = clock;
    }

    public IssuedToken issueAccessToken(User user) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(jwtProperties.accessTokenTtl());
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .audience(List.of(jwtProperties.audience()))
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim(JwtClaimNames.ROLE, user.getRoleName().name())
                .claim(JwtClaimNames.EMAIL, user.getEmail())
                .claim(JwtClaimNames.TOKEN_TYPE, JwtClaimNames.ACCESS_TOKEN_TYPE);
        if (user.getOrganizationId() != null) {
            claims.claim(JwtClaimNames.ORGANIZATION, user.getOrganizationId());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(jwtProperties.keyId()).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String value, Instant expiresAt) {
    }
}
