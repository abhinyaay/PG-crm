package com.pgms.billing.autopay;

import java.math.BigDecimal;

/**
 * Provider-neutral outcome. Mandates are found by {@code providerMandateId} or, failing that, {@code providerTokenId};
 * debit attempts by {@code providerPaymentId} or, failing that, our own {@code attemptId} echoed back by the provider.
 */
public record GatewayEvent(Type type, String providerMandateId, String providerTokenId, String providerPaymentId,
                           Long attemptId, BigDecimal amount, String reason) {

    public enum Type {
        MANDATE_ACTIVATED,
        MANDATE_FAILED,
        MANDATE_PAUSED,
        MANDATE_RESUMED,
        MANDATE_CANCELLED,
        PAYMENT_SUCCEEDED,
        PAYMENT_FAILED
    }

    public static GatewayEvent mandate(Type type, String providerMandateId, String tokenId, String reason) {
        return new GatewayEvent(type, providerMandateId, tokenId, null, null, null, reason);
    }

    public static GatewayEvent payment(Type type, String providerPaymentId, Long attemptId, BigDecimal amount, String reason) {
        return new GatewayEvent(type, null, null, providerPaymentId, attemptId, amount, reason);
    }

    public boolean isPayment() {
        return type == Type.PAYMENT_SUCCEEDED || type == Type.PAYMENT_FAILED;
    }
}
