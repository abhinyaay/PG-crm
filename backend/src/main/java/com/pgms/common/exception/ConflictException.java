package com.pgms.common.exception;

import org.springframework.http.HttpStatus;

/** The request conflicts with the current state of a resource (double allocation, duplicate key, ...). */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
