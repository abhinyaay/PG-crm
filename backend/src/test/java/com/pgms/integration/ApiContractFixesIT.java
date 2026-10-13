package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Small API contract fixes requested by the frontend team. */
class ApiContractFixesIT extends AbstractIntegrationTest {

    /** The API validates dates in the business time zone; the JVM running the tests may be in UTC. */
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    private Fixtures.Org org;
    private Fixtures.PropertyLayout layout;
    private String token;

    @BeforeEach
    void setUp() {
        org = fixtures.organization();
        layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        token = api.login(org.email("admin"));
    }

    @Test
    void wrongCurrentPasswordIsAValidationErrorNotAnAuthenticationFailure() {
        Response wrong = api.post("/api/v1/auth/change-password", token,
                "{\"currentPassword\":\"NotMyPassword1\",\"newPassword\":\"BrandNew123\"}");
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.body().get("code").asText()).isEqualTo("INVALID_CURRENT_PASSWORD");
        // the session is untouched
        assertThat(api.get("/api/v1/properties/" + layout.propertyId(), token).status()).isEqualTo(200);
    }

    @Test
    void missingHeadersAndBadParametersGetHumanMessages() {
        Response noKey = api.post("/api/v1/payments", token, "{\"invoiceId\":1,\"amount\":100,\"method\":\"UPI\"}");
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(noKey.body().get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(noKey.body().get("message").asText()).contains("Idempotency-Key").doesNotContain("java", "String");

        Response notANumber = api.get("/api/v1/beds?propertyId=abc", token);
        assertThat(notANumber.status()).isEqualTo(400);
        assertThat(notANumber.body().get("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(notANumber.body().get("message").asText()).isEqualTo("Invalid value for 'propertyId': expected a number");
        assertThat(notANumber.body().at("/fieldErrors/0/rejectedValue").asText()).isEqualTo("abc");

        Response badEnum = api.get("/api/v1/beds?status=BROKEN", token);
        assertThat(badEnum.body().get("message").asText()).contains("AVAILABLE").doesNotContain("com.pgms", "java");

        Response badDate = api.get("/api/v1/allocations?expectedEndBefore=tomorrow", token);
        assertThat(badDate.status()).isEqualTo(400);
        assertThat(badDate.body().get("message").asText()).doesNotContain("java", "LocalDate");

        Response noParam = api.post("/api/v1/rooms", token, "{}");
        assertThat(noParam.status()).isEqualTo(400);
        assertThat(noParam.body().get("message").asText()).isEqualTo("Required query parameter 'propertyId' is missing");
    }

    @Test
    void moneyIsSerialisedWithTwoDecimalsForFreshAndReplayedPayments() throws Exception {
        long tenant = fixtures.tenant(org, layout.propertyId());
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenant + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + TODAY + "\"}");
        long deposit = 0;
        for (JsonNode invoice : checkIn.body().get("invoices")) {
            if (invoice.get("type").asText().equals("SECURITY_DEPOSIT")) {
                deposit = invoice.get("id").asLong();
            }
        }
        String body = "{\"invoiceId\":" + deposit + ",\"amount\":100,\"method\":\"CASH\"}";
        String key = "money-" + System.nanoTime();
        Response fresh = api.post("/api/v1/payments", token, body, "Idempotency-Key", key);
        Response replay = api.post("/api/v1/payments", token, body, "Idempotency-Key", key);
        assertThat(fresh.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        String freshJson = fresh.raw().getResponse().getContentAsString();
        String replayJson = replay.raw().getResponse().getContentAsString();
        assertThat(freshJson).contains("\"amount\":100.00").contains("\"refundedAmount\":0.00");
        assertThat(replayJson).contains("\"amount\":100.00").contains("\"refundedAmount\":0.00");
        assertThat(fresh.body().get("tenantName").asText()).startsWith("Tenant ");
        assertThat(replay.body().get("tenantName").asText()).isEqualTo(fresh.body().get("tenantName").asText());

        // names on list endpoints too
        assertThat(api.get("/api/v1/payments?propertyId=" + layout.propertyId(), token).body().at("/content/0/tenantName")
                .asText()).startsWith("Tenant ");
        JsonNode invoices = api.get("/api/v1/invoices?propertyId=" + layout.propertyId(), token).body();
        assertThat(invoices.get("content")).allSatisfy(i -> assertThat(i.get("tenantName").asText()).startsWith("Tenant "));
        assertThat(api.get("/api/v1/invoices/" + deposit, token).body().get("tenantName").asText()).startsWith("Tenant ");
        assertThat(api.get("/api/v1/tenants/" + tenant + "/balance", token).body().at("/openInvoices/0/tenantName").asText())
                .startsWith("Tenant ");
    }

    @Test
    void ticketsCarryTheNamesOfTheirPeople() {
        long staff = fixtures.user(org, 4, "staff");
        fixtures.assign(staff, layout.propertyId());
        Response ticket = api.post("/api/v1/maintenance", token, "{\"propertyId\":" + layout.propertyId() + ",\"roomId\":"
                + layout.roomIds().get(0) + ",\"category\":\"PLUMBING\",\"priority\":\"LOW\",\"title\":\"Tap\",\"description\":\"Leak\"}");
        assertThat(ticket.status()).isEqualTo(201);
        assertThat(ticket.body().get("raisedByName").asText()).isEqualTo("admin");
        Response assigned = api.post("/api/v1/maintenance/" + ticket.id() + "/assign", token, "{\"assigneeUserId\":" + staff + "}");
        assertThat(assigned.body().get("assignedToName").asText()).isEqualTo("staff");
        assertThat(api.get("/api/v1/maintenance?propertyId=" + layout.propertyId(), token).body().at("/content/0/assignedToName")
                .asText()).isEqualTo("staff");

        Response complaint = api.post("/api/v1/complaints", token, "{\"propertyId\":" + layout.propertyId()
                + ",\"category\":\"NOISE\",\"priority\":\"LOW\",\"subject\":\"Noise\",\"description\":\"At night\"}");
        assertThat(complaint.status()).isEqualTo(201);
        assertThat(complaint.body().get("raisedByName").asText()).isEqualTo("admin");
        Response assignedComplaint = api.post("/api/v1/complaints/" + complaint.id() + "/assign", token,
                "{\"assigneeUserId\":" + staff + "}");
        assertThat(assignedComplaint.body().get("assignedToName").asText()).isEqualTo("staff");
        assertThat(api.get("/api/v1/complaints?propertyId=" + layout.propertyId(), token).body().at("/content/0/raisedByName")
                .asText()).isEqualTo("admin");
    }
}
