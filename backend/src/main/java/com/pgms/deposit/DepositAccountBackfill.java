package com.pgms.deposit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives every stay chain written before V6 (or by the SQL demo seeder) its deposit account, maps transfer successors
 * and mirrors manual deposit refunds into the ledger. Idempotent (ON CONFLICT DO NOTHING), so it runs at start-up
 * (after the demo seeder) and from the daily deposit job on any replica.
 *
 * <p>Legacy stays that already ended were never settled: they become REFUNDED when a refund was recorded, REFUND_DUE
 * (due 7 days after the move-out) when deposit money is still held, SETTLED otherwise.
 */
@Component
public class DepositAccountBackfill {

    private static final Logger log = LoggerFactory.getLogger(DepositAccountBackfill.class);

    private static final String ACCOUNTS = """
            WITH RECURSIVE chain AS (
                SELECT a.id AS root_id, a.id AS allocation_id
                FROM allocations a
                WHERE a.previous_allocation_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM deposit_accounts d WHERE d.root_allocation_id = a.id)
                UNION ALL
                SELECT c.root_id, n.id FROM chain c JOIN allocations n ON n.previous_allocation_id = c.allocation_id
            ), tail AS (
                SELECT DISTINCT ON (c.root_id) c.root_id, al.id AS tail_id, al.property_id, al.status, al.end_date
                FROM chain c JOIN allocations al ON al.id = c.allocation_id
                ORDER BY c.root_id, al.start_date DESC, al.id DESC
            ), money AS (
                SELECT c.root_id, sum(p.amount - p.refunded_amount) AS held, sum(p.refunded_amount) AS refunded
                FROM chain c
                JOIN invoices i ON i.allocation_id = c.allocation_id AND i.invoice_type = 'SECURITY_DEPOSIT'
                JOIN payments p ON p.invoice_id = i.id
                GROUP BY c.root_id
            )
            INSERT INTO deposit_accounts (organization_id, property_id, tenant_id, root_allocation_id, current_allocation_id,
                                          status, move_out_date, settled_at, refund_due, refund_due_by)
            SELECT r.organization_id, t.property_id, r.tenant_id, r.id, t.tail_id,
                   CASE WHEN t.status = 'ACTIVE' THEN 'HELD'
                        WHEN coalesce(m.refunded, 0) > 0 THEN 'REFUNDED'
                        WHEN coalesce(m.held, 0) > 0 THEN 'REFUND_DUE'
                        ELSE 'SETTLED' END,
                   CASE WHEN t.status = 'ACTIVE' THEN NULL ELSE t.end_date END,
                   CASE WHEN t.status = 'ACTIVE' THEN NULL ELSE now() END,
                   CASE WHEN t.status <> 'ACTIVE' AND coalesce(m.refunded, 0) = 0 THEN coalesce(m.held, 0) ELSE 0 END,
                   CASE WHEN t.status <> 'ACTIVE' AND coalesce(m.refunded, 0) = 0 AND coalesce(m.held, 0) > 0
                        THEN t.end_date + 7 END
            FROM tail t
            JOIN allocations r ON r.id = t.root_id
            LEFT JOIN money m ON m.root_id = t.root_id
            ON CONFLICT (root_allocation_id) DO NOTHING
            """;

    private static final String MAPPINGS = """
            WITH RECURSIVE chain AS (
                SELECT d.id AS account_id, d.root_allocation_id AS allocation_id FROM deposit_accounts d
                UNION ALL
                SELECT c.account_id, n.id FROM chain c JOIN allocations n ON n.previous_allocation_id = c.allocation_id
            )
            INSERT INTO deposit_account_allocations (allocation_id, deposit_account_id)
            SELECT allocation_id, account_id FROM chain
            ON CONFLICT (allocation_id) DO NOTHING
            """;

    private static final String MANUAL_REFUNDS = """
            INSERT INTO deposit_transactions (deposit_account_id, organization_id, tenant_id, type, amount, refund_id,
                                              description, created_at, created_by)
            SELECT m.deposit_account_id, r.organization_id, p.tenant_id, 'REFUND', r.amount, r.id,
                   left('Refund ' || r.refund_reference || ': ' || r.reason, 500), r.refunded_at, r.created_by
            FROM refunds r
            JOIN payments p ON p.id = r.payment_id
            JOIN invoices i ON i.id = p.invoice_id AND i.invoice_type = 'SECURITY_DEPOSIT'
            JOIN deposit_account_allocations m ON m.allocation_id = i.allocation_id
            WHERE r.kind = 'CASH' AND r.deposit_transaction_id IS NULL
              AND NOT EXISTS (SELECT 1 FROM deposit_transactions x WHERE x.refund_id = r.id)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbc;

    public DepositAccountBackfill(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            run();
        } catch (RuntimeException e) {
            // never block start-up; the daily job retries
            log.error("Deposit account backfill failed", e);
        }
    }

    public int run() {
        int accounts = jdbc.update(ACCOUNTS);
        int mappings = jdbc.update(MAPPINGS);
        int refunds = jdbc.update(MANUAL_REFUNDS);
        if (accounts + mappings + refunds > 0) {
            log.info("Deposit backfill: {} accounts, {} allocation mappings, {} refunds linked", accounts, mappings, refunds);
        }
        return accounts;
    }
}
