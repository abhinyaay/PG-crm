package com.pgms.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/** Standard error envelope returned by every endpoint (also mirrored by the analytics service). */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message, Object rejectedValue) {
    }
}
