# Interview Guide — design reasoning behind the platform

Each answer points at the code that implements it, so every claim can be demonstrated.
Anything not implemented is labelled as such.

---

## 1. Why Spring Boot for the core?

* The core is **transaction-heavy**: a check-in changes a bed, an allocation, a booking, a tenant, two invoices, an
  audit row and notification rows — all-or-nothing. Spring's declarative `@Transactional` (with propagation control:
  `MANDATORY`, `REQUIRES_NEW`) makes these boundaries explicit and reviewable
  (`AllocationService.checkIn`, `InvoiceService.issueMoveInInvoices`, `BillingJobs`).
* **Spring Security** gives a standard JWT resource-server pipeline plus method security; we only add the pieces that
  are domain-specific (revocation validator, `AccessGuard`).
* **Spring Data JPA** gives pessimistic/optimistic locking with one annotation (`@Lock`, `@Version`) and Specifications
  for dynamic filters; plain `NamedParameterJdbcTemplate` where SQL is the better tool (dashboard aggregates).
* **Actuator/Micrometer** give health probes and metrics needed by the load balancer and monitoring for free.
* Java 21 records keep DTOs immutable and concise.

## 2. Why PostgreSQL?

Correctness features we actually rely on, which most alternatives lack together:

* `SELECT … FOR UPDATE` row locks with a bounded `lock_timeout` — serialises bed allocation.
* **Exclusion constraints** (`EXCLUDE USING gist (bed_id WITH =, daterange(start,end) WITH &&)`) — the database
  refuses overlapping stays even for back-dated inserts. MySQL has no equivalent.
* **Partial unique indexes** — "one *RESERVED* booking per bed", "one *non-void* rent invoice per allocation/month",
  "unique email among *non-deleted* users".
* `CHECK` constraints for money invariants, `JSONB` for audit details, `pg_trgm` GIN index for fuzzy name search.
* **Views + roles** to publish a read-only analytics contract; read replicas on RDS for analytics traffic.

## 3. Why Redis — and only where?

Redis holds nothing that cannot be lost:

| Use | Why Redis | Behaviour if Redis is down |
|---|---|---|
| Property details cache (`PropertyCache`, TTL 10 min, evicted on update) | Read on most screens, rarely changes | Cache miss → DB (`CacheErrorHandler` logs and continues) |
| Dashboard KPIs (`DashboardService`, TTL 60 s, key = caller's exact scope) | ~10 aggregate queries per load | Computed live |
| Auth rate limiting (`AuthRateLimitFilter`, atomic Lua INCR+PEXPIRE) | Shared counter across replicas | Fail-open; nginx still rate-limits per IP |
| Token revocation (`TokenRevocationService`) | Stateless JWTs need a shared, TTL-based deny list | Fail-open; tokens expire within 15 min; refresh tokens are revoked in PostgreSQL |
| Analytics result cache (Python) | Expensive pandas computations | Computed live |

Deliberately **not** in Redis: bed availability (must be exact), idempotency keys (must be durable and atomic with the
payment), scheduler locks (ShedLock uses PostgreSQL so jobs survive a Redis outage), sessions (stateless JWT).

## 4. Why Python/FastAPI for analytics, and why a separate service?

* The work is **columnar**: bucket timestamps into months in a time zone, expand allocations into bed-days, merge a
  tenant's transfer chain into one stay, age receivables into buckets. pandas expresses these in a few lines and is
  unit-testable on small DataFrames (161 tests, 98% coverage).
* **Isolation**: analytics queries scan months of history; running them inside the OLTP service would compete for the
  same connection pool and heap. As a separate service it can point at a **read replica**, scale independently and
  be redeployed without touching the transactional core.
* **Least privilege**: it connects as `analytics_ro`, which can only `SELECT` from the `analytics.*` views
  (`V2__analytics_read_model.sql`). Those views exclude PII (names, phones, emails, ID numbers, password hashes).
* **Contract, not coupling**: the views are the published interface; base tables can be refactored as long as the
  views keep their shape. The analytics service does **not** duplicate any CRUD.
* It verifies the same JWTs via the backend's **JWKS** (RS256 public key) and enforces the same scope rules.

## 5. What happens if the Python service is unavailable?

Nothing in the core breaks. The backend has no runtime call to analytics; dashboards are computed by the backend
itself. Nginx/ALB return `502/503` for `/analytics/**` only. The ALB health check removes failed analytics tasks and
ECS replaces them. Analytics is stateless, so recovery is just a restart.

## 6. What happens if Redis is unavailable?

The app keeps serving every business operation (see table in §3). Concretely:

* Lettuce timeouts are short (`500ms` command, `1s` connect) so a dead Redis cannot stall requests for long.
* Readiness probes check **only PostgreSQL** (`management.endpoint.health.group.readiness.include=readinessState,db`),
  so the load balancer does **not** pull healthy backend tasks out of rotation because Redis is down — a classic
  cascading-failure mistake.
* Security degradation is bounded and explicit: logout of a single access token is not enforced for at most its
  remaining lifetime (≤ 15 min); refresh is still blocked (PostgreSQL), deactivated users cannot log in or refresh.

## 7. Why JWT (and why RS256)?

* Stateless verification → any backend replica (and the analytics service) can authenticate a request without a
  session store or a call to an auth server. Horizontal scaling stays trivial.
* **Short-lived access token (15 min) + rotating opaque refresh token (14 days)** — the usual compromise between
  statelessness and revocability.
* Refresh tokens are random 256-bit values stored only as SHA-256 hashes; each use rotates the token; all tokens of a
  login share a `family_id`. Presenting an already-rotated token is treated as theft: the whole family is revoked and
  all live access tokens of that user are invalidated (`AuthService.refresh`, `noRollbackFor` keeps that revocation even
  though we return 401). Tested in `AuthServiceTest` and `AuthFlowIT`.
* **RS256** instead of HS256: only the backend holds the private key; other services get the public key from
  `/api/v1/auth/jwks`. A compromised analytics service cannot forge tokens.
* Claims validated: signature, `iss`, `aud`, `exp`, `token_type=access`, plus revocation.

## 8. How is concurrent bed allocation prevented?

Three independent layers (`AllocationService`, `BookingService`, `V1__core_schema.sql`):

1. **Serialisation with row locks.** Every operation that changes a bed takes `SELECT … FOR UPDATE` on the bed
   (and on the tenant, booking, allocation it touches). Concurrent requests for the same bed queue; the second one
   re-reads the bed *after* the first commits. `lock_timeout = 5s` bounds the wait (→ `409 CONCURRENT_MODIFICATION`).
2. **State machine after the lock.** `Bed.reserve()` / `occupy()` only succeed from `AVAILABLE`/`RESERVED`; the loser
   gets `409 BED_NOT_AVAILABLE`.
3. **Database constraints** — the final guard independent of application code: exclusion constraints on
   `(bed_id, daterange)` and `(tenant_id, daterange)`, partial unique indexes on live bookings/allocations. Violations
   are translated into precise `409` codes by `ConstraintViolationTranslator`.

**Deadlock avoidance:** one global lock order — tenant → booking → allocation → beds in ascending id — so two transfers
that need the same two beds always lock them in the same order.

**A subtle Hibernate detail:** at flush time Hibernate executes INSERTs before UPDATEs. In a transfer, inserting the new
`ACTIVE` allocation before the old one is updated to `TRANSFERRED` would violate the "one active allocation per tenant"
constraint, so the old allocation is explicitly `saveAndFlush`-ed first.

Why not optimistic locking alone for beds? It would also prevent double allocation (`@Version` is on `Bed` too), but
under contention it produces late failures after work has been done; pessimistic locks give a clean "first wins, the
rest get an immediate business answer" behaviour. Optimistic locking is used everywhere contention is rare.

**Proof:** `BedAllocationConcurrencyIT` fires 8 simultaneous reservations / walk-in check-ins for one bed against real
PostgreSQL: exactly one `201`, seven `409`, one row in the database, and no orphan invoices from the losers.

## 9. How does payment idempotency work?

* Clients send `Idempotency-Key` (8–100 chars, a UUID is recommended) with `POST /payments` and refunds.
* We compute a **fingerprint** = SHA-256 of the canonical request (invoice, amount scaled to 2 dp, method, external
  reference, paid-at).
* `PaymentProcessor.recordPayment` (one transaction):
  1. lock the invoice row (`FOR UPDATE`) — all payments of one invoice serialise here;
  2. look up `(organization_id, idempotency_key)` *under the lock* — a committed earlier attempt is now visible;
     same fingerprint → return it as a replay (`200`, `Idempotent-Replayed: true`); different fingerprint →
     `422 IDEMPOTENCY_KEY_REUSED`;
  3. otherwise validate (invoice payable, **amount ≤ balance**), update the invoice, insert the payment and flush.
* The remaining race — the same key used concurrently for two *different* invoices (no shared lock) — is caught by the
  unique index `uk_payments_idempotency`. Because a constraint violation poisons the transaction, `PaymentService`
  catches it *outside* the transaction and performs the replay lookup in a fresh read.
* The key lives on the payment row itself, so "payment exists" and "key consumed" are the same atomic fact — no
  separate store that could disagree after a crash.
* An optional unique `(organization, method, external_reference)` also stops the same UPI transaction id being
  recorded twice under different keys.

**Proof:** `PaymentIdempotencyIT` — 6 concurrent identical requests → one `201` + five `200` replays with the same id,
one row; 3 concurrent different payments that would overpay → one succeeds, others `422 OVERPAYMENT`.

## 10. How are transactions handled?

* Service methods are the transaction boundary (`@Transactional` on public service methods; read-only for queries).
  `spring.jpa.open-in-view=false`, so no lazy loading leaks into the web layer.
* Everything a business action changes commits together: entity changes, **audit log** (`AuditService` uses
  `Propagation.MANDATORY` — it refuses to run outside a transaction) and **notification outbox rows**
  (`NotificationService` is `MANDATORY` too, invoked by synchronous `@EventListener`s inside the publisher's
  transaction).
* Batch jobs process each item in its **own** transaction (`REQUIRES_NEW`): one bad allocation cannot abort a whole
  billing run, and partial progress is kept.
* Runtime exceptions roll back; the only deliberate exception is `AuthService.refresh` with
  `noRollbackFor = UnauthorizedException.class` so that refresh-token-reuse revocation persists.
* Isolation level is PostgreSQL's default **READ COMMITTED**; correctness for contended data comes from explicit row
  locks and constraints rather than raising isolation globally (which would increase serialization failures).
* Constraint violations and lock conflicts are mapped to `409` with stable codes; clients can safely retry `409
  CONCURRENT_MODIFICATION`.

## 11. How does the system scale?

* **Stateless app tier**: JWT auth, no HTTP sessions → add backend tasks behind the ALB (ECS service auto-scaling on
  CPU/request count). Scheduled jobs remain single-run via ShedLock; the notification dispatcher scales out safely
  with `FOR UPDATE SKIP LOCKED`.
* **Database**: indexed access paths for every list/filter (`idx_*` in V1), partial indexes for hot subsets
  (open invoices, active allocations, SLA watch list), bounded pagination (max 100), N+1 avoided with entity graphs and
  batch bed loading, aggregates in SQL. Reads that tolerate staleness (analytics) go to a read replica.
* **Caching** for the hottest repeated reads (property details, dashboards).
* **Contention is naturally partitioned**: locks are per bed / per invoice, so different beds never block each other.
* **Analytics scales separately** and caches results for 5 minutes.

## 12. What changes for 10× traffic?

Measure first (Prometheus: p95 latency, Hikari pool usage, DB CPU/IOPS, lock waits). Then, in order:

1. **App tier**: raise ECS desired/max count (stateless); tune Hikari pool so `tasks × pool ≤ DB max_connections`, or
   add **RDS Proxy** to multiplex connections.
2. **Database**: scale RDS vertically; route all read-only endpoints that tolerate replica lag (search pages, history,
   analytics) to replicas via a routing `DataSource`; add covering indexes found from `pg_stat_statements`.
3. **Caching**: cache more read models (room catalogue, tenant summaries) with event-driven eviction.
4. **Write path**: allocation contention is per bed, so it scales with the number of beds, not traffic; billing jobs
   batch per allocation and can be parallelised by property.
5. **Async**: move outbox delivery to SQS/SNS consumers; generate receipts asynchronously if PDF rendering becomes hot.
6. **Data growth**: partition `audit_logs` and `notifications` by month; archive old payments/invoices to cold storage.
7. **Edge**: WAF rate rules, CloudFront for any static frontend.
Beyond that (100×), split the monolith along the existing module/event boundaries (billing first).

## 13. How is security implemented? (checklist)

* Authentication: bcrypt (delegating encoder), timing-safe unknown-user path, account status and organization
  suspension checks, rate limiting (Redis per IP + nginx), RS256 JWT, rotating refresh tokens with reuse detection,
  revocation (logout, logout-all, deactivation, password change).
* Authorization: `@PreAuthorize` per endpoint + `AccessGuard` data scoping; cross-organization ids → `404` (no
  existence leak); unit-tested matrix (`AccessGuardTest`) and HTTP-level tests (`AccessControlIT`).
* Input: Bean Validation on all DTOs, sort-field whitelist, size limits (nginx `client_max_body_size`, DTO `@Size`),
  parameterised queries only (JPA/JDBC binding; the one dynamic SQL builder in `DashboardQueries` concatenates only
  constant fragments).
* Data protection: refresh tokens hashed, ID document numbers masked before storage, analytics role cannot read PII,
  passwords never logged or echoed, emails masked in email-stub logs.
* Transport/headers: TLS at ALB (ACM), HSTS/CSP/X-Frame-Options/nosniff/Referrer-Policy, CORS allow-list.
* Secrets: environment variables only; `.env` git-ignored; AWS Secrets Manager → ECS task secrets; CI uses OIDC (no
  stored AWS keys); images run as non-root; Trivy scans images in CI.
* Auditability: append-only audit log with actor, IP and correlation id for every state change.

## 14. Why Docker (and Compose, and Nginx)?

* Identical runtime from laptop to ECS; multi-stage builds keep JDK/Maven/build tools out of runtime images; non-root
  users; container-aware JVM flags (`MaxRAMPercentage`, `ExitOnOutOfMemoryError`).
* Compose brings the full topology (PostgreSQL with least-privilege roles, Redis with auth, both services, nginx) up
  with **one command**, with health-checked startup ordering (backend waits for healthy DB/Redis; analytics waits for
  the backend because the backend applies migrations).
* Nginx locally plays the ALB's role: single origin, path routing, request ids, per-IP limits, security headers and
  hiding actuator endpoints. In AWS the ALB (+WAF) replaces it.

## 15. Other likely questions

**Multi-tenancy model?** Shared database, shared schema, `organization_id` on every business row, enforced in one place
(`AccessGuard`/`AccessScope` → JPA Specifications). Next step would be PostgreSQL row-level security as a second net.

**Why soft delete only for some tables?** Master data that history refers to (users, properties, rooms, beds, tenants)
is soft-deleted; financial and operational records (invoices, payments, tickets) are never deleted — they are voided or
closed, which is what an auditor expects.

**Why snapshot rent on the allocation?** Room prices change; an existing resident's agreed rent must not. It also makes
every invoice reproducible.

**Proration formula?** `rent × remainingDaysInclusive / daysInMonth`, `HALF_UP` to 2 dp (`RentCalculator`, tested for
31/30/28/29-day months).

**How do jobs avoid running twice with 5 replicas?** ShedLock row in PostgreSQL (with DB time). And every job is
idempotent anyway (unique indexes, status checks, notification dedup keys), so even a double run is harmless.

**Event-driven inside a monolith?** Domain modules publish events (`BookingEvents`, `BillingEvents`, …); the
notification module listens. Modules don't depend on notifications, so swapping the in-process listener for an
outbox-to-SQS relay later doesn't touch domain code.

**What would you do differently in production?** Terraform for infrastructure, key rotation for JWT, a real payment
gateway and email provider, partitioning of append-only tables, OpenTelemetry tracing, and contract tests between the
backend's `analytics.*` views and the analytics service (today guarded by migrations + analytics repository tests).
