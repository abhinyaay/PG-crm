# Operations runbook

How to run, watch, back up and fix the PG Management Platform when it runs as the single-host Docker Compose
stack (`docker compose -p pgms`). The AWS/ECS deployment is covered in [`AWS_DEPLOYMENT.md`](AWS_DEPLOYMENT.md);
most of the incident playbooks below apply to it as well.

> **Shared Docker host.** Other projects run on the same Docker engine. Always pass `-p pgms` (every `make` target
> does), never run `docker system prune`, `docker volume prune` or `docker compose down -v`, and never stop
> containers that are not named `pgms-*`.

Contents: [Quick reference](#quick-reference) · [Start / stop](#start--stop) · [Health checks](#health-checks) ·
[Logs](#logs) · [Dashboards and alerts](#dashboards-and-alerts) · [Backups and restore](#backups-and-restore) ·
[Rotating JWT keys](#rotating-jwt-keys) · [Incidents](#incident-playbooks) · [Load testing](#load-testing) ·
[Configuration reference](#configuration-reference)

---

## Quick reference

| What | Where (default host ports; this machine overrides some in `.env`) |
|---|---|
| Web app, API, analytics (via nginx) | `http://localhost:${NGINX_HOST_PORT:-80}` (`/api/v1/...`, `/analytics/...`, `/swagger-ui.html`) |
| Public readiness probe | `http://localhost:${NGINX_HOST_PORT}/health` |
| Backend direct (debug only) | `http://127.0.0.1:${BACKEND_HOST_PORT:-8080}` |
| Mailpit (all outgoing email) | `http://127.0.0.1:${MAILPIT_UI_HOST_PORT:-8025}` |
| Prometheus | `http://127.0.0.1:${PROMETHEUS_HOST_PORT:-58301}` |
| Grafana | `http://127.0.0.1:${GRAFANA_HOST_PORT:-58302}` → dashboard **PGMS / PGMS Backend** (`/d/pgms-backend/pgms-backend`) |

| Task | Command |
|---|---|
| Everything at a glance | `make help` |
| Start / stop app | `make up` / `make down` |
| Status / health | `make ps` / `make health` |
| Logs | `make logs S=backend` |
| Observability on / off | `make observability-up` / `make observability-down` |
| Backup / verify restore | `make backup` / `make restore-check` |
| Tests | `make smoke`, `make e2e`, `make test-backend`, `make test-analytics`, `make test-frontend` |
| Load test | `make loadtest` |
| New JWT key pair | `make keys` |

---

## Start / stop

```bash
cp .env.example .env          # first time only; replace every change-me value
make up                       # docker compose -p pgms up -d --build, then waits for nginx /health
make observability-up         # optional: Prometheus + Grafana (generates GRAFANA_ADMIN_PASSWORD in .env if missing)
make ps
```

* `make down` stops and removes the pgms containers and network, **including** Prometheus/Grafana. Named volumes
  (`pgms_pgdata`, `pgms_prometheus-data`, `pgms_grafana-data`) are kept, so data survives. Deleting `pgms_pgdata`
  destroys the database - take a backup first and only do it deliberately
  (`docker volume rm pgms_pgdata`, never a prune).
* Restart one service without touching the others: `docker compose -p pgms restart backend`.
* Apply a changed environment variable to one service: `docker compose -p pgms up -d --no-deps backend`
  (`--no-deps` keeps compose from recreating postgres/redis).
* After editing `infra/nginx/nginx.conf`: `docker compose -p pgms up -d --force-recreate --no-deps nginx`
  (single-file bind mounts keep pointing at the old inode).
* Observability is an override file and never restarts application containers:
  `docker compose -p pgms -f docker-compose.yml -f docker-compose.observability.yml up -d --no-deps prometheus grafana`.

Start order (enforced by health checks): postgres + redis → backend (runs Flyway) → analytics (needs the analytics
views from migration V2 and the backend JWKS) → frontend → nginx.

---

## Health checks

`make health` (`scripts/health-check.sh`) is read-only and checks, in order: container state/health of every
`pgms-*` container, nginx `/health` from the host, that `/actuator/*` is **not** reachable at the edge, backend
readiness/liveness, analytics readiness, Mailpit, Prometheus and Grafana from inside the network, PostgreSQL
(`pg_isready`, connection count), the notification outbox (overdue and failed rows), Redis (`PING`, memory) and the
Prometheus scrape targets. Exit code 1 means a critical component is down.

| Probe | Meaning |
|---|---|
| `GET /health` (nginx → backend `/actuator/health/readiness`) | Backend can serve traffic. Checks **PostgreSQL only** - Redis is optional by design. |
| backend `/actuator/health/liveness` | JVM is alive (restart if this fails, not readiness) |
| analytics `/health/live`, `/health/ready` | Analytics process up / can reach its DB view schema |
| backend `/actuator/prometheus` | Metrics; reachable only inside the docker network (nginx returns 404) |

Manual probes from inside the network:

```bash
docker run --rm --network pgms_default curlimages/curl:8.10.1 -s http://backend:8080/actuator/health/readiness
docker run --rm --network pgms_default curlimages/curl:8.10.1 -s http://analytics:8000/health/ready
docker exec pgms-postgres-1 sh -c 'pg_isready -U "$POSTGRES_USER" -d pgms'
docker exec pgms-redis-1 sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping'
```

---

## Logs

All services log to stdout; Docker keeps them per container (`docker logs`, json-file driver).

| Service | Command | Format |
|---|---|---|
| backend | `make logs S=backend` | JSON (logstash encoder). Every line has `correlationId`, plus `userId`/`orgId` when authenticated. One `http.access` line per request: `"GET /api/v1/dashboard -> 200 (379 ms)"` |
| analytics | `make logs S=analytics` | uvicorn / app logs |
| nginx | `make logs S=nginx` | JSON access log with `request_id` (= correlation id passed upstream), `status`, `request_time`, `upstream_time` |
| postgres, redis, mailpit | `make logs S=postgres` ... | plain text |

Follow one request end to end: take the `X-Correlation-Id` response header (or the `correlationId` in an error body)
and search for it:

```bash
docker logs pgms-backend-1 2>&1 | grep '"correlationId":"<id>"' | jq -r '[."@timestamp", .level, .message] | @tsv'
docker logs pgms-nginx-1 2>&1 | grep '<id>'
```

Useful filters: errors only - `docker logs --since 30m pgms-backend-1 2>&1 | jq -c 'select(.level=="ERROR")'`;
slow requests - `... | jq -r 'select(.logger_name=="http.access") | .message' | awk '{gsub(/[()]/,"",$(NF-1)); if ($(NF-1)+0 > 1000) print}'`.

---

## Dashboards and alerts

**Grafana** - `http://127.0.0.1:58302`, user `admin`, password = `GRAFANA_ADMIN_PASSWORD` in `.env`
(generated by `scripts/observability-env.sh`; never committed). Anonymous access and sign-up are disabled.
Dashboards are provisioned from `infra/observability/grafana/dashboards/*.json` (UI edits are not saved - edit the
JSON and Grafana reloads it within 30 s).

**PGMS Backend** dashboard (`uid: pgms-backend`), variables *Application / Instance / Endpoint*:

| Row | Panels |
|---|---|
| Overview | Backend up, request rate, 5xx ratio, 4xx ratio, average latency, heap used %, DB pool busy % |
| HTTP traffic by endpoint | Request rate by endpoint, responses by status (409 and 429 coloured separately), errors by endpoint, error ratio (5xx / 4xx / 409+429), average and max latency by endpoint, p95 by endpoint*, endpoint summary table (requests, 5xx ratio, avg latency) |
| Business events | Bookings reserved/expired, check-ins, check-outs, transfers, payments recorded, refunds, amount collected (INR) over the selected range; event bars per 5 min; payments by method; amount collected and average payment |
| JVM | Heap used/committed/max, heap by pool, non-heap, GC pause share, GC pauses + longest pause, CPU, threads, thread states, WARN/ERROR log events |
| Database pool & Redis | Hikari active/idle/pending/max, pool utilisation gauge, connection acquire/usage time and timeouts, Redis command latency, slowest repository methods |

\* **p95 needs histogram buckets.** The backend currently publishes `http_server_requests_seconds` as a summary
(count/sum/max only), so the p95 panel and the `PgmsHighP95Latency` alert stay empty. To enable them, the backend
owners can add `management.metrics.distribution.percentiles-histogram.http.server.requests: true` to
`backend/src/main/resources/application.yml`. Until then use the average and max latency panels.

Business counters are created lazily by Micrometer: `pgms_allocations_transferred_total` and
`pgms_refunds_issued_total` only exist after the first transfer/refund since the backend started, and Prometheus'
`increase()` does not count the very first event of a brand-new series. The stats show 0 rather than "No data" for
missing series. Counters reset on backend restart (`increase()` handles that).

**Prometheus** - `http://127.0.0.1:58301` (`/targets`, `/alerts`, `/graph`). Scrapes `backend:8080/actuator/prometheus`
and itself every 15 s, keeps 15 days (`PROMETHEUS_RETENTION`). Alert rules: `infra/observability/prometheus/rules/pgms-alerts.yml`

| Alert | Condition |
|---|---|
| `PgmsBackendDown` (critical) | scrape target down for 1 m |
| `PgmsHigh5xxRate` (critical) | > 5% of API requests are 5xx for 5 m (with > 0.1 req/s) |
| `PgmsHighP95Latency` (warning) | p95 > 1 s for 10 m (needs histogram buckets, see above) |
| `PgmsHighAvgLatency` (warning) | average API latency > 500 ms for 10 m |
| `PgmsDbPoolSaturation` (warning) | Hikari active/max > 80% for 5 m |
| `PgmsDbPoolPendingThreads` (warning) | threads waiting for a connection for 2 m |
| `PgmsDbConnectionTimeouts` (critical) | any connection-acquire timeout in 5 m |
| `PgmsJvmHeapHigh` (warning) | heap > 90% of max for 10 m |
| `PgmsAuthRateLimited` (info) | > 20 auth requests answered 429 in 5 m |

There is no Alertmanager locally - alerts are visible in Prometheus `/alerts`. After editing rules:
`make observability-check` then `curl -X POST http://127.0.0.1:58301/-/reload`.
The analytics service has no `/metrics` endpoint yet (a commented scrape job is ready in `prometheus.yml`).

---

## Backups and restore

### Taking a backup

```bash
make backup                                  # = ./scripts/backup-db.sh
BACKUP_RETENTION_DAYS=30 ./scripts/backup-db.sh
```

* `pg_dump --format=custom` of database `pgms`, run **inside** `pgms-postgres-1` as the admin user
  (`POSTGRES_ADMIN_USER`); the password is never read from `.env` or put on a command line.
* Output: `backups/pgms_<UTC timestamp>.dump` + `.sha256`, directory mode 700, files 600. `backups/` must be
  git-ignored.
* The archive is verified with `pg_restore --list` before it is kept; a failed dump never replaces a good one.
* Retention: dumps older than `BACKUP_RETENTION_DAYS` (default 14) are pruned after a successful backup
  (`--no-prune` to skip).
* Roles (`pgms_app`, `analytics_ro`) are cluster-wide and not in the dump; `infra/postgres/init/01-roles.sh` creates
  them on a fresh volume.
* Schedule it (example, nightly 02:30): `30 2 * * * cd /path/to/PG && ./scripts/backup-db.sh >> backups/backup.log 2>&1`.
  Copy `backups/` off the host - a backup on the same disk is not a backup.

### Verifying a backup (do this regularly)

```bash
make restore-check                           # latest dump -> pgms_restore_check, compare row counts, drop it
./scripts/restore-db.sh                      # same but keeps pgms_restore_check for inspection
./scripts/restore-db.sh backups/pgms_20261012T212749Z.dump --target pgms_yesterday
```

The restore script creates a **new** database, restores into it with `--exit-on-error`, then prints exact row counts
per table next to the live database (differences are expected for rows written since the backup and are marked
`(changed since backup)`; tables missing from the restore fail the check).

### Disaster recovery: overwrite the live database

Only when the live data is lost or corrupted:

```bash
docker compose -p pgms stop backend analytics            # nothing may be connected to pgms
./scripts/restore-db.sh backups/pgms_<stamp>.dump --target pgms --force-overwrite
#   -> refuses while other sessions are connected
#   -> asks you to type "pgms" (or RESTORE_CONFIRM=pgms for automation)
#   -> takes a safety backup of the current pgms first
#   -> pg_restore --clean --if-exists --single-transaction (all-or-nothing)
docker compose -p pgms start backend analytics
make health && make smoke
```

If the dump is from an older release, the backend applies the newer Flyway migrations on start. Refresh tokens and
sessions in the dump are restored as they were; consider forcing re-login by rotating the JWT keys.

---

## Rotating JWT keys

Access tokens are RS256 JWTs (15 min) signed with `APP_JWT_PRIVATE_KEY`; the analytics service verifies them via the
backend JWKS (`/api/v1/auth/jwks`, cached 300 s, refetched on an unknown `kid`). Refresh tokens are opaque database
rows and survive a key rotation.

1. `make keys` → writes `run-output/jwt-keys-<stamp>.env` (mode 600; does not touch `.env`).
2. Replace the three `APP_JWT_*` lines in `.env` with the file's contents (keep exactly one of each), delete the file.
3. `docker compose -p pgms up -d --no-deps backend` (recreates only the backend with the new key).
4. `docker compose -p pgms restart analytics` so it drops the cached JWKS immediately - the key id stays
   `pgms-key-1` because `APP_JWT_KEY_ID` is not passed through `docker-compose.yml`, so analytics would otherwise
   keep the old key for up to 5 minutes.
5. Verify: `make smoke` (login, refresh, analytics calls).

Effect: access tokens signed with the old key are rejected (401); clients transparently get a new one with their
refresh token. To force everyone to log in again (suspected key **and** token compromise), also revoke refresh
tokens: `docker exec pgms-postgres-1 psql -U pgms_admin -d pgms -c "update refresh_tokens set revoked_at = now(), revoked_reason = 'KEY_ROTATION' where revoked_at is null and expires_at > now()"`.

With ephemeral keys (`APP_JWT_ALLOW_EPHEMERAL_KEYS=true`, no keys in `.env`) every backend restart is a rotation, and
more than one backend instance cannot work - use persistent keys anywhere beyond a laptop.

---

## Incident playbooks

Start every incident with `make health`, the Grafana overview row, and `make logs S=backend` filtered to errors.

### Backend down

Symptoms: nginx `/health` 502/504, `PgmsBackendDown`, every `/api` call fails.

1. `make ps` - restarting in a loop? `docker logs --tail 200 pgms-backend-1`.
2. Common causes: PostgreSQL unreachable at boot (see *DB down*), failed Flyway migration (`FlywayException` in the
   log - fix the migration, never edit an applied one), missing env var / invalid JWT PEM, OOM (`docker inspect
   pgms-backend-1 --format '{{.State.OOMKilled}}'`).
3. `docker compose -p pgms restart backend`; nginx recovers on its own once the upstream is healthy.

### Database (PostgreSQL) down

Symptoms: readiness fails → nginx `/health` 503, API 500/503, `hikaricp_connections_timeout_total` rising,
analytics `/health/ready` fails.

1. `docker logs --tail 100 pgms-postgres-1`, `docker exec pgms-postgres-1 pg_isready`.
2. Disk full? `docker system df -v | grep pgms_pgdata`, `df -h`. Free space; do **not** prune other projects' volumes.
3. Too many connections? `select usename, state, count(*) from pg_stat_activity group by 1,2;` (max_connections 100;
   backend pool max 20 per instance).
4. `docker compose -p pgms restart postgres`, then `docker compose -p pgms restart backend analytics` if they don't
   recover by themselves (Hikari reconnects automatically in most cases).
5. Corruption / lost volume → [Disaster recovery](#disaster-recovery-overwrite-the-live-database).

### DB pool saturation

`PgmsDbPoolSaturation` / pending threads / slow requests everywhere. Look at *Repository calls* and *Connection
usage time* panels for the slow query, `select pid, now()-query_start as age, state, left(query,120) from
pg_stat_activity where datname='pgms' order by age desc;` in psql, and `pg_cancel_backend(pid)` for runaway queries.
Scaling the pool beyond 20 rarely helps a single PostgreSQL; fix the query or add an index.

### Redis down

The application **fails open** by design: readiness stays green and requests keep working, but

* auth rate limiting is skipped (log: `Redis unavailable - auth rate limiting skipped (fail-open)`),
* token revocation (logout) checks cannot run, so logged-out access tokens stay valid until they expire (≤ 15 min),
* caches (dashboard 60 s, properties 10 m) miss → more DB load and slower dashboard,
* analytics caching is bypassed.

Fix: `docker logs pgms-redis-1`; `docker compose -p pgms restart redis`. Redis runs without persistence
(`--appendonly no`, 256 MB `volatile-lru`), so a restart clears rate-limit counters, revocations and caches - that is
expected. If revocation matters (suspected stolen token during the outage), rotate the JWT keys.

### Analytics down

Symptoms: `/analytics/*` returns 502/503 from nginx, analytics charts in the web app fail; the core API is unaffected.

1. `docker logs --tail 100 pgms-analytics-1`.
2. 503 `SERVICE_UNAVAILABLE` → it cannot reach PostgreSQL (`analytics_ro`) or the backend JWKS. Check
   `docker run --rm --network pgms_default curlimages/curl:8.10.1 -s http://backend:8080/api/v1/auth/jwks`.
3. 401 for every request right after a key rotation → `docker compose -p pgms restart analytics`.
4. Missing view / permission errors → the backend did not apply migration V2 (analytics views + grants); check
   `flyway_schema_history`.
5. `docker compose -p pgms restart analytics`.

### Rate-limited users (HTTP 429)

Two layers limit `POST /api/v1/auth/login` and `/refresh` **per client IP**: nginx (`20r/m`, burst 20) and the
backend (20 per 1-minute window in Redis, key `ratelimit:auth:<ip>:<window>`). All other `/api/` traffic is limited
by nginx to 20 r/s per IP (burst 40). Password-reset endpoints have their own limits (3 per identifier, 10 per IP per
15 min).

* Users behind one office NAT share an IP - a burst of logins at 9 am can hit the limit. The response carries
  `Retry-After`; waiting one minute fixes it.
* Who is limited: `docker logs --since 15m pgms-backend-1 2>&1 | grep 'Auth rate limit exceeded' | grep -o 'ip=[^"]*' | sort | uniq -c`
  and nginx lines with `"status":429`.
* Clear one IP's backend counter immediately (nginx's in-memory counter clears itself within a minute):
  `docker exec pgms-redis-1 sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning --scan --pattern "ratelimit:auth:<ip>:*" | xargs -r redis-cli -a "$REDIS_PASSWORD" --no-auth-warning del'`
* Tune with `APP_AUTH_RATE_LIMIT` (backend; must be passed through `docker-compose.yml`) and the `limit_req_zone`
  in `infra/nginx/nginx.conf`. A sudden spike from one IP (`PgmsAuthRateLimited`) is more likely credential
  stuffing than real users - keep the limit and investigate.

### Stuck notifications

Notifications are an outbox table (`notifications`): rows are created `PENDING`, the dispatcher (every 30 s, every
backend replica, `FOR UPDATE SKIP LOCKED`) sends due rows, retries with exponential backoff (1, 2, 4, 8 min ...) and
marks them `FAILED` after 5 attempts. `make health` warns about overdue `PENDING` rows and recent `FAILED` rows.

```sql
-- docker exec -it pgms-postgres-1 psql -U pgms_admin -d pgms
select status, channel, count(*), min(next_attempt_at) from notifications group by 1, 2;
select id, channel, type, attempts, next_attempt_at, last_error from notifications
 where status in ('PENDING', 'FAILED') order by created_at desc limit 20;
```

* **Nothing is sent, rows stay PENDING with old `next_attempt_at`** → dispatcher not running: backend down, or
  scheduler threads stuck (see `tasks_scheduled_execution_*` metrics / thread dump `docker exec pgms-backend-1 jcmd 1 Thread.print`).
  Restart the backend.
* **Rows go FAILED with `last_error`** → provider problem. Email uses `APP_NOTIFICATIONS_EMAIL_PROVIDER`
  (`log` = only logged, `smtp` = real send; locally SMTP should point at `mailpit:1025` - check the Mailpit UI);
  SMS uses `APP_NOTIFICATIONS_SMS_PROVIDER` (`log`, `twilio`, `msg91`) and respects quiet hours for non-urgent
  messages. Fix the provider config, then requeue only what is still relevant:
  ```sql
  update notifications set status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error = null
   where status = 'FAILED' and created_at > now() - interval '1 day' and channel = 'EMAIL';
  ```
  Delivery is at-least-once; requeueing can duplicate a message that actually went out.
* `IN_APP` notifications are not dispatched externally - they are read from the inbox API.

---

## Load testing

`make loadtest` runs `scripts/loadtest/pgms-load.js` in the `grafana/k6:0.54.0` image on the `pgms_default`
network (no local k6), against nginx (`http://nginx`) by default. Output: console + `run-output/loadtest/<stamp>-summary.json`.

* **read_mix** - constant arrival rate (`RATE`, default 6/s, for `DURATION`, default 35 s, ≤ 6 VUs): dashboard 30%,
  available-beds search 40%, tenants list 30%. A single login in `setup()` is shared by all VUs because the auth
  endpoints allow ~20 logins/min per IP and all k6 traffic comes from one container IP. Keep `RATE` below 20, the
  nginx per-IP API limit, or use `BASE_URL=http://backend:8080` to bypass nginx.
* **contention** - after the read mix, 8 VUs reserve the **same** available bed at the same instant for 8 freshly
  created tenants. Expected and enforced by thresholds: exactly one `201`, seven `409`.
* Thresholds: `http_req_failed < 1%` (the contention 409s are declared expected), read p95 < 1.5 s / p99 < 3 s,
  beds and tenants p95 < 800 ms, dashboard p95 < 2.5 s, checks > 99%, `reserve_created == 1`,
  `reserve_conflict == 7`, `reserve_unexpected == 0`. Exit code 99 = a threshold failed.
* Side effects: 8 tenants named `Loadtest <run> #n`; teardown cancels the winning reservation and soft-deletes them.

### Results - 2026-10-12 21:31 UTC, local Docker Desktop (shared, busy host), via nginx

| Metric | Value |
|---|---|
| Duration / VUs | 41 s wall clock, max 8 VUs (read mix ≤ 6, contention 8) |
| Requests | 247 HTTP requests (6.3 req/s), 219 iterations, 0 failed (0.00%) |
| Read mix latency | avg 23.5 ms, median 19.8 ms, p95 46.9 ms, p99 67.0 ms, max 288 ms |
| Beds search | p95 45.5 ms |
| Tenants list | p95 59.1 ms |
| Dashboard | p95 27.3 ms (served from the 60 s Redis cache; a cold dashboard took ~0.4–0.9 s in the access log) |
| Bed contention (8 concurrent reservations, same bed) | **1 × 201, 7 × 409, 0 unexpected**; reservation latency avg 232 ms, max 251 ms |
| Checks / thresholds | 430/430 checks passed; all thresholds passed |

This run proves the harness and the double-booking guard; it is far below capacity. For a capacity test, run with
`BASE_URL=http://backend:8080 RATE=50 DURATION=5m` on an otherwise idle host and watch the Grafana JVM and Hikari rows.

---

## Configuration reference

Variables used by the operations kit (all optional unless noted; put them in `.env`, never commit real values):

| Variable | Default | Used by |
|---|---|---|
| `GRAFANA_ADMIN_PASSWORD` | **required** for Grafana (no default); `make observability-up` generates one | docker-compose.observability.yml |
| `GRAFANA_ADMIN_USER` | `admin` | docker-compose.observability.yml |
| `PROMETHEUS_HOST_PORT` / `GRAFANA_HOST_PORT` | `58301` / `58302` (bound to 127.0.0.1) | docker-compose.observability.yml |
| `PROMETHEUS_RETENTION` | `15d` | docker-compose.observability.yml |
| `BACKUP_RETENTION_DAYS` | `14` | scripts/backup-db.sh |
| `BACKUP_DIR` | `./backups` | backup/restore scripts |
| `PG_CONTAINER` | `pgms-postgres-1` | backup/restore scripts |
| `RATE`, `DURATION`, `BASE_URL`, `CONTENTION_VUS` | `6`, `35s`, `http://nginx`, `8` | scripts/loadtest/run.sh |
| `LOADTEST_PASSWORD` | falls back to `APP_DEMO_PASSWORD` | scripts/loadtest/run.sh |

Files: `docker-compose.observability.yml`, `infra/observability/` (Prometheus config + rules, Grafana provisioning +
dashboards), `scripts/{health-check,backup-db,restore-db,observability-env}.sh`, `scripts/loadtest/`, `Makefile`,
`.github/dependabot.yml`.
