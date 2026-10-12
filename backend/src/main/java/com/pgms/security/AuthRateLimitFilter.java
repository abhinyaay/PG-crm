package com.pgms.security;

import com.pgms.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Fixed-window rate limit on credential endpoints (login/refresh), keyed by client IP, shared by all replicas
 * through Redis. Slows down credential stuffing and brute force. Nginx applies a coarser per-IP limit in front.
 * Fail-open when Redis is unavailable: availability of login beats strict throttling for this threat model.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthRateLimitFilter.class);
    private static final Set<String> LIMITED_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/refresh");

    private final RedisRateLimiter limiter;
    private final AppProperties.RateLimit config;
    private final JsonErrorWriter errorWriter;

    public AuthRateLimitFilter(RedisRateLimiter limiter, AppProperties properties, JsonErrorWriter errorWriter) {
        this.limiter = limiter;
        this.config = properties.security().rateLimit();
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled() || !"POST".equals(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RedisRateLimiter.Decision decision = limiter.hit("auth", request.getRemoteAddr(), config.authRequestsPerWindow(),
                config.authWindow());
        if (!decision.allowed()) {
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            log.warn("Auth rate limit exceeded for ip={}", request.getRemoteAddr());
            errorWriter.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Too many authentication attempts. Try again in " + decision.retryAfterSeconds() + "s");
            return;
        }
        chain.doFilter(request, response);
    }
}
