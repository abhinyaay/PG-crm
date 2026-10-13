package com.pgms.billing.autopay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgms.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SandboxRecurringPaymentGatewayTest {

    private final SandboxRecurringPaymentGateway gateway = new SandboxRecurringPaymentGateway(new AutopayProperties("sandbox", 3,
            2, new AutopayProperties.Sandbox("http://portal/autopay/sandbox/authorise", "s3cret"),
            new AutopayProperties.Razorpay("https://api.razorpay.com", null, null, null, Duration.ofSeconds(1),
                    Duration.ofSeconds(1))), new ObjectMapper());

    private static RecurringPaymentGateway.DebitRequest debit(String amount) {
        return new RecurringPaymentGateway.DebitRequest(1L, "c", "t", "m", new BigDecimal(amount), "Rent");
    }

    @Test
    void debitOutcomesAreDeterministicByPaise() {
        assertThat(gateway.requestDebit(debit("9000.13")).outcome()).isEqualTo(RecurringPaymentGateway.DebitOutcome.FAILED);
        assertThat(gateway.requestDebit(debit("9000.77")).outcome()).isEqualTo(RecurringPaymentGateway.DebitOutcome.PENDING);
        RecurringPaymentGateway.DebitResult ok = gateway.requestDebit(debit("9000.00"));
        assertThat(ok.outcome()).isEqualTo(RecurringPaymentGateway.DebitOutcome.SUCCEEDED);
        assertThat(ok.providerPaymentId()).startsWith("sbx_pay_");
        assertThat(SandboxRecurringPaymentGateway.paise(new BigDecimal("12.5"))).isEqualTo(50);
    }

    @Test
    void mandatesPointAtTheInAppAuthorisationPage() {
        RecurringPaymentGateway.MandateSetup setup = gateway.createMandate(new RecurringPaymentGateway.MandateRequest("ref1",
                "Asha", null, "+919000000000", MandateMethod.UPI_AUTOPAY, BigDecimal.TEN, "Rent"));
        assertThat(setup.providerMandateId()).startsWith("sbx_mdt_");
        assertThat(setup.authorisationUrl()).isEqualTo("http://portal/autopay/sandbox/authorise/" + setup.providerMandateId());
        assertThat(gateway.provider()).isEqualTo("sandbox");
    }

    @Test
    void webhooksAreVerifiedOnTheRawBodyAndParsed() {
        byte[] body = ("{\"id\":\"evt_1\",\"type\":\"payment.failed\",\"paymentId\":\"sbx_pay_1\",\"attemptId\":7,"
                + "\"amount\":\"10.13\",\"reason\":\"NSF\"}").getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignatures.hmacSha256Hex("s3cret", body);
        assertThat(gateway.verifyWebhookSignature(body, Map.of("x-sandbox-signature", signature))).isTrue();
        assertThat(gateway.verifyWebhookSignature(body, Map.of("x-sandbox-signature", signature.toUpperCase()))).isTrue();
        assertThat(gateway.verifyWebhookSignature(body, Map.of())).isFalse();
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("10.13", "10.14").getBytes(StandardCharsets.UTF_8);
        assertThat(gateway.verifyWebhookSignature(tampered, Map.of("x-sandbox-signature", signature))).isFalse();

        RecurringPaymentGateway.WebhookPayload payload = gateway.parseWebhook(body, Map.of());
        assertThat(payload.eventId()).isEqualTo("evt_1");
        GatewayEvent event = payload.events().get(0);
        assertThat(event.type()).isEqualTo(GatewayEvent.Type.PAYMENT_FAILED);
        assertThat(event.attemptId()).isEqualTo(7L);
        assertThat(event.amount()).isEqualByComparingTo("10.13");
        assertThat(event.reason()).isEqualTo("NSF");

        for (String type : new String[] {"mandate.activated", "mandate.failed", "mandate.paused", "mandate.resumed",
                "mandate.cancelled", "payment.succeeded"}) {
            byte[] other = ("{\"id\":\"e\",\"type\":\"" + type + "\",\"mandateId\":\"m\",\"paymentId\":\"p\"}")
                    .getBytes(StandardCharsets.UTF_8);
            assertThat(gateway.parseWebhook(other, Map.of()).events()).hasSize(1);
        }
        assertThat(gateway.parseWebhook("{\"id\":\"e\",\"type\":\"unknown\"}".getBytes(StandardCharsets.UTF_8), Map.of())
                .events()).isEmpty();
        assertThatThrownBy(() -> gateway.parseWebhook("nope".getBytes(StandardCharsets.UTF_8), Map.of()))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gateway.parseWebhook("{\"type\":\"x\"}".getBytes(StandardCharsets.UTF_8), Map.of()))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void signatureHelperUsesHexHmacSha256() {
        // RFC 4231 test case 2
        assertThat(WebhookSignatures.hmacSha256Hex("Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
        assertThat(WebhookSignatures.matches("", new byte[0], "x")).isFalse();
        assertThat(WebhookSignatures.matches("k", null, "x")).isFalse();
    }
}
