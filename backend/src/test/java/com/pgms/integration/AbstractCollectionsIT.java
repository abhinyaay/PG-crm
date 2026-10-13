package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import com.pgms.support.BusinessDates;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;

/**
 * A checked-in resident with a portal login at a property with an assigned manager, plus an admin, a STAFF user, a
 * manager of another property and an admin of another organization - the cast for collections scenarios.
 */
abstract class AbstractCollectionsIT extends AbstractIntegrationTest {

    protected Fixtures.Org org;
    protected Fixtures.PropertyLayout layout;
    protected Fixtures.PropertyLayout otherProperty;
    protected LocalDate today;
    protected long tenantId;
    protected long allocationId;
    protected long rentInvoiceId;
    protected long depositInvoiceId;
    protected String adminToken;
    protected String managerToken;
    protected String staffToken;
    protected String otherManagerToken;
    protected String foreignAdminToken;
    protected String tenantToken;
    protected String rootToken;

    @BeforeEach
    void setUpTenancy() {
        today = BusinessDates.today();
        org = fixtures.organization();
        layout = fixtures.property(org, 2);
        otherProperty = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        long manager = fixtures.user(org, 3, "manager");
        fixtures.assign(manager, layout.propertyId());
        long staff = fixtures.user(org, 4, "staff");
        fixtures.assign(staff, layout.propertyId());
        long otherManager = fixtures.user(org, 3, "othermanager");
        fixtures.assign(otherManager, otherProperty.propertyId());
        Fixtures.Org foreign = fixtures.organization();
        fixtures.user(foreign, 2, "admin");

        adminToken = api.login(org.email("admin"));
        managerToken = api.login(org.email("manager"));
        staffToken = api.login(org.email("staff"));
        otherManagerToken = api.login(org.email("othermanager"));
        foreignAdminToken = api.login(foreign.email("admin"));
        rootToken = api.login("root@platform.test");

        tenantId = fixtures.tenant(org, layout.propertyId());
        long tenantUser = fixtures.user(org, 5, "resident");
        fixtures.linkTenantUser(tenantId, tenantUser);
        tenantToken = api.login(org.email("resident"));
        checkIn(tenantId, layout.bedIds().get(0));
    }

    protected void checkIn(long tenant, long bedId) {
        Response checkIn = api.post("/api/v1/allocations/check-in", adminToken, "{\"tenantId\":" + tenant + ",\"bedId\":"
                + bedId + ",\"checkInDate\":\"" + today + "\"}");
        assertThat(checkIn.status()).as("check-in: %s", checkIn.body()).isEqualTo(201);
        if (tenant == tenantId) {
            allocationId = checkIn.body().at("/allocation/id").asLong();
            for (JsonNode invoice : checkIn.body().get("invoices")) {
                if ("RENT".equals(invoice.get("type").asText())) {
                    rentInvoiceId = invoice.get("id").asLong();
                } else {
                    depositInvoiceId = invoice.get("id").asLong();
                }
            }
        }
    }

    /**
     * Turns the rent invoice into a regular (not move-in) month's rent of {@code amount} issued 25 days ago and due
     * {@code daysAgo} days ago, still unpaid and ISSUED (the overdue marking is left to the caller).
     */
    protected void makeRegularRentDue(long invoiceId, int daysAgo, String amount) {
        LocalDate due = today.minusDays(daysAgo);
        fixtures.jdbc().update("UPDATE invoice_line_items SET amount = ? WHERE invoice_id = ? AND line_type = 'RENT'",
                new BigDecimal(amount), invoiceId);
        fixtures.jdbc().update("""
                UPDATE invoices SET subtotal = ?, total_amount = ?, discount_total = 0, amount_paid = 0, status = 'ISSUED',
                       issue_date = ?, due_date = ?, period_start = billing_month
                WHERE id = ?""", new BigDecimal(amount), new BigDecimal(amount), due.minusDays(25), due, invoiceId);
    }

    protected void markOverdue(long invoiceId) {
        fixtures.jdbc().update("UPDATE invoices SET status = 'OVERDUE' WHERE id = ?", invoiceId);
    }

    protected String status(long invoiceId) {
        return fixtures.jdbc().queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId);
    }

    protected BigDecimal balance(long invoiceId) {
        return fixtures.decimal("SELECT total_amount - amount_paid FROM invoices WHERE id = ?", invoiceId);
    }

    protected int notifications(String type, String referenceType, long referenceId, String channel) {
        return fixtures.count("SELECT count(*) FROM notifications WHERE type = ? AND reference_type = ? AND reference_id = ? "
                + "AND channel = ?", type, referenceType, referenceId, channel);
    }

    protected Response pay(long invoiceId, String amount) {
        return api.post("/api/v1/payments", adminToken, "{\"invoiceId\":" + invoiceId + ",\"amount\":" + amount
                + ",\"method\":\"CASH\"}", "Idempotency-Key", "it-pay-" + System.nanoTime());
    }

    protected void setPolicy(long propertyId, String lateFeeJson, String offsets, String upi) {
        Response current = api.get("/api/v1/properties/" + propertyId + "/billing-policy", adminToken);
        String version = current.body().get("configured").asBoolean() ? current.body().get("version").asText() : "null";
        Response put = api.put("/api/v1/properties/" + propertyId + "/billing-policy", adminToken, "{\"lateFee\":" + lateFeeJson
                + ",\"reminders\":{\"dueDaysBefore\":3,\"overdueOffsets\":" + offsets + "},\"upi\":" + upi
                + ",\"version\":" + version + "}");
        assertThat(put.status()).as("policy: %s", put.body()).isEqualTo(200);
    }
}
