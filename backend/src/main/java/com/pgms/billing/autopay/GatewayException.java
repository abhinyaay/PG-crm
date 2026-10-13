package com.pgms.billing.autopay;

import com.pgms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** The payment provider rejected a call or could not be reached (502 to API clients). */
public class GatewayException extends ApiException {

    public GatewayException(String message) {
        super(HttpStatus.BAD_GATEWAY, "PAYMENT_GATEWAY_ERROR", message);
    }
}
