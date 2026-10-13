-- =====================================================================
-- V6: move-out workflow (notice policy, inspection, persisted settlement)
-- and per-stay deposit accounting (owner's Deposits tab).
--
--   * property_moveout_policies: notice / lock-in / final-month / refund SLA per property
--   * move_outs + move_out_inspection_items: the move-out case and its checklist
--   * deposit_accounts: one per stay chain (root allocation + its transfers), so a deposit settled at one
--     move-out can never be counted again on a later stay of the same resident
--   * deposit_transactions: ledger of what happened to a deposit (dues adjusted, deductions, refunds, ...)
--   * refunds.kind: CASH payouts vs non-cash ADJUSTMENT (deposit applied to dues)
-- Existing stays get their deposit account from DepositAccountBackfill at application start.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Shared enumerations (supersets agreed with the V5 billing migration)
-- ---------------------------------------------------------------------
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_type;
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_type
    CHECK (invoice_type IN ('RENT', 'SECURITY_DEPOSIT', 'UTILITY', 'SETTLEMENT'));

ALTER TABLE invoice_line_items DROP CONSTRAINT ck_line_items_type;
ALTER TABLE invoice_line_items ADD CONSTRAINT ck_line_items_type CHECK (line_type IN
    ('RENT', 'SECURITY_DEPOSIT', 'ELECTRICITY', 'FOOD', 'LAUNDRY', 'LATE_FEE', 'OTHER', 'DISCOUNT', 'PARKING',
     'INTERNET', 'DEDUCTION', 'NOTICE_SHORTFALL', 'DAMAGE'));

ALTER TABLE payments DROP CONSTRAINT ck_payments_method;
ALTER TABLE payments ADD CONSTRAINT ck_payments_method
    CHECK (method IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE', 'AUTOPAY', 'DEPOSIT_ADJUSTMENT'));

-- one SETTLEMENT invoice (notice shortfall, lock-in penalty, damages, later deductions) per allocation
CREATE UNIQUE INDEX uk_invoices_settlement ON invoices (allocation_id)
    WHERE invoice_type = 'SETTLEMENT' AND status <> 'VOID';

-- A deposit top-up (e.g. after a transfer to a costlier room, or a deposit raise) is a second deposit invoice on the
-- same allocation, so only one *open* deposit invoice per allocation is enforced now.
DROP INDEX uk_invoices_deposit;
CREATE UNIQUE INDEX uk_invoices_deposit_open ON invoices (allocation_id)
    WHERE invoice_type = 'SECURITY_DEPOSIT' AND status IN ('ISSUED', 'PARTIALLY_PAID', 'OVERDUE');

-- Zero-length stays ([d, d) is an empty range) escape the overlap exclusion constraints and kept a prorated rent
-- invoice for 0 nights. A check-out must be after the check-in. NOT VALID: enforced for new/updated rows only.
ALTER TABLE allocations ADD CONSTRAINT ck_allocations_checkout_after_start
    CHECK (end_reason IS DISTINCT FROM 'CHECK_OUT' OR end_date > start_date) NOT VALID;

-- ---------------------------------------------------------------------
-- Refund kinds: CASH (money paid out) vs ADJUSTMENT (deposit applied to dues, no money moves)
-- ---------------------------------------------------------------------
ALTER TABLE refunds
    ADD COLUMN kind                   VARCHAR(20)  NOT NULL DEFAULT 'CASH',
    ADD COLUMN method                 VARCHAR(20),
    ADD COLUMN payout_reference       VARCHAR(100),
    ADD COLUMN deposit_transaction_id BIGINT,
    ADD CONSTRAINT ck_refunds_kind CHECK (kind IN ('CASH', 'ADJUSTMENT')),
    ADD CONSTRAINT ck_refunds_method CHECK (method IS NULL OR method IN ('CASH', 'UPI', 'BANK_TRANSFER', 'CHEQUE'));

-- ---------------------------------------------------------------------
-- Property move-out policy (absence of a row = defaults below)
-- ---------------------------------------------------------------------
CREATE TABLE property_moveout_policies (
    property_id            BIGINT         PRIMARY KEY REFERENCES properties (id),
    organization_id        BIGINT         NOT NULL REFERENCES organizations (id),
    notice_period_days     INTEGER        NOT NULL DEFAULT 30,
    notice_shortfall_rule  VARCHAR(20)    NOT NULL DEFAULT 'CHARGE_DAYS',
    lock_in_months         INTEGER        NOT NULL DEFAULT 0,
    lock_in_penalty_rule   VARCHAR(20)    NOT NULL DEFAULT 'NONE',
    lock_in_penalty_amount NUMERIC(12, 2),
    final_month_billing    VARCHAR(10)    NOT NULL DEFAULT 'FULL',
    deposit_refund_days    INTEGER        NOT NULL DEFAULT 7,
    inspection_checklist   JSONB          NOT NULL,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_by             BIGINT,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT ck_pmp_notice CHECK (notice_period_days BETWEEN 0 AND 90),
    CONSTRAINT ck_pmp_lock_in CHECK (lock_in_months BETWEEN 0 AND 24),
    CONSTRAINT ck_pmp_refund_days CHECK (deposit_refund_days BETWEEN 0 AND 60),
    CONSTRAINT ck_pmp_shortfall CHECK (notice_shortfall_rule IN ('NONE', 'CHARGE_DAYS')),
    CONSTRAINT ck_pmp_lock_in_rule CHECK (lock_in_penalty_rule IN ('NONE', 'ONE_MONTH_RENT', 'FLAT')
        AND (lock_in_penalty_rule <> 'FLAT' OR lock_in_penalty_amount > 0)),
    CONSTRAINT ck_pmp_final_month CHECK (final_month_billing IN ('FULL', 'PRORATED'))
);

-- ---------------------------------------------------------------------
-- Deposit accounts: one per stay chain (first allocation + transfers)
-- ---------------------------------------------------------------------
CREATE TABLE deposit_accounts (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id          BIGINT         NOT NULL REFERENCES organizations (id),
    property_id              BIGINT         NOT NULL REFERENCES properties (id),
    tenant_id                BIGINT         NOT NULL REFERENCES tenants (id),
    root_allocation_id       BIGINT         NOT NULL REFERENCES allocations (id),
    current_allocation_id    BIGINT         NOT NULL REFERENCES allocations (id),
    status                   VARCHAR(20)    NOT NULL DEFAULT 'HELD',
    move_out_date            DATE,
    settled_at               TIMESTAMPTZ,
    refund_due               NUMERIC(12, 2) NOT NULL DEFAULT 0,
    refund_due_by            DATE,
    forfeit_reason           VARCHAR(500),
    overdue_notified_on      DATE,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by               BIGINT,
    updated_by               BIGINT,
    version                  BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_deposit_accounts_root UNIQUE (root_allocation_id),
    CONSTRAINT ck_deposit_accounts_status
        CHECK (status IN ('HELD', 'REFUND_DUE', 'PARTIALLY_REFUNDED', 'REFUNDED', 'SETTLED', 'FORFEITED')),
    CONSTRAINT ck_deposit_accounts_refund_due CHECK (refund_due >= 0)
);
CREATE INDEX idx_deposit_accounts_property_status ON deposit_accounts (property_id, status);
CREATE INDEX idx_deposit_accounts_tenant ON deposit_accounts (tenant_id);
CREATE INDEX idx_deposit_accounts_refund_due ON deposit_accounts (refund_due_by)
    WHERE status IN ('REFUND_DUE', 'PARTIALLY_REFUNDED');

-- every allocation of a chain points at its account (lets SQL join payments -> invoices -> account)
CREATE TABLE deposit_account_allocations (
    allocation_id      BIGINT PRIMARY KEY REFERENCES allocations (id),
    deposit_account_id BIGINT NOT NULL REFERENCES deposit_accounts (id)
);
CREATE INDEX idx_deposit_account_allocations_account ON deposit_account_allocations (deposit_account_id);

-- ---------------------------------------------------------------------
-- Move-out cases
-- ---------------------------------------------------------------------
CREATE SEQUENCE move_out_seq;

CREATE TABLE move_outs (
    id                     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    reference              VARCHAR(30)    NOT NULL,
    organization_id        BIGINT         NOT NULL REFERENCES organizations (id),
    property_id            BIGINT         NOT NULL REFERENCES properties (id),
    tenant_id              BIGINT         NOT NULL REFERENCES tenants (id),
    allocation_id          BIGINT         NOT NULL REFERENCES allocations (id),
    deposit_account_id     BIGINT REFERENCES deposit_accounts (id),
    requested_by_user_id   BIGINT REFERENCES users (id),
    initiated_by           VARCHAR(10)    NOT NULL,
    notice_date            DATE           NOT NULL,
    expected_move_out_date DATE           NOT NULL,
    reason                 VARCHAR(20)    NOT NULL,
    reason_text            VARCHAR(500),
    refund_upi_vpa         VARCHAR(100),
    shortfall_days         INTEGER        NOT NULL DEFAULT 0,
    shortfall_charge       NUMERIC(12, 2) NOT NULL DEFAULT 0,
    lock_in_penalty        NUMERIC(12, 2) NOT NULL DEFAULT 0,
    charges_waived         BOOLEAN        NOT NULL DEFAULT FALSE,
    status                 VARCHAR(20)    NOT NULL DEFAULT 'NOTICE_GIVEN',
    inspected_by           BIGINT REFERENCES users (id),
    inspected_at           TIMESTAMPTZ,
    inspection_notes       VARCHAR(2000),
    actual_move_out_date   DATE,
    settlement_invoice_id  BIGINT REFERENCES invoices (id),
    deposit_held           NUMERIC(12, 2),
    outstanding_dues       NUMERIC(12, 2),
    dues_adjusted          NUMERIC(12, 2),
    total_deductions       NUMERIC(12, 2),
    rent_credit            NUMERIC(12, 2),
    refund_due             NUMERIC(12, 2),
    payable_by_tenant      NUMERIC(12, 2),
    refund_due_by          DATE,
    settled_at             TIMESTAMPTZ,
    closed_at              TIMESTAMPTZ,
    close_note             VARCHAR(500),
    withdrawn_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by             BIGINT,
    updated_by             BIGINT,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uk_move_outs_reference UNIQUE (reference),
    CONSTRAINT ck_move_outs_status CHECK (status IN ('NOTICE_GIVEN', 'INSPECTED', 'SETTLED', 'CLOSED', 'WITHDRAWN')),
    CONSTRAINT ck_move_outs_initiated_by CHECK (initiated_by IN ('TENANT', 'STAFF')),
    CONSTRAINT ck_move_outs_reason CHECK (reason IN
        ('JOB_CHANGE', 'RELOCATION', 'DISSATISFIED', 'PRICE', 'PERSONAL', 'EVICTION', 'OTHER')),
    CONSTRAINT ck_move_outs_dates CHECK (expected_move_out_date >= notice_date),
    CONSTRAINT ck_move_outs_money CHECK (shortfall_days >= 0 AND shortfall_charge >= 0 AND lock_in_penalty >= 0)
);
-- at most one open case per resident (it follows the resident across transfers)
CREATE UNIQUE INDEX uk_move_outs_open_tenant ON move_outs (tenant_id) WHERE status IN ('NOTICE_GIVEN', 'INSPECTED');
CREATE INDEX idx_move_outs_property_status ON move_outs (property_id, status, expected_move_out_date);
CREATE INDEX idx_move_outs_allocation ON move_outs (allocation_id);

CREATE TABLE move_out_inspection_items (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    move_out_id      BIGINT         NOT NULL REFERENCES move_outs (id) ON DELETE CASCADE,
    item             VARCHAR(100)   NOT NULL,
    item_condition   VARCHAR(20)    NOT NULL,
    deduction_amount NUMERIC(12, 2) NOT NULL DEFAULT 0,
    note             VARCHAR(500),
    CONSTRAINT ck_inspection_condition CHECK (item_condition IN ('OK', 'CLEANING', 'DAMAGED', 'MISSING')),
    CONSTRAINT ck_inspection_deduction CHECK (deduction_amount >= 0)
);
CREATE INDEX idx_inspection_items_move_out ON move_out_inspection_items (move_out_id);

-- ---------------------------------------------------------------------
-- Deposit ledger
-- ---------------------------------------------------------------------
CREATE TABLE deposit_transactions (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    deposit_account_id  BIGINT         NOT NULL REFERENCES deposit_accounts (id),
    organization_id     BIGINT         NOT NULL REFERENCES organizations (id),
    tenant_id           BIGINT         NOT NULL REFERENCES tenants (id),
    move_out_id         BIGINT REFERENCES move_outs (id),
    type                VARCHAR(20)    NOT NULL,
    amount              NUMERIC(12, 2) NOT NULL,
    invoice_id          BIGINT REFERENCES invoices (id),
    payment_id          BIGINT REFERENCES payments (id),
    refund_id           BIGINT REFERENCES refunds (id),
    remaining           NUMERIC(12, 2),
    method              VARCHAR(20),
    reference           VARCHAR(100),
    description         VARCHAR(500)   NOT NULL,
    idempotency_key     VARCHAR(100),
    request_fingerprint VARCHAR(64),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by          BIGINT,
    CONSTRAINT ck_deposit_tx_type CHECK (type IN
        ('DUES_ADJUSTED', 'DEDUCTION', 'FORFEIT', 'REFUND', 'TOP_UP_REQUESTED', 'RENT_CREDIT')),
    CONSTRAINT ck_deposit_tx_amount CHECK (amount > 0),
    CONSTRAINT ck_deposit_tx_remaining CHECK (remaining IS NULL OR (remaining >= 0 AND remaining <= amount))
);
CREATE UNIQUE INDEX uk_deposit_tx_idempotency ON deposit_transactions (organization_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
-- a manual refund recorded through the payments API is linked to the ledger exactly once
CREATE UNIQUE INDEX uk_deposit_tx_refund ON deposit_transactions (refund_id) WHERE refund_id IS NOT NULL;
CREATE INDEX idx_deposit_tx_account ON deposit_transactions (deposit_account_id, created_at);

ALTER TABLE refunds ADD CONSTRAINT fk_refunds_deposit_transaction
    FOREIGN KEY (deposit_transaction_id) REFERENCES deposit_transactions (id);
