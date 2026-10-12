# PGMS Analytics Service

Read-only analytics and reporting API for the PG (paying guest / co-living) management platform.
It is written in Python with FastAPI, pandas and SQLAlchemy Core.

The Spring Boot backend owns all transactional data and every write. This service **only reads**. It
computes occupancy, revenue, receivables, tenant lifecycle, maintenance, complaint and property
comparison metrics. It does not implement any CRUD.

## Why it is a separate service

| Concern | How the split helps |
|---|---|
| Workload isolation | Wide scans and pandas aggregation stay off the transactional JVM. In production this service can point at a read replica. |
| Least privilege | It connects as `analytics_ro`, which has `SELECT` only on the `analytics.*` views (`V2__analytics_read_model.sql`). It cannot see base tables, PII (names, phones, emails, IDs) or password hashes, and it cannot write. Every session is also `default_transaction_read_only=on` with a `statement_timeout`. |
| Stable contract | The views are the published contract, so backend table refactors do not break analytics. |
| Right tool | pandas handles time bucketing, interval maths (bed-days, stay stitching), aging buckets and group-bys. Future data-science work (forecasting, anomaly detection) belongs naturally in Python. |
| Independent scaling and deploys | The service is stateless apart from an optional Redis cache, so it can be scaled or redeployed without touching the backend. |

## Architecture

```
app/
  core/          config (env only), JSON logging, correlation-id middleware, errors (backend error shape),
                 security (JWT/JWKS/revocation), authorization (scope rules), cache, Redis fail-open gateway, clock
  db/            SqlDatabase: SQLAlchemy engine (psycopg 3), read-only sessions, statement_timeout, UTC session tz
  repositories/  parameterised SQL against analytics.* views -> pandas DataFrames; scope filter applied in SQL
                 (access, occupancy, finance [revenue+payments], tenants, maintenance, complaints, properties)
  services/      PURE functions: DataFrames in -> Pydantic results out. No I/O, so they are fully unit-testable
  schemas/       Pydantic v2 response models (camelCase JSON)
  reports/       exporter interface + CSV exporter + registry keyed by `format` (Excel can be added here)
  api/           dependency providers (overridable in tests), rendering, routers (analytics, health)
```

The request flow is: auth dependency, then scope resolution (DB lookup for managers), then the Redis
cache lookup. On a cache miss the repositories run SQL, the services compute, the result is cached,
and the JSON or CSV response is rendered.

## Endpoints

All `/analytics/*` endpoints need `Authorization: Bearer <access token>` from the backend and one of
the roles **SUPER_ADMIN, PROPERTY_ADMIN or MANAGER**.

Common query parameters (all optional):

| Param | Meaning |
|---|---|
| `propertyId` | Restrict to one property. It must be inside your scope, otherwise **403**. |
| `organizationId` | SUPER_ADMIN only filters by it. Other roles get **403** unless it equals their own org. |
| `from`, `to` | `YYYY-MM`, inclusive, between `1900-01` and `2199-12`. The default is the last 12 months including the current month. If only `from` is given, the period runs to the current month (or is that single month when `from` is in the future). If only `to` is given, the period is the 12 months ending at `to`. The maximum span is 60 months. |
| `format` | `json` (default) or `csv`. |

| Method and path | Extra params | Description |
|---|---|---|
| `GET /analytics/occupancy` | | Bed snapshot, per-property occupancy, room-type utilisation, monthly bed-day trend |
| `GET /analytics/revenue` | | Monthly net rent revenue, deposits, refunds, billed rent, collection rate, MoM growth, by payment method |
| `GET /analytics/payments` | | Outstanding total, aging buckets, outstanding by property and type, default trend per billing month |
| `GET /analytics/tenants` | | Active tenants, status and gender mix, move-ins and move-outs, churn, stay duration |
| `GET /analytics/maintenance` | | Counts by status, category and priority, resolution hours, SLA compliance, breached-open, monthly trend |
| `GET /analytics/complaints` | | Counts by status, category, priority and escalation level, escalation rate, resolution hours, overdue-open, trend |
| `GET /analytics/properties` | `sortBy` (`name, occupancyRate, totalBeds, activeTenants, currentMonthRevenue, revenuePerBed, collectionRate, outstanding, openMaintenance, slaCompliance, openComplaints`; default `occupancyRate`), `order` (`asc`/`desc`; default `desc`) | Property comparison table with rank |
| `GET /health/live` | | Process is up (no auth) |
| `GET /health/ready` | | 200 if the DB is reachable, else 503. Redis is reported but never affects readiness. |
| `GET /analytics/docs`, `GET /analytics/openapi.json` | | Swagger UI and the OpenAPI document |

nginx forwards `/analytics/...` unchanged. The `/health/*` endpoints are for container probes.

Every report contains a `meta` block:

```json
{"generatedAt": "2026-10-05T06:30:00Z", "timezone": "Asia/Kolkata", "organizationId": 1,
 "propertyIds": null, "fromMonth": "2026-01", "toMonth": "2026-10"}
```

`propertyIds: null` means all properties of the organization. A list means exactly those properties.
For a manager with no assignments it is `[]`, and the report is empty.

### Example responses (abridged)

`GET /analytics/occupancy?from=2026-01&to=2026-10`
```json
{
  "meta": {"...": "..."},
  "snapshot": {"totalBeds": 4, "occupiedBeds": 2, "reservedBeds": 0, "availableBeds": 1, "maintenanceBeds": 1,
               "occupancyRatePct": 66.67, "rawOccupancyRatePct": 50.0, "vacancyRatePct": 33.33},
  "byProperty": [{"propertyId": 2, "propertyName": "Beta", "totalBeds": 1, "occupiedBeds": 1, "occupancyRatePct": 100.0, "...": "..."}],
  "byRoomType": [{"roomType": "DOUBLE", "rooms": 1, "totalBeds": 2, "occupiedBeds": 1, "maintenanceBeds": 0,
                  "utilizationPct": 50.0, "fullyOccupiedRooms": 0, "partiallyOccupiedRooms": 1, "emptyRooms": 0}],
  "monthlyTrend": [{"month": "2026-02", "daysCounted": 28, "occupiedBedDays": 56, "availableBedDays": 112,
                    "occupancyRatePct": 50.0, "averageOccupiedBeds": 2.0}]
}
```

`GET /analytics/revenue`
```json
{
  "totals": {"netRentRevenue": 13500.0, "grossRentCollected": 14000.0, "depositsCollected": 18000.0, "totalRefunds": 500.0,
             "billedRent": 33000.0, "billedRentCollected": 14000.0, "collectionRatePct": 42.42,
             "averageMonthlyNetRentRevenue": 1350.0},
  "monthly": [{"month": "2026-09", "grossRentCollected": 14000.0, "rentRefunds": 500.0, "netRentRevenue": 13500.0,
               "depositsCollected": 0.0, "depositRefunds": 0.0, "totalRefunds": 500.0, "billedRent": 21000.0,
               "billedRentCollected": 14000.0, "collectionRatePct": 66.67, "momGrowthPct": null}],
  "byMethod": [{"method": "UPI", "paymentCount": 1, "netRentRevenue": 8500.0, "depositsCollected": 0.0,
                "shareOfRentRevenuePct": 62.96}]
}
```

`GET /analytics/payments`
```json
{
  "summary": {"outstandingTotal": 19000.0, "overdueTotal": 7000.0, "openInvoiceCount": 2,
              "collectionRatePct": 42.42, "defaultRatePct": 100.0, "avgDaysLate": 3.0},
  "aging": [{"bucket": "CURRENT", "label": "Not yet due", "invoiceCount": 1, "outstandingAmount": 12000.0, "sharePct": 63.16},
            {"bucket": "DAYS_1_30", "label": "1-30 days past due", "invoiceCount": 1, "outstandingAmount": 7000.0, "sharePct": 36.84},
            {"bucket": "DAYS_31_60", "...": "..."}, {"bucket": "DAYS_61_90", "...": "..."}, {"bucket": "DAYS_90_PLUS", "...": "..."}],
  "outstandingByProperty": [{"propertyId": 2, "propertyName": "Beta", "invoiceCount": 2, "outstandingAmount": 19000.0, "overdueAmount": 7000.0}],
  "outstandingByType": [{"invoiceType": "RENT", "invoiceCount": 2, "outstandingAmount": 19000.0},
                        {"invoiceType": "SECURITY_DEPOSIT", "invoiceCount": 0, "outstandingAmount": 0.0}],
  "defaultTrend": [{"billingMonth": "2026-09", "invoices": 2, "evaluated": 2, "paidOnTime": 0, "paidLate": 1,
                    "unpaidPastDue": 1, "notYetDue": 0, "defaultRatePct": 100.0, "latePaymentRatePct": 50.0,
                    "avgDaysLate": 3.0, "billedAmount": 21000.0, "collectedAmount": 14000.0, "collectionRatePct": 66.67}]
}
```

`GET /analytics/tenants`
```json
{
  "summary": {"totalTenants": 3, "activeTenants": 3, "currentlyAllocatedTenants": 2, "moveInsInPeriod": 3, "moveOutsInPeriod": 1},
  "byStatus": [{"key": "ACTIVE", "count": 3, "sharePct": 100.0}, {"key": "MOVED_OUT", "count": 0, "sharePct": 0.0}],
  "genderMix": [{"key": "MALE", "count": 1, "sharePct": 33.33}, {"key": "UNKNOWN", "count": 1, "sharePct": 33.33}],
  "stayDuration": {"completedStays": 1, "averageCompletedStayDays": 31.0, "medianCompletedStayDays": 31.0,
                   "staysIncludingOngoing": 3, "averageStayDaysIncludingOngoing": 181.67, "medianStayDaysIncludingOngoing": 246.0},
  "monthly": [{"month": "2026-04", "moveIns": 0, "moveOuts": 1, "activeAtMonthStart": 3, "churnRatePct": 33.33}]
}
```

`GET /analytics/maintenance` returns `summary` (`createdInPeriod`, `resolvedInPeriod`, `openNow`,
`breachedOpenNow`, `avgResolutionHours`, `medianResolutionHours`, `slaCompliancePct`), `byStatus`,
`byCategory`, `byPriority`, `resolutionByCategory` and `monthly` (created, resolved, avg hours, SLA %).

`GET /analytics/complaints` returns `summary` (`createdInPeriod`, `resolvedInPeriod`, `openNow`,
`overdueOpenNow`, `escalatedInPeriod`, `escalationRatePct`, avg and median resolution hours),
`byStatus`, `byCategory`, `byPriority`, `byEscalationLevel` (`"0"`–`"3"`) and `monthly`.

`GET /analytics/properties?sortBy=outstanding&order=desc`
```json
{
  "currentMonth": "2026-10", "sortBy": "outstanding", "order": "desc",
  "properties": [{"rank": 1, "propertyId": 2, "propertyCode": "B", "propertyName": "Beta", "city": "Pune", "status": "ACTIVE",
                  "totalBeds": 1, "occupiedBeds": 1, "occupancyRatePct": 100.0, "activeTenants": 1,
                  "currentMonthRevenue": 0.0, "revenuePerBed": 0.0, "collectionRatePct": 20.83,
                  "outstandingAmount": 19000.0, "openMaintenance": 1, "slaCompliancePct": null, "openComplaints": 1}]
}
```

### CSV export (`format=csv`)

The response is `text/csv` with `Content-Disposition: attachment; filename="<report>-<date>.csv"`. The
file is a multi-section CSV. Each section starts with a `# <name>` line, followed by a header row and
data rows, and sections are separated by a blank line. The first section is `summary` with the
columns `metric,value`, where nested scalars appear as dotted paths such as `snapshot.occupancyRatePct`.
Each list in the report becomes its own section, for example `monthlyTrend` or `byProperty`. Text
cells that start with `= + - @` are prefixed with `'` to prevent spreadsheet formula injection.

### Errors

Errors use the same shape as the Java backend:

```json
{"timestamp": "2026-10-05T18:39:06.122Z", "status": 403, "error": "Forbidden", "code": "ACCESS_DENIED",
 "message": "You do not have access to this property", "path": "/analytics/occupancy", "correlationId": "..."}
```

Query-parameter validation failures (400) also carry the backend's `fieldErrors` array, for example
`"fieldErrors": [{"field": "propertyId", "message": "Input should be a valid integer...", "rejectedValue": "abc"}]`.
Like the backend (`@JsonInclude(NON_EMPTY)`), the key is omitted when there are no field errors.

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bad `from`/`to`/`format`/`sortBy`/ids (ids must fit a BIGINT), `from` > `to`, span > 60 months, month outside 1900-01..2199-12 |
| 401 | `UNAUTHORIZED` | Missing, malformed, expired, wrongly signed or revoked token, wrong `iss`/`aud`, or `token_type` other than `access` |
| 403 | `ACCESS_DENIED` | Role not allowed, or org/property outside your scope (same code as the backend) |
| 404 / 405 | `NOT_FOUND` / `METHOD_NOT_ALLOWED` | Unknown route or method |
| 503 | `SERVICE_UNAVAILABLE` | Database unreachable, query timeout or other DB error, or JWKS endpoint unreachable |
| 500 | `INTERNAL_ERROR` | Unexpected bug. Details are logged, never returned. |

## Metric definitions

Conventions:
- Money is returned as a float rounded to 2 dp. Rates are percentages (0–100, 2 dp).
- A ratio or average with an empty denominator is `null`, never `NaN`. Counts and sums default to `0`.
- Months are calendar months in `BUSINESS_TIMEZONE` (default `Asia/Kolkata`). Timestamps
  (`paid_at`, `created_at`, …) are converted from UTC to that zone before bucketing. `DATE` columns
  are used as-is.
- "Today" and "now" come from the service clock in the business timezone.
- VOID invoices, and payments against them, are excluded everywhere.

### Occupancy
- **Inventory**: non-deleted beds in `ACTIVE` rooms of non-deleted properties (`analytics.beds` ⋈ `rooms` ⋈ `properties`).
- `occupancyRatePct = OCCUPIED / (total − MAINTENANCE) × 100`. This is the operational rate: beds under maintenance cannot be sold.
- `rawOccupancyRatePct = OCCUPIED / total × 100`.
- `vacancyRatePct = AVAILABLE / (total − MAINTENANCE) × 100`. RESERVED beds count as neither occupied nor vacant.
- **Room-type utilisation**: per `room_type`, `utilizationPct = occupied / (beds − maintenance beds)`, plus
  counts of fully occupied (`occupied = beds`), partially occupied and empty rooms.
- **Monthly trend (bed-days)**: an allocation occupies its bed on each day `d` with `start_date ≤ d < end_date`,
  and `end_date` NULL means it is ongoing.
  `occupancyRatePct = Σ occupied bed-days / (current bed count × days counted) × 100`.
  The current month is counted only up to today, and future months have `daysCounted = 0` and a null rate.
  **Approximation:** the read model does not version bed inventory, so the denominator uses the *current* bed
  count for every month. Beds added or removed during the period skew historical months, so the rate is
  capped at 100%. The allocation-based numerator is exact.

### Revenue
- **Net rent revenue** = `Σ (amount − refunded_amount)` over payments on **RENT** invoices, bucketed by the local
  month of `paid_at`. This is a cash basis.
- **Deposits collected** = `Σ amount` on SECURITY_DEPOSIT payments. Deposits are a liability and are never part of revenue.
  `depositRefunds` and `rentRefunds` are reported separately, and `totalRefunds` is their sum.
  **Approximation:** the views expose only the cumulative `refunded_amount` per payment and not refund dates,
  so refunds are attributed to the month of the original payment.
- **Billed rent** = `Σ total_amount` of non-VOID RENT invoices by `billing_month`.
- **Collection rate (month)** = `Σ amount_paid / Σ total_amount` of that billing month's non-VOID RENT invoices.
  The totals use the same formula over all billing months in the period.
- **MoM growth** = `(net_m − net_{m−1}) / |net_{m−1}| × 100`. It is null for the first month, when the previous month was 0,
  and for months after the current one. The current month is month-to-date, so its growth is usually negative until month end.
- **Average monthly net rent revenue** = net rent revenue / number of months in the period up to and including the
  current month (future months in the period do not dilute it; a period entirely in the future gives 0).
- **By method**: payment count, net rent revenue, deposits collected and share of net rent revenue per method.
  All five methods are always present.

### Payments and receivables
- **Outstanding** = `total_amount − amount_paid` for invoices in status ISSUED, PARTIALLY_PAID or OVERDUE, of any
  type. This is a snapshot as of today and is not limited by the period.
- **Aging** uses `days past due = today − due_date`. The buckets are `CURRENT` (≤ 0, not yet due, including due today),
  `DAYS_1_30`, `DAYS_31_60`, `DAYS_61_90` and `DAYS_90_PLUS` (≥ 91). `overdueTotal` is the outstanding amount with days past due > 0.
- **Default trend** covers non-VOID RENT invoices grouped by `billing_month`. The "paid date" is the local date of the
  **latest** payment `paid_at` for the invoice.
  - `paidLate`: status PAID and paid date > `due_date`. `avgDaysLate` is the mean of `(paid date − due_date)` over these invoices.
  - `paidOnTime`: status PAID and not late. This includes a PAID invoice with no payment rows.
  - `unpaidPastDue`: not PAID and `due_date < today`.
  - `notYetDue`: not PAID and `due_date ≥ today`. These are excluded from the denominator.
  - `defaultRatePct = (paidLate + unpaidPastDue) / evaluated`, where `evaluated = paidOnTime + paidLate + unpaidPastDue`.
    `latePaymentRatePct = paidLate / evaluated`.
- The top-level `collectionRatePct` is `Σ amount_paid / Σ total_amount` over the period's non-VOID RENT invoices.

### Tenants
- `activeTenants` = tenants with status ACTIVE or ON_NOTICE. `currentlyAllocatedTenants` = distinct tenants with an ACTIVE allocation.
  `byStatus` covers all four statuses. `genderMix` covers active tenants, and a NULL gender is reported as `UNKNOWN`.
- **Stay** = the union of a tenant's allocation intervals. Allocations that touch or overlap are merged, so a room
  transfer (one allocation ends and the next starts the same day) is **one stay**. A gap of one day or more starts a new stay.
- **Move-ins** = stays whose start falls in the month. **Move-outs** = completed stays whose (exclusive) end date
  falls in the month. Transfers are neither.
- **Active at month start** = stays in residence the night before the 1st (`start < month_start ≤ end`).
  **Churn** = `move-outs / active at month start`. This follows the requested definition and can exceed 100% in a
  month with many short stays. It is null when the denominator is 0; future months have `activeAtMonthStart = 0`
  and therefore a null churn rate.
- **Stay duration**:
  - The average and median over completed stays use stays that ended within the period, measured as `end − start` days.
  - The "including ongoing" figures add the ongoing stays, measured as `today − start`.

### Maintenance
- Counts by status, category and priority cover requests **created** in the period.
- **Resolution hours** = `resolved_at − created_at`, falling back to `closed_at` when `resolved_at` is NULL. It is
  computed over requests resolved in the period, excluding CANCELLED requests. Both the average and the median are reported.
- **SLA compliance** = the share of those resolved requests that were not breached. A request is breached if
  `sla_breached` is true **or** `resolved_at > sla_due_at`, which catches breaches the scheduler had not flagged yet.
- `breachedOpenNow` = requests currently OPEN, ASSIGNED or IN_PROGRESS with `sla_breached` or `sla_due_at < now`.
- The monthly trend shows created per created-month and resolved per resolved-month. `resolutionByCategory` gives the
  count, average and median hours and SLA % per category.

### Complaints
- Counts by status, category, priority and escalation level cover complaints created in the period.
- **Escalation rate** = complaints created in the period with `escalation_level > 0` divided by complaints created in the period.
- **Resolution hours** use the same definition as maintenance, with `closed_at` as the fallback.
- `overdueOpenNow` = complaints that are OPEN, IN_PROGRESS or ESCALATED with `due_at < now`.

### Property comparison
One row per property in scope:
- occupancy rate and beds as in the snapshot
- active tenants, using the tenant's current `property_id`
- current-month net rent revenue for the calendar month containing today, independent of `from`/`to`
- revenue per bed = current-month revenue / total beds
- collection rate over the period
- current outstanding amount
- open maintenance count, and SLA compliance over the period
- open complaints

Rows are sorted by the whitelisted `sortBy` with nulls last and ties broken by name. `rank` is a competition
rank (ties share a rank, as in 1, 1, 3). For `sortBy=name`, the rank is the row position.

## Authentication, authorization and scoping

- The service validates RS256 JWTs with RSA keys from `JWKS_URL` (`JwksKeyStore` in `app/core/security.py`):
  - The key set is cached for `JWKS_CACHE_SECONDS` (default 300) and **replaced** on every refetch, so a key the backend
    withdraws stops being accepted within one cache period.
  - A token with an unknown `kid` forces a refetch (key rotation), but at most once per `JWKS_MIN_REFRESH_SECONDS`
    (default 10). Other unknown kids in that window get 401 without contacting the backend. Tokens without a `kid` get 401
    without any fetch.
  - If a fetch fails, the last known keys keep working and retries back off for `JWKS_MIN_REFRESH_SECONDS`. A token whose
    key is not cached then gets 503.
- It validates `iss == JWT_ISSUER`, that `aud` contains `JWT_AUDIENCE`, `exp`, and `iat`, with a 30 s leeway.
  The claims `sub`, `jti`, `role` and `token_type == "access"` are required.
- Revocation uses the backend's Redis:
  - The token is rejected if `auth:revoked-jti:{jti}` exists.
  - It is also rejected if `auth:invalid-before:{sub}` exists and the token's `iat` is earlier than that epoch-seconds value.
  - If Redis is unavailable the check **fails open** and a warning is logged. Tokens live 15 minutes and the service is read-only.
- Scope rules:
  - **SUPER_ADMIN** sees everything and can narrow with `organizationId` and/or `propertyId`.
  - **PROPERTY_ADMIN** sees their `org` claim only. `propertyId` must belong to that org, which is checked against `analytics.properties`.
  - **MANAGER** sees only the properties in `analytics.user_property_access` for their user id. This is queried on
    every request and intersected with their org, and `propertyId` must be one of those properties.
  - **STAFF and TENANT** always get 403.
- The scope is applied **in SQL** by every repository through `scope_clause()`: an `organization_id` predicate plus
  `property_id = ANY(:ids)`. Views without an `organization_id` column (`beds`, `rooms`) are scoped through
  `analytics.properties`. All values are bound parameters.

## Caching and failure modes

| Situation | Behaviour |
|---|---|
| Cache hit | The JSON result is served from Redis key `analytics:cache:{endpoint}:org-{org\|all}:{sha256(scope+params+today)}` with TTL `CACHE_TTL_SECONDS` (default 300; `0` disables it). The CSV export is rendered from the cached result. |
| Redis down or slow | Socket timeouts are 0.3 s. The error is logged, the result is computed directly, and revocation checks are skipped (fail open). After a failure Redis is bypassed for `REDIS_RETRY_COOLDOWN_SECONDS` (5 s), so a dead Redis does not add latency to every request. `/health/ready` reports `redis: DOWN` and stays 200. |
| DB down or query > `statement_timeout` (10 s) | 503 `SERVICE_UNAVAILABLE`, and `/health/ready` returns 503. |
| JWKS endpoint down | Keys that were already fetched keep working (retries back off for `JWKS_MIN_REFRESH_SECONDS`). Tokens whose key is not known get 503 `SERVICE_UNAVAILABLE`, as does a JWKS without any RSA signing key. |
| New org with no data | All endpoints return 200 with zeros, empty lists and null ratios. |

The cache is time-based only: data written by the backend shows up after at most one TTL. Keys are
namespaced by org, so `analytics:cache:*:org-{id}:*` can be deleted for targeted invalidation.

## Observability

- Logs are structured JSON on stdout, one object per line. Each request produces one access line with
  `method, path, status, duration_ms, correlation_id, user_id`. Health probes are logged at DEBUG.
  uvicorn's own logs also go through the JSON formatter.
- `X-Correlation-Id` is read from the request, or a UUID is generated when it is missing or invalid (max 64
  safe characters). It is echoed on every response, included in every log line and returned in error bodies.

## Configuration (environment variables)

| Variable | Default | Notes |
|---|---|---|
| `DATABASE_URL` | `postgresql+psycopg://analytics_ro@localhost:5432/pgms` | Must use the `analytics_ro` role |
| `REDIS_URL` | `redis://localhost:6379/0` | Empty means no cache and no revocation checks (a warning is logged) |
| `JWKS_URL` | `http://backend:8080/api/v1/auth/jwks` | |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `pgms-auth` / `pgms-api` | |
| `BUSINESS_TIMEZONE` | `Asia/Kolkata` | Validated IANA zone name |
| `CACHE_TTL_SECONDS` | `300` | |
| `LOG_LEVEL` | `INFO` | |
| `CORS_ORIGINS` | empty | Comma-separated. Leave empty when served through the same nginx origin. |
| Advanced | | `DB_STATEMENT_TIMEOUT_MS` (10000), `DB_POOL_SIZE` (5), `DB_MAX_OVERFLOW` (5), `REDIS_SOCKET_TIMEOUT_SECONDS` (0.3), `JWKS_CACHE_SECONDS` (300), `JWKS_MIN_REFRESH_SECONDS` (10), `JWT_LEEWAY_SECONDS` (30), `MAX_REPORT_MONTHS` (60) |

See `.env.example`. It contains placeholders only.

## Running locally

```bash
cd analytics-service
~/.local/bin/uv venv --python 3.12 .venv
~/.local/bin/uv pip install --python .venv/bin/python -r requirements-dev.txt -r requirements.txt
cp .env.example .env    # edit, then export (the app reads real env vars only)
set -a; source .env; set +a
.venv/bin/uvicorn app.main:create_app --factory --reload --port 8000
# http://localhost:8000/analytics/docs
```

Docker (from the repo root):

```bash
docker build -t pgms-analytics:local analytics-service
docker run --rm -p 8000:8000 --env-file analytics-service/.env pgms-analytics:local
```

The image is a multi-stage `python:3.12-slim` build. The runtime stage has no build tools and runs as non-root
uid 10001. Its `HEALTHCHECK` calls `/health/live` through Python `urllib`, and it starts
`uvicorn --factory --proxy-headers`. `FORWARDED_ALLOW_IPS` defaults to `*`; restrict it to the nginx
address if you know it.

## Tests and quality

```bash
.venv/bin/ruff check . && .venv/bin/ruff format --check .
.venv/bin/pytest --cov=app
```

None of the tests need a live database or Redis.

- `tests/unit` covers every service computation with hand-built DataFrames and asserted numbers, including edge
  cases: empty frames, a transfer counted as one stay, VOID excluded, deposits excluded from revenue, late-payment
  detection in the business timezone, aging boundaries and bed-days across month boundaries. It also covers
  period parsing, scoping rules, the SQL scope clause and repository SQL (only `analytics.*` views, values
  bound), the cache, the Redis gateway cooldown, exporters, config and logging.
- `tests/api` runs `TestClient` against every endpoint with repository fakes injected through
  `app.dependency_overrides`. It covers JSON, CSV, empty data, validation errors, 503 on DB failure, 500 shape,
  correlation ids, caching, health, OpenAPI and CORS.
- `tests/security` generates an RSA key pair in the test and monkeypatches the JWKS fetch. It checks missing,
  garbage and bad-signature tokens, HS256, wrong `aud`/`iss`, expired tokens, refresh tokens, missing claims,
  STAFF/TENANT 403, manager property scoping, cross-org 403, revoked `jti`, `invalid-before`, Redis down (still
  allowed), key rotation refetch, an unknown kid, a kid-less token, a withdrawn key, a random-kid flood (one refetch
  per interval), a JWKS outage (last known keys, back-off), a JWKS with no RSA key (503), and JWKS down (503).

## Extension points (future work, **not implemented**)

- **Scheduled reports**: a scheduler (cron / ECS scheduled task, or a backend-triggered job) could call the
  services with a service principal, render through the exporter registry and email or upload the result. The
  pure `build_*_report` functions are already reusable outside HTTP.
- **Excel export**: implement `ReportExporter` (for example with openpyxl, one sheet per `Section`) and register it
  in `app/reports/registry.py`. Then extend the `format` Literal in `app/api/deps.py`. No router changes are needed.
- **Forecasting** (occupancy, revenue) and **anomaly detection** (unusual collection drops, maintenance spikes) could
  be added as new service modules over the same repository DataFrames, exposed as new endpoints. Nothing of the
  sort exists today and no ML dependencies are included.
- **Materialised aggregates**: if data volume grows, monthly rollups could become materialised views in the
  `analytics` schema owned by the backend migrations. The repositories would switch to them without service changes.

## Known limitations

- The bed-day occupancy denominator uses the current bed inventory (see above).
- Refunds are attributed to the original payment's month, because refund timestamps are not exposed by the views.
- A manager's tenant and stay metrics only see allocations in their assigned properties. A tenant who transferred
  between a visible and an invisible property shows a truncated stay.
- During a random-kid flood, a genuinely rotated key can be rejected (401) for up to `JWKS_MIN_REFRESH_SECONDS` before
  the next permitted refetch picks it up.
- Soft-deleted properties are hidden from the property-level views (`properties`, `beds` via `rooms`/`properties`), but
  the transactional views (`invoices`, `payments`, `allocations`, `tenants`, tickets) do not expose the property's
  `deleted_at`. Org-wide revenue, tenant and ticket figures therefore still include a deleted property's history, and its
  allocations still count in the bed-day numerator while its beds are no longer in the denominator.
