package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.deposit.DepositAccountBackfill;
import com.pgms.deposit.DepositJobs;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The owner's Deposits tab: list + totals, ledger, refund / deduction / forfeit / top-up, overdue job. */
class DepositsTabIT extends MoveOutTestSupport {

    @Autowired
    private DepositJobs depositJobs;

    @Autowired
    private DepositAccountBackfill backfill;

    /** A resident checked in 10 days ago with deposit and rent paid, then checked out today: refund due. */
    private long movedOutWithRefundDue(long bedId) {
        long tenantId = fixtures.tenant(org, layout.propertyId());
        long allocation = checkIn(tenantId, bedId, today.minusDays(10)).at("/allocation/id").asLong();
        payEverything(tenantId);
        Response out = legacyCheckOut(allocation, today, null);
        assertThat(out.status()).as("%s", out.body()).isEqualTo(200);
        return tenantId;
    }

    @Test
    void listShowsEveryStayWithTotalsAndRespectsRoles() {
        long active = fixtures.tenant(org, layout.propertyId());
        checkIn(active, layout.bedIds().get(0), today.minusDays(10));
        payEverything(active);
        long movedOut = movedOutWithRefundDue(layout.bedIds().get(2));
        long movedOutAccount = accountOf(movedOut);
        BigDecimal due = fixtures.decimal("SELECT refund_due FROM deposit_accounts WHERE id = ?", movedOutAccount);
        fixtures.jdbc().update("UPDATE deposit_accounts SET refund_due_by = ? WHERE id = ?", today.minusDays(1), movedOutAccount);

        Response list = api.get("/api/v1/deposits?propertyId=" + layout.propertyId(), admin);
        assertThat(list.status()).isEqualTo(200);
        JsonNode totals = list.body().get("totals");
        assertThat(totals.get("totalHeld").decimalValue()).isEqualByComparingTo("18000");
        assertThat(totals.get("heldCount").asLong()).isEqualTo(1);
        assertThat(totals.get("totalRefundDue").decimalValue()).isEqualByComparingTo(due);
        assertThat(totals.get("refundDueCount").asLong()).isEqualTo(1);
        assertThat(totals.get("overdueRefundCount").asLong()).isEqualTo(1);
        assertThat(totals.get("overdueRefundAmount").decimalValue()).isEqualByComparingTo(due);
        assertThat(list.body().get("content")).hasSize(2);

        JsonNode held = api.get("/api/v1/deposits?propertyId=" + layout.propertyId() + "&status=HELD", admin).body();
        assertThat(held.get("content")).singleElement().satisfies(row -> {
            assertThat(row.get("tenantId").asLong()).isEqualTo(active);
            assertThat(row.get("collected").decimalValue()).isEqualByComparingTo("18000");
            assertThat(row.get("held").decimalValue()).isEqualByComparingTo("18000");
            assertThat(row.get("refundOverdue").asBoolean()).isFalse();
        });
        assertThat(held.get("totals").get("refundDueCount").asLong()).isEqualTo(1); // totals ignore the status filter
        JsonNode overdue = api.get("/api/v1/deposits?overdue=true&sort=tenantName,asc", admin).body();
        assertThat(overdue.get("content")).singleElement().satisfies(row -> {
            assertThat(row.get("tenantId").asLong()).isEqualTo(movedOut);
            assertThat(row.get("status").asText()).isEqualTo("REFUND_DUE");
            assertThat(row.get("refundOverdue").asBoolean()).isTrue();
        });
        assertThat(api.get("/api/v1/deposits?sort=secret,asc", admin).status()).isEqualTo(400);

        // managers read their properties only and cannot move money; residents see only their own ledger
        assertThat(api.get("/api/v1/deposits?propertyId=" + layout.propertyId(), manager).body().get("content")).hasSize(2);
        assertThat(api.post("/api/v1/deposits/accounts/" + movedOutAccount + "/refund", manager, "{\"method\":\"UPI\"}",
                "Idempotency-Key", key()).status()).isEqualTo(403);
        assertThat(api.post("/api/v1/deposits/accounts/" + accountOf(active) + "/deductions", manager,
                "{\"description\":\"x\",\"amount\":1}").status()).isEqualTo(403);
        fixtures.user(org, 3, "other-manager");
        String outsider = api.login(org.email("other-manager"));
        assertThat(api.get("/api/v1/deposits?propertyId=" + layout.propertyId(), outsider).status()).isEqualTo(403);
        assertThat(api.get("/api/v1/deposits", outsider).body().get("content")).isEmpty();
        assertThat(api.get("/api/v1/deposits", staff).status()).isEqualTo(403);

        Fixtures.Org otherOrg = fixtures.organization();
        fixtures.user(otherOrg, 2, "admin");
        assertThat(api.get("/api/v1/deposits", api.login(otherOrg.email("admin"))).body().get("content")).isEmpty();
    }

    @Test
    void deductionTopUpAndForfeitPostRevenueAndKeepTheLedgerBalanced() {
        Object[] resident = residentWithLogin("asha");
        long active = (long) resident[0];
        String residentToken = (String) resident[1];
        checkIn(active, layout.bedIds().get(0), today.minusDays(10));
        payEverything(active);
        long account = accountOf(active);

        Response deduction = api.post("/api/v1/deposits/accounts/" + account + "/deductions", admin,
                "{\"description\":\"Broken window\",\"amount\":1000,\"type\":\"DAMAGE\"}", "Idempotency-Key", key());
        assertThat(deduction.status()).as("%s", deduction.body()).isEqualTo(201);
        assertThat(deduction.body().at("/deposit/held").decimalValue()).isEqualByComparingTo("17000");
        assertThat(deduction.body().at("/deposit/deductions").decimalValue()).isEqualByComparingTo("1000");
        assertThat(deduction.body().at("/deposit/status").asText()).isEqualTo("HELD");
        long settlementInvoice = deduction.body().get("invoiceId").asLong();
        JsonNode invoice = api.get("/api/v1/invoices/" + settlementInvoice, admin).body();
        assertThat(invoice.get("type").asText()).isEqualTo("SETTLEMENT");
        assertThat(invoice.get("status").asText()).isEqualTo("PAID");
        assertThat(invoice.at("/lineItems/0/type").asText()).isEqualTo("DAMAGE");

        Response tooMuch = api.post("/api/v1/deposits/accounts/" + account + "/deductions", admin,
                "{\"description\":\"Everything\",\"amount\":20000}");
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().get("code").asText()).isEqualTo("DEDUCTION_EXCEEDS_DEPOSIT");

        // held 17000 of an agreed 18000: the default top-up bills the difference
        Response topUp = api.post("/api/v1/deposits/accounts/" + account + "/top-up", admin, "{\"reason\":\"Restore deposit\"}");
        assertThat(topUp.status()).as("%s", topUp.body()).isEqualTo(201);
        assertThat(topUp.body().at("/transaction/type").asText()).isEqualTo("TOP_UP_REQUESTED");
        assertThat(topUp.body().at("/deposit/pendingCollection").decimalValue()).isEqualByComparingTo("1000");
        assertThat(fixtures.decimal("SELECT total_amount FROM invoices WHERE id = ?", topUp.body().get("invoiceId").asLong()))
                .isEqualByComparingTo("1000");
        assertThat(api.post("/api/v1/deposits/accounts/" + account + "/top-up", admin, "{\"reason\":\"again\"}").body()
                .get("code").asText()).isEqualTo("TOP_UP_NOT_REQUIRED");
        assertThat(api.post("/api/v1/deposits/accounts/" + account + "/top-up", admin, "{\"amount\":500,\"reason\":\"more\"}")
                .body().get("code").asText()).isEqualTo("DEPOSIT_INVOICE_OPEN");
        assertThat(api.post("/api/v1/deposits/accounts/" + account + "/forfeit", admin, "{\"reason\":\"No\"}").body()
                .get("code").asText()).isEqualTo("STAY_ACTIVE");

        long movedOut = movedOutWithRefundDue(layout.bedIds().get(2));
        long movedOutAccount = accountOf(movedOut);
        BigDecimal due = fixtures.decimal("SELECT refund_due FROM deposit_accounts WHERE id = ?", movedOutAccount);
        Response forfeit = api.post("/api/v1/deposits/accounts/" + movedOutAccount + "/forfeit", admin,
                "{\"reason\":\"Left without returning keys\"}");
        assertThat(forfeit.status()).as("%s", forfeit.body()).isEqualTo(201);
        assertThat(forfeit.body().at("/deposit/status").asText()).isEqualTo("FORFEITED");
        assertThat(forfeit.body().at("/deposit/refundDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(forfeit.body().at("/deposit/forfeited").decimalValue()).isEqualByComparingTo(due);
        assertThat(refund(movedOutAccount, key(), "{\"method\":\"UPI\"}").body().get("code").asText()).isEqualTo("NO_REFUND_DUE");
        assertThat(fixtures.decimal("SELECT coalesce(sum(l.amount), 0) FROM invoice_line_items l JOIN invoices i ON "
                + "i.id = l.invoice_id WHERE i.tenant_id = ? AND i.invoice_type = 'SETTLEMENT'", movedOut))
                .isEqualByComparingTo(due);

        JsonNode ledger = api.get("/api/v1/deposits/" + movedOut, admin).body().get("stays").get(0);
        List<String> types = new ArrayList<>();
        ledger.get("lines").forEach(l -> types.add(l.get("type").asText()));
        assertThat(types).contains("COLLECTED", "FORFEIT");
        assertThat(ledger.get("lines").get(ledger.get("lines").size() - 1).get("balanceAfter").decimalValue())
                .isEqualByComparingTo("0");

        JsonNode mine = api.get("/api/v1/deposits/me", residentToken).body();
        assertThat(mine.get("stays")).hasSize(1);
        assertThat(mine.at("/stays/0/deposit/held").decimalValue()).isEqualByComparingTo("17000");
        assertThat(api.get("/api/v1/deposits/" + movedOut, residentToken).status()).isEqualTo(404);
    }

    @Test
    void concurrentRefundsWithOneKeyPayOutOnceAndDistinctKeysCannotOverpay() throws Exception {
        long tenantId = movedOutWithRefundDue(layout.bedIds().get(0));
        long account = accountOf(tenantId);
        BigDecimal due = fixtures.decimal("SELECT refund_due FROM deposit_accounts WHERE id = ?", account);
        String sameKey = key();
        List<Callable<Response>> attempts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attempts.add(() -> refund(account, sameKey, "{\"method\":\"UPI\",\"reference\":\"UTR-SAME\"}"));
        }
        List<Response> responses = ConcurrencySupport.runConcurrently(attempts);
        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() == 200).hasSize(5)
                .allSatisfy(r -> assertThat(r.raw().getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true"));
        assertThat(fixtures.count("SELECT count(*) FROM deposit_transactions WHERE deposit_account_id = ? AND type = 'REFUND'",
                account)).isEqualTo(1);
        assertThat(fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM refunds WHERE kind = 'CASH' AND "
                + "deposit_transaction_id IN (SELECT id FROM deposit_transactions WHERE deposit_account_id = ?)", account))
                .isEqualByComparingTo(due);

        long other = movedOutWithRefundDue(layout.bedIds().get(2));
        long otherAccount = accountOf(other);
        List<Callable<Response>> distinct = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String k = key();
            distinct.add(() -> refund(otherAccount, k, "{\"method\":\"CASH\"}"));
        }
        List<Response> results = ConcurrencySupport.runConcurrently(distinct);
        assertThat(results).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(results).filteredOn(r -> r.status() == 409)
                .allSatisfy(r -> assertThat(r.body().get("code").asText()).isEqualTo("NO_REFUND_DUE"));
        assertThat(fixtures.jdbc().queryForObject("SELECT status FROM deposit_accounts WHERE id = ?", String.class, otherAccount))
                .isEqualTo("REFUNDED");
        assertThat(api.post("/api/v1/deposits/accounts/" + account + "/refund", admin, "{\"method\":\"UPI\"}").body()
                .get("code").asText()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void overdueRefundsAlertOwnersOncePerDay() {
        long tenantId = movedOutWithRefundDue(layout.bedIds().get(0));
        long account = accountOf(tenantId);
        fixtures.jdbc().update("UPDATE deposit_accounts SET refund_due_by = ? WHERE id = ?", today.minusDays(2), account);

        assertThat(depositJobs.reportOverdueRefunds()).isGreaterThanOrEqualTo(1);
        String alerts = "SELECT count(*) FROM notifications WHERE type = 'DEPOSIT_REFUND_OVERDUE' AND reference_id = ? "
                + "AND (recipient_user_id = ? OR recipient_address = ?)";
        int first = fixtures.count(alerts, account, adminUserId, org.email("admin"));
        assertThat(first).isEqualTo(2); // in-app + email
        assertThat(fixtures.jdbc().queryForObject("SELECT overdue_notified_on FROM deposit_accounts WHERE id = ?",
                java.time.LocalDate.class, account)).isEqualTo(today);

        depositJobs.reportOverdueRefunds();
        assertThat(fixtures.count(alerts, account, adminUserId, org.email("admin"))).isEqualTo(first);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'DEPOSIT_REFUND_OVERDUE' AND reference_id = ?",
                account)).isEqualTo(first); // owners only, not managers
    }

    /** Stays recorded before V6 (here: written with SQL like the demo seeder) get an account from the backfill. */
    @Test
    void backfillCreatesAccountsForLegacyStaysAndLinksManualRefunds() {
        long tenantId = fixtures.tenant(org, layout.propertyId());
        long allocation = checkIn(tenantId, layout.bedIds().get(0), today.minusDays(10)).at("/allocation/id").asLong();
        payEverything(tenantId);
        long depositPayment = fixtures.jdbc().queryForObject("SELECT p.id FROM payments p JOIN invoices i ON i.id = "
                + "p.invoice_id WHERE i.allocation_id = ? AND i.invoice_type = 'SECURITY_DEPOSIT'", Long.class, allocation);
        // simulate a legacy stay: no account, ended, partly refunded by hand
        fixtures.jdbc().update("DELETE FROM deposit_account_allocations WHERE allocation_id = ?", allocation);
        fixtures.jdbc().update("DELETE FROM deposit_accounts WHERE root_allocation_id = ?", allocation);
        fixtures.jdbc().update("UPDATE allocations SET status = 'ENDED', end_date = ?, end_reason = 'CHECK_OUT' WHERE id = ?",
                today.minusDays(2), allocation);
        fixtures.jdbc().update("UPDATE beds SET status = 'AVAILABLE' WHERE id = ?", layout.bedIds().get(0));
        fixtures.jdbc().update("""
                INSERT INTO refunds (refund_reference, organization_id, payment_id, amount, reason, idempotency_key,
                                     request_fingerprint, refunded_at)
                VALUES (?, ?, ?, 17000, 'Deposit refund on move-out', ?, 'legacy', now())""",
                "RFD-LEGACY-" + depositPayment, org.id(), depositPayment, "legacy-" + depositPayment);
        fixtures.jdbc().update("UPDATE payments SET refunded_amount = 17000, status = 'PARTIALLY_REFUNDED' WHERE id = ?",
                depositPayment);

        backfill.run();
        backfill.run(); // idempotent

        long account = accountOf(tenantId);
        assertThat(fixtures.jdbc().queryForObject("SELECT status FROM deposit_accounts WHERE id = ?", String.class, account))
                .isEqualTo("REFUNDED");
        assertThat(fixtures.count("SELECT count(*) FROM deposit_account_allocations WHERE deposit_account_id = ?", account))
                .isEqualTo(1);
        assertThat(fixtures.count("SELECT count(*) FROM deposit_transactions WHERE deposit_account_id = ? AND type = 'REFUND'",
                account)).isEqualTo(1);
        JsonNode row = api.get("/api/v1/deposits?tenantId=" + tenantId, admin).body().get("content").get(0);
        assertThat(row.get("refunded").decimalValue()).isEqualByComparingTo("17000");
        assertThat(row.get("held").decimalValue()).isEqualByComparingTo("1000");
    }
}
