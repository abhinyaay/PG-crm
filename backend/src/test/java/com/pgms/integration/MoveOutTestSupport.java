package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Shared set-up for the move-out / deposit ITs: an organization with an owner (PROPERTY_ADMIN), an assigned manager
 * and staff member, a 2-room property (4 beds, rent 9000, deposit 18000) and helpers. Dates come from the application
 * clock (business time zone), never from the JVM default zone.
 */
abstract class MoveOutTestSupport extends AbstractIntegrationTest {

    private static final AtomicLong KEYS = new AtomicLong();

    @Autowired
    protected Clock clock;

    protected Fixtures.Org org;
    protected Fixtures.PropertyLayout layout;
    protected long adminUserId;
    protected String admin;
    protected String manager;
    protected String staff;
    protected LocalDate today;

    @BeforeEach
    void setUpOrganization() {
        org = fixtures.organization();
        layout = fixtures.property(org, 2);
        adminUserId = fixtures.user(org, 2, "admin");
        long managerId = fixtures.user(org, 3, "manager");
        long staffId = fixtures.user(org, 4, "staff");
        fixtures.assign(managerId, layout.propertyId());
        fixtures.assign(staffId, layout.propertyId());
        admin = api.login(org.email("admin"));
        manager = api.login(org.email("manager"));
        staff = api.login(org.email("staff"));
        today = LocalDate.now(clock);
    }

    protected static String key() {
        return "it-dep-" + KEYS.incrementAndGet() + "-" + System.nanoTime();
    }

    /** A resident with a portal login; returns {tenantId, token}. */
    protected Object[] residentWithLogin(String local) {
        long tenantId = fixtures.tenant(org, layout.propertyId());
        long userId = fixtures.user(org, 5, local);
        fixtures.linkTenantUser(tenantId, userId);
        return new Object[] {tenantId, api.login(org.email(local))};
    }

    protected JsonNode checkIn(long tenantId, long bedId, LocalDate date) {
        Response r = api.post("/api/v1/allocations/check-in", admin, "{\"tenantId\":" + tenantId + ",\"bedId\":" + bedId
                + ",\"checkInDate\":\"" + date + "\"}");
        assertThat(r.status()).as("check-in %s", r.body()).isEqualTo(201);
        return r.body();
    }

    protected void pay(long invoiceId, BigDecimal amount) {
        Response r = api.post("/api/v1/payments", admin, "{\"invoiceId\":" + invoiceId + ",\"amount\":" + amount.toPlainString()
                + ",\"method\":\"UPI\"}", "Idempotency-Key", key());
        assertThat(r.status()).as("payment %s", r.body()).isEqualTo(201);
    }

    /** Pays every open invoice of the resident in full. */
    protected void payEverything(long tenantId) {
        for (Map<String, Object> row : openInvoices(tenantId)) {
            pay(((Number) row.get("id")).longValue(), (BigDecimal) row.get("balance"));
        }
    }

    protected List<Map<String, Object>> openInvoices(long tenantId) {
        return fixtures.jdbc().queryForList("SELECT id, invoice_type, total_amount - amount_paid AS balance FROM invoices "
                + "WHERE tenant_id = ? AND status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE') ORDER BY id", tenantId);
    }

    protected BigDecimal openBalance(long tenantId) {
        return fixtures.decimal("SELECT coalesce(sum(total_amount - amount_paid), 0) FROM invoices WHERE tenant_id = ? "
                + "AND status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE')", tenantId);
    }

    protected long accountOf(long tenantId) {
        return fixtures.jdbc().queryForObject("SELECT id FROM deposit_accounts WHERE tenant_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, tenantId);
    }

    protected Response legacyCheckOut(long allocationId, LocalDate date, String deductionsJson) {
        return api.post("/api/v1/allocations/" + allocationId + "/check-out", admin, "{\"checkOutDate\":\"" + date + "\""
                + (deductionsJson == null ? "" : ",\"deductions\":" + deductionsJson) + "}");
    }

    protected void setPolicy(int noticeDays, String finalMonth) {
        Response r = api.put("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", admin, "{\"noticePeriodDays\":"
                + noticeDays + ",\"noticeShortfallRule\":\"CHARGE_DAYS\",\"lockInMonths\":0,\"lockInPenaltyRule\":\"NONE\","
                + "\"finalMonthBilling\":\"" + finalMonth + "\",\"depositRefundDays\":7,"
                + "\"inspectionChecklist\":[\"Chair\",\"Walls\",\"Keys returned\"]}");
        assertThat(r.status()).as("policy %s", r.body()).isEqualTo(200);
    }

    protected Response refund(long accountId, String key, String body) {
        return api.post("/api/v1/deposits/accounts/" + accountId + "/refund", admin, body, "Idempotency-Key", key);
    }
}
