package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.pgms.integration.Api.Response;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Billing around month boundaries: the rent job, transfers and check-outs recorded after the 1st's billing run,
 * back-dated check-ins and the move-out settlement. Dates are relative to the application clock (business zone),
 * so the scenarios hold on any calendar day.
 */
class BillingEdgeCasesIT extends AbstractIntegrationTest {

    @Autowired
    private Clock clock;

    private Fixtures.Org org;
    private Fixtures.PropertyLayout layout;
    private String token;
    private String rootToken;
    private LocalDate today;
    private LocalDate firstOfMonth;
    private long tenantId;

    @BeforeEach
    void setUp() {
        org = fixtures.organization();
        layout = fixtures.property(org, 1);
        fixtures.user(org, 2, "admin");
        token = api.login(org.email("admin"));
        rootToken = api.login("root@platform.test");
        today = LocalDate.now(clock);
        firstOfMonth = today.withDayOfMonth(1);
        tenantId = fixtures.tenant(org, layout.propertyId());
    }

    /** 31 days back always lands in an earlier month, so the stay straddles at least one month boundary. */
    private long checkInBackDated() {
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenantId + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + today.minusDays(31) + "\"}");
        assertThat(checkIn.status()).isEqualTo(201);
        return checkIn.body().at("/allocation/id").asLong();
    }

    private Response generate(YearMonth month) {
        return api.post("/api/v1/invoices/generate?month=" + month, rootToken, null);
    }

    private int liveRentInvoices(LocalDate billingMonth) {
        return fixtures.count("SELECT count(*) FROM invoices WHERE tenant_id = ? AND invoice_type = 'RENT' "
                + "AND billing_month = ? AND status <> 'VOID'", tenantId, billingMonth);
    }

    private String status(long invoiceId) {
        return fixtures.jdbc().queryForObject("SELECT status FROM invoices WHERE id = ?", String.class, invoiceId);
    }

    @Test
    void backDatedCheckInLeavesNoMonthUnbilled() {
        checkInBackDated();
        for (YearMonth m = YearMonth.from(today.minusDays(31)); !m.isAfter(YearMonth.from(today)); m = m.plusMonths(1)) {
            assertThat(liveRentInvoices(m.atDay(1))).as("rent invoice for %s", m).isEqualTo(1);
        }
    }

    /**
     * The 1st's billing run bills the old allocation; a transfer effective that same day (or back-dated before it)
     * must not make the new allocation look unbilled and charge the month a second time.
     */
    @Test
    void transferRecordedAfterTheBillingRunDoesNotBillTheMonthTwice() {
        long allocation = checkInBackDated();
        assertThat(generate(YearMonth.from(today)).status()).isEqualTo(200);
        assertThat(liveRentInvoices(firstOfMonth)).isEqualTo(1);

        Response transfer = api.post("/api/v1/allocations/" + allocation + "/transfer", token,
                "{\"targetBedId\":" + layout.bedIds().get(1) + ",\"transferDate\":\"" + firstOfMonth + "\"}");
        assertThat(transfer.status()).isEqualTo(200);

        Response rerun = generate(YearMonth.from(today));
        assertThat(rerun.body().get("generated").asInt()).isZero();
        assertThat(liveRentInvoices(firstOfMonth)).isEqualTo(1);
    }

    /**
     * Rent for a month the resident never stayed (typically billed at 00:30 on the 1st, before the check-out effective
     * that day was recorded) and the never-collected deposit must not end up in the settlement as dues.
     */
    @Test
    void checkOutVoidsRentForUnstayedPeriodsAndUncollectedDeposit() {
        long allocation = checkInBackDated();
        generate(YearMonth.from(today));
        long currentRent = fixtures.jdbc().queryForObject("SELECT id FROM invoices WHERE tenant_id = ? AND invoice_type = "
                + "'RENT' AND billing_month = ? AND status <> 'VOID'", Long.class, tenantId, firstOfMonth);
        long deposit = fixtures.jdbc().queryForObject("SELECT id FROM invoices WHERE tenant_id = ? AND invoice_type = "
                + "'SECURITY_DEPOSIT'", Long.class, tenantId);
        BigDecimal earlierRent = fixtures.decimal("SELECT coalesce(sum(total_amount), 0) FROM invoices WHERE tenant_id = ? "
                + "AND invoice_type = 'RENT' AND billing_month < ? AND status <> 'VOID'", tenantId, firstOfMonth);

        Response checkOut = api.post("/api/v1/allocations/" + allocation + "/check-out", token,
                "{\"checkOutDate\":\"" + firstOfMonth + "\"}");

        assertThat(checkOut.status()).isEqualTo(200);
        assertThat(status(currentRent)).isEqualTo("VOID");
        assertThat(status(deposit)).isEqualTo("VOID");
        JsonNode settlement = checkOut.body().get("settlement");
        assertThat(settlement.get("depositHeld").decimalValue()).isEqualByComparingTo("0");
        assertThat(settlement.get("outstandingDues").decimalValue()).isEqualByComparingTo(earlierRent);
        assertThat(settlement.get("amountPayableByTenant").decimalValue()).isEqualByComparingTo(earlierRent);
        assertThat(settlement.get("refundDue").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void partiallyCollectedDepositIsSettledOnWhatWasActuallyPaid() {
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenantId + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + today + "\"}");
        long allocation = checkIn.body().at("/allocation/id").asLong();
        for (JsonNode invoice : checkIn.body().get("invoices")) {
            boolean isDeposit = "SECURITY_DEPOSIT".equals(invoice.get("type").asText());
            String amount = isDeposit ? "10000" : invoice.get("totalAmount").decimalValue().toPlainString();
            assertThat(api.post("/api/v1/payments", token, "{\"invoiceId\":" + invoice.get("id").asLong() + ",\"amount\":"
                    + amount + ",\"method\":\"UPI\"}", "Idempotency-Key", "settle-" + System.nanoTime()).status())
                    .isEqualTo(201);
        }

        Response checkOut = api.post("/api/v1/allocations/" + allocation + "/check-out", token,
                "{\"checkOutDate\":\"" + today + "\",\"deductions\":[{\"description\":\"Key lost\",\"amount\":500}]}");

        JsonNode settlement = checkOut.body().get("settlement");
        assertThat(settlement.get("depositHeld").decimalValue()).isEqualByComparingTo("10000");
        assertThat(settlement.get("outstandingDues").decimalValue()).isEqualByComparingTo("0");
        assertThat(settlement.get("refundDue").decimalValue()).isEqualByComparingTo("9500");
        assertThat(settlement.get("amountPayableByTenant").decimalValue()).isEqualByComparingTo("0");
        assertThat(api.get("/api/v1/tenants/" + tenantId + "/balance", token).body().get("outstanding").decimalValue())
                .isEqualByComparingTo("0");
    }

    @Test
    void rentCannotBeGeneratedForAFutureMonth() {
        Response response = generate(YearMonth.from(today).plusMonths(1));
        assertThat(response.status()).isEqualTo(422);
        assertThat(response.body().get("code").asText()).isEqualTo("FUTURE_BILLING_MONTH");
    }

    /** Deposits are a liability, not rent: the KPI labelled "outstanding rent" must not include unpaid deposits. */
    @Test
    void dashboardOutstandingRentExcludesDeposits() {
        Response checkIn = api.post("/api/v1/allocations/check-in", token, "{\"tenantId\":" + tenantId + ",\"bedId\":"
                + layout.bedIds().get(0) + ",\"checkInDate\":\"" + today + "\"}");
        BigDecimal rent = BigDecimal.ZERO;
        for (JsonNode invoice : checkIn.body().get("invoices")) {
            if ("RENT".equals(invoice.get("type").asText())) {
                rent = rent.add(invoice.get("totalAmount").decimalValue());
            }
        }
        JsonNode dashboard = api.get("/api/v1/dashboard?propertyId=" + layout.propertyId(), token).body();
        assertThat(dashboard.get("outstandingRent").decimalValue()).isEqualByComparingTo(rent);
    }
}
