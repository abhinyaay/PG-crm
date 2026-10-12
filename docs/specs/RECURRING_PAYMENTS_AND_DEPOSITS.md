# Spec: Recurring payments and the owner's Deposits tab

## 1. Recurring charges (fixed monthly add-ons)

Package `com.pgms.billing`, part of migration `V5`.

* `recurring_charges`: id, organization_id, property_id, `tenant_id`, `allocation_id` (null = follows the tenant
  across transfers), `line_type` (`FOOD`, `LAUNDRY`, `OTHER`, plus new `PARKING`, `INTERNET`), description, `amount`
  (> 0), `start_month` (first day of month), `end_month` null, `active`, audit cols, version.
* The monthly rent generation (`InvoiceService.generateRentInvoice`) and the move-in invoice add one line per active
  recurring charge whose month range covers the billed month (not prorated by default; a `prorate_first_month` flag
  may prorate on move-in). Charges are snapshotted as invoice lines, so editing a recurring charge never changes
  already-issued invoices.
* API: `GET/POST /api/v1/tenants/{id}/recurring-charges`, `PUT/DELETE /api/v1/recurring-charges/{id}`
  (MANAGER, PROPERTY_ADMIN; tenant can read own).

## 2. Recurring payments (AutoPay)

Lets a resident authorise automatic monthly rent collection (UPI AutoPay / e-NACH / card) once.

* Gateway abstraction `RecurringPaymentGateway` with providers selected by `app.payments.autopay.provider`:
  * `sandbox` (default, local): simulates mandate authorisation (an in-app "Authorise" page) and successful/failed
    debits, so the full flow is testable without a payment company account;
  * `razorpay`: Razorpay Subscriptions/Tokens API via `RestClient` (key id/secret from env), webhook signature
    verification (`X-Razorpay-Signature`, HMAC-SHA256 of the raw body).
* `payment_mandates`: id, organization_id, tenant_id, provider, provider_mandate_id (unique per provider), method
  (`UPI_AUTOPAY`, `ENACH`, `CARD`), `max_amount`, `status` (`PENDING_AUTHORISATION`, `ACTIVE`, `PAUSED`, `CANCELLED`,
  `FAILED`), authorisation_url, `debit_day_offset` (charge N days before due; default 0 = on due date), timestamps.
* `autopay_attempts`: id, mandate_id, invoice_id, amount, provider_payment_id, status (`SCHEDULED`, `SUCCEEDED`,
  `FAILED`), failure_reason, attempt_no, created_at; unique (invoice_id, attempt_no).
* Job (ShedLock, daily): for open RENT invoices of tenants with an ACTIVE mandate whose debit date has come and whose
  balance ≤ `max_amount`, request a debit (one attempt per invoice per day, max 3). Success arrives via webhook →
  recorded through the existing idempotent `PaymentProcessor` with idempotency key = provider payment id, method
  `AUTOPAY`. Failures notify the tenant (and managers after the last attempt).
* Webhook: `POST /api/v1/payments/webhooks/{provider}` (public, signature-verified, idempotent).
* API: tenant `POST /api/v1/autopay/mandates` (start), `GET /api/v1/autopay/mandates/me`, `POST /{id}/pause|resume|cancel`;
  operators `GET /api/v1/autopay/mandates?propertyId=` and attempts list.
* Migration must extend `ck_payments_method` with `AUTOPAY` (and analytics must treat it as a normal method).

## 3. Deposits tab for the owner (part of the move-out/deposit work, `V6`)

Owners want one place to see every deposit and refund it.

* `GET /api/v1/deposits?propertyId=&status=` (PROPERTY_ADMIN, SUPER_ADMIN; MANAGER read-only for own properties):
  per resident/stay → `collected`, `refunded`, `held`, `deductions`, `refundDue`, `status`
  (`HELD`, `REFUND_DUE`, `PARTIALLY_REFUNDED`, `REFUNDED`, `FORFEITED`), move-out date, refund due-by date.
  Totals at the top (total held, total refund due, overdue refunds).
* `GET /api/v1/deposits/{tenantId}` → ledger lines (collected payments, deductions, refunds) for that resident.
* Actions (PROPERTY_ADMIN only): **Refund** (full/partial, idempotent, method + reference + note), **Record
  deduction** (description + amount, posted as revenue line), **Forfeit** with reason (policy-driven, audited),
  **Top-up** request (creates a deposit invoice for the difference, e.g. after a transfer to a costlier room).
* Settlement at check-out is persisted (not only returned): dues are paid from the deposit (an internal
  "deposit adjustment" payment against the open invoices, method `DEPOSIT_ADJUSTMENT`), deductions become an invoice,
  and the remaining amount becomes `refundDue` with a due-by date from property policy (e.g. 7 days).
* Notifications: refund issued (tenant: in-app + email + SMS), refund overdue (owner).
