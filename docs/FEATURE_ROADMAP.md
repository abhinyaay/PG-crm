# Feature roadmap & implementation specs (from product gap analysis)

I read the README, INTERVIEW_GUIDE, all ~100 operations in `docs/openapi.json`, `V1`/`V2` migrations, the billing, booking, notification, maintenance, security and audit code, and the analytics README and finance code. I also made a few GET calls to the running API at :58088 (demo org: 3 properties, 1,501 invoices).

**Overall:** the transactional core is strong: bed allocation, invoicing, idempotent payments, ticket workflows and the outbox. What's missing is the operational and money layer between rent invoices and what an Indian PG owner does every day:
- chasing payments (late fees, repeat reminders, UPI)
- electricity billing
- the move-out and deposit-refund process
- expenses and P&L
- compliance (KYC files, police verification)
- tenant self-service (forgot password, giving notice, onboarding)

## 0. Gaps in existing flows (relevant to the bug and notification agents)
1. **Tenants can't give notice.** `POST /allocations/{id}/notice` is restricted to operator roles. There is no minimum notice period, lock-in or shortfall rule; `Allocation.recordNotice` accepts any future date.
2. **Check-out settlement isn't saved.** It is computed in `InvoiceService.settlement`, returned once and only otherwise stored in the audit JSON. Deductions never become invoice lines or revenue. Deposit refunds are done by hand through `POST /payments/{depositPaymentId}/refunds`, and nothing records whether a refund is still owed.
3. **Final month is never prorated.** A tenant leaving on the 10th keeps a full-month invoice (generated on the 1st). This should be a property policy.
4. **Only one overdue reminder is sent.** `markOverdue` emits it on day 1 and then nothing more.
5. **Move-in invoices are due the same day.** `dueDate == issueDate` when check-in is after the due day (seen in demo data: INV-202610-001495). They go overdue the next day, so automatic late fees must exclude them or allow grace.
6. **Some tenants get no notifications.** `NotificationService.notifyTenant` sends in-app (if the tenant has a login) and email (if known). A tenant with neither gets nothing. `tenants.phone` is NOT NULL, so SMS is the only channel that always reaches a tenant.
7. **No forgot-password or OTP flow.** The auth endpoints are login, refresh, logout, logout-all, change-password, me and jwks. Portal accounts are created with an `initialPassword` chosen by staff.
8. **Analytics hard-codes invoice types.** `analytics-service/app/services/payments.py:33` has `INVOICE_TYPES = ("RENT","SECURITY_DEPOSIT")`, and revenue (`repositories/finance.py`) is `invoice_type='RENT'` only. Any new invoice type or payment method below needs matching analytics changes.
9. **KYC is metadata only.** There is no file upload and no police-verification field.

## 1. Prioritised missing features

| # | Pri | Feature | Why it matters | Roles | Size |
|---|---|---|---|---|---|
| 1 | P0 | **Late-fee automation and overdue reminder schedule** (per-property policy, daily overdue digest to managers) | Collections are the business. Manual `LATE_FEE` charges don't happen in practice, so late payment costs tenants nothing. | Admin sets policy; Manager waives; Tenant sees | S/M |
| 2 | P0 | **UPI pay link/QR and tenant "I've paid" claims (UTR)** with a verification queue | Most PG rent is paid by UPI to the owner's VPA, followed by a WhatsApp screenshot and manual matching. This removes the screenshot step without a payment gateway. | Tenant, Manager | M |
| 3 | P0 | **Electricity sub-meter billing per room, split by occupancy days** | Near-universal for AC rooms, done today in Excel and disputed every month. `ELECTRICITY` line type exists but there is no workflow. | Staff take readings; Manager bills; Tenant views | M |
| 4 | P0 | **Move-out workflow**: notice/lock-in rules, tenant-initiated notice, inspection checklist, saved settlement, deposit-refund tracking | Deposit disputes and slow refunds are the top tenant grievance and a legal risk. Notice-period income is real money. | Tenant, Staff, Manager, Admin | L (splits into 4a S/M + 4b M) |
| 5 | P0 | **Expenses and per-property P&L** (recurring expenses, approval threshold) | Owners running 3+ buildings, often leased, can't see which property makes money. Revenue exists; costs don't. | Manager records; Admin approves and views P&L | M |
| 6 | P0 | **Self-service password reset / OTP login** (email or SMS OTP) | Without it the tenant portal fails once real email/SMS exists; staff set passwords today. | All, mainly tenants | S |
| 7 | P0 | **Notification preferences and org channel settings** (DLT template IDs, quiet hours) | SMS costs money per message, and Indian SMS needs DLT-registered templates. Operators must control volume. | All; Admin for org settings | S |
| 8 | P0 | **KYC file upload and police-verification tracking** (object storage via presigned URLs, encryption, retention) | Tenant police verification is mandatory in many Indian cities; the operator is liable. | Manager, Tenant | M (needs storage infra) |
| 9 | P1 | Leads/enquiries pipeline: public enquiry form, visit scheduling, conversion to booking, source tracking | Occupancy is revenue; enquiries from NoBroker/JustDial/walk-ins are lost in WhatsApp | Manager, Admin | M |
| 10 | P1 | Announcements / notice board (optional "urgent" SMS) | Water outages, pest control, rule changes; replaces WhatsApp groups | Manager to Tenants | S |
| 11 | P1 | Tenant self-onboarding invite link (profile, emergency contacts, KYC, agreement) | Cuts move-in desk time; data quality | Tenant, Manager | M |
| 12 | P1 | Digital rental agreement (template to PDF, click/OTP accept with timestamp and IP; captures rent, deposit, notice, lock-in) | Enforceable terms for notice and deductions | Admin, Tenant | M |
| 13 | P1 | Bulk import (CSV/XLSX) of rooms, tenants, active stays, opening balances | Adoption blocker for any operator with existing tenants | Admin | M |
| 14 | P1 | Payment gateway (Razorpay/Cashfree) with webhooks into the existing idempotent `PaymentProcessor` | Automatic reconciliation; card/netbanking | Tenant | L |
| 15 | P1 | WhatsApp Business channel (template messages) as another `NotificationSender` | Indian tenants read WhatsApp far more than email | All | M |
| 16 | P1 | Rent revision / annual escalation; deposit top-up on transfer (README limitation) | Rent snapshot never changes today | Admin, Manager | S/M |
| 17 | P1 | Food/mess: weekly menu, meal opt-out with cutoff, kitchen headcount | Most Indian PGs include food; opt-out cuts waste | Manager, Tenant, Kitchen staff | M |
| 18 | P1 | Visitor log / gate pass and night-out/leave requests with guardian notification | Safety, especially for women's and student PGs (demo has a `WOMEN` property) | Staff (gate), Tenant, Manager | M |
| 19 | P1 | Staff tasks / housekeeping and recurring checklists (room cleaning, tank cleaning, pest control) | Maintenance tickets are reactive; recurring work isn't tracked | Manager, Staff | M |
| 20 | P1 | Room inventory/asset register (feeds move-out inspection and maintenance) | Damage deductions need a baseline | Manager, Staff | S/M |
| 21 | P1 | Excel export and scheduled email reports (monthly collection/P&L to owner) | Owners live in Excel; the analytics exporter registry already plans for it | Admin | S/M |
| 22 | P2 | Tally XML / Zoho Books export | Accountant handover | Admin | M |
| 23 | P2 | GST invoicing fields (GSTIN, SAC, tax lines) | Only for larger operators; applicability varies, confirm with a CA | Admin | M |
| 24 | P2 | Tenant feedback (post-ticket CSAT, monthly NPS, move-out survey) | Retention signal | Tenant, Admin | S |
| 25 | P2 | Multi-language tenant UI and templates (hi/kn/ta/te/mr) | Staff and tenant comfort | All | M |
| 26 | P2 | Biometric/QR attendance, parking/vehicle register, laundry, referral credits, push (FCM), occupancy forecasting and pricing | Nice to have | Various | S–L |

**Top 5 to build now** (best value against fit with the current code): 1, 2, 3, 4, 5. Specs follow. The preference model (#7) is specified in section 3 because the SMS/email work needs it. #6 and #10 have short specs at the end of section 2.

## 2. Implementation specs

**Conventions for all specs:**
- Migration numbers are suggestions; take the next free number at merge time (another agent may claim V3). Never edit V1 or V2.
- Package by feature; records in `<Feature>Dtos`.
- `AccessGuard.requirePropertyAccess` / `scopeFor` for staff; `requireCurrentTenant` for tenants (foreign record returns 404).
- `AuditService.record` in the same transaction; events go from `ApplicationEventPublisher` to `NotificationEventHandler`.
- Jobs use `@Scheduled` + `@SchedulerLock`, with each item in its own `REQUIRES_NEW` transaction.
- New unique constraints are added to `ConstraintViolationTranslator.KNOWN`; sort fields are whitelisted.

### Spec 1: Property policies, automatic late fees and overdue reminders (`com.pgms.billing` + `com.pgms.property`)

**Migration `V3__property_policies_and_late_fees.sql`**
```sql
CREATE TABLE property_policies (
    id                         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id            BIGINT         NOT NULL REFERENCES organizations (id),
    property_id                BIGINT         NOT NULL REFERENCES properties (id),
    late_fee_enabled           BOOLEAN        NOT NULL DEFAULT FALSE,
    late_fee_mode              VARCHAR(20)    NOT NULL DEFAULT 'FLAT',      -- FLAT | PERCENT_OF_RENT
    late_fee_value             NUMERIC(12, 2) NOT NULL DEFAULT 0,          -- INR or percent
    late_fee_grace_days        INTEGER        NOT NULL DEFAULT 0,
    late_fee_recurrence        VARCHAR(10)    NOT NULL DEFAULT 'ONCE',      -- ONCE | DAILY | WEEKLY
    late_fee_cap               NUMERIC(12, 2),                             -- max cumulative per invoice
    late_fee_on_move_in        BOOLEAN        NOT NULL DEFAULT FALSE,       -- apply to prorated move-in invoice?
    due_reminder_days_before   INTEGER        NOT NULL DEFAULT 3,
    overdue_reminder_offsets   INTEGER[]      NOT NULL DEFAULT '{1,3,7,15}',
    upi_vpa                    VARCHAR(100),
    upi_payee_name             VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_property_policies_property UNIQUE (property_id),
    CONSTRAINT ck_pp_late_fee_mode CHECK (late_fee_mode IN ('FLAT', 'PERCENT_OF_RENT')),
    CONSTRAINT ck_pp_late_fee_recurrence CHECK (late_fee_recurrence IN ('ONCE', 'DAILY', 'WEEKLY')),
    CONSTRAINT ck_pp_late_fee_value CHECK (late_fee_value >= 0 AND (late_fee_mode <> 'PERCENT_OF_RENT' OR late_fee_value <= 100)),
    CONSTRAINT ck_pp_grace CHECK (late_fee_grace_days BETWEEN 0 AND 31),
    CONSTRAINT ck_pp_cap CHECK (late_fee_cap IS NULL OR late_fee_cap > 0),
    CONSTRAINT ck_pp_due_reminder CHECK (due_reminder_days_before BETWEEN 0 AND 15),
    CONSTRAINT ck_pp_upi_vpa CHECK (upi_vpa IS NULL OR upi_vpa ~ '^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$')
);

CREATE TABLE late_fee_entries (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organizations (id),
    invoice_id      BIGINT         NOT NULL REFERENCES invoices (id),
    line_item_id    BIGINT         NOT NULL REFERENCES invoice_line_items (id),
    kind            VARCHAR(10)    NOT NULL,          -- CHARGE | WAIVER
    period_key      DATE,                             -- idempotency key for CHARGE
    amount          NUMERIC(12, 2) NOT NULL,
    reason          VARCHAR(255),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by      BIGINT,
    CONSTRAINT ck_late_fee_kind CHECK (kind IN ('CHARGE', 'WAIVER')),
    CONSTRAINT ck_late_fee_amount CHECK (amount > 0),
    CONSTRAINT ck_late_fee_period CHECK ((kind = 'CHARGE') = (period_key IS NOT NULL))
);
CREATE UNIQUE INDEX uk_late_fee_invoice_period ON late_fee_entries (invoice_id, period_key) WHERE kind = 'CHARGE';
CREATE INDEX idx_late_fee_invoice ON late_fee_entries (invoice_id);
```

**Entities and enums**
- `property.PropertyPolicy` (extends `AuditableEntity`). Map `overdueReminderOffsets` as `Integer[]` with `@JdbcTypeCode(SqlTypes.ARRAY)`.
- `billing.LateFeeMode {FLAT, PERCENT_OF_RENT}`, `billing.LateFeeRecurrence {ONCE, DAILY, WEEKLY}`.
- `billing.LateFeeEntry` (immutable), `LateFeeEntryKind {CHARGE, WAIVER}`.
- `PropertyPolicyService.effectiveFor(propertyId)` returns the stored row, or defaults built from `app.billing.*` if none exists (`configured=false`).
- Pure helper `billing.LateFeeCalculator` with no I/O, so it is unit-testable.

**Endpoints**

| Method | Path | Roles | Request | Response / codes |
|---|---|---|---|---|
| GET | `/api/v1/properties/{id}/policies` | SUPER_ADMIN, PROPERTY_ADMIN, MANAGER | – | `PolicyResponse{propertyId, configured, lateFee{enabled,mode,value,graceDays,recurrence,cap,onMoveIn}, reminders{dueDaysBefore, overdueOffsets[]}, upi{vpa,payeeName}, version}` 200; 404 cross-org; 403 unassigned |
| PUT | `/api/v1/properties/{id}/policies` | SUPER_ADMIN, PROPERTY_ADMIN | the same fields plus `version` (null on first create) | 200; 400 validation (`overdueOffsets` 1..10 distinct ints in 1..90, sorted server-side); 409 `CONCURRENT_MODIFICATION` on version mismatch. Audit `PROPERTY_POLICY_UPDATED` with before/after. |
| GET | `/api/v1/invoices/{id}/late-fees` | admin roles, MANAGER, TENANT (own) | – | `[LateFeeEntryResponse{id,kind,periodKey,amount,reason,createdAt}]` plus totals `{charged, waived, net}` |
| POST | `/api/v1/invoices/{id}/late-fees/waive` | SUPER_ADMIN, PROPERTY_ADMIN, MANAGER | `{amount (DecimalMin 0.01, Digits 10,2), reason @NotBlank ≤255}` | 200 `InvoiceResponse`. 422 `WAIVER_EXCEEDS_LATE_FEES` (amount > charged − waived); 422 `DISCOUNT_EXCEEDS_BALANCE` (existing); 409 `INVOICE_VOID`. Creates a DISCOUNT line "Late fee waiver: {reason}" plus a WAIVER entry; audit `LATE_FEE_WAIVED`. |
| POST | `/api/v1/billing/late-fees/run?date=YYYY-MM-DD` | SUPER_ADMIN | – | `{date, applied, skipped, failed}` (manual trigger, idempotent; mirrors `/invoices/generate`) |

**Business rules**
- **Eligible invoices:** `invoice_type = RENT` (also UTILITY once Spec 3 exists), `status = OVERDUE`, `balance > 0`, and the property policy has `late_fee_enabled`.
- **Move-in invoices** (`period_start ≠ billing_month`) are skipped unless `late_fee_on_move_in`.
- **Day calculation:** let `d = today − due_date` in days, and let `firstDay = due_date + grace + 1`. Nothing applies before `firstDay`.
- **Idempotency key (`period_key`):**
  - ONCE: `period_key = firstDay`.
  - DAILY: `period_key = today`.
  - WEEKLY: only when `(today − firstDay) % 7 == 0`, with `period_key = today`.
- **Amount:**
  - FLAT: `value`.
  - PERCENT_OF_RENT: `value% × sum(RENT lines)`, HALF_UP to 2 dp. Based on rent, not balance, so it never compounds.
  - If `cap` is set: `amount = min(amount, cap − charged)`. Skip when the result is ≤ 0.
- **Locking:** `invoiceRepository.findByIdForUpdate`, then re-check status and balance under the lock. This serialises with payments. Then `invoice.addCharge(LATE_FEE, "Late fee ({period_key})", …)`, save the entry, flush, and catch `uk_late_fee_invoice_period` as "skipped".
- Never apply to VOID, PAID or SECURITY_DEPOSIT invoices. A payment that clears the balance stops further fees, because status leaves OVERDUE.
- **Policy changes are not retroactive.** Existing entries stay; new runs use the new policy.
- **Reminders:** the due reminder uses the policy's `due_reminder_days_before` (fall back to the global config). Overdue reminders go out when `today − due_date ∈ overdue_reminder_offsets`.
- **Dedup key:** unify as `PAYMENT_OVERDUE:{invoiceId}:D{n}`, and change `markOverdue` to use the same format so day 1 isn't sent twice.

**Scheduled jobs (`BillingJobs`)**
- `applyLateFees`: cron `0 15 1 * * *` IST, lock `invoiceLateFees`. Runs after overdue marking at 01:00.
- `sendOverdueReminders`: `0 15 9 * * *`. A native query joins `property_policies` and uses `COALESCE` to default offsets.
- `overdueDigest`: `0 30 9 * * *`. One `OVERDUE_DIGEST` per property with ≥1 overdue invoice, sent to managers (and admins on Mondays). Dedup `OVERDUE_DIGEST:{propertyId}:{date}`. Body: count, total, top 5 by amount.

**Events**
- `BillingEvents.LateFeeApplied(invoiceId, invoiceNumber, orgId, propertyId, tenantId, amount, newBalance)`
- `LateFeeWaived(...)`
- `OverdueReminder(invoiceId, …, daysOverdue, balance)`, which replaces `PaymentDue(overdue=true)` for the offsets.
- `OverdueDigest(propertyId, orgId, count, total)`

**Tests**
- Unit `LateFeeCalculatorTest`: FLAT/PERCENT, grace boundary (day `firstDay−1` gives nothing, `firstDay` gives a fee), ONCE/DAILY/WEEKLY keys, cap truncation and exhaustion, rounding, move-in exclusion.
- IT `LateFeeIT`:
  - job run twice gives exactly one line (`uk_late_fee_invoice_period`)
  - concurrent payment plus late-fee job: no lost update, no fee if payment cleared first
  - disabled policy gives no fee
  - deposit invoice untouched
  - waiver > charged gives 422
  - manager on an unassigned property gets 403 on waive; another org gets 404
  - policy PUT with a stale version gives 409
  - reminder offsets produce one notification per offset (dedup on rerun)

### Spec 2: UPI pay link and tenant payment claims (`com.pgms.billing`)

**Migration `V4__payment_claims.sql`**
```sql
CREATE SEQUENCE payment_claim_seq;
CREATE TABLE payment_claims (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    claim_reference      VARCHAR(30)    NOT NULL,                 -- PCL-202610-000001
    organization_id      BIGINT         NOT NULL REFERENCES organizations (id),
    property_id          BIGINT         NOT NULL REFERENCES properties (id),
    tenant_id            BIGINT         NOT NULL REFERENCES tenants (id),
    invoice_id           BIGINT         NOT NULL REFERENCES invoices (id),
    submitted_by_user_id BIGINT         NOT NULL REFERENCES users (id),
    amount               NUMERIC(12, 2) NOT NULL,
    method               VARCHAR(20)    NOT NULL,
    utr                  VARCHAR(40),
    paid_on              DATE           NOT NULL,
    tenant_note          VARCHAR(500),
    proof_storage_key    VARCHAR(500),                            -- used once file storage (P0 #8) exists
    status               VARCHAR(20)    NOT NULL DEFAULT 'SUBMITTED',
    reviewed_by          BIGINT REFERENCES users (id),
    reviewed_at          TIMESTAMPTZ,
    rejection_reason     VARCHAR(255),
    payment_id           BIGINT REFERENCES payments (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_payment_claims_reference UNIQUE (claim_reference),
    CONSTRAINT ck_payment_claims_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_claims_method CHECK (method IN ('UPI', 'BANK_TRANSFER', 'CASH', 'CHEQUE')),
    CONSTRAINT ck_payment_claims_status CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_payment_claims_utr CHECK (method = 'CASH' OR utr IS NOT NULL),
    CONSTRAINT ck_payment_claims_approved CHECK ((status = 'APPROVED') = (payment_id IS NOT NULL))
);
CREATE UNIQUE INDEX uk_payment_claims_open_invoice ON payment_claims (invoice_id) WHERE status = 'SUBMITTED';
CREATE UNIQUE INDEX uk_payment_claims_utr ON payment_claims (organization_id, upper(utr))
    WHERE utr IS NOT NULL AND status IN ('SUBMITTED', 'APPROVED');
CREATE INDEX idx_payment_claims_property_status ON payment_claims (property_id, status, created_at);
CREATE INDEX idx_payment_claims_tenant ON payment_claims (tenant_id, created_at DESC);
```
Add `ReferenceNumberGenerator.Kind.PAYMENT_CLAIM("PCL", "payment_claim_seq")`. Translator entries:
- `uk_payment_claims_open_invoice` → 409 `CLAIM_ALREADY_PENDING`
- `uk_payment_claims_utr` → 409 `DUPLICATE_TRANSACTION`

**Entities:** `PaymentClaim` (AuditableEntity). Its state methods `approve(paymentId, reviewer, at)`, `reject(reason, reviewer, at)` and `cancel()` throw 409 `CLAIM_NOT_PENDING` unless the claim is SUBMITTED. Enum `PaymentClaimStatus`; reuse `PaymentMethod` for the subset.

**Endpoints**

| Method | Path | Roles | Request | Response / codes |
|---|---|---|---|---|
| GET | `/api/v1/invoices/{id}/pay-link?amount=` | TENANT (own), admin roles, MANAGER | optional `amount` ≤ balance | `{invoiceId, invoiceNumber, amount, payeeName, vpa, upiUri}` 200. 422 `UPI_NOT_CONFIGURED`; 409 `INVOICE_NOT_PAYABLE`; 422 `OVERPAYMENT`. |
| POST | `/api/v1/invoices/{id}/payment-claims` | TENANT | `{amount, method, utr, paidOn @PastOrPresent, note ≤500}` | 201 `PaymentClaimResponse`. 404 not own; 409 `INVOICE_NOT_PAYABLE`; 422 `OVERPAYMENT`; 400 UTR format; 409 `CLAIM_ALREADY_PENDING` / `DUPLICATE_TRANSACTION`. |
| GET | `/api/v1/payment-claims?propertyId&status&tenantId&from&to` | staff roles (scoped), TENANT (own only) | page/sort (`createdAt`, `amount`, `paidOn`) | `PageResponse<PaymentClaimResponse>` |
| GET | `/api/v1/payment-claims/{id}` | same | – | 200 / 404 |
| POST | `/api/v1/payment-claims/{id}/approve` | SUPER_ADMIN, PROPERTY_ADMIN, MANAGER | `{amount? (≤ claim amount), paidAt?, notes?}` | 200 `{claim, payment: PaymentResponse}`. 409 `CLAIM_NOT_PENDING`; 422 `OVERPAYMENT`; 409 `DUPLICATE_TRANSACTION`. |
| POST | `/api/v1/payment-claims/{id}/reject` | same | `{reason @NotBlank ≤255}` | 200 |
| POST | `/api/v1/payment-claims/{id}/cancel` | TENANT (own) | – | 200 / 409 |

**UPI link format:** `upi://pay?pa={vpa}&pn={payeeName}&am={amount 2dp}&cu=INR&tn={"Rent " + invoiceNumber, ≤50 chars}&tr={invoiceNumber}`, every value URL-encoded. The frontend renders the QR code; the backend doesn't produce images.

**UTR validation by method:**
- UPI: `^\d{12}$`
- BANK_TRANSFER: `^[A-Za-z0-9]{6,22}$`
- CHEQUE: `^\d{6}$`
- CASH: UTR must be null and `note` is required (for example "Paid to warden Ravi").

**Business rules**
- **Approval runs in one transaction.**
  - Lock the claim (`FOR UPDATE`), then call the existing `PaymentProcessor.recordPayment` with:
    - idempotency key `claim:{claimId}`
    - `externalReference = utr`
    - `paidAt = paidOn 12:00 IST`, unless overridden
  - The invoice is locked inside the processor (lock order: claim → invoice).
  - Then set the claim to APPROVED with the payment id. The existing `PaymentReceived` event notifies the tenant; there is no separate approval notification.
  - Audit `PAYMENT_CLAIM_APPROVED`.
- **Reduced approvals:** if a manager approves a smaller amount (for example a partial credit received), the claim stores the approved amount on the payment and keeps the original claim amount, with a note.
- **Duplicate references:** if the same UTR was already recorded manually, `uk_payments_external_reference` returns 409 `DUPLICATE_TRANSACTION`. The manager should reject the claim with "already recorded".
- **Claimed invoice becomes void:** approving gives 409 `INVOICE_NOT_PAYABLE`; reject the claim.
- **Late fees:** claims don't pause them. A policy flag `pause_late_fee_while_claim_pending` is a reasonable v2 (charge only if no SUBMITTED claim exists with `paid_on ≤ due_date + grace`).

**Jobs:** `paymentClaimSla` (`0 0 10 * * *`). Claims SUBMITTED for more than 24h produce `PAYMENT_CLAIMS_PENDING` digests to managers and admins, deduped per property per day. Add a `pendingPaymentClaims` KPI to the manager/admin dashboard.

**Events:** `BillingEvents.PaymentClaimSubmitted(claimId, ref, orgId, propertyId, tenantId, invoiceNumber, amount)` and `PaymentClaimRejected(…, reason)`.

**Tests**
- Unit: UPI URI encoding (spaces, `&` in payee name), UTR patterns, state machine.
- IT:
  - tenant submits, then manager approves; invoice paid and payment row has `external_reference = utr`
  - two concurrent approvals give one payment (second gets 409)
  - a second SUBMITTED claim on the same invoice gives 409
  - duplicate UTR across invoices gives 409
  - tenant A cannot see or claim tenant B's invoice (404)
  - STAFF approve gives 403; manager on another property gives 403
  - reject notifies the tenant; cancel by tenant
  - pay-link without VPA gives 422

### Spec 3: Electricity / utility meter billing (`com.pgms.utility`)

**Migration `V5__utility_metering.sql`**
```sql
ALTER TABLE invoices DROP CONSTRAINT ck_invoices_type;
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_type CHECK (invoice_type IN ('RENT', 'SECURITY_DEPOSIT', 'UTILITY'));
ALTER TABLE property_policies
    ADD COLUMN utility_invoice_due_days INTEGER NOT NULL DEFAULT 7 CHECK (utility_invoice_due_days BETWEEN 0 AND 30),
    ADD COLUMN meter_reading_day        INTEGER NOT NULL DEFAULT 1 CHECK (meter_reading_day BETWEEN 1 AND 28);

CREATE TABLE utility_meters (
    id                         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id            BIGINT        NOT NULL REFERENCES organizations (id),
    property_id                BIGINT        NOT NULL REFERENCES properties (id),
    room_id                    BIGINT REFERENCES rooms (id),               -- NULL = common-area meter
    meter_type                 VARCHAR(20)   NOT NULL DEFAULT 'ELECTRICITY', -- ELECTRICITY | WATER | GAS
    label                      VARCHAR(60)   NOT NULL,
    serial_number              VARCHAR(60),
    rate_per_unit              NUMERIC(8, 2) NOT NULL,
    fixed_charge               NUMERIC(12, 2) NOT NULL DEFAULT 0,          -- per reading period, per room
    free_units_per_occupant_month NUMERIC(8, 2) NOT NULL DEFAULT 0,        -- "first N units included in rent"
    split_method               VARCHAR(20)   NOT NULL DEFAULT 'OCCUPANT_DAYS', -- OCCUPANT_DAYS | EQUAL | NOT_BILLED
    active                     BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_meters_type CHECK (meter_type IN ('ELECTRICITY', 'WATER', 'GAS')),
    CONSTRAINT ck_meters_split CHECK (split_method IN ('OCCUPANT_DAYS', 'EQUAL', 'NOT_BILLED')),
    CONSTRAINT ck_meters_money CHECK (rate_per_unit >= 0 AND fixed_charge >= 0 AND free_units_per_occupant_month >= 0),
    CONSTRAINT ck_meters_common_not_billed CHECK (room_id IS NOT NULL OR split_method = 'NOT_BILLED')
);
CREATE UNIQUE INDEX uk_meters_room_type ON utility_meters (room_id, meter_type) WHERE active AND room_id IS NOT NULL;
CREATE INDEX idx_meters_property ON utility_meters (property_id) WHERE active;

CREATE TABLE meter_readings (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    meter_id         BIGINT         NOT NULL REFERENCES utility_meters (id),
    reading_date     DATE           NOT NULL,
    reading_value    NUMERIC(12, 2) NOT NULL CHECK (reading_value >= 0),
    previous_reading_id BIGINT REFERENCES meter_readings (id),           -- NULL = baseline
    meter_replaced   BOOLEAN        NOT NULL DEFAULT FALSE,
    units            NUMERIC(12, 2),                                     -- NULL for baseline
    rate_applied     NUMERIC(8, 2),
    amount           NUMERIC(12, 2),                                     -- units*rate + fixed (before free units)
    anomaly          BOOLEAN        NOT NULL DEFAULT FALSE,
    status           VARCHAR(10)    NOT NULL DEFAULT 'RECORDED',         -- RECORDED | BILLED | VOID
    note             VARCHAR(255),
    photo_storage_key VARCHAR(500),
    recorded_by      BIGINT REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_readings_status CHECK (status IN ('RECORDED', 'BILLED', 'VOID'))
);
CREATE UNIQUE INDEX uk_readings_meter_date ON meter_readings (meter_id, reading_date) WHERE status <> 'VOID';
CREATE INDEX idx_readings_meter_date ON meter_readings (meter_id, reading_date DESC);

CREATE TABLE utility_charges (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organizations (id),
    property_id     BIGINT         NOT NULL REFERENCES properties (id),
    reading_id      BIGINT         NOT NULL REFERENCES meter_readings (id),
    allocation_id   BIGINT         NOT NULL REFERENCES allocations (id),
    tenant_id       BIGINT         NOT NULL REFERENCES tenants (id),
    period_start    DATE           NOT NULL,
    period_end      DATE           NOT NULL,                  -- exclusive
    occupant_days   INTEGER        NOT NULL CHECK (occupant_days > 0),
    units_share     NUMERIC(12, 2) NOT NULL,
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    status          VARCHAR(10)    NOT NULL DEFAULT 'PENDING',  -- PENDING | BILLED | WAIVED
    invoice_id      BIGINT REFERENCES invoices (id),
    line_item_id    BIGINT REFERENCES invoice_line_items (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_utility_charges_reading_alloc UNIQUE (reading_id, allocation_id),
    CONSTRAINT ck_utility_charges_status CHECK (status IN ('PENDING', 'BILLED', 'WAIVED')),
    CONSTRAINT ck_utility_charges_billed CHECK ((status = 'BILLED') = (invoice_id IS NOT NULL))
);
CREATE INDEX idx_utility_charges_pending ON utility_charges (allocation_id) WHERE status = 'PENDING';
CREATE INDEX idx_utility_charges_tenant ON utility_charges (tenant_id, period_end DESC);
```

**Entities and enums**
- `UtilityMeter`, `MeterReading`, `UtilityCharge`
- `MeterType`, `SplitMethod`, `ReadingStatus`, `UtilityChargeStatus`
- Add `InvoiceType.UTILITY`
- Pure calculator `UtilitySplitCalculator.split(roomCost, freeUnitsRule, List<OccupancySlice>) → List<Share>`

**Endpoints**

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/api/v1/meters` | SUPER_ADMIN, PROPERTY_ADMIN, MANAGER | `{propertyId, roomId?, meterType, label, serialNumber?, ratePerUnit, fixedCharge, freeUnitsPerOccupantMonth, splitMethod, initialReading, initialReadingDate}`. Creates a baseline reading. 201; 409 `METER_EXISTS` (`uk_meters_room_type`); 404 room not in property. |
| GET | `/api/v1/meters?propertyId&roomId&active` | staff roles (scoped, includes STAFF) | Includes `lastReading{date,value}` and `readingDue` (bool). |
| PUT | `/api/v1/meters/{id}` | admin roles, MANAGER | Rate changes apply to readings recorded afterwards; audit old and new rate. |
| POST | `/api/v1/meters/{id}/deactivate` | admin roles, MANAGER | – |
| POST | `/api/v1/meters/{id}/readings` | admin roles, MANAGER, STAFF | `{readingDate (not future, > last), readingValue, meterReplaced=false, note}`. 201 with `preview: [Share{tenantId, name, occupantDays, unitsShare, amount}]`. 422 `METER_ROLLBACK` (value < previous and not replaced); 422 `READING_DATE_NOT_AFTER_PREVIOUS`; 409 `READING_EXISTS`. |
| POST | `/api/v1/meter-readings/bulk` | same | `{propertyId, readingDate, readings:[{meterId, value, meterReplaced?}]}`. Per-row results; partial success allowed, each row in its own transaction. |
| GET | `/api/v1/meters/{id}/readings` | staff roles | paged |
| POST | `/api/v1/meter-readings/{id}/bill` | admin roles, MANAGER | Creates charges and posts them. 200 `{charges[]}`. 409 `READING_ALREADY_BILLED`. |
| POST | `/api/v1/meter-readings/bill?propertyId&readingDate` | same | Bills every RECORDED reading for that date. |
| POST | `/api/v1/meter-readings/{id}/void` | admin roles, MANAGER | `{reason}`. 409 `READING_ALREADY_BILLED` if any charge is BILLED (waive charges first). |
| GET | `/api/v1/utility-charges?propertyId&tenantId&status&from&to` | staff (scoped), TENANT (own) | Tenant view includes room units, rate and their days for transparency. |
| POST | `/api/v1/utility-charges/{id}/waive` | admin roles, MANAGER | `{reason}`. PENDING becomes WAIVED. If BILLED, adds a DISCOUNT on the invoice (≤ balance). |

**Calculation rules**
- **Period:** `[prev.reading_date, reading_date)`. `units = value − prev.value`, or `value` if `meterReplaced`.
- **Room cost:** `units × rate + fixed_charge`.
- **Occupancy slices:** every allocation (any status) on any bed of the room overlapping the period. The slice is the overlap of `[start_date, coalesce(end_date, ∞))` with the period. A transfer mid-period gives two slices for two allocations, each billed to its own allocation.
- **Free units:** `free = free_units_per_occupant_month × Σdays / 30`. `billableCost = max(0, units − free) × rate + fixed`.
- **Split:**
  - OCCUPANT_DAYS: `share_i = billableCost × days_i / Σdays`.
  - EQUAL: equal across slices.
  - Round HALF_UP to 2 dp and put the rounding remainder on the largest share so the shares sum exactly.
  - Shares of 0 are dropped.
- **Vacant room** (Σdays = 0) or NOT_BILLED: no charges. The cost is borne by the owner and should be recorded as an expense automatically once Spec 5 exists.
- **Anomaly:** set `anomaly = units > 3 × average of the last 3 periods`. It is a warning only.
- **Posting** (each charge, in the billing transaction, with the invoice locked):
  1. Target = the allocation's RENT invoice with `billing_month = first day of month(reading_date)`.
  2. If the target exists, is ISSUED or PARTIALLY_PAID, and `due_date ≥ today`: `addCharge(ELECTRICITY, "Electricity {label} {period}: {units} u", amount)` and mark BILLED.
  3. Else if the target doesn't exist yet, the allocation is ACTIVE, and the month ≥ current month: stay PENDING. `InvoiceService.generateRentInvoice` must attach PENDING charges for that allocation after creating the rent line, in the same transaction.
  4. Else: create one UTILITY invoice per tenant per billing run (`due = today + utility_invoice_due_days`) containing all that tenant's charges from the run, and publish `InvoiceIssued`.
- **Move-out:** inspection (Spec 4) prompts an interim reading for metered rooms. That reading bills everyone in the room up to the move-out date, so the leaver's share lands before settlement.

**Jobs:** `meterReadingReminder` (`0 0 9 * * *`). On `meter_reading_day` per property, and daily for 3 days after while meters lack a reading for the cycle, notify STAFF and MANAGER assigned to the property (`METER_READINGS_DUE`, dedup per property per date).

**Events:** `UtilityEvents.ChargesPosted(tenantId, orgId, propertyId, totalAmount, units, periodStart, periodEnd, invoiceId?)` and `ReadingsDue(propertyId, missingCount)`. A UTILITY invoice also emits the existing `InvoiceIssued`.

**Analytics and other knock-on changes**
- Add `'UTILITY'` to analytics `INVOICE_TYPES`.
- Decide whether utility counts as revenue (recommended: report it as its own "utility recoveries" line, not rent).
- Late fees (Spec 1) may apply to UTILITY invoices.
- Optionally add an `analytics.utility_charges` view.

**Tests**
- Unit `UtilitySplitCalculatorTest`: OCCUPANT_DAYS with mid-period move-in/out, transfer giving two slices, EQUAL, free units exceeding units (giving 0), rounding remainder (100.00 split three ways sums to 100.00), vacant room.
- IT:
  - rollback rejected; meter replaced
  - bill twice gives 409 or no duplicates (`uk_utility_charges_reading_alloc`)
  - attach to open rent invoice
  - PENDING charge picked up by rent generation
  - paid or overdue target creates a UTILITY invoice
  - void blocked after billing
  - tenant sees only own charges
  - STAFF can record but not bill (403)
  - manager on another property gets 403

### Spec 4: Move-out workflow, notice policy and deposit settlement (`com.pgms.moveout`; reuses `booking` and `billing`)

Can ship in two parts: **4a** (policy, tenant notice, preview, withdraw) and **4b** (inspection, completion, deposit refund tracking).

**Migration `V6__move_out_workflow.sql`**
```sql
ALTER TABLE property_policies
    ADD COLUMN notice_period_days     INTEGER     NOT NULL DEFAULT 30 CHECK (notice_period_days BETWEEN 0 AND 90),
    ADD COLUMN lock_in_months         INTEGER     NOT NULL DEFAULT 0  CHECK (lock_in_months BETWEEN 0 AND 24),
    ADD COLUMN notice_shortfall_rule  VARCHAR(20) NOT NULL DEFAULT 'CHARGE_DAYS',   -- NONE | CHARGE_DAYS
    ADD COLUMN lock_in_penalty_rule   VARCHAR(20) NOT NULL DEFAULT 'NONE',          -- NONE | ONE_MONTH_RENT | FLAT
    ADD COLUMN lock_in_penalty_amount NUMERIC(12, 2),
    ADD COLUMN final_month_billing    VARCHAR(10) NOT NULL DEFAULT 'FULL',          -- FULL | PRORATED
    ADD COLUMN deposit_refund_days    INTEGER     NOT NULL DEFAULT 7 CHECK (deposit_refund_days BETWEEN 0 AND 60),
    ADD COLUMN inspection_checklist   TEXT[]      NOT NULL DEFAULT
        '{"Room keys returned","Cupboard key returned","Access card returned","Mattress & bed","Furniture","Walls & paint","Bathroom fittings","Electricals & AC remote"}',
    ADD CONSTRAINT ck_pp_shortfall CHECK (notice_shortfall_rule IN ('NONE', 'CHARGE_DAYS')),
    ADD CONSTRAINT ck_pp_lockin CHECK (lock_in_penalty_rule IN ('NONE', 'ONE_MONTH_RENT', 'FLAT')
        AND (lock_in_penalty_rule <> 'FLAT' OR lock_in_penalty_amount > 0)),
    ADD CONSTRAINT ck_pp_final_month CHECK (final_month_billing IN ('FULL', 'PRORATED'));

ALTER TABLE invoices DROP CONSTRAINT ck_invoices_type;
ALTER TABLE invoices ADD CONSTRAINT ck_invoices_type
    CHECK (invoice_type IN ('RENT', 'SECURITY_DEPOSIT', 'UTILITY', 'SETTLEMENT'));
CREATE UNIQUE INDEX uk_invoices_settlement ON invoices (allocation_id) WHERE invoice_type = 'SETTLEMENT' AND status <> 'VOID';
ALTER TABLE invoice_line_items DROP CONSTRAINT ck_line_items_type;
ALTER TABLE invoice_line_items ADD CONSTRAINT ck_line_items_type CHECK (line_type IN
    ('RENT', 'SECURITY_DEPOSIT', 'ELECTRICITY', 'FOOD', 'LAUNDRY', 'LATE_FEE', 'OTHER', 'DISCOUNT',
     'DAMAGE', 'CLEANING', 'NOTICE_SHORTFALL', 'LOCK_IN_PENALTY'));
ALTER TABLE payments DROP CONSTRAINT ck_payments_method;
ALTER TABLE payments ADD CONSTRAINT ck_payments_method
    CHECK (method IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE', 'DEPOSIT_ADJUSTMENT'));

CREATE SEQUENCE move_out_seq;
CREATE TABLE move_outs (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    reference                VARCHAR(30)    NOT NULL,                 -- MO-202610-000001
    organization_id          BIGINT         NOT NULL REFERENCES organizations (id),
    property_id              BIGINT         NOT NULL REFERENCES properties (id),
    tenant_id                BIGINT         NOT NULL REFERENCES tenants (id),
    allocation_id            BIGINT         NOT NULL REFERENCES allocations (id),
    requested_by_user_id     BIGINT         NOT NULL REFERENCES users (id),
    notice_date              DATE           NOT NULL,
    expected_move_out_date   DATE           NOT NULL,
    reason                   VARCHAR(20)    NOT NULL,     -- JOB_CHANGE | RELOCATION | DISSATISFIED | PRICE | PERSONAL | EVICTION | OTHER
    reason_text              VARCHAR(500),
    shortfall_days           INTEGER        NOT NULL DEFAULT 0,
    shortfall_charge         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    lock_in_penalty          NUMERIC(12, 2) NOT NULL DEFAULT 0,
    refund_upi_vpa           VARCHAR(100),
    status                   VARCHAR(20)    NOT NULL DEFAULT 'NOTICE_GIVEN', -- NOTICE_GIVEN | INSPECTED | SETTLED | CLOSED | WITHDRAWN
    inspected_by             BIGINT REFERENCES users (id),
    inspected_at             TIMESTAMPTZ,
    inspection_notes         VARCHAR(2000),
    actual_move_out_date     DATE,
    settlement_invoice_id    BIGINT REFERENCES invoices (id),
    deposit_held             NUMERIC(12, 2),
    dues_adjusted            NUMERIC(12, 2),
    total_deductions         NUMERIC(12, 2),
    refund_due               NUMERIC(12, 2),
    payable_by_tenant        NUMERIC(12, 2),
    refund_status            VARCHAR(20),                 -- NOT_APPLICABLE | PENDING | PAID
    refund_due_by            DATE,
    refund_id                BIGINT REFERENCES refunds (id),
    settled_at               TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_move_outs_reference UNIQUE (reference),
    CONSTRAINT ck_move_outs_status CHECK (status IN ('NOTICE_GIVEN', 'INSPECTED', 'SETTLED', 'CLOSED', 'WITHDRAWN')),
    CONSTRAINT ck_move_outs_refund_status CHECK (refund_status IS NULL OR refund_status IN ('NOT_APPLICABLE', 'PENDING', 'PAID')),
    CONSTRAINT ck_move_outs_dates CHECK (expected_move_out_date >= notice_date)
);
CREATE UNIQUE INDEX uk_move_outs_open_allocation ON move_outs (allocation_id) WHERE status IN ('NOTICE_GIVEN', 'INSPECTED');
CREATE INDEX idx_move_outs_property_status ON move_outs (property_id, status, expected_move_out_date);
CREATE INDEX idx_move_outs_refund_due ON move_outs (refund_due_by) WHERE refund_status = 'PENDING';

CREATE TABLE move_out_inspection_items (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    move_out_id      BIGINT         NOT NULL REFERENCES move_outs (id) ON DELETE CASCADE,
    item             VARCHAR(100)   NOT NULL,
    item_condition   VARCHAR(20)    NOT NULL,             -- OK | NEEDS_CLEANING | DAMAGED | MISSING
    deduction_type   VARCHAR(20),                         -- DAMAGE | CLEANING | OTHER
    deduction_amount NUMERIC(12, 2) NOT NULL DEFAULT 0 CHECK (deduction_amount >= 0),
    note             VARCHAR(500),
    photo_storage_key VARCHAR(500),
    CONSTRAINT ck_inspection_condition CHECK (item_condition IN ('OK', 'NEEDS_CLEANING', 'DAMAGED', 'MISSING')),
    CONSTRAINT ck_inspection_deduction CHECK (deduction_amount = 0 OR deduction_type IS NOT NULL)
);
CREATE INDEX idx_inspection_items_move_out ON move_out_inspection_items (move_out_id);
```

**Entities and enums**
- `MoveOut`, with state methods `inspect`, `settle`, `close`, `withdraw`. Each throws 409 `MOVE_OUT_INVALID_STATE`.
- `MoveOutInspectionItem`
- `MoveOutStatus`, `MoveOutReason`, `ItemCondition`, `RefundStatus`
- Add `InvoiceType.SETTLEMENT`; `LineItemType` additions; `PaymentMethod.DEPOSIT_ADJUSTMENT`. DEPOSIT_ADJUSTMENT is not allowed in `RecordPaymentRequest`; reject with 400.
- Pure `MoveOutCalculator`.

**Calculation rules**
- `dailyRent = monthly_rent / 30` (HALF_UP to 2 dp).
- `earliestNoPenaltyDate = notice_date + notice_period_days`.
- `shortfall_days = max(0, earliestNoPenaltyDate − expected_move_out_date)`.
- Shortfall charge: `shortfall_days × dailyRent` if the rule is CHARGE_DAYS, else 0.
- Lock-in: if `expected_move_out_date < allocation.start_date + lock_in_months`, the penalty is one month's rent (ONE_MONTH_RENT) or the flat amount (FLAT).
- **Transfers:** for the lock-in check, use the first allocation's start date in the `previous_allocation_id` chain.

**Endpoints**

| Method | Path | Roles | Request / response / codes |
|---|---|---|---|
| GET | `/api/v1/move-outs/preview?allocationId&moveOutDate` | TENANT (own allocation; `allocationId` optional), admin roles, MANAGER | `{noticePeriodDays, earliestNoPenaltyDate, shortfallDays, shortfallCharge, lockInEndDate, lockInPenalty, depositHeld, outstandingDues, estimatedRefund}` |
| POST | `/api/v1/move-outs` | TENANT (own active allocation), admin roles, MANAGER | `{allocationId (staff only), expectedMoveOutDate (≥ today), reason, reasonText, refundUpiVpa?}`. 201. Calls the existing allocation notice logic (`allocation.recordNotice`, `tenant.giveNotice`) in the same transaction. 409 `MOVE_OUT_ALREADY_OPEN`; 422 `NOT_A_RESIDENT`; 409 `ALLOCATION_NOT_ACTIVE`. Audit `MOVE_OUT_REQUESTED`. |
| GET | `/api/v1/move-outs?propertyId&status&from&to` | staff (scoped), TENANT (own) | paged; sort `expectedMoveOutDate`, `createdAt` |
| GET | `/api/v1/move-outs/{id}` | same | includes inspection items and settlement |
| PUT | `/api/v1/move-outs/{id}/date` | admin roles, MANAGER | `{expectedMoveOutDate, waiveShortfall: bool, reason}`. Recomputes shortfall; audit. |
| POST | `/api/v1/move-outs/{id}/withdraw` | TENANT (own), admin roles, MANAGER | Only from NOTICE_GIVEN; clears the allocation notice (`withdrawNotice`). |
| PUT | `/api/v1/move-outs/{id}/inspection` | admin roles, MANAGER, STAFF | `{items:[{item, condition, deductionType?, deductionAmount, note}] ≤50, notes}`. Status becomes INSPECTED; replaces items while NOT SETTLED. Prompts for interim meter reading (Spec 3) if the room is metered. |
| POST | `/api/v1/move-outs/{id}/complete` | admin roles, MANAGER | `{actualMoveOutDate, sendBedToMaintenance, extraDeductions:[{type, description, amount}] ≤20}`. 200 `MoveOutResponse` with settlement. 409 if not INSPECTED (or NOTICE_GIVEN with `skipInspection=true` for admins only). |
| GET | `/api/v1/move-outs/{id}/statement` | TENANT (own), staff | PDF (OpenPDF, like `ReceiptService`) |
| POST | `/api/v1/move-outs/{id}/refund` | SUPER_ADMIN, PROPERTY_ADMIN (matches existing refund role) | Header `Idempotency-Key` required. `{amount (= refundDue default; ≤ refundDue), method UPI/BANK_TRANSFER/CASH/CHEQUE, payoutReference}`. Uses the existing `RefundService` on the deposit payment(s) and sets refund_status PAID. Status becomes CLOSED when nothing is payable by the tenant. |
| POST | `/api/v1/move-outs/{id}/close` | admin roles | Closes a case with dues still payable after collection or write-off. Write-off = DISCOUNT "Written off" on open invoices; audit `DUES_WRITTEN_OFF`. |

**`complete`: one transaction.** Lock order: tenant → allocation → bed → invoices (ascending id) → deposit payments.
1. Run the existing `AllocationService.checkOut` internals (bed vacate/maintenance, allocation end, tenant MOVED_OUT, `TenantCheckedOut` event). Refactor it into a package method that `complete` calls with an empty deductions list. Keep `POST /allocations/{id}/check-out` as-is for backward compatibility; also have it create a SETTLED `move_outs` row so reports stay consistent.
2. If the policy is PRORATED and the final month's RENT invoice extends beyond `actualMoveOutDate`: unusedCredit = dailyRent × unused days.
   - If the invoice is unpaid or partially paid, apply a DISCOUNT up to its balance.
   - Add any remainder to `refund_due`; it is refunded from that rent payment at refund time.
3. Create a SETTLEMENT invoice (due today) with these lines:
   - NOTICE_SHORTFALL
   - LOCK_IN_PENALTY
   - DAMAGE/CLEANING/OTHER from inspection and extra deductions

   Skip it if the total is 0.
4. **Apply the deposit to the oldest open invoices first** (settlement invoice included) until either the deposit or the dues are exhausted. For each slice, two writes:
   - `Payment(method=DEPOSIT_ADJUSTMENT, idempotencyKey="mo:{id}:inv:{invoiceId}")` on the due invoice
   - a non-cash `Refund` on the deposit payment (reason "Adjusted against {invoiceNumber}", key `"mo:{id}:adj:{invoiceId}"`)

   This keeps `depositHeldForTenant` and the outstanding figures consistent with the existing queries.
5. Save the settlement figures:
   - `refund_due` = remaining deposit + credit
   - `payable_by_tenant` = remaining dues
   - `refund_status` = PENDING if `refund_due > 0`, else NOT_APPLICABLE
   - `refund_due_by` = today + `deposit_refund_days`
   - status SETTLED
6. Audit `MOVE_OUT_SETTLED`; publish `SettlementFinalized`.

**Edge cases**
- A tenant with an unpaid deposit invoice holds a deposit of 0, so everything is payable.
- Transfer while on notice: an open move-out follows the new allocation. Update `allocation_id` in the transfer, or block transfer with 409 `MOVE_OUT_OPEN`.
- Late fees must not accrue on the SETTLEMENT invoice. Exclude it in Spec 1.
- Utility charges after settlement land as a UTILITY invoice (tenant owes it). Flag it to the manager.
- Eviction: reason EVICTION, created by an admin, shortfall waived.
- Concurrent `complete` calls: the row lock on the move-out plus the state check gives 409 to the second.

**Jobs**
- `moveOutReminders` (`0 0 9 * * *`): T−3 days before `expected_move_out_date`, notify the tenant (prepare for inspection, share refund UPI) and managers (schedule inspection). Dedup `MOVE_OUT_REMINDER:{id}`.
- `overdueMoveOuts`: expected date passed and still NOTICE_GIVEN/INSPECTED gives a digest to managers.
- `depositRefundOverdue` (`0 30 9 * * *`): `refund_status = PENDING AND refund_due_by < today` gives `DEPOSIT_REFUND_OVERDUE` to admins, deduped per case per day.

**Events:** `MoveOutEvents.MoveOutRequested`, `MoveOutWithdrawn`, `MoveOutReminder`, `SettlementFinalized(moveOutId, tenantId, refundDue, payableByTenant)`, `DepositRefundOverdue`. The refund payout reuses `BillingEvents.RefundIssued`.

**Analytics:** add `'SETTLEMENT'` to `INVOICE_TYPES`. Revenue should include DEPOSIT_ADJUSTMENT payments on RENT invoices (it is rent collected). Cash-by-method charts should label it "Adjusted from deposit". Deposit refunds that are adjustments must not count as cash refunds: filter on refunds whose matching adjustment payment exists, or add a `refunds.kind` column (CASH | ADJUSTMENT). Adding that column in this migration is recommended.

**Tests**
- Unit `MoveOutCalculatorTest`: shortfall (30-day policy, 10 days' notice gives 20 days), zero-notice policy, lock-in via transfer chain, prorated credit, deposit waterfall (deposit 20,000, dues 5,000, deductions 1,500 gives refund 13,500; deposit 3,000, dues 5,000 gives payable 2,000).
- IT:
  - tenant creates, then a second open case gives 409
  - withdraw clears the allocation notice
  - inspection by STAFF OK; complete by STAFF gives 403
  - complete posts the correct payments and refunds, and `depositHeldForTenant` = refund_due afterwards
  - complete twice gives one settlement (`uk_invoices_settlement`)
  - refund idempotent (same key returns 200 replay)
  - refund > refund_due gives 422 `REFUND_EXCEEDS_DUE`
  - legacy check-out still works and creates a SETTLED row
  - another tenant's case gives 404
  - deposit refund overdue job notifies once per day

### Spec 5: Expenses and per-property P&L (`com.pgms.expense` + analytics `/analytics/pnl`)

**Migration `V7__expenses.sql`**
```sql
ALTER TABLE property_policies ADD COLUMN expense_approval_threshold NUMERIC(12, 2);   -- NULL = no approval needed

CREATE TABLE expense_categories (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id BIGINT REFERENCES organizations (id),     -- NULL = system default
    code            VARCHAR(40)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    cost_class      VARCHAR(10)  NOT NULL DEFAULT 'OPEX',     -- OPEX | CAPEX
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_expense_categories_class CHECK (cost_class IN ('OPEX', 'CAPEX'))
);
CREATE UNIQUE INDEX uk_expense_categories_code ON expense_categories (coalesce(organization_id, 0), code);
INSERT INTO expense_categories (code, name, cost_class) VALUES
 ('BUILDING_LEASE','Building lease / rent to landlord','OPEX'), ('ELECTRICITY','Electricity (EB) bill','OPEX'),
 ('WATER','Water / tanker','OPEX'), ('GAS','LPG / gas','OPEX'), ('GROCERIES','Groceries & food supplies','OPEX'),
 ('SALARIES','Staff salaries','OPEX'), ('REPAIRS','Repairs & maintenance','OPEX'), ('HOUSEKEEPING','Housekeeping supplies','OPEX'),
 ('INTERNET','Internet / Wi-Fi','OPEX'), ('LAUNDRY','Laundry','OPEX'), ('TAXES_FEES','Property tax, licences & fees','OPEX'),
 ('MARKETING','Marketing & brokerage','OPEX'), ('FURNITURE','Furniture & appliances','CAPEX'), ('OTHER','Other','OPEX');

CREATE TABLE recurring_expenses (
    id              BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id BIGINT         NOT NULL REFERENCES organizations (id),
    property_id     BIGINT         NOT NULL REFERENCES properties (id),
    category_id     BIGINT         NOT NULL REFERENCES expense_categories (id),
    amount          NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    day_of_month    INTEGER        NOT NULL CHECK (day_of_month BETWEEN 1 AND 28),
    vendor_name     VARCHAR(150),
    description     VARCHAR(255)   NOT NULL,
    start_month     DATE           NOT NULL,
    end_month       DATE,
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE expenses (
    id                     BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    organization_id        BIGINT         NOT NULL REFERENCES organizations (id),
    property_id            BIGINT         NOT NULL REFERENCES properties (id),
    category_id            BIGINT         NOT NULL REFERENCES expense_categories (id),
    expense_date           DATE           NOT NULL,
    amount                 NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    payment_method         VARCHAR(20)    NOT NULL,
    vendor_name            VARCHAR(150),
    reference              VARCHAR(100),
    description            VARCHAR(500)   NOT NULL,
    receipt_storage_key    VARCHAR(500),
    maintenance_request_id BIGINT REFERENCES maintenance_requests (id),
    recurring_expense_id   BIGINT REFERENCES recurring_expenses (id),
    status                 VARCHAR(20)    NOT NULL,
    reviewed_by            BIGINT REFERENCES users (id),
    reviewed_at            TIMESTAMPTZ,
    review_note            VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by BIGINT, updated_by BIGINT, version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_expenses_method CHECK (payment_method IN ('CASH', 'UPI', 'CARD', 'BANK_TRANSFER', 'CHEQUE')),
    CONSTRAINT ck_expenses_status CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'VOID'))
);
CREATE UNIQUE INDEX uk_expenses_recurring_date ON expenses (recurring_expense_id, expense_date)
    WHERE recurring_expense_id IS NOT NULL AND status <> 'VOID';
CREATE INDEX idx_expenses_property_date ON expenses (property_id, expense_date DESC);
CREATE INDEX idx_expenses_pending ON expenses (organization_id) WHERE status = 'PENDING_APPROVAL';

CREATE OR REPLACE VIEW analytics.expenses AS          -- no vendor/description (may contain PII)
SELECT e.id, e.organization_id, e.property_id, c.code AS category_code, c.name AS category_name, c.cost_class,
       e.expense_date, e.amount, e.payment_method, e.status
FROM expenses e JOIN expense_categories c ON c.id = e.category_id;
GRANT SELECT ON analytics.expenses TO analytics_ro;   -- wrap in the same IF EXISTS DO-block as V2
```

**Entities and enums:** `ExpenseCategory`, `Expense`, `RecurringExpense`, `ExpenseStatus`, `CostClass`.

**Endpoints**

| Method | Path | Roles | Notes |
|---|---|---|---|
| POST | `/api/v1/expenses` | SUPER_ADMIN, PROPERTY_ADMIN, MANAGER | `{propertyId, categoryId, expenseDate (≤ today, ≥ today−365), amount, paymentMethod, vendorName?, reference?, description, maintenanceRequestId?}`. Status APPROVED, or PENDING_APPROVAL if created by a MANAGER and amount > threshold. 201. 404 category not visible to org; 422 `MAINTENANCE_PROPERTY_MISMATCH`. |
| GET | `/api/v1/expenses?propertyId&categoryId&status&from&to&q` | admin roles, MANAGER (scoped) | Sort `expenseDate`, `amount`, `createdAt`. Response includes `totalAmount` for the filter. |
| GET / PUT | `/api/v1/expenses/{id}` | same | Edit allowed unless VOID/REJECTED. A manager editing an APPROVED expense over threshold returns it to PENDING_APPROVAL. Audit `EXPENSE_UPDATED` with diff. |
| POST | `/api/v1/expenses/{id}/approve`, `/reject` | SUPER_ADMIN, PROPERTY_ADMIN | `{note}` (required for reject). 409 `EXPENSE_NOT_PENDING`. |
| POST | `/api/v1/expenses/{id}/void` | SUPER_ADMIN, PROPERTY_ADMIN | `{reason}` |
| GET | `/api/v1/expenses/summary?propertyId&month=YYYY-MM` | admin roles, MANAGER | `{month, total, byCategory[{code,name,amount}], pendingApprovalCount}`. Backend SQL, for the dashboard card. |
| GET / POST / PUT | `/api/v1/expense-categories` | read: staff; write: PROPERTY_ADMIN | Org-custom categories; system ones are read-only (403 `SYSTEM_CATEGORY`). |
| CRUD | `/api/v1/recurring-expenses` | PROPERTY_ADMIN, MANAGER (scoped) | Deactivation stops future runs only. |
| GET | `/analytics/pnl?propertyId&from&to&basis=cash\|accrual&format=json\|csv` | SUPER_ADMIN, PROPERTY_ADMIN, MANAGER | Per month and property: income (rent, utility, settlement/other charges, less discounts), expenses by category (APPROVED, OPEX), NOI, margin %, revenue per occupied bed, cost per bed, plus CAPEX separately. Cash basis = payments net of cash refunds, excluding SECURITY_DEPOSIT. Accrual basis = non-void invoice totals by `billing_month` / `issue_date`. |

**Rules**
- P&L counts APPROVED only.
- Deposits are never income.
- DEPOSIT_ADJUSTMENT counts as income on accrual and on cash (cash received earlier as deposit is recognised now). Document this in the analytics README.
- Vacant-room meter cost from Spec 3 is auto-created as an `ELECTRICITY` expense when expenses exist (idempotent by reading id: add `source_reading_id` + unique, optional).

**Jobs**
- `recurringExpenses` (`0 0 2 * * *`): for each active template whose `day_of_month == today.day` (or missed days this month), create an APPROVED expense. Idempotent via `uk_expenses_recurring_date`.
- `expenseApprovalDigest` (`0 0 18 * * *`): pending items go to admins.

**Events:** `ExpenseEvents.ExpensePendingApproval(expenseId, propertyId, amount, submittedBy)` and `ExpenseReviewed(expenseId, approved, submitterUserId)`.

**Tests**
- Backend: threshold routing, manager on unassigned property gets 403, cross-org category 404, recurring job run twice gives one row, void excluded from summary, edit returns to pending.
- Analytics (pure DataFrame tests): cash vs accrual, deposit exclusion, adjustment refunds not counted as cash out, CAPEX separated, empty months, scope filtering, CSV export.
- View contract test: no vendor or description columns.

### Short specs for two quick wins
- **Forgot password / OTP (P0 #6, `com.pgms.auth`)**
  - Table `password_reset_tokens(id, user_id FK, channel EMAIL|SMS, code_hash VARCHAR(64), expires_at, attempts INT DEFAULT 0, consumed_at, created_at, created_by_ip)`, index on `(user_id, created_at DESC)`.
  - `POST /auth/password/forgot {identifier: email|phone}` always returns 202, whether or not the account exists (no enumeration). Rate limited per IP and per identifier (existing Redis limiter). Generates a 6-digit OTP, stored as SHA-256, TTL 10 min, max 5 attempts.
  - `POST /auth/password/reset {identifier, code, newPassword}` returns 204. It revokes all refresh tokens and sets invalid-before (reuse the logic of `logout-all`). 400 `INVALID_OR_EXPIRED_CODE`.
  - Change portal-account creation to send a "set your password" OTP or link instead of a staff-chosen `initialPassword` (keep the old field optional).
  - These notifications are mandatory and bypass preferences. The tenant's phone number is needed for SMS, so match on `users.phone`, falling back to `tenants.phone` via `tenants.user_id`.
- **Announcements (P1 #10, `com.pgms.announcement`)**
  - `announcements(id, organization_id, property_id NULL=all org properties, title ≤150, body TEXT, category GENERAL|MAINTENANCE_OUTAGE|RULES|EVENT|FOOD, urgent BOOL, send_email BOOL, send_sms BOOL, publish_at, expires_at, status DRAFT|PUBLISHED|ARCHIVED, audit cols)`.
  - Endpoints: `POST/GET /api/v1/announcements` (staff create: PROPERTY_ADMIN, MANAGER); `POST /{id}/publish`; tenants get `GET /api/v1/announcements?active=true` scoped to their property.
  - Publishing fans out to ACTIVE/ON_NOTICE tenants of the property. Use batches in the job, not inline (500 tenants × 3 channels is a lot of outbox rows); dedup `ANN:{id}`.
  - `send_sms` is allowed only when `urgent`, to control cost.

## 3. Notifications: preferences model and matrix

**Preference model**
- **`notification_preferences(id, user_id FK NOT NULL, notification_type VARCHAR(40), channel VARCHAR(10), enabled BOOL, updated_at)`**, `UNIQUE (user_id, notification_type, channel)`. Tenants without a login use defaults.
- **`organization_notification_settings(organization_id, notification_type, channel, enabled, PRIMARY KEY(...))`.** Lets an admin turn off SMS for a type, for cost.
- **New user columns:**
  - `users.preferred_language VARCHAR(5) DEFAULT 'en'`
  - `users.phone_verified_at`
  - `users.sms_opt_out_at` (also set from DLT STOP replies)
  - `users.email_bounced_at` (set by SMTP bounce handling; suppresses EMAIL)
  - For tenants without logins, the same opt-out and bounce columns on `tenants`.
- **Resolution, in `NotificationService.store` before inserting EMAIL/SMS rows:**
  1. Mandatory type → always send.
  2. Org setting off → skip.
  3. User preference row → use it.
  4. Otherwise the matrix default.

  Also skip the channel if there's no address or it has bounced or opted out.
- **Quiet hours:** non-urgent SMS between 21:00 and 08:00 IST gets `next_attempt_at` = 08:00 (dispatcher change).
- **DLT for India:** every SMS template needs a registered header (sender ID) and template ID. Map `NotificationType → {dltTemplateId, smsTemplate}` in config (`app.notifications.sms.templates.*`). SMS text must use fixed template text with variables, not the free-form `body`. Keep SMS ≤160 GSM chars when possible.
- **Endpoints:**
  - `GET /api/v1/notifications/preferences` returns `[{type, channel, enabled, mandatory, orgDisabled}]` for the caller's role-relevant types.
  - `PUT /api/v1/notifications/preferences {items:[{type, channel, enabled}]}` returns 200, or 422 `PREFERENCE_MANDATORY`.
  - `GET/PUT /api/v1/organizations/{id}/notification-settings` (PROPERTY_ADMIN, SUPER_ADMIN).
- `NotificationType` gains new values; `notifications.type` is VARCHAR(40) with no CHECK, so no migration is needed for that.

**Matrix** (✓ = default on, ○ = available but off, – = not offered, M = mandatory)

| Event (type) | Recipients | In-app | Email | SMS | Template sketch |
|---|---|---|---|---|---|
| BOOKING_CONFIRMED | Tenant | ✓ | ✓ | ✓ | "{PG}: Bed {room}/{bed} reserved (ref {ref}). Check-in {date}. Held till {dt}." |
| BOOKING_RELEASED | Tenant | ✓ | ✓ | ✓ | "{PG}: Your reservation {ref} has {expired/been cancelled}. Call {phone} to rebook." |
| MOVE_IN (welcome) | Tenant / Managers | ✓ / ✓ | ✓ (house rules, invoices, portal link) / ○ | ✓ (portal link) / – | "Welcome to {PG}, {name}! Room {room}. Rent ₹{rent} due on {day}th. Portal: {link}" |
| ROOM_TRANSFER | Tenant | ✓ | ✓ | ○ | "Moved to {room}/{bed} from {date}. New rent ₹{rent}." |
| INVOICE_ISSUED (rent/utility/settlement) | Tenant | ✓ | ✓ (with pay link) | ✓ (rent & settlement only) | "{PG}: {month} rent ₹{amt} due {date}. Pay: {upiLink}" |
| PAYMENT_DUE (T−N) | Tenant | ✓ | ✓ | ✓ | "Reminder: ₹{bal} due on {date} for {inv}. Pay: {link}" |
| PAYMENT_OVERDUE (D+offsets) | Tenant | ✓ | ✓ | ✓ | "₹{bal} overdue by {n} days on {inv}. {Late fee ₹x applies from {date}.} Pay: {link}" |
| LATE_FEE_APPLIED | Tenant | ✓ | ✓ | ✓ | "Late fee ₹{fee} added to {inv}. New balance ₹{bal}." |
| LATE_FEE_WAIVED | Tenant | ✓ | ✓ | ○ | "₹{amt} late fee waived on {inv}." |
| OVERDUE_DIGEST (daily) | Managers (admins weekly) | ✓ | ✓ | – | "{property}: {n} overdue, ₹{total}. Top: {list}" |
| PAYMENT_RECEIVED | Tenant | M | ✓ (receipt PDF link) | ✓ | "Received ₹{amt} for {inv}, receipt {rcpt}. Balance ₹{bal}. – {PG}" |
| PAYMENT_CLAIM_SUBMITTED | Managers of property | ✓ | ○ | – | "{name} reports ₹{amt} paid via {method} (UTR {utr}) for {inv}. Verify." |
| PAYMENT_CLAIMS_PENDING (>24h digest) | Managers + Admins | ✓ | ✓ | – | "{n} payment claims waiting >24h at {property}." |
| PAYMENT_CLAIM_REJECTED | Tenant | ✓ | ✓ | ✓ | "Your payment claim ₹{amt} (UTR {utr}) was not verified: {reason}." |
| REFUND_ISSUED / deposit refund paid | Tenant | M | ✓ | ✓ | "Refund ₹{amt} sent via {method}, ref {ref}." |
| UTILITY_CHARGES_POSTED | Tenant | ✓ | ✓ (units, rate, days breakdown) | ○ | "Electricity {period}: {units}u, your share ₹{amt} added to {inv}." |
| METER_READINGS_DUE | Staff + Managers | ✓ | – | ○ | "{n} meters at {property} need readings today." |
| MOVE_OUT_REQUESTED | Tenant (ack) / Managers | ✓ / ✓ | ✓ / ✓ | ✓ / – | Tenant: "Notice received. Move-out {date}. Notice shortfall: {n} days (₹{x})." Mgr: "{name} ({room}) gave notice for {date}." |
| MOVE_OUT_WITHDRAWN | Managers | ✓ | ○ | – | "{name} withdrew notice." |
| MOVE_OUT_REMINDER (T−3) | Tenant / Managers | ✓ / ✓ | ✓ / ○ | ✓ / – | "Move-out on {date}. Please schedule inspection & share refund UPI." |
| SETTLEMENT_FINALIZED | Tenant | M | ✓ (statement PDF) | ✓ | "Final settlement: deposit ₹{d}, deductions ₹{x}, refund ₹{r} by {date} / payable ₹{p}." |
| DEPOSIT_REFUND_OVERDUE | Admins | ✓ | ✓ | ○ | "Deposit refund ₹{r} for {name} overdue since {date}." |
| MAINTENANCE created | Managers | ✓ | ○ | – | "New {priority} ticket {no}: {title} ({room})" |
| MAINTENANCE assigned | Assignee (staff) | ✓ | ○ | ✓ for HIGH/CRITICAL, ○ otherwise | "Assigned {no}: {title}, {room}. SLA {due}." |
| MAINTENANCE status change | Tenant/raiser | ✓ | ○ (✓ on RESOLVED) | ○ | "Ticket {no} is now {status}." |
| MAINTENANCE_SLA_BREACH | Managers + Admins | ✓ | ✓ | ✓ only CRITICAL | "SLA breached: {no} ({priority})." |
| COMPLAINT_UPDATE | Tenant / Assignee | ✓ / ✓ | ✓ on RESOLVED / ○ | ○ | "Complaint {no} is {status}." |
| COMPLAINT_ESCALATED | Managers + Admins | ✓ | ✓ | ✓ at level ≥2 | "Complaint {no} escalated to L{n}." |
| EXPENSE_PENDING_APPROVAL | Admins | ✓ | ✓ (evening digest) | – | "{n} expenses (₹{total}) await approval." |
| EXPENSE_REVIEWED | Submitter | ✓ | ○ | – | "Expense ₹{amt} {approved/rejected}: {note}" |
| ANNOUNCEMENT_PUBLISHED | Tenants of property | ✓ | per announcement (`send_email`) | only if `urgent && send_sms` | "{PG}: {title}. {short body}" |
| PORTAL_ACCOUNT_CREATED | Tenant | – | M | M | "Your {PG} portal is ready. Set password: {link/OTP}" |
| PASSWORD_RESET_OTP | User | – | M (if email identifier) | M (if phone) | "{OTP} is your {PG} password reset code. Valid 10 min. Don't share." |
| PASSWORD_CHANGED / LOGOUT_ALL | User | ✓ | M | ○ | "Your password was changed at {time}. Not you? Contact {phone}." |

## 4. Frontend screens the new features need

**Admin / owner (PROPERTY_ADMIN)**
- **Property → Policies tab:** late fees, reminder offsets, UPI VPA/payee, notice/lock-in/final-month/refund days, inspection checklist editor, expense approval threshold, meter reading day.
- **Organization → Notification settings:** type × channel toggle grid, SMS cost hint.
- **Expenses approval queue;** recurring expenses manager; expense categories.
- **P&L report:** property and month range, cash/accrual toggle, category breakdown chart, per-bed metrics, CSV download.
- **Deposit refunds due list:** PENDING with due-by and overdue highlighting; "Pay refund" dialog with idempotency key.

**Manager**
- **Payment claims queue:** filter by property/status; card per claim (tenant, invoice, amount, UTR, paid on, note); approve (editable amount) / reject (reason).
- **Invoice detail additions:** late-fee history panel, "Waive late fee" dialog, "Share pay link" (copy UPI link / show QR).
- **Overdue / defaulters list:** from invoices `status=OVERDUE`, with days overdue, late fees, last reminder, one-tap "Send reminder".
- **Meters:** list per property (room, last reading, reading-due badge), create/edit meter, reading history with anomaly flags.
- **Monthly reading round:** mobile-first bulk entry grid (room → previous value → new value), live units/amount preview, then "Bill all".
- **Utility charges list:** waive action.
- **Move-outs board:** columns NOTICE_GIVEN / INSPECTED / SETTLED / CLOSED; expected-date sorting.
- **Move-out detail:** timeline; adjust date / waive shortfall; inspection results; complete-settlement wizard (review dues → deductions → deposit waterfall preview → confirm); statement PDF.
- **Expenses:** list with filters and totals; add-expense form (mobile; link to maintenance ticket).
- **Announcements:** list, compose (property, category, urgent, email/SMS toggles), publish.
- **Dashboard cards:** pending payment claims, overdue digest, upcoming move-outs (7 days), readings due, month expenses vs revenue.

**Staff**
- **Meter reading entry:** mobile, room-by-room with a numeric keypad.
- **Move-out inspection checklist:** per item condition chips (OK / Cleaning / Damaged / Missing), deduction amount, note; photo later once storage exists.
- **My tasks:** readings due and inspections scheduled.

**Tenant (portal, mobile-first)**
- **Home:** dues card with "Pay via UPI" (deep link on mobile, QR on desktop) and an "I've paid" button.
- **"I've paid" form:** amount, method, UTR (12-digit hint for UPI), date, note; then a claim status list (Submitted / Verified / Rejected with reason).
- **Invoice detail:** line breakdown including late fees and an electricity breakdown (units, rate, my days, share).
- **Electricity history:** per period.
- **Give notice:** date picker showing live preview (notice period, earliest no-penalty date, shortfall charge, lock-in penalty, estimated refund), reason, refund UPI ID, then confirm. Withdraw while pending.
- **Move-out status:** timeline, inspection results, final settlement statement (PDF download), refund status.
- **Announcements / notice board.**
- **Settings → Notification preferences:** type × channel toggles; mandatory ones shown locked.
- **Language selector** (future).

**Auth (all roles)**
- Forgot password → enter email/phone → enter OTP → new password.
- Set-password screen for newly created portal accounts.

## Key files
- `/Users/abhinyaay/Desktop/CRM/PG/backend/src/main/resources/db/migration/V1__core_schema.sql` (CHECK constraints to alter: `ck_invoices_type`, `ck_line_items_type`, `ck_payments_method`)
- `/Users/abhinyaay/Desktop/CRM/PG/backend/src/main/java/com/pgms/notification/NotificationService.java` (`store()` is where preference resolution goes; `notifyTenant` has no SMS path)
- `/Users/abhinyaay/Desktop/CRM/PG/backend/src/main/java/com/pgms/notification/NotificationEventHandler.java`
- `/Users/abhinyaay/Desktop/CRM/PG/backend/src/main/java/com/pgms/billing/BillingJobs.java`, `InvoiceService.java` (settlement, `generateRentInvoice`), `InvoiceFactory.java`, `Invoice.java`
- `/Users/abhinyaay/Desktop/CRM/PG/backend/src/main/java/com/pgms/booking/AllocationService.java` (`checkOut`, `giveNotice`), `AllocationController.java` (notice restricted to operator roles)
- `/Users/abhinyaay/Desktop/CRM/PG/backend/src/main/java/com/pgms/common/exception/ConstraintViolationTranslator.java`, `common/util/ReferenceNumberGenerator.java`
- `/Users/abhinyaay/Desktop/CRM/PG/analytics-service/app/services/payments.py` (line 33, hard-coded `INVOICE_TYPES`), `app/repositories/finance.py`, `app/services/revenue.py` (RENT-only revenue)