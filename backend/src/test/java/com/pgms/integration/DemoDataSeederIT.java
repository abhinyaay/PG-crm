package com.pgms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.pgms.demo.DemoDataSeeder;
import java.time.Clock;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The demo history is written with SQL, bypassing the domain services, so its consistency has to be checked
 * explicitly: nothing may lie in the future, timelines must be ordered, and occupancy/billing must agree with what
 * the services and the billing job would have produced.
 */
class DemoDataSeederIT extends AbstractIntegrationTest {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private int count(String sql, Object... args) {
        return fixtures.count(sql, args);
    }

    @Test
    void generatedHistoryIsInternallyConsistent() {
        DemoDataSeeder seeder = new DemoDataSeeder(fixtures.jdbc(), passwordEncoder, clock, Fixtures.PASSWORD);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seeder.run(null));
        long org = fixtures.jdbc().queryForObject("SELECT id FROM organizations WHERE slug = 'demo-sunrise'", Long.class);

        // nothing happens in the future
        assertThat(count("SELECT count(*) FROM refunds WHERE organization_id = ? AND refunded_at > now()", org)).isZero();
        assertThat(count("SELECT count(*) FROM payments WHERE organization_id = ? AND paid_at > now()", org)).isZero();
        assertThat(count("""
                SELECT count(*) FROM maintenance_requests WHERE organization_id = ?
                  AND (assigned_at > now() OR started_at > now() OR resolved_at > now() OR closed_at > now())""", org))
                .isZero();
        assertThat(count("""
                SELECT count(*) FROM complaints WHERE organization_id = ?
                  AND (escalated_at > now() OR resolved_at > now() OR closed_at > now())""", org)).isZero();

        // timelines are ordered
        assertThat(count("""
                SELECT count(*) FROM maintenance_requests WHERE organization_id = ?
                  AND (started_at < assigned_at OR resolved_at < started_at OR closed_at < resolved_at)""", org)).isZero();
        assertThat(count("SELECT count(*) FROM complaints WHERE organization_id = ? AND escalated_at > resolved_at", org))
                .isZero();
        assertThat(count("SELECT count(*) FROM allocations WHERE organization_id = ? AND notice_date < start_date", org))
                .isZero();

        // occupancy agrees with allocations and tenant status
        assertThat(count("""
                SELECT count(*) FROM beds b JOIN properties p ON p.id = b.property_id WHERE p.organization_id = ?
                  AND (b.status = 'OCCUPIED') <> EXISTS (SELECT 1 FROM allocations a WHERE a.bed_id = b.id AND a.status = 'ACTIVE')""",
                org)).isZero();
        assertThat(count("""
                SELECT count(*) FROM tenants t WHERE t.organization_id = ?
                  AND (t.status IN ('ACTIVE', 'ON_NOTICE')) <> EXISTS (SELECT 1 FROM allocations a WHERE a.tenant_id = t.id
                                                                       AND a.status = 'ACTIVE')""", org)).isZero();

        // the billing job has nothing left to bill for the current month
        LocalDate monthStart = LocalDate.now(clock).withDayOfMonth(1);
        assertThat(count("""
                SELECT count(*) FROM allocations a WHERE a.organization_id = ?
                  AND a.start_date <= ? AND (a.end_date IS NULL OR a.end_date > ?)
                  AND NOT EXISTS (SELECT 1 FROM invoices i WHERE i.tenant_id = a.tenant_id AND i.invoice_type = 'RENT'
                                  AND i.billing_month = ? AND i.status <> 'VOID')""", org, monthStart, monthStart, monthStart))
                .isZero();

        // the advertised logins work and see the generated data
        String owner = api.login("owner@sunrise.example.com");
        assertThat(api.get("/api/v1/dashboard", owner).body().at("/beds/total").asLong()).isEqualTo(90);
        assertThat(api.get("/api/v1/dashboard", api.login("tenant.demo@example.com")).status()).isEqualTo(200);
    }
}
