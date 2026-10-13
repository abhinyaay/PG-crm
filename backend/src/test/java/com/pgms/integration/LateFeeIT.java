package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.billing.BillingJobs;
import com.pgms.billing.CollectionsJobs;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec 1: property billing policy, automatic late fees, waivers, the overdue reminder schedule and the digest. */
class LateFeeIT extends AbstractCollectionsIT {

    private static final String NO_UPI = "null";

    @Autowired
    private CollectionsJobs collectionsJobs;

    @Autowired
    private BillingJobs billingJobs;

    private Response runLateFees(LocalDate date) {
        Response run = api.post("/api/v1/billing/late-fees/run?date=" + date, rootToken, null);
        assertThat(run.status()).isEqualTo(200);
        return run;
    }

    private int entries(long invoiceId) {
        return fixtures.count("SELECT count(*) FROM late_fee_entries WHERE invoice_id = ? AND kind = 'CHARGE'", invoiceId);
    }

    private BigDecimal lateFeeLines(long invoiceId) {
        return fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM invoice_line_items WHERE invoice_id = ? "
                + "AND line_type = 'LATE_FEE'", invoiceId);
    }

    private void flatPolicy(String amount, int graceDays) {
        setPolicy(layout.propertyId(), "{\"enabled\":true,\"type\":\"FLAT\",\"value\":" + amount + ",\"graceDays\":" + graceDays
                + ",\"onMoveIn\":false}", "[1,3,7]", NO_UPI);
    }

    @Test
    void flatLateFeeIsChargedOnceAfterTheGracePeriodAndRerunsAreIdempotent() {
        flatPolicy("200", 3);
        makeRegularRentDue(rentInvoiceId, 3, "9000");
        markOverdue(rentInvoiceId);

        // due + 3 grace days: the first late day is tomorrow
        runLateFees(today);
        assertThat(entries(rentInvoiceId)).isZero();

        runLateFees(today.plusDays(1));
        runLateFees(today.plusDays(1));
        runLateFees(today.plusDays(2));

        assertThat(entries(rentInvoiceId)).isEqualTo(1);
        assertThat(lateFeeLines(rentInvoiceId)).isEqualByComparingTo("200");
        assertThat(balance(rentInvoiceId)).isEqualByComparingTo("9200");
        assertThat(fixtures.jdbc().queryForObject("SELECT period_key FROM late_fee_entries WHERE invoice_id = ?",
                LocalDate.class, rentInvoiceId)).isEqualTo(today.plusDays(1));
        assertThat(notifications("LATE_FEE_APPLIED", "INVOICE", rentInvoiceId, "IN_APP")).isEqualTo(1);
        assertThat(notifications("LATE_FEE_APPLIED", "INVOICE", rentInvoiceId, "SMS")).isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'LATE_FEE_APPLIED' AND entity_id = ?",
                rentInvoiceId)).isEqualTo(1);

        Response history = api.get("/api/v1/invoices/" + rentInvoiceId + "/late-fees", tenantToken);
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("charged").decimalValue()).isEqualByComparingTo("200");
        assertThat(history.body().get("lateFeeFrom").asText()).isEqualTo(today.plusDays(1).toString());
        assertThat(history.body().get("entries")).hasSize(1);
    }

    @Test
    void perDayFeeAccruesDailyAndStopsAtTheCap() {
        setPolicy(layout.propertyId(), "{\"enabled\":true,\"type\":\"PER_DAY\",\"value\":50,\"cap\":120,\"graceDays\":0}",
                "[1]", NO_UPI);
        makeRegularRentDue(rentInvoiceId, 1, "9000");
        markOverdue(rentInvoiceId);

        runLateFees(today);
        runLateFees(today);
        assertThat(lateFeeLines(rentInvoiceId)).isEqualByComparingTo("50");
        runLateFees(today.plusDays(1));
        assertThat(lateFeeLines(rentInvoiceId)).isEqualByComparingTo("100");
        // a missed day is caught up, but never beyond the cap
        runLateFees(today.plusDays(3));
        runLateFees(today.plusDays(4));
        assertThat(lateFeeLines(rentInvoiceId)).isEqualByComparingTo("120");
        assertThat(entries(rentInvoiceId)).isEqualTo(3);
    }

    @Test
    void percentOfRentIsComputedOnTheRentLinesAndRoundedHalfUp() {
        setPolicy(layout.propertyId(), "{\"enabled\":true,\"type\":\"PERCENT_OF_RENT\",\"value\":2.5,\"graceDays\":0}",
                "[1]", NO_UPI);
        makeRegularRentDue(rentInvoiceId, 2, "9999");
        markOverdue(rentInvoiceId);

        runLateFees(today);

        assertThat(lateFeeLines(rentInvoiceId)).isEqualByComparingTo("249.98");
    }

    @Test
    void moveInInvoicesDueOnTheirIssueDateAreExcludedOrGetGrace() {
        flatPolicy("300", 0);
        LocalDate issued = today.minusDays(3);
        fixtures.jdbc().update("""
                UPDATE invoices SET issue_date = ?, due_date = ?, period_start = billing_month + 5, status = 'OVERDUE'
                WHERE id = ?""", issued, issued, rentInvoiceId);

        runLateFees(today);
        assertThat(entries(rentInvoiceId)).as("move-in invoices excluded by default").isZero();

        setPolicy(layout.propertyId(), "{\"enabled\":true,\"type\":\"FLAT\",\"value\":300,\"graceDays\":0,"
                + "\"moveInGraceDays\":5,\"onMoveIn\":true}", "[1]", NO_UPI);
        runLateFees(today);
        runLateFees(today.plusDays(2));
        assertThat(entries(rentInvoiceId)).as("issued + 5 days grace: first late day is issue + 6").isZero();
        runLateFees(today.plusDays(3));
        assertThat(lateFeeLines(rentInvoiceId)).isEqualByComparingTo("300");
    }

    @Test
    void noFeeWithoutAnEnabledPolicyAndNeverOnDepositOrPaidInvoices() {
        makeRegularRentDue(rentInvoiceId, 10, "9000");
        markOverdue(rentInvoiceId);
        fixtures.jdbc().update("UPDATE invoices SET due_date = ?, status = 'OVERDUE' WHERE id = ?", today.minusDays(10),
                depositInvoiceId);

        runLateFees(today);
        assertThat(entries(rentInvoiceId)).as("default policy: late fees disabled").isZero();

        flatPolicy("200", 0);
        runLateFees(today);
        assertThat(entries(depositInvoiceId)).isZero();
        assertThat(entries(rentInvoiceId)).isEqualTo(1);

        // a second invoice that was paid in full before the run is left alone
        long other = fixtures.tenant(org, layout.propertyId());
        checkIn(other, layout.bedIds().get(1));
        long otherRent = fixtures.jdbc().queryForObject("SELECT id FROM invoices WHERE tenant_id = ? AND invoice_type = 'RENT'",
                Long.class, other);
        makeRegularRentDue(otherRent, 10, "9000");
        markOverdue(otherRent);
        assertThat(pay(otherRent, "9000").status()).isEqualTo(201);
        assertThat(status(otherRent)).isEqualTo("PAID");
        runLateFees(today);
        assertThat(entries(otherRent)).isZero();
    }

    @Test
    void concurrentPaymentAndLateFeeRunNeverLoseAnUpdate() throws Exception {
        flatPolicy("200", 0);
        makeRegularRentDue(rentInvoiceId, 5, "9000");
        markOverdue(rentInvoiceId);

        List<Callable<Response>> tasks = List.of(() -> pay(rentInvoiceId, "9000"), () -> runLateFees(today));
        List<Response> results = ConcurrencySupport.runConcurrently(tasks);

        assertThat(results.get(0).status()).isEqualTo(201);
        assertThat(fixtures.decimal("SELECT amount_paid FROM invoices WHERE id = ?", rentInvoiceId)).isEqualByComparingTo("9000");
        if (entries(rentInvoiceId) == 0) {
            assertThat(status(rentInvoiceId)).isEqualTo("PAID");
        } else {
            assertThat(balance(rentInvoiceId)).isEqualByComparingTo("200");
            assertThat(status(rentInvoiceId)).isEqualTo("OVERDUE");
        }
    }

    @Test
    void managersWaiveLateFeesWithinWhatWasChargedAndScopingApplies() {
        flatPolicy("200", 0);
        makeRegularRentDue(rentInvoiceId, 2, "9000");
        markOverdue(rentInvoiceId);
        runLateFees(today);
        String waive = "{\"amount\":150,\"reason\":\"First delay, goodwill\"}";

        assertThat(api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", otherManagerToken, waive).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", foreignAdminToken, waive).status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", staffToken, waive).status())
                .isEqualTo(403);
        assertThat(api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", tenantToken, waive).status())
                .isEqualTo(403);

        Response waived = api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", managerToken, waive);
        assertThat(waived.status()).isEqualTo(200);
        assertThat(waived.body().get("balance").decimalValue()).isEqualByComparingTo("9050");
        assertThat(waived.body().get("discountTotal").decimalValue()).isEqualByComparingTo("150");

        Response tooMuch = api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", managerToken,
                "{\"amount\":50.01,\"reason\":\"again\"}");
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().get("code").asText()).isEqualTo("WAIVER_EXCEEDS_LATE_FEES");
        assertThat(api.post("/api/v1/invoices/" + rentInvoiceId + "/late-fees/waive", managerToken,
                "{\"amount\":10,\"reason\":\"  \"}").status()).isEqualTo(400);

        Response history = api.get("/api/v1/invoices/" + rentInvoiceId + "/late-fees", managerToken);
        assertThat(history.body().get("charged").decimalValue()).isEqualByComparingTo("200");
        assertThat(history.body().get("waived").decimalValue()).isEqualByComparingTo("150");
        assertThat(history.body().get("net").decimalValue()).isEqualByComparingTo("50");
        assertThat(history.body().at("/entries/1/kind").asText()).isEqualTo("WAIVER");
        assertThat(history.body().at("/entries/1/reason").asText()).isEqualTo("First delay, goodwill");
        assertThat(api.get("/api/v1/invoices/" + rentInvoiceId + "/late-fees", otherManagerToken).status()).isEqualTo(403);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'LATE_FEE_WAIVED' AND entity_id = ?",
                rentInvoiceId)).isEqualTo(1);
        assertThat(notifications("LATE_FEE_WAIVED", "INVOICE", rentInvoiceId, "IN_APP")).isEqualTo(1);

        // another tenant cannot read this invoice's late fees
        long other = fixtures.tenant(org, layout.propertyId());
        long otherUser = fixtures.user(org, 5, "resident2");
        fixtures.linkTenantUser(other, otherUser);
        assertThat(api.get("/api/v1/invoices/" + rentInvoiceId + "/late-fees", api.login(org.email("resident2"))).status())
                .isEqualTo(404);
        assertThat(api.post("/api/v1/billing/late-fees/run?date=" + today, adminToken, null).status()).isEqualTo(403);
    }

    @Test
    void policyEndpointsValidateScopeAndUseOptimisticLocking() {
        String path = "/api/v1/properties/" + layout.propertyId() + "/billing-policy";
        Response defaults = api.get(path, managerToken);
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.body().get("configured").asBoolean()).isFalse();
        assertThat(defaults.body().at("/lateFee/enabled").asBoolean()).isFalse();
        assertThat(defaults.body().at("/reminders/overdueOffsets").toString()).isEqualTo("[1,5,10]");
        assertThat(api.get(path, otherManagerToken).status()).isEqualTo(403);
        assertThat(api.get(path, foreignAdminToken).status()).isEqualTo(404);
        assertThat(api.get(path, staffToken).status()).isEqualTo(403);

        String body = "{\"lateFee\":{\"enabled\":true,\"type\":\"FLAT\",\"value\":250,\"graceDays\":2},"
                + "\"reminders\":{\"dueDaysBefore\":2,\"overdueOffsets\":[10,1,5]},"
                + "\"upi\":{\"vpa\":\"greennest@okhdfc\",\"payeeName\":\"Green Nest PG\"},\"version\":%s}";
        assertThat(api.put(path, managerToken, body.formatted("null")).status()).isEqualTo(403);
        Response created = api.put(path, adminToken, body.formatted("null"));
        assertThat(created.status()).isEqualTo(200);
        assertThat(created.body().get("configured").asBoolean()).isTrue();
        assertThat(created.body().at("/reminders/overdueOffsets").toString()).isEqualTo("[1,5,10]");
        long version = created.body().get("version").asLong();

        Response stale = api.put(path, adminToken, body.formatted(version + 1));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code").asText()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(api.put(path, adminToken, body.formatted("null")).status()).isEqualTo(409);
        assertThat(api.put(path, adminToken, body.formatted(version)).status()).isEqualTo(200);
        assertThat(fixtures.count("SELECT count(*) FROM audit_logs WHERE action = 'PROPERTY_POLICY_UPDATED' AND entity_id = ?",
                layout.propertyId())).isEqualTo(2);

        String invalid = "{\"lateFee\":{\"enabled\":true,\"type\":\"%s\",\"value\":%s,\"graceDays\":0},"
                + "\"reminders\":{\"dueDaysBefore\":2,\"overdueOffsets\":%s},\"upi\":{\"vpa\":\"%s\"},\"version\":null}";
        String other = "/api/v1/properties/" + otherProperty.propertyId() + "/billing-policy";
        assertThat(api.put(other, adminToken, invalid.formatted("PERCENT_OF_RENT", "101", "[1]", "a@b1")).status())
                .isEqualTo(400);
        assertThat(api.put(other, adminToken, invalid.formatted("FLAT", "10", "[1,1]", "pg@okaxis")).status()).isEqualTo(400);
        assertThat(api.put(other, adminToken, invalid.formatted("FLAT", "10", "[0]", "pg@okaxis")).status()).isEqualTo(400);
        assertThat(api.put(other, adminToken, invalid.formatted("FLAT", "10", "[]", "pg@okaxis")).status()).isEqualTo(400);
        assertThat(api.put(other, adminToken, invalid.formatted("FLAT", "0", "[1]", "pg@okaxis")).status()).isEqualTo(400);
        assertThat(api.put(other, adminToken, invalid.formatted("FLAT", "10", "[1]", "not a vpa")).status()).isEqualTo(400);
        assertThat(api.put(other, foreignAdminToken, invalid.formatted("FLAT", "10", "[1]", "pg@okaxis")).status())
                .isEqualTo(404);
    }

    @Test
    void overdueRemindersGoOutOncePerConfiguredOffset() {
        setPolicy(layout.propertyId(), "{\"enabled\":false,\"type\":\"FLAT\",\"value\":0,\"graceDays\":0}", "[1,3]", NO_UPI);
        makeRegularRentDue(rentInvoiceId, 3, "9000");

        // overdue marking (normally 01:00) sends the D+3 notice; the 09:15 reminder job must not repeat it
        billingJobs.markOverdueInvoices();
        assertThat(status(rentInvoiceId)).isEqualTo("OVERDUE");
        collectionsJobs.runOverdueReminders(today);
        collectionsJobs.runOverdueReminders(today);
        assertThat(notifications("PAYMENT_OVERDUE", "INVOICE", rentInvoiceId, "IN_APP")).isEqualTo(1);
        assertThat(notifications("PAYMENT_OVERDUE", "INVOICE", rentInvoiceId, "SMS")).isEqualTo(1);

        collectionsJobs.runOverdueReminders(today.plusDays(1)); // D+4: not an offset
        assertThat(notifications("PAYMENT_OVERDUE", "INVOICE", rentInvoiceId, "IN_APP")).isEqualTo(1);
        collectionsJobs.runOverdueReminders(today.minusDays(2)); // D+1
        collectionsJobs.runOverdueReminders(today.minusDays(2));
        assertThat(notifications("PAYMENT_OVERDUE", "INVOICE", rentInvoiceId, "IN_APP")).isEqualTo(2);
        assertThat(fixtures.jdbc().queryForList("SELECT dedup_key FROM notifications WHERE type = 'PAYMENT_OVERDUE' "
                + "AND reference_id = ? AND channel = 'IN_APP' ORDER BY id", String.class, rentInvoiceId))
                .allSatisfy(k -> assertThat(k).startsWith("PAYMENT_OVERDUE:" + rentInvoiceId + ":D"))
                .anySatisfy(k -> assertThat(k).contains(":D3:"))
                .anySatisfy(k -> assertThat(k).contains(":D1:"));
    }

    @Test
    void dueRemindersUseThePropertysDaysBefore() {
        setPolicy(layout.propertyId(), "{\"enabled\":false,\"type\":\"FLAT\",\"value\":0,\"graceDays\":0}", "[1]", NO_UPI);
        fixtures.jdbc().update("UPDATE invoices SET due_date = ?, status = 'ISSUED' WHERE id = ?", today.plusDays(3),
                rentInvoiceId);

        collectionsJobs.runDueReminders(today.plusDays(1)); // T-2: policy says T-3
        assertThat(notifications("PAYMENT_DUE", "INVOICE", rentInvoiceId, "IN_APP")).isZero();
        collectionsJobs.runDueReminders(today);
        collectionsJobs.runDueReminders(today);
        assertThat(notifications("PAYMENT_DUE", "INVOICE", rentInvoiceId, "IN_APP")).isEqualTo(1);
    }

    @Test
    void overdueDigestReachesManagersDailyAndAdminsOnMondays() {
        makeRegularRentDue(rentInvoiceId, 4, "9000");
        markOverdue(rentInvoiceId);
        long managerId = fixtures.jdbc().queryForObject("SELECT id FROM users WHERE email = ?", Long.class,
                org.email("manager"));
        long adminId = fixtures.jdbc().queryForObject("SELECT id FROM users WHERE email = ?", Long.class, org.email("admin"));
        LocalDate notMonday = today.getDayOfWeek() == DayOfWeek.MONDAY ? today.plusDays(1) : today;
        LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));

        collectionsJobs.runOverdueDigest(notMonday);
        collectionsJobs.runOverdueDigest(notMonday);
        String digests = "SELECT count(*) FROM notifications WHERE type = 'OVERDUE_DIGEST' AND reference_id = ? "
                + "AND channel = 'IN_APP' AND recipient_user_id = ?";
        assertThat(fixtures.count(digests, layout.propertyId(), managerId)).isEqualTo(1);
        assertThat(fixtures.count(digests, layout.propertyId(), adminId)).isZero();
        assertThat(fixtures.jdbc().queryForObject("SELECT body FROM notifications WHERE type = 'OVERDUE_DIGEST' "
                + "AND reference_id = ? AND recipient_user_id = ?", String.class, layout.propertyId(), managerId))
                .contains("1 invoice is overdue").contains("9000");

        collectionsJobs.runOverdueDigest(monday);
        assertThat(fixtures.count(digests, layout.propertyId(), managerId)).isEqualTo(2);
        assertThat(fixtures.count(digests, layout.propertyId(), adminId)).isEqualTo(1);
    }
}
