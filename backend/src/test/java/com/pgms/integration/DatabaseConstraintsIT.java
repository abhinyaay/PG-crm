package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pgms.billing.BillingJobs;
import com.pgms.common.exception.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** The last line of defence: invariants enforced by PostgreSQL itself, independent of application code. */
class DatabaseConstraintsIT extends AbstractIntegrationTest {

    @Autowired
    private BillingJobs billingJobs;

    @Autowired
    private Clock clock;

    private long allocate(Fixtures.Org org, long propertyId, long tenantId, long bedId, LocalDate start, LocalDate end) {
        return fixtures.jdbc().queryForObject("""
                INSERT INTO allocations (organization_id, property_id, tenant_id, bed_id, status, start_date, end_date,
                                         end_reason, monthly_rent, security_deposit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 9000, 18000) RETURNING id""", Long.class, org.id(), propertyId, tenantId, bedId,
                end == null ? "ACTIVE" : "ENDED", start, end, end == null ? null : "CHECK_OUT");
    }

    @Test
    void exclusionConstraintRejectsOverlappingStaysOnOneBedEvenInHistory() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long bed = layout.bedIds().get(0);
        allocate(org, layout.propertyId(), fixtures.tenant(org, layout.propertyId()), bed,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 1));
        // back-to-back is fine: end date is exclusive
        allocate(org, layout.propertyId(), fixtures.tenant(org, layout.propertyId()), bed,
                LocalDate.of(2025, 3, 1), LocalDate.of(2025, 5, 1));

        long intruder = fixtures.tenant(org, layout.propertyId());
        assertThatThrownBy(() -> allocate(org, layout.propertyId(), intruder, bed, LocalDate.of(2025, 2, 15),
                LocalDate.of(2025, 2, 20)))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
                    assertThat(ConstraintViolationTranslator.constraintName(e)).contains("ex_allocations_bed_overlap");
                    assertThat(ConstraintViolationTranslator.translate(e).getCode()).isEqualTo("BED_ALLOCATION_OVERLAP");
                });
    }

    @Test
    void oneTenantCannotOccupyTwoBedsAtOnce() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long tenant = fixtures.tenant(org, layout.propertyId());
        allocate(org, layout.propertyId(), tenant, layout.bedIds().get(0), LocalDate.of(2025, 1, 1), null);
        assertThatThrownBy(() -> allocate(org, layout.propertyId(), tenant, layout.bedIds().get(1), LocalDate.of(2025, 6, 1), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void invoiceInvariantsAreCheckedByTheDatabase() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long tenant = fixtures.tenant(org, layout.propertyId());
        long allocation = allocate(org, layout.propertyId(), tenant, layout.bedIds().get(0), LocalDate.of(2025, 1, 1), null);
        assertThatThrownBy(() -> fixtures.jdbc().update("""
                INSERT INTO invoices (invoice_number, organization_id, property_id, tenant_id, allocation_id, invoice_type,
                    billing_month, issue_date, due_date, status, subtotal, total_amount, amount_paid)
                VALUES ('X-1', ?, ?, ?, ?, 'RENT', '2025-01-01', '2025-01-01', '2025-01-05', 'PAID', 100, 100, 150)""",
                org.id(), layout.propertyId(), tenant, allocation))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invoices_paid");
    }

    @Test
    void monthlyRentGenerationIsIdempotent() {
        Fixtures.Org org = fixtures.organization();
        Fixtures.PropertyLayout layout = fixtures.property(org, 1);
        long tenant = fixtures.tenant(org, layout.propertyId());
        long allocation = allocate(org, layout.propertyId(), tenant, layout.bedIds().get(0),
                LocalDate.now(clock).minusMonths(2), null);
        YearMonth month = YearMonth.now(clock); // future months are rejected (FUTURE_BILLING_MONTH)

        billingJobs.generateRentInvoices(month);
        billingJobs.generateRentInvoices(month);

        assertThat(fixtures.count("SELECT count(*) FROM invoices WHERE allocation_id = ? AND billing_month = ?", allocation,
                month.atDay(1))).isEqualTo(1);
        assertThat(fixtures.decimal("SELECT total_amount FROM invoices WHERE allocation_id = ? AND billing_month = ?",
                allocation, month.atDay(1))).isEqualByComparingTo("9000");
    }
}
