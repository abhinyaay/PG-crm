package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.integration.Api.Response;
import com.pgms.support.BusinessDates;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentIdempotencyIT extends AbstractIntegrationTest {

    private String token;
    private long depositInvoice;

    @BeforeEach
    void checkInATenant() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        token = api.login(org.email("admin"));
        long tenant = fixtures.tenant(org, layout.propertyId());
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenant + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + BusinessDates.today() + "\"}");
        assertThat(checkIn.status()).isEqualTo(201);
        checkIn.body().get("invoices").forEach(i -> {
            if (i.get("type").asText().equals("SECURITY_DEPOSIT")) {
                depositInvoice = i.get("id").asLong();
            }
        });
    }

    private String payment(String amount) {
        return "{\"invoiceId\":" + depositInvoice + ",\"amount\":" + amount + ",\"method\":\"UPI\"}";
    }

    @Test
    void retriesWithTheSameKeyNeverChargeTwice() throws Exception {
        String key = "it-key-" + System.nanoTime();
        List<Callable<Response>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(() -> api.post("/api/v1/payments", token, payment("5000"), "Idempotency-Key", key));
        }

        List<Response> responses = ConcurrencySupport.runConcurrently(attempts);

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 200).hasSize(5)
                .allSatisfy(r -> assertThat(r.raw().getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true"));
        assertThat(responses).extracting(Response::id).containsOnly(responses.get(0).id());
        assertThat(fixtures.count("SELECT count(*) FROM payments WHERE invoice_id = ?", depositInvoice)).isEqualTo(1);
        assertThat(fixtures.decimal("SELECT amount_paid FROM invoices WHERE id = ?", depositInvoice)).isEqualByComparingTo("5000");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRejected() {
        String key = "it-key-" + System.nanoTime();
        assertThat(api.post("/api/v1/payments", token, payment("1000"), "Idempotency-Key", key).status()).isEqualTo(201);
        Response reuse = api.post("/api/v1/payments", token, payment("2000"), "Idempotency-Key", key);
        assertThat(reuse.status()).isEqualTo(422);
        assertThat(reuse.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void parallelDistinctPaymentsCannotOverpayAnInvoice() throws Exception {
        // deposit is 18000; three concurrent 10000 payments: the invoice row lock lets only one through
        List<Callable<Response>> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String key = "it-over-" + i + "-" + System.nanoTime();
            attempts.add(() -> api.post("/api/v1/payments", token, payment("10000"), "Idempotency-Key", key));
        }

        List<Response> responses = ConcurrencySupport.runConcurrently(attempts);

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 422)
                .allSatisfy(r -> assertThat(r.body().get("code").asText()).isEqualTo("OVERPAYMENT"));
        assertThat(fixtures.decimal("SELECT amount_paid FROM invoices WHERE id = ?", depositInvoice)).isEqualByComparingTo("10000");
    }

    @Test
    void missingIdempotencyKeyIsABadRequest() {
        Response response = api.post("/api/v1/payments", token, payment("100"));
        assertThat(response.status()).isEqualTo(400);
    }

    @Test
    void refundsAreIdempotentAndBounded() {
        long paymentId = api.post("/api/v1/payments", token, payment("18000"), "Idempotency-Key", "it-full-" + System.nanoTime())
                .id();
        String refundKey = "it-refund-" + System.nanoTime();
        String body = "{\"amount\":15000,\"reason\":\"Deposit refund after move-out\"}";
        assertThat(api.post("/api/v1/payments/" + paymentId + "/refunds", token, body, "Idempotency-Key", refundKey).status())
                .isEqualTo(201);
        assertThat(api.post("/api/v1/payments/" + paymentId + "/refunds", token, body, "Idempotency-Key", refundKey).status())
                .isEqualTo(200);
        Response tooMuch = api.post("/api/v1/payments/" + paymentId + "/refunds", token,
                "{\"amount\":3001,\"reason\":\"extra\"}", "Idempotency-Key", "it-refund2-" + System.nanoTime());
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(api.get("/api/v1/payments/" + paymentId, token).body().get("status").asText()).isEqualTo("PARTIALLY_REFUNDED");
    }
}
