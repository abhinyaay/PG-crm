package com.pgms.common.exception;

import org.springframework.http.HttpStatus;

/** Authenticated, but not allowed to touch this resource (e.g. a property outside the caller's scope). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }
}
