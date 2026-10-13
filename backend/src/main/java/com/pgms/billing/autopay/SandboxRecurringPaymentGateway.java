package com.pgms.billing.autopay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgms.common.exception.BadRequestException;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local, fully simulated provider (the default). Mandates are authorised on an in-app "bank" page
 * ({@code POST /api/v1/autopay/sandbox/mandates/{id}/authorise}); debits resolve deterministically by the paise of the
 * amount so every path is testable:
 * <ul>
 *   <li>{@code .13} → FAILED (insufficient funds);</li>
 *   <li>{@code .77} → PENDING, completed later only by a webhook ({@code POST /api/v1/payments/webhooks/sandbox});</li>
 *   <li>anything else → SUCCEEDED immediately.</li>
 * </ul>
 * Webhooks are JSON {@code {id, type, mandateId, paymentId, attemptId, amount, reason}} signed with HMAC-SHA256 of the
 * raw body in {@code X-Sandbox-Signature} (secret {@code app.payments.autopay.sandbox.webhook-secret}).
 */
@Component
@ConditionalOnProperty(name = "app.payments.autopay.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxRecurringPaymentGateway implements RecurringPaymentGateway {

    public static final String NAME = "sandbox";
    public static final String SIGNATURE_HEADER = "x-sandbox-signature";
    static final int FAIL_PAISE = 13;
    static final int PENDING_PAISE = 77;

    private final AutopayProperties.Sandbox config;
    private final ObjectMapper json;

    public SandboxRecurringPaymentGateway(AutopayProperties properties, ObjectMapper json) {
        this.config = properties.sandbox();
        this.json = json;
    }

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public MandateSetup createMandate(MandateRequest request) {
        String id = "sbx_mdt_" + UUID.randomUUID().toString().replace("-", "");
        return new MandateSetup(id, "sbx_cust_" + request.reference(), config.authorisationBaseUrl() + "/" + id);
    }

    @Override
    public void pauseMandate(PaymentMandate mandate) {
        // nothing to tell the simulated bank: the debit job skips paused mandates
    }

    @Override
    public void resumeMandate(PaymentMandate mandate) {
        // nothing to tell the simulated bank
    }

    @Override
    public void cancelMandate(PaymentMandate mandate) {
        // nothing to tell the simulated bank
    }

    @Override
    public DebitResult requestDebit(DebitRequest request) {
        String paymentId = "sbx_pay_" + UUID.randomUUID().toString().replace("-", "");
        return switch (paise(request.amount())) {
            case FAIL_PAISE -> new DebitResult(paymentId, DebitOutcome.FAILED, "Insufficient funds (sandbox)");
            case PENDING_PAISE -> new DebitResult(paymentId, DebitOutcome.PENDING, null);
            default -> new DebitResult(paymentId, DebitOutcome.SUCCEEDED, null);
        };
    }

    static int paise(BigDecimal amount) {
        return amount.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, Map<String, String> headers) {
        return WebhookSignatures.matches(config.webhookSecret(), rawBody, headers.get(SIGNATURE_HEADER));
    }

    @Override
    public WebhookPayload parseWebhook(byte[] rawBody, Map<String, String> headers) {
        JsonNode body;
        try {
            body = json.readTree(rawBody);
        } catch (IOException e) {
            throw new BadRequestException("MALFORMED_WEBHOOK", "Webhook body is not JSON");
        }
        String eventId = text(body, "id");
        String type = text(body, "type");
        if (eventId == null || type == null) {
            throw new BadRequestException("MALFORMED_WEBHOOK", "Webhook needs id and type");
        }
        String mandateId = text(body, "mandateId");
        String paymentId = text(body, "paymentId");
        Long attemptId = body.hasNonNull("attemptId") ? body.get("attemptId").asLong() : null;
        BigDecimal amount = body.hasNonNull("amount") ? new BigDecimal(body.get("amount").asText()) : null;
        String reason = text(body, "reason");
        GatewayEvent event = switch (type) {
            case "mandate.activated" -> GatewayEvent.mandate(GatewayEvent.Type.MANDATE_ACTIVATED, mandateId, null, null);
            case "mandate.failed" -> GatewayEvent.mandate(GatewayEvent.Type.MANDATE_FAILED, mandateId, null, reason);
            case "mandate.paused" -> GatewayEvent.mandate(GatewayEvent.Type.MANDATE_PAUSED, mandateId, null, reason);
            case "mandate.resumed" -> GatewayEvent.mandate(GatewayEvent.Type.MANDATE_RESUMED, mandateId, null, reason);
            case "mandate.cancelled" -> GatewayEvent.mandate(GatewayEvent.Type.MANDATE_CANCELLED, mandateId, null, reason);
            case "payment.succeeded" -> GatewayEvent.payment(GatewayEvent.Type.PAYMENT_SUCCEEDED, paymentId, attemptId, amount, null);
            case "payment.failed" -> GatewayEvent.payment(GatewayEvent.Type.PAYMENT_FAILED, paymentId, attemptId, amount, reason);
            default -> null;
        };
        return new WebhookPayload(eventId, type, event == null ? List.of() : List.of(event));
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }
}
