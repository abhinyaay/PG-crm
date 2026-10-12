-- =====================================================================
-- Read-only contract for the Python analytics service.
--
-- The analytics service never touches base tables. It reads these views
-- through a dedicated read-only role (analytics_ro), which:
--   * decouples it from table refactors (views are the published contract)
--   * keeps PII (names, phones, emails, password hashes, ID numbers) out of reach
--   * lets the analytics service point at a read replica in production
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS analytics;

CREATE OR REPLACE VIEW analytics.properties AS
SELECT p.id, p.organization_id, p.code, p.name, p.city, p.state, p.property_type, p.status, p.created_at
FROM properties p
WHERE p.deleted_at IS NULL;

CREATE OR REPLACE VIEW analytics.rooms AS
SELECT r.id, r.property_id, r.room_number, r.room_type, r.capacity, r.monthly_rent,
       r.air_conditioned, r.attached_bathroom, r.status
FROM rooms r
WHERE r.deleted_at IS NULL;

CREATE OR REPLACE VIEW analytics.beds AS
SELECT b.id, b.room_id, b.property_id, b.status
FROM beds b
WHERE b.deleted_at IS NULL;

CREATE OR REPLACE VIEW analytics.tenants AS
SELECT t.id, t.organization_id, t.property_id, t.status, t.gender, t.joining_date, t.move_out_date, t.created_at
FROM tenants t
WHERE t.deleted_at IS NULL;

CREATE OR REPLACE VIEW analytics.allocations AS
SELECT a.id, a.organization_id, a.property_id, a.tenant_id, a.bed_id, b.room_id, a.status,
       a.start_date, a.end_date, a.end_reason, a.monthly_rent
FROM allocations a
JOIN beds b ON b.id = a.bed_id;

CREATE OR REPLACE VIEW analytics.invoices AS
SELECT i.id, i.organization_id, i.property_id, i.tenant_id, i.allocation_id, i.invoice_type,
       i.billing_month, i.issue_date, i.due_date, i.status, i.total_amount, i.amount_paid
FROM invoices i;

CREATE OR REPLACE VIEW analytics.payments AS
SELECT p.id, p.organization_id, p.property_id, p.tenant_id, p.invoice_id, i.invoice_type,
       i.due_date AS invoice_due_date, p.amount, p.refunded_amount, p.method, p.status, p.paid_at
FROM payments p
JOIN invoices i ON i.id = p.invoice_id;

CREATE OR REPLACE VIEW analytics.maintenance_requests AS
SELECT m.id, m.organization_id, m.property_id, m.category, m.priority, m.status,
       m.assigned_to_user_id, m.created_at, m.assigned_at, m.started_at, m.resolved_at, m.closed_at,
       m.sla_due_at, m.sla_breached
FROM maintenance_requests m;

CREATE OR REPLACE VIEW analytics.complaints AS
SELECT c.id, c.organization_id, c.property_id, c.category, c.priority, c.status, c.escalation_level,
       c.created_at, c.resolved_at, c.closed_at, c.due_at
FROM complaints c;

-- used by the analytics service to scope MANAGER requests to their assigned properties
CREATE OR REPLACE VIEW analytics.user_property_access AS
SELECT pa.user_id, pa.property_id
FROM property_assignments pa;

-- Grant read access only if the role has been provisioned (by infra/postgres/init or by ops in AWS).
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'analytics_ro') THEN
        GRANT USAGE ON SCHEMA analytics TO analytics_ro;
        GRANT SELECT ON ALL TABLES IN SCHEMA analytics TO analytics_ro;
        ALTER DEFAULT PRIVILEGES IN SCHEMA analytics GRANT SELECT ON TABLES TO analytics_ro;
    END IF;
END
$$;
