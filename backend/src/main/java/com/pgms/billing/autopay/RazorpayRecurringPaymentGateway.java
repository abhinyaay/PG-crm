package com.pgms.billing.autopay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgms.common.exception.BadRequestException;
import com.pgms.common.util.Hashing;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Razorpay recurring payments (UPI AutoPay / e-NACH / card tokens) over its REST API with HTTP basic auth (key id +
 * secret from the environment):
 * <ol>
 *   <li>mandate: {@code POST /v1/customers} then {@code POST /v1/subscription_registration/auth_links}; the
 *       registration link's id is our provider mandate id and its {@code short_url} the authorisation URL. The token
 *       arrives with the registration payment ({@code payment.captured} carrying {@code invoice_id} + {@code token_id})
 *       or {@code token.confirmed};</li>
 *   <li>debit: {@code POST /v1/orders} then {@code POST /v1/payments/create/recurring}; the result is PENDING until
 *       {@code payment.captured} / {@code payment.failed} arrives. Our attempt id travels in {@code notes.attempt_id};</li>
 *   <li>webhooks: {@code X-Razorpay-Signature} = hex HMAC-SHA256 of the raw body with the webhook secret; the event id
 *       comes from {@code X-Razorpay-Event-Id} (body hash as fallback).</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "app.payments.autopay.provider", havingValue = "razorpay")
public class RazorpayRecurringPaymentGateway implements RecurringPaymentGateway {

    public static final String NAME = "razorpay";
    static final String SIGNATURE_HEADER = "x-razorpay-signature";
    static final String EVENT_ID_HEADER = "x-razorpay-event-id";
    /** Validity of a registration (mandate) - ten years, Razorpay's maximum for e-mandates. */
    private static final Duration MANDATE_VALIDITY = Duration.ofDays(3650);

    private final RestClient client;
    private final AutopayProperties.Razorpay config;
    private final ObjectMapper json;
    private final Clock clock;

    @Autowired
    public RazorpayRecurringPaymentGateway(RestClient.Builder builder, AutopayProperties properties, ObjectMapper json,
                                           Clock clock) {
        this(httpClient(builder, properties.razorpay()), properties.razorpay(), json, clock);
    }

    RazorpayRecurringPaymentGateway(RestClient client, AutopayProperties.Razorpay config, ObjectMapper json, Clock clock) {
        if (isBlank(config.keyId()) || isBlank(config.keySecret()) || isBlank(config.webhookSecret())) {
            throw new IllegalStateException("Razorpay AutoPay needs RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET");
        }
        this.client = client;
        this.config = config;
        this.json = json;
        this.clock = clock;
    }

    private static RestClient httpClient(RestClient.Builder builder, AutopayProperties.Razorpay config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.connectTimeout());
        factory.setReadTimeout(config.readTimeout());
        return builder.baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public String provider() {
        return NAME;
    }

    @Override
    public MandateSetup createMandate(MandateRequest request) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", request.tenantName());
        if (request.email() != null) {
            customer.put("email", request.email());
        }
        customer.put("contact", request.phone());
        customer.put("fail_existing", "0");
        String customerId = post("/v1/customers", customer).path("id").asText();

        Map<String, Object> registration = new LinkedHashMap<>();
        registration.put("method", switch (request.method()) {
            case UPI_AUTOPAY -> "upi";
            case ENACH -> "emandate";
            case CARD -> "card";
        });
        registration.put("max_amount", paise(request.maxAmount()));
        registration.put("expire_at", clock.instant().plus(MANDATE_VALIDITY).getEpochSecond());
        if (request.method() == MandateMethod.UPI_AUTOPAY) {
            registration.put("frequency", "as_presented");
        }
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("customer_id", customerId);
        link.put("type", "link");
        // UPI AutoPay registrations need a token first payment of at least Re 1; e-mandates and cards use 0
        link.put("amount", request.method() == MandateMethod.UPI_AUTOPAY ? 100 : 0);
        link.put("currency", "INR");
        link.put("description", request.description());
        link.put("subscription_registration", registration);
        link.put("receipt", request.reference());
        link.put("notes", Map.of("mandate_ref", request.reference()));
        JsonNode created = post("/v1/subscription_registration/auth_links", link);
        return new MandateSetup(created.path("id").asText(), customerId, created.path("short_url").asText(null));
    }

    @Override
    public void pauseMandate(PaymentMandate mandate) {
        // Razorpay tokens have no pause; the debit job simply skips paused mandates
    }

    @Override
    public void resumeMandate(PaymentMandate mandate) {
        // see pauseMandate
    }

    @Override
    public void cancelMandate(PaymentMandate mandate) {
        if (mandate.getProviderTokenId() == null || mandate.getProviderCustomerId() == null) {
            return;
        }
        try {
            client.delete().uri("/v1/customers/{customer}/tokens/{token}", mandate.getProviderCustomerId(),
                    mandate.getProviderTokenId()).header(HttpHeaders.AUTHORIZATION, basicAuth()).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw failure("cancel token", e);
        }
    }

    @Override
    public DebitResult requestDebit(DebitRequest request) {
        if (request.providerTokenId() == null) {
            return new DebitResult(null, DebitOutcome.FAILED, "Mandate token not confirmed yet");
        }
        Map<String, Object> notes = Map.of("attempt_id", String.valueOf(request.attemptId()));
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("amount", paise(request.amount()));
        order.put("currency", "INR");
        order.put("receipt", "autopay-" + request.attemptId());
        order.put("payment_capture", true);
        order.put("notes", notes);
        String orderId = post("/v1/orders", order).path("id").asText();

        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("amount", paise(request.amount()));
        payment.put("currency", "INR");
        payment.put("order_id", orderId);
        payment.put("customer_id", request.providerCustomerId());
        payment.put("token", request.providerTokenId());
        payment.put("recurring", "1");
        payment.put("description", request.description());
        payment.put("notes", notes);
        JsonNode created = post("/v1/payments/create/recurring", payment);
        String paymentId = created.path("razorpay_payment_id").asText(created.path("id").asText(null));
        return new DebitResult(paymentId, DebitOutcome.PENDING, null);
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
        String type = body.path("event").asText("");
        String eventId = headers.get(EVENT_ID_HEADER);
        if (isBlank(eventId)) {
            eventId = "sha256:" + Hashing.sha256Hex(new String(rawBody, StandardCharsets.UTF_8));
        }
        List<GatewayEvent> events = new ArrayList<>();
        JsonNode payment = body.path("payload").path("payment").path("entity");
        JsonNode token = body.path("payload").path("token").path("entity");
        JsonNode invoice = body.path("payload").path("invoice").path("entity");
        String attemptRef = payment.path("notes").path("attempt_id").asText(null);
        Long attemptId = attemptRef == null || !attemptRef.matches("\\d+") ? null : Long.valueOf(attemptRef);
        switch (type) {
            case "payment.captured" -> {
                if (attemptId != null) {
                    events.add(GatewayEvent.payment(GatewayEvent.Type.PAYMENT_SUCCEEDED, payment.path("id").asText(null), attemptId,
                            rupees(payment.path("amount")), null));
                } else if (payment.hasNonNull("invoice_id") || payment.hasNonNull("token_id")) {
                    events.add(GatewayEvent.mandate(GatewayEvent.Type.MANDATE_ACTIVATED, payment.path("invoice_id").asText(null),
                            payment.path("token_id").asText(null), null));
                }
            }
            case "payment.failed" -> {
                if (attemptId != null) {
                    events.add(GatewayEvent.payment(GatewayEvent.Type.PAYMENT_FAILED, payment.path("id").asText(null), attemptId,
                            rupees(payment.path("amount")), payment.path("error_description").asText("Payment failed")));
                }
            }
            case "token.confirmed" -> events.add(GatewayEvent.mandate(GatewayEvent.Type.MANDATE_ACTIVATED, null,
                    token.path("id").asText(null), null));
            case "token.rejected" -> events.add(GatewayEvent.mandate(GatewayEvent.Type.MANDATE_FAILED, null,
                    token.path("id").asText(null), "Rejected by the bank"));
            case "token.paused" -> events.add(GatewayEvent.mandate(GatewayEvent.Type.MANDATE_PAUSED, null,
                    token.path("id").asText(null), null));
            case "token.cancelled" -> events.add(GatewayEvent.mandate(GatewayEvent.Type.MANDATE_CANCELLED, null,
                    token.path("id").asText(null), "Cancelled at the bank"));
            case "invoice.expired" -> events.add(GatewayEvent.mandate(GatewayEvent.Type.MANDATE_FAILED,
                    invoice.path("id").asText(null), null, "Authorisation link expired"));
            default -> {
                // other events (order.paid, invoice.paid ...) carry nothing we act on
            }
        }
        return new WebhookPayload(eventId, type, events);
    }

    private JsonNode post(String path, Object body) {
        try {
            String response = client.post().uri(path)
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return json.readTree(response == null ? "{}" : response);
        } catch (RestClientException e) {
            throw failure(path, e);
        } catch (IOException e) {
            throw new GatewayException("Razorpay returned an unreadable response for " + path);
        }
    }

    private GatewayException failure(String action, RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            String detail = response.getResponseBodyAsString();
            detail = detail.length() > 200 ? detail.substring(0, 200) : detail;
            return new GatewayException("Razorpay " + action + " failed with " + response.getStatusCode().value() + ": " + detail);
        }
        return new GatewayException("Razorpay " + action + " failed: " + e.getMessage());
    }

    private String basicAuth() {
        return "Basic " + Base64.getEncoder().encodeToString((config.keyId() + ":" + config.keySecret())
                .getBytes(StandardCharsets.UTF_8));
    }

    static long paise(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static BigDecimal rupees(JsonNode paise) {
        return paise.isNumber() ? BigDecimal.valueOf(paise.asLong()).movePointLeft(2) : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank() || value.toLowerCase(Locale.ROOT).equals("null");
    }
}
