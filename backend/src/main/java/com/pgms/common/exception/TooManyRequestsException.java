package com.pgms.common.exception;

import org.springframework.http.HttpStatus;

/** A per-client or per-identifier throttle was exceeded. */
public class TooManyRequestsException extends ApiException {

    public TooManyRequestsException(String message) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }
}
