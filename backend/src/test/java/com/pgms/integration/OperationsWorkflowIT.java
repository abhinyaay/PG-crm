package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import com.pgms.support.BusinessDates;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Maintenance, complaints, invoices, notifications and dashboards across the four operational roles. */
class OperationsWorkflowIT extends AbstractIntegrationTest {

    private Fixtures.Org org;
    private Fixtures.PropertyLayout layout;
    private long managerId;
    private long staffId;
    private long tenantId;
    private String adminToken;
    private String managerToken;
    private String staffToken;
    private String tenantToken;

    @BeforeEach
    void setUp() {
        org = fixtures.organization();
        layout = fixtures.property(org, 2);
        fixtures.user(org, 2, "admin");
        managerId = fixtures.user(org, 3, "manager");
        staffId = fixtures.user(org, 4, "staff");
        fixtures.assign(managerId, layout.propertyId());
        fixtures.assign(staffId, layout.propertyId());
        tenantId = fixtures.tenant(org, layout.propertyId());
        fixtures.linkTenantUser(tenantId, fixtures.user(org, 5, "resident"));
        adminToken = api.login(org.email("admin"));
        managerToken = api.login(org.email("manager"));
        staffToken = api.login(org.email("staff"));
        tenantToken = api.login(org.email("resident"));
        assertThat(api.post("/api/v1/allocations/check-in", managerToken, "{\"tenantId\":" + tenantId + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + BusinessDates.today() + "\"}").status()).isEqualTo(201);
    }

    @Test
    void maintenanceTicketFromTenantToClosure() {
        Response created = api.post("/api/v1/maintenance", tenantToken,
                "{\"category\":\"ELECTRICAL\",\"priority\":\"HIGH\",\"title\":\"Fan not working\",\"description\":\"Ceiling fan\"}");
        assertThat(created.status()).isEqualTo(201);
        long id = created.id();
        assertThat(created.body().get("roomId").asLong()).isEqualTo(layout.roomIds().get(0)); // derived from allocation

        assertThat(api.post("/api/v1/maintenance/" + id + "/assign", staffToken, "{\"assigneeUserId\":" + staffId + "}")
                .status()).isEqualTo(403);
        assertThat(api.post("/api/v1/maintenance/" + id + "/assign", managerToken, "{\"assigneeUserId\":" + staffId + "}")
                .status()).isEqualTo(200);
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", staffToken, "{\"status\":\"IN_PROGRESS\"}").status())
                .isEqualTo(200);
        Response noNotes = api.post("/api/v1/maintenance/" + id + "/status", staffToken, "{\"status\":\"RESOLVED\"}");
        assertThat(noNotes.status()).isEqualTo(422);
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", staffToken,
                "{\"status\":\"RESOLVED\",\"notes\":\"Replaced capacitor\"}").status()).isEqualTo(200);
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", tenantToken, "{\"status\":\"CLOSED\"}").status())
                .isEqualTo(200);

        JsonNode history = api.get("/api/v1/maintenance/" + id + "/history", managerToken).body();
        assertThat(history).hasSize(5);
        assertThat(api.get("/api/v1/maintenance?status=CLOSED", managerToken).body().get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/maintenance", tenantToken).body().get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/maintenance?assignedToMe=true", staffToken).body().get("totalElements").asLong())
                .isEqualTo(1);
        // the tenant was notified in-app about progress
        assertThat(api.get("/api/v1/notifications/unread-count", tenantToken).body().get("unread").asLong()).isPositive();
    }

    @Test
    void complaintWithInternalNotesAndEscalation() {
        Response created = api.post("/api/v1/complaints", tenantToken,
                "{\"category\":\"NOISE\",\"priority\":\"MEDIUM\",\"subject\":\"Loud music\",\"description\":\"Room 102 at night\"}");
        assertThat(created.status()).isEqualTo(201);
        long id = created.id();
        assertThat(api.post("/api/v1/complaints/" + id + "/assign", managerToken, "{\"assigneeUserId\":" + staffId + "}")
                .status()).isEqualTo(200);
        assertThat(api.post("/api/v1/complaints/" + id + "/comments", staffToken,
                "{\"body\":\"Repeat offender, warn formally\",\"internal\":true}").status()).isEqualTo(201);
        assertThat(api.post("/api/v1/complaints/" + id + "/comments", staffToken,
                "{\"body\":\"We have spoken to the resident\",\"internal\":false}").status()).isEqualTo(201);
        assertThat(api.post("/api/v1/complaints/" + id + "/comments", tenantToken, "{\"body\":\"x\",\"internal\":true}").status())
                .isEqualTo(403);

        assertThat(api.get("/api/v1/complaints/" + id + "/comments", tenantToken).body()).hasSize(1);
        assertThat(api.get("/api/v1/complaints/" + id + "/comments", managerToken).body()).hasSize(2);

        // tenants may escalate only after the deadline; managers any time
        assertThat(api.post("/api/v1/complaints/" + id + "/escalate", tenantToken, "{\"reason\":\"No action\"}").status())
                .isEqualTo(422);
        Response escalated = api.post("/api/v1/complaints/" + id + "/escalate", managerToken, "{\"reason\":\"Needs owner\"}");
        assertThat(escalated.body().get("status").asText()).isEqualTo("ESCALATED");
        assertThat(escalated.body().get("escalationLevel").asInt()).isEqualTo(1);

        assertThat(api.post("/api/v1/complaints/" + id + "/status", staffToken,
                "{\"status\":\"RESOLVED\",\"resolution\":\"Formal warning issued\"}").status()).isEqualTo(200);
        assertThat(api.post("/api/v1/complaints/" + id + "/status", tenantToken, "{\"status\":\"OPEN\"}").status()).isEqualTo(200);
        assertThat(api.get("/api/v1/complaints/" + id + "/history", managerToken).body().size()).isGreaterThanOrEqualTo(6);
        assertThat(api.get("/api/v1/complaints?minEscalationLevel=1", adminToken).body().get("totalElements").asLong())
                .isEqualTo(1);
    }

    @Test
    void invoiceAdjustmentsAndTenantViews() {
        JsonNode invoices = api.get("/api/v1/invoices?tenantId=" + tenantId + "&type=RENT", managerToken).body();
        long rentInvoice = invoices.at("/content/0/id").asLong();

        Response charged = api.post("/api/v1/invoices/" + rentInvoice + "/charges", managerToken,
                "{\"type\":\"ELECTRICITY\",\"description\":\"Sub-meter\",\"amount\":640.50}");
        assertThat(charged.status()).isEqualTo(200);
        assertThat(charged.body().get("lineItems")).hasSize(2);
        Response discounted = api.post("/api/v1/invoices/" + rentInvoice + "/discounts", managerToken,
                "{\"reason\":\"Early bird\",\"amount\":500}");
        assertThat(discounted.body().get("discountTotal").decimalValue()).isEqualByComparingTo("500");
        assertThat(api.post("/api/v1/invoices/" + rentInvoice + "/void", managerToken, "{\"reason\":\"x\"}").status())
                .isEqualTo(403);

        assertThat(api.get("/api/v1/invoices", tenantToken).body().get("totalElements").asLong()).isEqualTo(2);
        assertThat(api.get("/api/v1/invoices/" + rentInvoice, tenantToken).status()).isEqualTo(200);
        assertThat(api.get("/api/v1/payments", tenantToken).status()).isEqualTo(200);

        long payment = api.post("/api/v1/payments", managerToken, "{\"invoiceId\":" + rentInvoice
                + ",\"amount\":100,\"method\":\"CASH\"}", "Idempotency-Key", "ops-" + System.nanoTime()).id();
        Response receipt = api.get("/api/v1/payments/" + payment + "/receipt", tenantToken);
        assertThat(receipt.status()).isEqualTo(200);
        assertThat(receipt.raw().getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(receipt.raw().getResponse().getContentAsByteArray()).startsWith("%PDF".getBytes());
        assertThat(api.post("/api/v1/invoices/" + rentInvoice + "/void", adminToken, "{\"reason\":\"x\"}").status())
                .isEqualTo(409);
    }

    @Test
    void dashboardsReflectTheCallersScope() {
        JsonNode ops = api.get("/api/v1/dashboard", managerToken).body();
        assertThat(ops.get("activeTenants").asLong()).isEqualTo(1);
        assertThat(ops.at("/beds/total").asLong()).isEqualTo(4);
        assertThat(ops.at("/beds/occupied").asLong()).isEqualTo(1);
        assertThat(ops.get("outstandingRent").decimalValue()).isPositive();

        JsonNode mine = api.get("/api/v1/dashboard", tenantToken).body();
        assertThat(mine.get("tenantId").asLong()).isEqualTo(tenantId);
        assertThat(mine.get("roomNumber").asText()).isEqualTo("101");

        assertThat(api.get("/api/v1/properties/" + layout.propertyId() + "/occupancy", staffToken).body()
                .get("occupiedBeds").asLong()).isEqualTo(1);
    }

    @Test
    void notificationsInbox() {
        JsonNode inbox = api.get("/api/v1/notifications", tenantToken).body();
        assertThat(inbox.get("totalElements").asLong()).isGreaterThanOrEqualTo(2); // move-in + invoices
        long first = inbox.at("/content/0/id").asLong();
        assertThat(api.post("/api/v1/notifications/" + first + "/read", tenantToken, null).body().has("readAt")).isTrue();
        assertThat(api.post("/api/v1/notifications/" + first + "/read", managerToken, null).status()).isEqualTo(404);
        api.post("/api/v1/notifications/read-all", tenantToken, null);
        assertThat(api.get("/api/v1/notifications/unread-count", tenantToken).body().get("unread").asLong()).isZero();
    }

    @Test
    void tenantMayReopenButNotStartWorkAndLongNotesAreAccepted() {
        long id = api.post("/api/v1/maintenance", tenantToken,
                "{\"category\":\"PLUMBING\",\"priority\":\"LOW\",\"title\":\"Leak\",\"description\":\"Tap\"}").id();
        assertThat(api.post("/api/v1/maintenance/" + id + "/assign", managerToken, "{\"assigneeUserId\":" + staffId + "}")
                .status()).isEqualTo(200);
        // IN_PROGRESS is allowed to tenants only as "reopen a resolved ticket", never as "work has started"
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", tenantToken, "{\"status\":\"IN_PROGRESS\"}").status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", staffToken, "{\"status\":\"IN_PROGRESS\"}").status())
                .isEqualTo(200);
        // the API accepts notes up to 4000 chars; the history copy must not overflow its 1000-char column
        String longNotes = "Replaced the cartridge. ".repeat(60);
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", staffToken,
                api.toJson(Map.of("status", "RESOLVED", "notes", longNotes))).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/maintenance/" + id + "/status", tenantToken, "{\"status\":\"IN_PROGRESS\"}").status())
                .isEqualTo(200);
        assertThat(api.get("/api/v1/maintenance/" + id, managerToken).body().get("resolutionNotes").asText()).isEqualTo(longNotes);

        long complaint = api.post("/api/v1/complaints", tenantToken,
                "{\"category\":\"FOOD\",\"priority\":\"LOW\",\"subject\":\"Cold food\",\"description\":\"Dinner\"}").id();
        assertThat(api.post("/api/v1/complaints/" + complaint + "/status", managerToken,
                api.toJson(Map.of("status", "RESOLVED", "resolution", longNotes))).status()).isEqualTo(200);
    }

    @Test
    void newComplaintNotifiesThePropertyManagers() {
        long complaint = api.post("/api/v1/complaints", tenantToken,
                "{\"category\":\"NOISE\",\"priority\":\"HIGH\",\"subject\":\"Party\",\"description\":\"Room 101\"}").id();
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND channel = 'IN_APP' "
                + "AND reference_type = 'COMPLAINT' AND reference_id = ?", managerId, complaint)).isEqualTo(1);
    }
}
