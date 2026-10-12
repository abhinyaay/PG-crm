package com.pgms.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Access to per-request diagnostic context (correlation id, client IP) from any layer. */
public final class RequestContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_USER_ID = "userId";
    public static final String MDC_ORG_ID = "orgId";

    private RequestContext() {
    }

    public static String correlationId() {
        return MDC.get(MDC_CORRELATION_ID);
    }

    public static Optional<HttpServletRequest> currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return Optional.of(attributes.getRequest());
        }
        return Optional.empty();
    }

    /** Client IP; X-Forwarded-For is already resolved by Tomcat's RemoteIpValve (server.forward-headers-strategy). */
    public static String clientIp() {
        return currentRequest().map(HttpServletRequest::getRemoteAddr).orElse(null);
    }

    public static String userAgent() {
        return currentRequest()
                .map(r -> r.getHeader("User-Agent"))
                .map(ua -> ua.length() > 255 ? ua.substring(0, 255) : ua)
                .orElse(null);
    }
}
