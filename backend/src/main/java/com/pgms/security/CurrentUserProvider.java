package com.pgms.security;

import com.pgms.common.exception.UnauthorizedException;
import com.pgms.user.RoleName;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserProvider {

    public Optional<CurrentUser> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        Number org = jwt.getClaim(JwtClaimNames.ORGANIZATION);
        return Optional.of(new CurrentUser(
                Long.valueOf(jwt.getSubject()),
                org == null ? null : org.longValue(),
                RoleName.valueOf(jwt.getClaimAsString(JwtClaimNames.ROLE)),
                jwt.getClaimAsString(JwtClaimNames.EMAIL)));
    }

    public CurrentUser require() {
        return current().orElseThrow(() -> new UnauthorizedException("UNAUTHENTICATED", "Authentication required"));
    }

    public Optional<Long> currentUserId() {
        return current().map(CurrentUser::id);
    }
}
