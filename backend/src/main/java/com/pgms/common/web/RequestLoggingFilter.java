package com.pgms.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import net.logstash.logback.argument.StructuredArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** One structured access-log line per API request (method, path, status, latency, user). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("http.access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            String message = "{} {} -> {} ({} ms)";
            Object[] args = {
                StructuredArguments.value("method", request.getMethod()),
                StructuredArguments.value("path", request.getRequestURI()),
                StructuredArguments.value("status", status),
                StructuredArguments.value("durationMs", durationMs),
                StructuredArguments.keyValue("clientIp", request.getRemoteAddr())
            };
            if (status >= 500) {
                log.error(message, args);
            } else if (status >= 400) {
                log.warn(message, args);
            } else {
                log.info(message, args);
            }
        }
    }
}
