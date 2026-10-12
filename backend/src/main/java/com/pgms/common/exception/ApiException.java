package com.pgms.common.exception;

import org.springframework.http.HttpStatus;

/** Base type for expected, client-facing failures. Carries an HTTP status and a stable machine-readable code. */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
