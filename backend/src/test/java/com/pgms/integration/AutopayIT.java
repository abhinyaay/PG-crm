package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.billing.autopay.WebhookSignatures;
import com.pgms.integration.Api.Response;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;

/** AutoPay with the sandbox provider: mandate lifecycle, daily debits, retries, webhooks and their idempotency. */
class AutopayIT extends AbstractCollectionsIT {

    private static final String SECRET = "sandbox-webhook-secret";

    private long startAndAuthorise(String maxAmount) {
        Response started = api.post("/api/v1/autopay/mandates", tenantToken,
                "{\"method\":\"UPI_AUTOPAY\",\"maxAmount\":" + maxAmount + ",\"debitDayOffset\":0}");
        assertThat(started.status()).as(started.body().toString()).isEqualTo(201);
        Response authorised = api.post("/api/v1/autopay/sandbox/mandates/" + started.id() + "/authorise", tenantToken,
                "{\"approve\":true}");
        assertThat(authorised.status()).isEqualTo(200);
        assertThat(authorised.body().get("status").asText()).isEqualTo("ACTIVE");
        return started.id();
    }

    private Response runDebits(LocalDate date) {
        Response run = api.post("/api/v1/billing/autopay/run?date=" + date, rootToken, null);
        assertThat(run.status()).isEqualTo(200);
        return run;
    }

    private int attempts(long invoiceId) {
        return fixtures.count("SELECT count(*) FROM autopay_attempts WHERE invoice_id = ?", invoiceId);
    }

    private Response webhook(String body, String signature) {
        return signature == null ? api.post("/api/v1/payments/webhooks/sandbox", null, body)
                : api.post("/api/v1/payments/webhooks/sandbox", null, body, "X-Sandbox-Signature", signature);
    }

    private static String sign(String body) {
        return WebhookSignatures.hmacSha256Hex(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void authorisedMandateCollectsTheDueRentOncePerInvoice() {
        makeRegularRentDue(rentInvoiceId, 0, "9000");
        Response started = api.post("/api/v1/autopay/mandates", tenantToken,
                "{\"method\":\"UPI_AUTOPAY\",\"maxAmount\":20000,\"debitDayOffset\":0}");
        assertThat(started.status()).isEqualTo(201);
        assertThat(started.body().get("status").asText()).isEqualTo("PENDING_AUTHORISATION");
        assertThat(started.body().get("authorisationUrl").asText()).contains("/autopay/sandbox/authorise/sbx_mdt_");
        assertThat(api.post("/api/v1/autopay/mandates", tenantToken, "{\"method\":\"ENACH\",\"maxAmount\":20000}")
                .body().get("code").asText()).isEqualTo("MANDATE_EXISTS");

        runDebits(today);
        assertThat(attempts(rentInvoiceId)).as("not authorised yet").isZero();

        assertThat(api.post("/api/v1/autopay/sandbox/mandates/" + started.id() + "/authorise", managerToken,
                "{\"approve\":true}").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/autopay/sandbox/mandates/" + started.id() + "/authorise", tenantToken,
                "{\"approve\":true}").body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(notifications("AUTOPAY_MANDATE_UPDATED", "PAYMENT_MANDATE", started.id(), "IN_APP")).isGreaterThanOrEqualTo(1);

        runDebits(today);
        runDebits(today);

        assertThat(attempts(rentInvoiceId)).isEqualTo(1);
        assertThat(status(rentInvoiceId)).isEqualTo("PAID");
        List<java.util.Map<String, Object>> payments = fixtures.jdbc().queryForList(
                "SELECT method, idempotency_key, external_reference, amount FROM payments WHERE invoice_id = ?", rentInvoiceId);
        assertThat(payments).hasSize(1);
        assertThat(payments.get(0).get("method")).isEqualTo("AUTOPAY");
        assertThat((String) payments.get(0).get("idempotency_key")).startsWith("autopay:sandbox:sbx_pay_");
        Response attemptsOfTenant = api.get("/api/v1/autopay/attempts", tenantToken);
        assertThat(attemptsOfTenant.body().at("/content/0/status").asText()).isEqualTo("SUCCEEDED");
        assertThat(attemptsOfTenant.body().at("/content/0/paymentId").asLong()).isPositive();

        Response mandates = api.get("/api/v1/autopay/mandates?propertyId=" + layout.propertyId(), managerToken);
        assertThat(mandates.body().get("content")).hasSize(1);
        assertThat(mandates.body().at("/content/0/authorisationUrl").isMissingNode()).isTrue();
        assertThat(api.get("/api/v1/autopay/mandates?propertyId=" + layout.propertyId(), otherManagerToken).status())
                .isEqualTo(403);
        assertThat(api.get("/api/v1/autopay/mandates", otherManagerToken).body().get("content")).isEmpty();
        assertThat(api.get("/api/v1/autopay/mandates", foreignAdminToken).body().get("content")).isEmpty();
        assertThat(api.get("/api/v1/autopay/attempts?propertyId=" + layout.propertyId(), adminToken).body().get("content"))
                .hasSize(1);
        assertThat(api.get("/api/v1/autopay/mandates", tenantToken).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/autopay/mandates/me", tenantToken).body()).hasSize(1);
        assertThat(api.post("/api/v1/billing/autopay/run?date=" + today, adminToken, null).status()).isEqualTo(403);

        // AUTOPAY is a system method: it cannot be recorded by hand
        Response manual = api.post("/api/v1/payments", adminToken, "{\"invoiceId\":" + depositInvoiceId
                + ",\"amount\":10,\"method\":\"AUTOPAY\"}", "Idempotency-Key", "it-autopay-" + System.nanoTime());
        assertThat(manual.status()).isEqualTo(400);
        assertThat(manual.body().get("code").asText()).isEqualTo("PAYMENT_METHOD_NOT_ALLOWED");
    }

    @Test
    void failedDebitsAreRetriedDailyUpToThreeTimesThenManagersAreTold() {
        makeRegularRentDue(rentInvoiceId, 0, "9000.13"); // sandbox: paise .13 always fails
        startAndAuthorise("20000");

        runDebits(today);
        runDebits(today);
        assertThat(attempts(rentInvoiceId)).as("one attempt per invoice per day").isEqualTo(1);
        long first = fixtures.jdbc().queryForObject("SELECT id FROM autopay_attempts WHERE invoice_id = ? AND attempt_no = 1",
                Long.class, rentInvoiceId);
        assertThat(fixtures.jdbc().queryForObject("SELECT status FROM autopay_attempts WHERE id = ?", String.class, first))
                .isEqualTo("FAILED");
        assertThat(notifications("AUTOPAY_DEBIT_FAILED", "AUTOPAY_ATTEMPT", first, "IN_APP")).as("tenant only").isEqualTo(1);
        assertThat(notifications("AUTOPAY_DEBIT_FAILED", "AUTOPAY_ATTEMPT", first, "SMS")).isEqualTo(1);

        runDebits(today.plusDays(1));
        runDebits(today.plusDays(2));
        runDebits(today.plusDays(3));

        assertThat(attempts(rentInvoiceId)).isEqualTo(3);
        long last = fixtures.jdbc().queryForObject("SELECT id FROM autopay_attempts WHERE invoice_id = ? AND attempt_no = 3",
                Long.class, rentInvoiceId);
        // the final failure also reaches the property's manager and the admin
        assertThat(notifications("AUTOPAY_DEBIT_FAILED", "AUTOPAY_ATTEMPT", last, "IN_APP")).isEqualTo(3);
        assertThat(fixtures.count("SELECT count(*) FROM payments WHERE invoice_id = ?", rentInvoiceId)).isZero();
        assertThat(status(rentInvoiceId)).isEqualTo("ISSUED");
    }

    @Test
    void pendingDebitIsCompletedByASignedWebhookAndReplaysAreIgnored() throws Exception {
        makeRegularRentDue(rentInvoiceId, 0, "9000.77"); // sandbox: paise .77 waits for the webhook
        startAndAuthorise("20000");
        runDebits(today);
        String paymentId = fixtures.jdbc().queryForObject("SELECT provider_payment_id FROM autopay_attempts WHERE invoice_id = ? "
                + "AND status = 'SCHEDULED'", String.class, rentInvoiceId);
        assertThat(paymentId).startsWith("sbx_pay_");
        assertThat(status(rentInvoiceId)).isEqualTo("ISSUED");

        String body = "{\"id\":\"evt_" + paymentId + "\",\"type\":\"payment.succeeded\",\"paymentId\":\"" + paymentId + "\"}";
        assertThat(webhook(body, "deadbeef").status()).isEqualTo(401);
        assertThat(webhook(body, null).status()).isEqualTo(401);
        assertThat(api.post("/api/v1/payments/webhooks/razorpay", null, body, "X-Sandbox-Signature", sign(body)).status())
                .isEqualTo(404);
        assertThat(status(rentInvoiceId)).isEqualTo("ISSUED");

        List<Callable<Response>> deliveries = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            deliveries.add(() -> webhook(body, sign(body)));
        }
        List<Response> results = ConcurrencySupport.runConcurrently(deliveries);
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(results).filteredOn(r -> "processed".equals(r.body().get("status").asText())).hasSize(1);
        assertThat(results).filteredOn(r -> "duplicate".equals(r.body().get("status").asText())).hasSize(3);

        // a different delivery of the same confirmation changes nothing either
        String redelivery = "{\"id\":\"evt2_" + paymentId + "\",\"type\":\"payment.succeeded\",\"paymentId\":\"" + paymentId
                + "\"}";
        assertThat(webhook(redelivery, sign(redelivery)).body().get("status").asText()).isEqualTo("processed");

        assertThat(status(rentInvoiceId)).isEqualTo("PAID");
        assertThat(fixtures.count("SELECT count(*) FROM payments WHERE invoice_id = ? AND method = 'AUTOPAY' "
                + "AND external_reference = ?", rentInvoiceId, paymentId)).isEqualTo(1);
        assertThat(fixtures.jdbc().queryForObject("SELECT status FROM autopay_attempts WHERE provider_payment_id = ?",
                String.class, paymentId)).isEqualTo("SUCCEEDED");
        // a late failure report never overrides the recorded success
        String failure = "{\"id\":\"evt3_" + paymentId + "\",\"type\":\"payment.failed\",\"paymentId\":\"" + paymentId
                + "\",\"reason\":\"late\"}";
        assertThat(webhook(failure, sign(failure)).status()).isEqualTo(200);
        assertThat(fixtures.jdbc().queryForObject("SELECT status FROM autopay_attempts WHERE provider_payment_id = ?",
                String.class, paymentId)).isEqualTo("SUCCEEDED");
    }

    @Test
    void residentControlsTheMandateAndOnlyTheirOwn() {
        makeRegularRentDue(rentInvoiceId, 0, "9000");
        long mandate = startAndAuthorise("20000");
        long other = fixtures.tenant(org, layout.propertyId());
        long otherUser = fixtures.user(org, 5, "resident2");
        fixtures.linkTenantUser(other, otherUser);
        String otherToken = api.login(org.email("resident2"));

        assertThat(api.post("/api/v1/autopay/mandates/" + mandate + "/pause", otherToken, null).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/autopay/mandates/" + mandate + "/pause", managerToken, null).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/autopay/mandates", otherToken, "{\"method\":\"UPI_AUTOPAY\",\"maxAmount\":5000}")
                .body().get("code").asText()).as("not checked in").isEqualTo("TENANT_NOT_RESIDENT");

        assertThat(api.post("/api/v1/autopay/mandates/" + mandate + "/pause", tenantToken, null).body().get("status").asText())
                .isEqualTo("PAUSED");
        runDebits(today);
        assertThat(attempts(rentInvoiceId)).as("paused mandates are skipped").isZero();
        assertThat(api.post("/api/v1/autopay/mandates/" + mandate + "/resume", tenantToken, null).body().get("status").asText())
                .isEqualTo("ACTIVE");
        Response cancelled = api.post("/api/v1/autopay/mandates/" + mandate + "/cancel", tenantToken,
                "{\"reason\":\"Moving to manual UPI\"}");
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.body().get("statusReason").asText()).isEqualTo("Moving to manual UPI");
        assertThat(api.post("/api/v1/autopay/mandates/" + mandate + "/resume", tenantToken, null).status()).isEqualTo(409);
        runDebits(today);
        assertThat(attempts(rentInvoiceId)).isZero();

        // a new mandate can be set up after cancelling; rejecting it at the "bank" fails it
        Response next = api.post("/api/v1/autopay/mandates", tenantToken, "{\"method\":\"ENACH\",\"maxAmount\":5000}");
        assertThat(next.status()).isEqualTo(201);
        assertThat(api.post("/api/v1/autopay/sandbox/mandates/" + next.id() + "/authorise", tenantToken, "{\"approve\":false}")
                .body().get("status").asText()).isEqualTo("FAILED");
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE entity_type = 'PAYMENT_MANDATE' AND entity_id = ?",
                mandate)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void balancesAboveTheMandateLimitAreNotDebitedAndProviderCancellationsAreMirrored() {
        makeRegularRentDue(rentInvoiceId, 0, "9000");
        long mandate = startAndAuthorise("5000");
        runDebits(today);
        assertThat(attempts(rentInvoiceId)).isZero();

        String providerId = fixtures.jdbc().queryForObject("SELECT provider_mandate_id FROM payment_mandates WHERE id = ?",
                String.class, mandate);
        String body = "{\"id\":\"evt_cancel_" + providerId + "\",\"type\":\"mandate.cancelled\",\"mandateId\":\"" + providerId
                + "\",\"reason\":\"Revoked in the UPI app\"}";
        assertThat(webhook(body, sign(body)).body().get("status").asText()).isEqualTo("processed");
        assertThat(api.get("/api/v1/autopay/mandates/me", tenantToken).body().get(0).get("status").asText())
                .isEqualTo("CANCELLED");
    }
}
