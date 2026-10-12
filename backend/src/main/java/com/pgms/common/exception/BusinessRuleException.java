package com.pgms.common.exception;

import org.springframework.http.HttpStatus;

/** The request is well-formed but violates a domain rule (e.g. paying more than the invoice balance). */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
