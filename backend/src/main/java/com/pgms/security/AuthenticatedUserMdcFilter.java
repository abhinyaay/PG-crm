package com.pgms.security;

import com.pgms.common.web.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Adds the authenticated user/org to the logging MDC so every log line of the request is attributable. */
@Component
public class AuthenticatedUserMdcFilter extends OncePerRequestFilter {

    private final CurrentUserProvider currentUserProvider;

    public AuthenticatedUserMdcFilter(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        currentUserProvider.current().ifPresent(user -> {
            MDC.put(RequestContext.MDC_USER_ID, String.valueOf(user.id()));
            if (user.organizationId() != null) {
                MDC.put(RequestContext.MDC_ORG_ID, String.valueOf(user.organizationId()));
            }
        });
        chain.doFilter(request, response);
    }
}
