package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Move-out workflow end to end: notice, inspection, persisted settlement, deposit adjustment and refund. */
class MoveOutWorkflowIT extends MoveOutTestSupport {

    @Test
    void fullMoveOutWithInspectionSettlesDuesFromTheDepositAndRefundsTheRest() {
        setPolicy(5, "FULL");
        Object[] resident = residentWithLogin("meera");
        long tenantId = (long) resident[0];
        String tenant = (String) resident[1];
        checkIn(tenantId, layout.bedIds().get(0), today.minusDays(20));
        payEverything(tenantId);
        // 1000 of dues stay open: an extra charge on the first rent invoice (its period started at check-in)
        long firstRent = fixtures.jdbc().queryForObject("SELECT min(id) FROM invoices WHERE tenant_id = ? AND invoice_type = "
                + "'RENT'", Long.class, tenantId);
        // on the 1st of a month the current month's (paid) invoice starts on the move-out day: never stayed, credited back
        BigDecimal unstayedPaid = fixtures.decimal("SELECT coalesce(sum(amount_paid), 0) FROM invoices WHERE tenant_id = ? "
                + "AND invoice_type = 'RENT' AND period_start >= ?", tenantId, today);
        assertThat(api.post("/api/v1/invoices/" + firstRent + "/charges", admin,
                "{\"type\":\"LAUNDRY\",\"description\":\"Laundry\",\"amount\":1000}").status()).isEqualTo(200);

        // the resident gives notice from the portal for today: 5-day policy -> 5 days short at 9000/30
        Response preview = api.get("/api/v1/move-outs/preview?moveOutDate=" + today, tenant);
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().get("shortfallDays").asInt()).isEqualTo(5);
        assertThat(preview.body().get("shortfallCharge").decimalValue()).isEqualByComparingTo("1500");
        assertThat(preview.body().get("depositHeld").decimalValue()).isEqualByComparingTo("18000");
        assertThat(preview.body().get("outstandingDues").decimalValue()).isEqualByComparingTo("1000");
        assertThat(preview.body().get("estimatedRefund").decimalValue()).isEqualByComparingTo("15500");

        Response notice = api.post("/api/v1/move-outs", tenant, "{\"expectedMoveOutDate\":\"" + today
                + "\",\"reason\":\"JOB_CHANGE\",\"refundUpiVpa\":\"meera@okaxis\"}");
        assertThat(notice.status()).as("%s", notice.body()).isEqualTo(201);
        long moveOut = notice.id();
        assertThat(notice.body().get("status").asText()).isEqualTo("NOTICE_GIVEN");
        assertThat(notice.body().get("initiatedBy").asText()).isEqualTo("TENANT");
        assertThat(notice.body().get("reference").asText()).startsWith("MO-");
        assertThat(api.get("/api/v1/tenants/" + tenantId, admin).body().get("status").asText()).isEqualTo("ON_NOTICE");

        // completing needs an inspection; staff records it but cannot settle
        assertThat(api.post("/api/v1/move-outs/" + moveOut + "/complete", manager,
                "{\"actualMoveOutDate\":\"" + today + "\"}").body().get("code").asText()).isEqualTo("MOVE_OUT_NOT_INSPECTED");
        Response inspection = api.put("/api/v1/move-outs/" + moveOut + "/inspection", staff, """
                {"items":[{"item":"Chair","condition":"DAMAGED","deductionAmount":500,"note":"leg broken"},
                          {"item":"Walls","condition":"CLEANING","deductionAmount":300},
                          {"item":"Keys returned","condition":"OK"}],
                 "notes":"Room otherwise fine"}""");
        assertThat(inspection.status()).as("%s", inspection.body()).isEqualTo(200);
        assertThat(inspection.body().get("status").asText()).isEqualTo("INSPECTED");
        assertThat(inspection.body().get("inspectionItems")).hasSize(3);
        assertThat(api.post("/api/v1/move-outs/" + moveOut + "/complete", staff,
                "{\"actualMoveOutDate\":\"" + today + "\"}").status()).isEqualTo(403);

        Response wizard = api.get("/api/v1/move-outs/" + moveOut + "/settlement-preview?actualMoveOutDate=" + today, manager);
        assertThat(wizard.status()).isEqualTo(200);
        assertThat(wizard.body().get("totalDeductions").decimalValue()).isEqualByComparingTo("2300");
        assertThat(wizard.body().get("estimatedRefund").decimalValue()).isEqualByComparingTo("14700");

        Response complete = api.post("/api/v1/move-outs/" + moveOut + "/complete", manager,
                "{\"actualMoveOutDate\":\"" + today + "\"}");
        assertThat(complete.status()).as("%s", complete.body()).isEqualTo(200);
        JsonNode settlement = complete.body().get("settlement");
        assertThat(complete.body().get("status").asText()).isEqualTo("SETTLED");
        assertThat(settlement.get("depositHeld").decimalValue()).isEqualByComparingTo("18000");
        assertThat(settlement.get("outstandingDues").decimalValue()).isEqualByComparingTo("1000");
        assertThat(settlement.get("duesAdjusted").decimalValue()).isEqualByComparingTo("1000");
        assertThat(settlement.get("totalDeductions").decimalValue()).isEqualByComparingTo("2300");
        BigDecimal refundDue = new BigDecimal("14700").add(unstayedPaid);
        assertThat(settlement.get("refundDue").decimalValue()).isEqualByComparingTo(refundDue);
        assertThat(settlement.get("rentCredit").decimalValue()).isEqualByComparingTo(unstayedPaid);
        assertThat(settlement.get("payableByTenant").decimalValue()).isEqualByComparingTo("0");
        assertThat(settlement.get("refundDueBy").asText()).isEqualTo(today.plusDays(7).toString());
        assertThat(settlement.get("depositStatus").asText()).isEqualTo("REFUND_DUE");

        // the dues counted against the deposit are really closed, deductions became revenue lines
        assertThat(openInvoices(tenantId)).isEmpty();
        assertThat(fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM payments WHERE tenant_id = ? AND method = "
                + "'DEPOSIT_ADJUSTMENT'", tenantId)).isEqualByComparingTo("3300");
        long settlementInvoice = settlement.get("settlementInvoiceId").asLong();
        JsonNode invoice = api.get("/api/v1/invoices/" + settlementInvoice, admin).body();
        assertThat(invoice.get("type").asText()).isEqualTo("SETTLEMENT");
        assertThat(invoice.get("status").asText()).isEqualTo("PAID");
        List<String> lines = new ArrayList<>();
        invoice.get("lineItems").forEach(l -> lines.add(l.get("type").asText() + ":" + l.get("amount").decimalValue()
                .stripTrailingZeros().toPlainString()));
        assertThat(lines).containsExactly("NOTICE_SHORTFALL:1500", "DAMAGE:500", "DEDUCTION:300");
        JsonNode balance = api.get("/api/v1/tenants/" + tenantId + "/balance", admin).body();
        assertThat(balance.get("outstanding").decimalValue()).isEqualByComparingTo("0");
        assertThat(balance.get("depositHeld").decimalValue()).isEqualByComparingTo("14700");

        // owner refunds in two steps; the same key replays, more than due is rejected
        long account = settlement.get("depositAccountId").asLong();
        String firstKey = key();
        Response first = refund(account, firstKey, "{\"amount\":4700,\"method\":\"UPI\",\"reference\":\"UTR001\"}");
        assertThat(first.status()).as("%s", first.body()).isEqualTo(201);
        assertThat(first.body().at("/deposit/status").asText()).isEqualTo("PARTIALLY_REFUNDED");
        Response replay = refund(account, firstKey, "{\"amount\":4700,\"method\":\"UPI\",\"reference\":\"UTR001\"}");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.raw().getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(refund(account, firstKey, "{\"amount\":100,\"method\":\"UPI\"}").body().get("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        Response tooMuch = refund(account, key(), "{\"amount\":" + refundDue.subtract(new BigDecimal("4699.99")).toPlainString()
                + ",\"method\":\"UPI\"}");
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.body().get("code").asText()).isEqualTo("REFUND_EXCEEDS_DUE");
        assertThat(refund(account, key(), "{\"method\":\"CARD\"}").body().get("code").asText()).isEqualTo("INVALID_REFUND_METHOD");

        Response rest = api.post("/api/v1/move-outs/" + moveOut + "/refund", admin,
                "{\"method\":\"BANK_TRANSFER\",\"reference\":\"NEFT42\"}", "Idempotency-Key", key());
        assertThat(rest.status()).as("%s", rest.body()).isEqualTo(200);
        assertThat(rest.body().get("status").asText()).isEqualTo("CLOSED");
        assertThat(rest.body().at("/settlement/depositStatus").asText()).isEqualTo("REFUNDED");
        assertThat(rest.body().at("/settlement/refundStillDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(fixtures.decimal("SELECT coalesce(sum(amount), 0) FROM refunds WHERE kind = 'CASH' AND payment_id IN "
                + "(SELECT id FROM payments WHERE tenant_id = ?)", tenantId)).isEqualByComparingTo(refundDue);
        assertThat(api.get("/api/v1/tenants/" + tenantId + "/balance", admin).body().get("depositHeld").decimalValue())
                .isEqualByComparingTo("0");

        // the resident is told on every channel; the ledger tells the whole story
        assertThat(fixtures.jdbc().queryForList("SELECT DISTINCT channel FROM notifications WHERE type = "
                + "'DEPOSIT_REFUND_ISSUED' AND reference_id = ?", String.class, account))
                .containsExactlyInAnyOrder("IN_APP", "EMAIL", "SMS");
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'SETTLEMENT_FINALIZED' AND reference_id = ?",
                moveOut)).isGreaterThanOrEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM notifications WHERE type = 'MOVE_OUT_REQUESTED' AND reference_id = ?",
                moveOut)).isGreaterThanOrEqualTo(2);
        JsonNode ledger = api.get("/api/v1/deposits/me", tenant).body().get("stays").get(0);
        List<String> types = new ArrayList<>();
        ledger.get("lines").forEach(l -> types.add(l.get("type").asText()));
        assertThat(types).contains("COLLECTED", "DUES_ADJUSTED", "DEDUCTION", "REFUND");
        JsonNode last = ledger.get("lines").get(ledger.get("lines").size() - 1);
        assertThat(last.get("balanceAfter").decimalValue()).isEqualByComparingTo("0");
        assertThat(api.get("/api/v1/move-outs/" + moveOut, tenant).body().get("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void residentsGiveAndWithdrawNoticeOnlyForTheirOwnStay() {
        Object[] a = residentWithLogin("ravi");
        Object[] b = residentWithLogin("sita");
        long tenantA = (long) a[0];
        String tokenA = (String) a[1];
        String tokenB = (String) b[1];
        long allocationA = checkIn(tenantA, layout.bedIds().get(0), today.minusDays(3)).at("/allocation/id").asLong();
        checkIn((long) b[0], layout.bedIds().get(1), today.minusDays(3));

        Response notice = api.post("/api/v1/move-outs", tokenA, "{\"expectedMoveOutDate\":\"" + today.plusDays(40)
                + "\",\"reason\":\"RELOCATION\"}");
        assertThat(notice.status()).as("%s", notice.body()).isEqualTo(201);
        assertThat(notice.body().get("shortfallDays").asInt()).isZero(); // default policy: 30 days
        long moveOut = notice.id();
        assertThat(api.get("/api/v1/allocations/" + allocationA, admin).body().get("expectedEndDate").asText())
                .isEqualTo(today.plusDays(40).toString());

        Response second = api.post("/api/v1/move-outs", tokenA, "{\"expectedMoveOutDate\":\"" + today.plusDays(41)
                + "\",\"reason\":\"OTHER\"}");
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("code").asText()).isEqualTo("MOVE_OUT_ALREADY_OPEN");
        assertThat(api.post("/api/v1/move-outs", tokenA, "{\"expectedMoveOutDate\":\"" + today.plusDays(41)
                + "\",\"reason\":\"EVICTION\"}").status()).isIn(403, 409);

        // another resident cannot see or touch it; residents cannot inspect or settle
        assertThat(api.get("/api/v1/move-outs/" + moveOut, tokenB).status()).isEqualTo(404);
        assertThat(api.post("/api/v1/move-outs/" + moveOut + "/withdraw", tokenB, null).status()).isEqualTo(404);
        assertThat(api.get("/api/v1/move-outs", tokenB).body().get("totalElements").asLong()).isZero();
        assertThat(api.put("/api/v1/move-outs/" + moveOut + "/inspection", tokenA, "{\"items\":[]}").status()).isEqualTo(403);
        assertThat(api.post("/api/v1/move-outs/" + moveOut + "/complete", tokenA, "{\"actualMoveOutDate\":\"" + today + "\"}")
                .status()).isEqualTo(403);
        assertThat(api.get("/api/v1/move-outs", tokenA).body().get("totalElements").asLong()).isEqualTo(1);
        assertThat(api.get("/api/v1/move-outs?propertyId=" + layout.propertyId() + "&status=NOTICE_GIVEN", manager)
                .body().get("totalElements").asLong()).isEqualTo(1);

        // staff cannot open a case; a manager of another property cannot either
        assertThat(api.post("/api/v1/move-outs", staff, "{\"allocationId\":" + allocationA + ",\"expectedMoveOutDate\":\""
                + today.plusDays(5) + "\",\"reason\":\"OTHER\"}").status()).isEqualTo(403);
        fixtures.user(org, 3, "outsider");
        assertThat(api.post("/api/v1/move-outs", api.login(org.email("outsider")), "{\"allocationId\":" + allocationA
                + ",\"expectedMoveOutDate\":\"" + today.plusDays(5) + "\",\"reason\":\"OTHER\"}").status()).isEqualTo(403);

        Response withdrawn = api.post("/api/v1/move-outs/" + moveOut + "/withdraw", tokenA, null);
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(api.get("/api/v1/allocations/" + allocationA, admin).body().has("expectedEndDate")).isFalse();
        assertThat(api.get("/api/v1/tenants/" + tenantA, admin).body().get("status").asText()).isEqualTo("ACTIVE");

        // notice for today: the whole 30-day period is short; once inspected it can no longer be withdrawn
        Response urgent = api.post("/api/v1/move-outs", tokenA, "{\"expectedMoveOutDate\":\"" + today + "\",\"reason\":\"PERSONAL\"}");
        assertThat(urgent.status()).isEqualTo(201);
        assertThat(urgent.body().get("shortfallDays").asInt()).isEqualTo(30);
        assertThat(urgent.body().get("shortfallCharge").decimalValue()).isEqualByComparingTo("9000");
        Response waived = api.put("/api/v1/move-outs/" + urgent.id() + "/date", manager, "{\"expectedMoveOutDate\":\""
                + today.plusDays(2) + "\",\"waiveCharges\":true,\"reason\":\"Job loss\"}");
        assertThat(waived.status()).isEqualTo(200);
        assertThat(waived.body().get("chargesWaived").asBoolean()).isTrue();
        assertThat(waived.body().get("shortfallDays").asInt()).isEqualTo(28);
        assertThat(api.put("/api/v1/move-outs/" + urgent.id() + "/inspection", staff, "{\"items\":[]}").status()).isEqualTo(200);
        Response late = api.post("/api/v1/move-outs/" + urgent.id() + "/withdraw", tokenA, null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().get("code").asText()).isEqualTo("MOVE_OUT_INVALID_STATE");

        long nonResident = fixtures.tenant(org, layout.propertyId());
        long nonResidentUser = fixtures.user(org, 5, "visitor");
        fixtures.linkTenantUser(nonResident, nonResidentUser);
        Response notResident = api.post("/api/v1/move-outs", api.login(org.email("visitor")), "{\"expectedMoveOutDate\":\""
                + today.plusDays(5) + "\",\"reason\":\"OTHER\"}");
        assertThat(notResident.status()).isEqualTo(422);
        assertThat(notResident.body().get("code").asText()).isEqualTo("NOT_A_RESIDENT");
    }

    @Test
    void zeroLengthStaysAreRejected() {
        long tenantId = fixtures.tenant(org, layout.propertyId());
        long allocation = checkIn(tenantId, layout.bedIds().get(0), today).at("/allocation/id").asLong();

        Response sameDay = legacyCheckOut(allocation, today, null);
        assertThat(sameDay.status()).isEqualTo(422);
        assertThat(sameDay.body().get("code").asText()).isEqualTo("ZERO_LENGTH_STAY");
        assertThat(api.get("/api/v1/allocations/" + allocation, admin).body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(fixtures.count("SELECT count(*) FROM move_outs WHERE tenant_id = ?", tenantId)).isZero();

        Response notice = api.post("/api/v1/move-outs", admin, "{\"allocationId\":" + allocation + ",\"expectedMoveOutDate\":\""
                + today + "\",\"reason\":\"OTHER\"}");
        assertThat(notice.status()).isEqualTo(422);
        assertThat(notice.body().get("code").asText()).isEqualTo("ZERO_LENGTH_STAY");
    }

    /**
     * Stay 1 is settled with its refund still pending; stay 2 (same resident, re-check-in) must neither count stay 1's
     * deposit again nor treat its own unpaid deposit invoice as held, and must not be billed rent a second time for
     * days stay 1's invoice already covers.
     */
    @Test
    void reCheckInDoesNotDoubleCountDepositsOrDoubleBillRent() {
        // check-in, check-out and re-check-in inside one calendar month, all in the past
        LocalDate base = today.minusDays(14);
        if (base.getDayOfMonth() > base.lengthOfMonth() - 6) {
            base = base.withDayOfMonth(1).plusMonths(1);
        }
        LocalDate checkOut1 = base.plusDays(4);
        LocalDate reCheckIn = base.plusDays(6);
        long tenantId = fixtures.tenant(org, layout.propertyId());
        long first = checkIn(tenantId, layout.bedIds().get(0), base).at("/allocation/id").asLong();
        payEverything(tenantId);
        Response out1 = legacyCheckOut(first, checkOut1, null);
        assertThat(out1.status()).as("%s", out1.body()).isEqualTo(200);
        JsonNode s1 = out1.body().get("settlement");
        assertThat(s1.get("depositHeld").decimalValue()).isEqualByComparingTo("18000");
        BigDecimal refundDue1 = s1.get("refundDue").decimalValue();
        assertThat(refundDue1).isEqualByComparingTo(new BigDecimal("18000").add(s1.get("rentCredit").decimalValue()));
        long account1 = s1.get("depositAccountId").asLong();

        long second = checkIn(tenantId, layout.bedIds().get(2), reCheckIn).at("/allocation/id").asLong();
        // the re-check-in month is already billed by stay 1's invoice: no second rent invoice for it
        assertThat(fixtures.count("SELECT count(*) FROM invoices WHERE allocation_id = ? AND invoice_type = 'RENT' "
                + "AND billing_month = ?", second, base.withDayOfMonth(1))).isZero();
        // no two live rent invoices cover the same day (invoices for periods a stay never reached do not count)
        assertThat(fixtures.count("""
                WITH live AS (
                    SELECT i.* FROM invoices i JOIN allocations a ON a.id = i.allocation_id
                    WHERE i.tenant_id = ? AND i.invoice_type = 'RENT' AND i.status <> 'VOID'
                      AND NOT (coalesce(a.end_reason, '') = 'CHECK_OUT' AND a.end_date <= i.period_start))
                SELECT count(*) FROM live x JOIN live y ON x.id < y.id
                WHERE daterange(x.period_start, x.period_end, '[]') && daterange(y.period_start, y.period_end, '[]')""",
                tenantId)).isZero();

        BigDecimal stayTwoRent = fixtures.decimal("SELECT coalesce(sum(total_amount - amount_paid), 0) FROM invoices "
                + "WHERE allocation_id = ? AND invoice_type = 'RENT' AND status <> 'VOID' AND period_start < ?", second, today);
        Response out2 = legacyCheckOut(second, today, "[{\"description\":\"Lost key\",\"amount\":500}]");
        assertThat(out2.status()).as("%s", out2.body()).isEqualTo(200);
        JsonNode s2 = out2.body().get("settlement");
        assertThat(s2.get("depositHeld").decimalValue()).isEqualByComparingTo("0");
        assertThat(s2.get("refundDue").decimalValue()).isEqualByComparingTo("0");
        assertThat(s2.get("amountPayableByTenant").decimalValue()).isEqualByComparingTo(stayTwoRent.add(new BigDecimal("500")));
        assertThat(s2.get("depositAccountId").asLong()).isNotEqualTo(account1);

        // stay 1's refund is untouched and still owed
        Map<String, Object> stay1 = fixtures.jdbc().queryForMap("SELECT status, refund_due FROM deposit_accounts WHERE id = ?",
                account1);
        assertThat(stay1.get("status")).isEqualTo("REFUND_DUE");
        assertThat((BigDecimal) stay1.get("refund_due")).isEqualByComparingTo(refundDue1);
        JsonNode deposits = api.get("/api/v1/deposits?tenantId=" + tenantId, admin).body();
        assertThat(deposits.get("totalElements").asLong()).isEqualTo(2);
        assertThat(fixtures.count("SELECT count(*) FROM move_outs WHERE tenant_id = ? AND status IN ('SETTLED', 'CLOSED')",
                tenantId)).isEqualTo(2);
    }

    /** Transfer to a costlier room bills the deposit difference; a prorated final month credits unused rent. */
    @Test
    void transferTopUpAndProratedFinalMonthFlowIntoTheRefund() {
        setPolicy(0, "PRORATED");
        long tenantId = fixtures.tenant(org, layout.propertyId());
        long first = checkIn(tenantId, layout.bedIds().get(0), today.minusDays(12)).at("/allocation/id").asLong();
        payEverything(tenantId);

        Response transfer = api.post("/api/v1/allocations/" + first + "/transfer", admin, "{\"targetBedId\":"
                + layout.bedIds().get(2) + ",\"transferDate\":\"" + today.minusDays(6) + "\",\"newSecurityDeposit\":20000}");
        assertThat(transfer.status()).as("%s", transfer.body()).isEqualTo(200);
        long second = transfer.body().at("/current/id").asLong();
        assertThat(fixtures.decimal("SELECT total_amount FROM invoices WHERE allocation_id = ? AND invoice_type = "
                + "'SECURITY_DEPOSIT' AND status = 'ISSUED'", second)).isEqualByComparingTo("2000");
        JsonNode row = api.get("/api/v1/deposits?tenantId=" + tenantId, admin).body().get("content").get(0);
        assertThat(row.get("agreedDeposit").decimalValue()).isEqualByComparingTo("20000");
        assertThat(row.get("collected").decimalValue()).isEqualByComparingTo("18000");
        assertThat(row.get("pendingCollection").decimalValue()).isEqualByComparingTo("2000");
        assertThat(row.get("currentAllocationId").asLong()).isEqualTo(second);
        payEverything(tenantId);

        Response out = legacyCheckOut(second, today, null);
        assertThat(out.status()).as("%s", out.body()).isEqualTo(200);
        JsonNode s = out.body().get("settlement");
        assertThat(s.get("depositHeld").decimalValue()).isEqualByComparingTo("20000");
        BigDecimal credit = s.get("rentCredit").decimalValue();
        assertThat(credit).isPositive();
        assertThat(s.get("refundDue").decimalValue()).isEqualByComparingTo(new BigDecimal("20000").add(credit));
        assertThat(fixtures.count("SELECT count(*) FROM invoices WHERE tenant_id = ? AND invoice_type = 'RENT' "
                + "AND status <> 'VOID' AND period_start < ? AND period_end >= ?", tenantId, today, today)).isZero();

        long account = s.get("depositAccountId").asLong();
        Response paid = refund(account, key(), "{\"method\":\"UPI\",\"reference\":\"UTR9\"}");
        assertThat(paid.status()).as("%s", paid.body()).isEqualTo(201);
        assertThat(paid.body().at("/deposit/status").asText()).isEqualTo("REFUNDED");
        assertThat(fixtures.decimal("SELECT coalesce(sum(r.amount), 0) FROM refunds r JOIN payments p ON p.id = r.payment_id "
                + "JOIN invoices i ON i.id = p.invoice_id WHERE p.tenant_id = ? AND i.invoice_type = 'RENT' AND r.kind = 'CASH'",
                tenantId)).isEqualByComparingTo(credit);
    }

    @Test
    void policyIsReadableByStaffAndEditableByOwnersOnly() {
        Response defaults = api.get("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", staff);
        assertThat(defaults.status()).isEqualTo(200);
        assertThat(defaults.body().get("customized").asBoolean()).isFalse();
        assertThat(defaults.body().get("noticePeriodDays").asInt()).isEqualTo(30);
        assertThat(defaults.body().get("inspectionChecklist").size()).isEqualTo(8);
        String body = "{\"noticePeriodDays\":15,\"noticeShortfallRule\":\"NONE\",\"lockInMonths\":3,"
                + "\"lockInPenaltyRule\":\"FLAT\",\"finalMonthBilling\":\"PRORATED\",\"depositRefundDays\":10,"
                + "\"inspectionChecklist\":[\"Keys\"]}";
        assertThat(api.put("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", manager, body).status())
                .isEqualTo(403);
        Response invalid = api.put("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", admin, body);
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.body().get("code").asText()).isEqualTo("INVALID_POLICY");
        Response saved = api.put("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", admin,
                body.replace("\"FLAT\"", "\"FLAT\",\"lockInPenaltyAmount\":2500"));
        assertThat(saved.status()).isEqualTo(200);
        assertThat(saved.body().get("customized").asBoolean()).isTrue();
        assertThat(saved.body().get("lockInPenaltyAmount").decimalValue()).isEqualByComparingTo("2500");
        assertThat(api.put("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", admin,
                body.replace("15", "91")).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/properties/" + layout.propertyId() + "/moveout-policy", manager).body()
                .get("depositRefundDays").asInt()).isEqualTo(10);
    }
}
