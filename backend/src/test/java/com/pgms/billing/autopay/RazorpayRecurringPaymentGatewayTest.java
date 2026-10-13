package com.pgms.billing.autopay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class RazorpayRecurringPaymentGatewayTest {

    private static final String BASE = "https://api.razorpay.test";
    private static final String AUTH = "Basic " + Base64.getEncoder().encodeToString("rzp_key:rzp_secret".getBytes(StandardCharsets.UTF_8));
    private static final AutopayProperties.Razorpay CONFIG = new AutopayProperties.Razorpay(BASE, "rzp_key", "rzp_secret", "whsec",
            Duration.ofSeconds(1), Duration.ofSeconds(1));

    private MockRestServiceServer server;
    private RazorpayRecurringPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new RazorpayRecurringPaymentGateway(builder.build(), CONFIG, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void createsACustomerAndARegistrationLink() {
        server.expect(requestTo(BASE + "/v1/customers")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", AUTH))
                .andExpect(jsonPath("$.contact").value("+919000000000"))
                .andRespond(withSuccess("{\"id\":\"cust_1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/subscription_registration/auth_links"))
                .andExpect(jsonPath("$.customer_id").value("cust_1"))
                .andExpect(jsonPath("$.subscription_registration.method").value("upi"))
                .andExpect(jsonPath("$.subscription_registration.max_amount").value(2000000))
                .andExpect(jsonPath("$.receipt").value("ref-1"))
                .andRespond(withSuccess("{\"id\":\"inv_1\",\"short_url\":\"https://rzp.io/i/x\"}", MediaType.APPLICATION_JSON));

        RecurringPaymentGateway.MandateSetup setup = gateway.createMandate(new RecurringPaymentGateway.MandateRequest("ref-1",
                "Asha", "asha@example.com", "+919000000000", MandateMethod.UPI_AUTOPAY, new BigDecimal("20000"), "Rent"));

        assertThat(setup.providerMandateId()).isEqualTo("inv_1");
        assertThat(setup.providerCustomerId()).isEqualTo("cust_1");
        assertThat(setup.authorisationUrl()).isEqualTo("https://rzp.io/i/x");
        server.verify();
    }

    @Test
    void debitCreatesAnOrderAndARecurringPaymentThatIsPendingUntilTheWebhook() {
        server.expect(requestTo(BASE + "/v1/orders")).andExpect(jsonPath("$.amount").value(900013))
                .andExpect(jsonPath("$.notes.attempt_id").value("42"))
                .andRespond(withSuccess("{\"id\":\"order_1\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v1/payments/create/recurring"))
                .andExpect(jsonPath("$.order_id").value("order_1"))
                .andExpect(jsonPath("$.token").value("token_1"))
                .andExpect(jsonPath("$.recurring").value("1"))
                .andRespond(withSuccess("{\"razorpay_payment_id\":\"pay_1\"}", MediaType.APPLICATION_JSON));

        RecurringPaymentGateway.DebitResult result = gateway.requestDebit(new RecurringPaymentGateway.DebitRequest(42L, "cust_1",
                "token_1", "inv_1", new BigDecimal("9000.13"), "Rent INV-1"));

        assertThat(result.outcome()).isEqualTo(RecurringPaymentGateway.DebitOutcome.PENDING);
        assertThat(result.providerPaymentId()).isEqualTo("pay_1");
        assertThat(gateway.requestDebit(new RecurringPaymentGateway.DebitRequest(43L, "cust_1", null, "inv_1", BigDecimal.TEN,
                "x")).outcome()).isEqualTo(RecurringPaymentGateway.DebitOutcome.FAILED);
        server.verify();
    }

    @Test
    void providerErrorsBecomeGatewayExceptions() {
        server.expect(requestTo(BASE + "/v1/orders"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"description\":\"bad\"}}")
                        .contentType(MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gateway.requestDebit(new RecurringPaymentGateway.DebitRequest(1L, "c", "t", "m", BigDecimal.TEN,
                "x"))).isInstanceOf(GatewayException.class).hasMessageContaining("400");
    }

    @Test
    void cancellingDeletesTheTokenWhenOneExists() {
        PaymentMandate mandate = new PaymentMandate(1L, 2L, 3L, "razorpay", "inv_1", "cust_1", MandateMethod.ENACH,
                BigDecimal.TEN, null, 0);
        gateway.cancelMandate(mandate); // no token yet: nothing to call
        mandate.activate("token_1", Instant.now());
        server.expect(requestTo(BASE + "/v1/customers/cust_1/tokens/token_1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess());
        gateway.cancelMandate(mandate);
        gateway.pauseMandate(mandate);
        gateway.resumeMandate(mandate);
        server.verify();
    }

    @Test
    void verifiesTheSignatureAndMapsWebhookEvents() {
        byte[] captured = ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_1\","
                + "\"amount\":900000,\"notes\":{\"attempt_id\":\"42\"}}}}}").getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignatures.hmacSha256Hex("whsec", captured);
        assertThat(gateway.verifyWebhookSignature(captured, Map.of("x-razorpay-signature", signature))).isTrue();
        assertThat(gateway.verifyWebhookSignature(captured, Map.of("x-razorpay-signature", "00" + signature.substring(2))))
                .isFalse();

        RecurringPaymentGateway.WebhookPayload payload = gateway.parseWebhook(captured, Map.of("x-razorpay-event-id", "evt_9"));
        assertThat(payload.eventId()).isEqualTo("evt_9");
        GatewayEvent event = payload.events().get(0);
        assertThat(event.type()).isEqualTo(GatewayEvent.Type.PAYMENT_SUCCEEDED);
        assertThat(event.providerPaymentId()).isEqualTo("pay_1");
        assertThat(event.attemptId()).isEqualTo(42L);
        assertThat(event.amount()).isEqualByComparingTo("9000.00");

        byte[] registration = ("{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_0\","
                + "\"invoice_id\":\"inv_1\",\"token_id\":\"token_1\",\"notes\":{}}}}}").getBytes(StandardCharsets.UTF_8);
        RecurringPaymentGateway.WebhookPayload activated = gateway.parseWebhook(registration, Map.of());
        assertThat(activated.eventId()).startsWith("sha256:");
        assertThat(activated.events().get(0).type()).isEqualTo(GatewayEvent.Type.MANDATE_ACTIVATED);
        assertThat(activated.events().get(0).providerMandateId()).isEqualTo("inv_1");
        assertThat(activated.events().get(0).providerTokenId()).isEqualTo("token_1");

        assertThat(type("{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"p\","
                + "\"notes\":{\"attempt_id\":\"5\"},\"error_description\":\"NSF\"}}}}")).isEqualTo(GatewayEvent.Type.PAYMENT_FAILED);
        assertThat(type("{\"event\":\"token.confirmed\",\"payload\":{\"token\":{\"entity\":{\"id\":\"token_1\"}}}}"))
                .isEqualTo(GatewayEvent.Type.MANDATE_ACTIVATED);
        assertThat(type("{\"event\":\"token.rejected\",\"payload\":{\"token\":{\"entity\":{\"id\":\"token_1\"}}}}"))
                .isEqualTo(GatewayEvent.Type.MANDATE_FAILED);
        assertThat(type("{\"event\":\"token.paused\",\"payload\":{\"token\":{\"entity\":{\"id\":\"token_1\"}}}}"))
                .isEqualTo(GatewayEvent.Type.MANDATE_PAUSED);
        assertThat(type("{\"event\":\"token.cancelled\",\"payload\":{\"token\":{\"entity\":{\"id\":\"token_1\"}}}}"))
                .isEqualTo(GatewayEvent.Type.MANDATE_CANCELLED);
        assertThat(type("{\"event\":\"invoice.expired\",\"payload\":{\"invoice\":{\"entity\":{\"id\":\"inv_1\"}}}}"))
                .isEqualTo(GatewayEvent.Type.MANDATE_FAILED);
        assertThat(gateway.parseWebhook("{\"event\":\"order.paid\"}".getBytes(StandardCharsets.UTF_8), Map.of()).events())
                .isEmpty();
        assertThat(RazorpayRecurringPaymentGateway.paise(new BigDecimal("12.345"))).isEqualTo(1235);
    }

    @Test
    void refusesToStartWithoutCredentials() {
        assertThatThrownBy(() -> new RazorpayRecurringPaymentGateway(RestClient.create(), new AutopayProperties.Razorpay(BASE,
                "", "x", "y", Duration.ofSeconds(1), Duration.ofSeconds(1)), new ObjectMapper(), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }

    private GatewayEvent.Type type(String body) {
        return gateway.parseWebhook(body.getBytes(StandardCharsets.UTF_8), Map.of()).events().get(0).type();
    }
}
