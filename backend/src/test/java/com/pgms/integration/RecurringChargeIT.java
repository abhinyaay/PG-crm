package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.billing.RentCalculator;
import com.pgms.integration.Api.Response;
import com.pgms.support.BusinessDates;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** Recurring monthly add-ons: snapshot into move-in and monthly rent invoices, editing, ending and access rules. */
class RecurringChargeIT extends AbstractCollectionsIT {

    private Response addCharge(String token, long tenant, String type, String amount, String start, String end,
                               Long allocation, boolean prorate) {
        return api.post("/api/v1/tenants/" + tenant + "/recurring-charges", token, "{\"type\":\"" + type + "\",\"description\":\""
                + type + " plan\",\"amount\":" + amount + ",\"startMonth\":\"" + start + "\",\"endMonth\":"
                + (end == null ? "null" : "\"" + end + "\"") + ",\"allocationId\":" + allocation + ",\"prorateFirstMonth\":"
                + prorate + "}");
    }

    private BigDecimal line(long invoiceId, String type) {
        return fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM invoice_line_items WHERE invoice_id = ? AND line_type = ?",
                invoiceId, type);
    }

    @Test
    void moveInInvoiceSnapshotsTheActiveChargesForTheMonth() {
        YearMonth month = BusinessDates.thisMonth();
        long tenant = fixtures.tenant(org, layout.propertyId());
        assertThat(addCharge(managerToken, tenant, "FOOD", "3500", month.toString(), null, null, false).status()).isEqualTo(201);
        assertThat(addCharge(managerToken, tenant, "INTERNET", "600", month.toString(), null, null, true).status()).isEqualTo(201);
        assertThat(addCharge(managerToken, tenant, "PARKING", "800", month.minusMonths(3).toString(), month.minusMonths(1).toString(),
                null, false).status()).isEqualTo(201);
        assertThat(addCharge(managerToken, tenant, "LAUNDRY", "400", month.plusMonths(1).toString(), null, null, false).status())
                .isEqualTo(201);

        checkIn(tenant, layout.bedIds().get(1));
        long rent = fixtures.jdbc().queryForObject("SELECT id FROM invoices WHERE tenant_id = ? AND invoice_type = 'RENT'",
                Long.class, tenant);

        assertThat(line(rent, "FOOD")).isEqualByComparingTo("3500");
        assertThat(line(rent, "INTERNET")).isEqualByComparingTo(RentCalculator.proratedRent(new BigDecimal("600"), today));
        assertThat(line(rent, "PARKING")).isEqualByComparingTo("0");
        assertThat(line(rent, "LAUNDRY")).isEqualByComparingTo("0");
        BigDecimal expectedTotal = line(rent, "RENT").add(new BigDecimal("3500")).add(line(rent, "INTERNET"));
        assertThat(fixtures.decimal("SELECT total_amount FROM invoices WHERE id = ?", rent)).isEqualByComparingTo(expectedTotal);
    }

    @Test
    void monthlyGenerationAddsChargesAsSnapshotsThatLaterEditsDoNotChange() {
        String month = BusinessDates.thisMonth().toString();
        Response food = addCharge(managerToken, tenantId, "FOOD", "3500", month, null, null, false);
        long foodId = food.id();
        assertThat(addCharge(managerToken, tenantId, "LAUNDRY", "450", month, null, allocationId, false).status()).isEqualTo(201);
        long otherAllocation = fixtures.jdbc().queryForObject("""
                INSERT INTO allocations (organization_id, property_id, tenant_id, bed_id, start_date, end_date, status, end_reason,
                                         monthly_rent, security_deposit)
                VALUES (?, ?, ?, ?, ?, ?, 'ENDED', 'CHECK_OUT', 9000, 0) RETURNING id""", Long.class, org.id(),
                layout.propertyId(), tenantId, layout.bedIds().get(3), today.minusYears(2), today.minusYears(2).plusDays(10));
        assertThat(addCharge(managerToken, tenantId, "PARKING", "700", month, null, otherAllocation, false).status())
                .isEqualTo(201);

        // re-bill the month as a regular month: the stay started on the 1st, void the move-in invoice, run generation
        fixtures.jdbc().update("UPDATE allocations SET start_date = ? WHERE id = ?", today.withDayOfMonth(1), allocationId);
        assertThat(api.post("/api/v1/invoices/" + rentInvoiceId + "/void", adminToken, "{\"reason\":\"re-bill\"}").status())
                .isEqualTo(200);
        assertThat(api.post("/api/v1/invoices/generate?month=" + month, rootToken, null).status()).isEqualTo(200);
        long rent = fixtures.jdbc().queryForObject("SELECT id FROM invoices WHERE allocation_id = ? AND invoice_type = 'RENT' "
                + "AND status <> 'VOID'", Long.class, allocationId);

        assertThat(line(rent, "FOOD")).isEqualByComparingTo("3500");
        assertThat(line(rent, "LAUNDRY")).as("pinned to this allocation").isEqualByComparingTo("450");
        assertThat(line(rent, "PARKING")).as("pinned to another allocation").isEqualByComparingTo("0");

        Response current = api.get("/api/v1/tenants/" + tenantId + "/recurring-charges", tenantToken);
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body()).hasSize(3);
        long version = current.body().get(0).get("version").asLong();
        String update = "{\"description\":\"Veg meals\",\"amount\":4000,\"endMonth\":null,\"prorateFirstMonth\":false,"
                + "\"active\":true,\"version\":%d}";
        assertThat(api.put("/api/v1/recurring-charges/" + foodId, managerToken, update.formatted(version + 5)).status())
                .isEqualTo(409);
        Response updated = api.put("/api/v1/recurring-charges/" + foodId, managerToken, update.formatted(version));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("amount").decimalValue()).isEqualByComparingTo("4000");
        assertThat(line(rent, "FOOD")).as("issued invoices keep their snapshot").isEqualByComparingTo("3500");

        assertThat(api.delete("/api/v1/recurring-charges/" + foodId, managerToken).status()).isEqualTo(204);
        assertThat(api.get("/api/v1/tenants/" + tenantId + "/recurring-charges", managerToken).body().get(0).get("active")
                .asBoolean()).isFalse();
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE entity_type = 'RECURRING_CHARGE' AND entity_id = ?",
                foodId)).isEqualTo(3);
    }

    @Test
    void validationAndAccessRules() {
        String month = BusinessDates.thisMonth().toString();
        assertThat(addCharge(managerToken, tenantId, "RENT", "100", month, null, null, false).status()).isEqualTo(400);
        assertThat(addCharge(managerToken, tenantId, "FOOD", "100", month, BusinessDates.thisMonth().minusMonths(1).toString(),
                null, false).status()).isEqualTo(400);
        assertThat(addCharge(managerToken, tenantId, "FOOD", "100", "2026-13", null, null, false).status()).isEqualTo(400);
        assertThat(addCharge(managerToken, tenantId, "FOOD", "0", month, null, null, false).status()).isEqualTo(400);
        assertThat(addCharge(managerToken, tenantId, "FOOD", "100", month, null, 999_999_999L, false).status()).isEqualTo(404);

        assertThat(addCharge(tenantToken, tenantId, "FOOD", "100", month, null, null, false).status()).isEqualTo(403);
        assertThat(addCharge(staffToken, tenantId, "FOOD", "100", month, null, null, false).status()).isEqualTo(403);
        assertThat(addCharge(otherManagerToken, tenantId, "FOOD", "100", month, null, null, false).status()).isEqualTo(403);
        assertThat(addCharge(foreignAdminToken, tenantId, "FOOD", "100", month, null, null, false).status()).isEqualTo(404);
        long id = addCharge(adminToken, tenantId, "FOOD", "100", month, null, null, false).id();

        assertThat(api.get("/api/v1/tenants/" + tenantId + "/recurring-charges", otherManagerToken).status()).isEqualTo(403);
        assertThat(api.delete("/api/v1/recurring-charges/" + id, otherManagerToken).status()).isEqualTo(403);
        assertThat(api.delete("/api/v1/recurring-charges/" + id, tenantToken).status()).isEqualTo(403);
        long other = fixtures.tenant(org, layout.propertyId());
        long otherUser = fixtures.user(org, 5, "resident2");
        fixtures.linkTenantUser(other, otherUser);
        assertThat(api.get("/api/v1/tenants/" + tenantId + "/recurring-charges", api.login(org.email("resident2"))).status())
                .isEqualTo(404);
    }
}
