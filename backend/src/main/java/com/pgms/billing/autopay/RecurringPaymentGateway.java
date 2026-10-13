package com.pgms.billing.autopay;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A recurring-payment provider (mandate registration + debits + webhooks). Exactly one implementation is active,
 * chosen by {@code app.payments.autopay.provider}. Outcomes are reported as provider-neutral {@link GatewayEvent}s,
 * either returned synchronously from {@link #requestDebit} or parsed from a signature-verified webhook.
 */
public interface RecurringPaymentGateway {

    /** Path segment of the webhook URL and value stored in {@code payment_mandates.provider}. */
    String provider();

    MandateSetup createMandate(MandateRequest request);

    void pauseMandate(PaymentMandate mandate);

    void resumeMandate(PaymentMandate mandate);

    void cancelMandate(PaymentMandate mandate);

    DebitResult requestDebit(DebitRequest request);

    /** HMAC check over the exact raw body; header names are lower-cased. */
    boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers);

    WebhookPayload parseWebhook(byte[] rawBody, Map<String, String> headers);

    record MandateRequest(String reference, String tenantName, String email, String phone, MandateMethod method,
                          BigDecimal maxAmount, String description) {
    }

    record MandateSetup(String providerMandateId, String providerCustomerId, String authorisationUrl) {
    }

    record DebitRequest(Long attemptId, String providerCustomerId, String providerTokenId, String providerMandateId,
                        BigDecimal amount, String description) {
    }

    enum DebitOutcome { SUCCEEDED, FAILED, PENDING }

    record DebitResult(String providerPaymentId, DebitOutcome outcome, String failureReason) {
    }

    record WebhookPayload(String eventId, String eventType, List<GatewayEvent> events) {
    }
}
