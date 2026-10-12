# StayDesk — PG management web app (frontend)

Mobile-first single-page app for the PG / co-living platform in this repository. It talks to the Spring Boot API
(`/api/v1/**`) and the FastAPI analytics service (`/analytics/**`) **on the same origin** — through the Vite dev proxy
locally and through the main nginx in production.

| | |
|---|---|
| Stack | Vite 6 · React 18 · TypeScript (strict) · React Router 6 · TanStack Query 5 · Tailwind CSS 3 · react-hook-form + zod · Recharts · lucide-react |
| Tests | Vitest + Testing Library (jsdom) |
| Node | 20.19+ (the Docker build uses `node:20-alpine`) |

## Scripts

```bash
npm ci
npm run dev        # http://127.0.0.1:55173 (strictPort), proxies /api and /analytics to http://localhost:58088
npm run build      # tsc -b && vite build  -> dist/
npm run preview    # serves dist/ on http://127.0.0.1:55174 with the same proxy
npm test           # vitest run
npm run lint       # eslint, zero warnings allowed
npm run typecheck  # tsc -b --noEmit
```

Override the API target with `PGMS_API_TARGET=http://host:port npm run dev`.

> **Why the proxy rewrites `Origin`.** The backend's Spring CORS filter answers `403 Invalid CORS request` to any
> request whose `Origin` is not in its allow-list (`APP_CORS_ORIGINS`). In production the SPA and the API share an
> origin, so the dev proxy rewrites `Origin` to the target to reproduce exactly that situation.

## Structure

```
src/
  api/            typed client (client.ts), error envelope (errors.ts), session/refresh (session.ts), DTOs (types.ts)
  auth/           AuthProvider (login/logout/change password), route guards (RequireAuth, RequireRole)
  components/
    layout/       AppShell (sidebar ≥ md, bottom tab bar + "More" sheet on phones), nav config per role
    ui/           Button, Field inputs, Card, Badge/StatusBadge, Modal (bottom sheet on phones), ConfirmDialog,
                  Toast, ResponsiveTable (table ≥ md, cards on phones), FilterPanel, Pagination, Tabs, Timeline,
                  KpiCard, StickyActions, Skeleton, Empty/Error states
  features/       one folder per domain: api.ts (TanStack Query hooks) + pages + feature components
    account/ audit/ auth/ billing/ bookings/ complaints/ dashboard/ maintenance/ notifications/
    portal/ properties/ rooms/ shared/ tenants/ users/
  lib/            formatting (INR / Asia/Kolkata), idempotency, roles, validation, URL-synced filters
  router.tsx      routes (lazy-loaded per page) with role gates
public/           manifest.webmanifest, icons, sw.js (app-shell only)
nginx/            default.conf + security-headers.conf for the production image
screenshots/      verification screenshots (375 px and 1280 px)
```

Adding a feature (e.g. notification preferences): create `src/features/<name>/` with an `api.ts` of query/mutation
hooks and its pages, add the route in `router.tsx` and, if it needs a menu entry, one line in
`components/layout/nav.ts` (roles + optional bottom-tab placement).

## Roles and screens

| Role | Home | Navigation |
|---|---|---|
| SUPER_ADMIN, PROPERTY_ADMIN | `/dashboard` | Dashboard, Bed board, Tenants, Billing, Payments, Bookings, Maintenance, Complaints, Properties, Notifications, **Users**, **Audit log** |
| MANAGER | `/dashboard` | same as admins without Users / Audit log; no void / refunds / property edits |
| STAFF | `/work` | Work queue, Bed board (read-only), Tenants (read-only), Maintenance, Complaints, Properties, Notifications |
| TENANT | `/me` | Home, Bills (invoices, payments, PDF receipts), Maintenance, Complaints, Notifications, My profile |

Actions are only offered when the backend would allow them (e.g. maintenance status buttons are derived from the
backend state machine **and** the per-role rules in `MaintenanceService`; tenants never see the internal-note
toggle; staff only get start/resolve on tickets assigned to them). The backend remains the authority.

## Auth & token storage

* **Access token (15 min JWT): memory only.** It never touches `localStorage`/`sessionStorage`, so an XSS payload
  cannot simply read it from storage, and it disappears with the tab.
* **Refresh token (opaque, 14 days, rotated on every use): `localStorage` key `pgms.refreshToken`.**
  This keeps users signed in across reloads, new tabs and installed-PWA launches.

  *Trade-off:* a script running in the page (XSS) could read the refresh token. The ideal is an `HttpOnly`,
  `Secure`, `SameSite=Strict` cookie scoped to `/api/v1/auth`, but the current backend accepts and returns refresh
  tokens only in JSON bodies. Mitigations in place: a strict CSP served with the SPA (`script-src 'self'`, no inline
  scripts), no third-party scripts, React escaping, and the backend's **rotation with reuse detection** — if a
  stolen token is used after the legitimate client already rotated it (or vice versa) the whole token family is
  revoked and all of the user's access tokens are invalidated. Logout revokes the family server-side
  (`POST /auth/logout`). Moving to an `HttpOnly` cookie later only changes `src/api/session.ts`.

### Refresh algorithm (`src/api/session.ts`)

1. Before a request, if the in-memory access token is missing or expires within 30 s, refresh first.
2. On a `401` with code `UNAUTHORIZED` (token problem) the request is retried **once** after a refresh. Other 401
   codes (e.g. `INVALID_CREDENTIALS` from change-password) are business errors and never trigger a refresh.
3. **Single flight in a tab:** concurrent callers share one in-flight refresh promise.
4. **Serialized across tabs:** the refresh runs inside a Web Lock (`navigator.locks`, `pgms-auth-refresh`). Inside
   the lock the refresh token is **re-read from storage**, so a token another tab has just rotated is never sent
   again. If another tab rotated it, this tab first waits (≤ 1 s) for that tab's `BroadcastChannel` message carrying
   the new access token and uses it without calling the server.
5. A refresh whose response is lost (network error) may already have rotated the token server-side, so the token is
   **discarded** (the user signs in again) instead of being re-sent and tripping reuse detection. `429`/`502-504`
   keep the token (the request did not reach the rotation logic). While offline no refresh is attempted.
6. Rejected refresh (`400/401`) → session cleared, every tab is signed out (BroadcastChannel + `storage` event),
   the login page explains why.

Change password and "sign out of all devices" clear the local session because the backend revokes all sessions.

## API layer

* `ApiClient` (`src/api/client.ts`) — typed `get/post/put/patch/delete/blob`, relative URLs, JSON in/out.
* Errors are normalised to `ApiError {status, code, message, fieldErrors, correlationId}` from the shared envelope
  (falls back to `X-Correlation-Id` and friendly messages for non-JSON gateway errors; network failures become
  `NETWORK_ERROR`). Toasts show the message **and the correlation id** (click to copy). `fieldErrors` are mapped onto
  react-hook-form fields (`emergencyContacts[0].phone` → `emergencyContacts.0.phone`), focusing the first one.
* Pagination envelope `{content, page, size, totalElements, totalPages, last}` is typed as `Page<T>`; list filters and
  page live in the URL (shareable, back-button friendly).
* TanStack Query: 4xx are never retried, transient errors twice; mutations invalidate the affected domains.

## Payments & idempotency

`IdempotentSubmission` (`src/lib/idempotency.ts`) owns the `Idempotency-Key` of one "Record payment" form:

* the key is generated (`crypto.randomUUID()`) on the first submit and **reused for retries of the same body**;
* if the outcome is ambiguous (network error, 5xx, 409, 429) the form keeps the key and offers **Retry safely**;
* editing the amount/method/reference makes it a new submission (new key), avoiding `422 IDEMPOTENCY_KEY_REUSED`;
* `201` → "Payment recorded"; `200` + `Idempotent-Replayed: true` → "Payment already recorded — nothing was charged
  twice" with the original receipt.

Refunds (admins) use the same mechanism. Receipts are fetched with the bearer token as a `Blob` and opened via an
object URL (a tab is opened synchronously on click so pop-up blockers allow it; falls back to a download).

## Mobile & PWA

* Phones: bottom tab bar (4 role-specific tabs + "More" sheet), tables collapse into cards, filters collapse behind a
  "Filters" button, primary form actions are sticky above the tab bar, dialogs are bottom sheets, all targets ≥ 44 px,
  no horizontal page scroll at 360 px. ≥ 768 px: sidebar + tables.
* Light/dark follows `prefers-color-scheme`; charts use a colour-blind-checked palette stepped for each mode.
* Money: INR with Indian grouping (`₹12,34,567.5`, compact `₹6.1L`); instants shown in Asia/Kolkata; business dates
  (`YYYY-MM-DD`) are never timezone-shifted.
* Installable: `manifest.webmanifest`, 192/512/maskable/Apple icons, `theme-color` per scheme. `public/sw.js` caches
  only the app shell and hashed assets — **`/api/**` and `/analytics/**` always go to the network.**
* Analytics degrade gracefully: each chart card shows "Analytics is temporarily unavailable" with a retry while the
  KPIs (served by the core API) keep working. Every chart has a table view.

## Docker

```bash
docker build -t pgms-frontend:local frontend
docker run --rm -p 127.0.0.1:58090:80 pgms-frontend:local   # SPA only; /api is served by the edge nginx
```

Multi-stage: `node:20-alpine` (`npm ci && npm run build`) → `nginx:1.27-alpine` running as the unprivileged `nginx`
user (uid 101) on port 80, SPA history fallback, gzip, `immutable` 1-year caching for `/assets/*`, `no-cache` for
`index.html`, `sw.js` and the manifest, CSP + security headers, `/healthz` for health checks.

### Wiring into the stack (same origin as `/api` and `/analytics`)

`docker-compose.yml` — add the service and make nginx depend on it:

```yaml
  frontend:
    build: ./frontend
    image: pgms-frontend:local
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "wget -q -O /dev/null http://127.0.0.1/healthz || exit 1"]
      interval: 15s
      timeout: 3s
      retries: 5

  nginx:
    depends_on:
      - backend
      - analytics
      - frontend
```

`infra/nginx/nginx.conf` — add an upstream and replace the JSON catch-all `location /`:

```nginx
    upstream frontend {
        server frontend:80;
        keepalive 16;
    }

    # inside server { ... }, after the /api/, /analytics/, swagger, /health and /actuator locations:
    location / {
        proxy_pass http://frontend;
    }
```

No other change is needed: the SPA uses relative URLs, so the backend sees same-origin requests (no CORS).

## Tests

`npm test` — 50 tests:

* `src/api/client.test.ts` — single-flight refresh under concurrent 401s, no refresh-token reuse across two
  simulated tabs (shared storage + lock + broadcast), broadcast hand-off, expired/rejected refresh, network failure
  drops the token, 429 keeps it, offline, business 401 does not refresh, error-envelope parsing, header fallback,
  network errors, field-error path mapping, query building.
* `src/lib/idempotency.test.ts` — key reuse for identical bodies, new key on change/reset, retry after a network
  failure sends the same `Idempotency-Key` and reports the `Idempotent-Replayed` response.
* `src/lib/format.test.ts` — INR formatting, IST conversion, business dates, month maths.
* Components: login form validation & error display, bed board rendering/filtering/interaction, role-based
  navigation and route gating in the real `AppShell`.
