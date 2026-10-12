# Spec: Biometric entry / exit (optional module)

Status: approved for implementation. Package `com.pgms.access`, Flyway `V7__access_control.sql`.

## Goals

* **Optional per property** — off by default; an owner turns it on in property settings.
* **Entry is always verified** — only a biometric device (fingerprint / face / card) can record an entry.
  Staff may record a *manual override entry* (e.g. device broken), which requires a reason and is audited.
* **Exit is the owner's choice per property** — `DEVICE`, `BUTTON` (resident taps "I'm leaving" in the app,
  or staff taps it for them) or `DEVICE_OR_BUTTON`.
* **Entry limit is configurable by the owner** — "how many times can a person enter per day":
  property default `maxEntriesPerDay` (null = unlimited) plus optional per-resident override.
* **Works with real hardware both on a local network and when the site is published on the internet.**
* **Privacy** — fingerprint templates never leave the device. The platform stores only the device-side user id
  (PIN) ↔ resident mapping and the access events (India DPDP Act friendly: data minimisation).

## Data model (V7)

| Table | Key columns |
|---|---|
| `access_settings` | `property_id` PK/FK, `enabled` bool default false, `exit_mode` (`DEVICE`, `BUTTON`, `DEVICE_OR_BUTTON`), `max_entries_per_day` int null (≥1), `track_staff` bool default false, `device_offline_minutes` int default 15, audit cols, version |
| `access_devices` | id, organization_id, property_id, name, `vendor` (`ZKTECO_ADMS`, `ESSL_ADMS`, `GENERIC_WEBHOOK`), `serial_number` (unique among non-deleted), `direction` (`ENTRY`, `EXIT`, `BOTH`), `api_key_hash` (SHA-256, for webhook/bridge auth), `status` (`ACTIVE`, `DISABLED`), `last_seen_at`, `last_ip`, audit cols, deleted_at |
| `access_credentials` | id, property_id, `device_user_id` (the PIN typed when enrolling on the device), `tenant_id` or `user_id` (staff), `credential_type` (`FINGERPRINT`, `FACE`, `CARD`), `status` (`ACTIVE`, `REVOKED`), enrolled_at; unique (`property_id`, `device_user_id`) where ACTIVE |
| `access_member_limits` | `tenant_id` PK, `max_entries_per_day` int null (null = unlimited, overrides property default) |
| `access_events` | id, organization_id, property_id, device_id null, tenant_id null, user_id null, device_user_id null, `direction` (`ENTRY`, `EXIT`), `method` (`FINGERPRINT`, `FACE`, `CARD`, `BUTTON`, `MANUAL_OVERRIDE`), `occurred_at`, `received_at`, `result` (`ALLOWED`, `DENIED_LIMIT_EXCEEDED`, `DENIED_NOT_RESIDENT`, `DENIED_DISABLED`, `UNKNOWN_CREDENTIAL`, `IGNORED_EXIT_MODE`), reason, recorded_by; **unique (`device_id`, `device_user_id`, `occurred_at`, `direction`)** so devices that re-send logs are idempotent |

`access_presence` can be a view or a cached column: last ALLOWED event per resident → `IN` / `OUT`.

## Rules

1. Module disabled for the property → device events are stored with `DENIED_DISABLED` (so nothing is lost) and no
   alerts; tenant "exit" button hidden.
2. **Entry**: only from a device with direction `ENTRY`/`BOTH`, or `MANUAL_OVERRIDE` by MANAGER/PROPERTY_ADMIN with a
   reason. The person must map to an ACTIVE credential of an `ACTIVE`/`ON_NOTICE` resident of that property (or an
   assigned staff user when `track_staff`), else `DENIED_NOT_RESIDENT` / `UNKNOWN_CREDENTIAL`.
3. **Entry limit**: count ALLOWED entries of that resident for the current business day (Asia/Kolkata). If
   `count >= limit` → `DENIED_LIMIT_EXCEEDED`, notify the property's managers (in-app + email; SMS optional) and the
   resident (in-app). Limit = per-resident override if present, else property default, else unlimited.
4. **Exit**: `DEVICE` → only device events; `BUTTON` → only `POST /access/exit` (resident or staff on their behalf);
   device exits are stored as `IGNORED_EXIT_MODE`; `DEVICE_OR_BUTTON` → both. An exit when already OUT is stored
   but does not change presence.
5. Direction for `BOTH` devices comes from the device's punch state (ZKTeco ATTLOG status 0 = check-in, 1 =
   check-out; other values → ENTRY).
6. Device heartbeat updates `last_seen_at`; a ShedLock job alerts managers when an ACTIVE device has been silent
   longer than `device_offline_minutes` (deduplicated per outage).
7. Everything is audited (settings changes, device registration, credential changes, manual overrides).

## How devices connect

### A. ZKTeco / eSSL devices with "ADMS" / "Cloud server" push (most common in India)

The device itself calls the server over HTTP(S) — no software on a PC is needed.

* Endpoints (public, no JWT; identified by the registered serial number):
  * `GET  /iclock/cdata?SN=<serial>&options=all` → handshake; reply with the standard `GET OPTION FROM:` block
    (`ATTLOGStamp`, `TransTimes`, `TransInterval`, `Realtime=1`, `Encrypt=0`, `TimeZone=330`).
  * `POST /iclock/cdata?SN=<serial>&table=ATTLOG` → tab-separated lines `PIN  YYYY-MM-DD HH:MM:SS  status  verify ...`;
    reply `OK: <n>`.
  * `GET  /iclock/getrequest?SN=<serial>` → heartbeat; reply `OK`.
* Unknown or disabled serial numbers get `401`-equivalent text and are logged; rate-limited at nginx.
* **On a local network**: device menu → *COMM → Cloud Server Setting* → Server address = the PC/server's LAN IP,
  port = 58088 (or 80), HTTPS off.
* **When the site is published** (e.g. `https://pg.example.com`): Server address = `pg.example.com`, port 443,
  *Enable domain name* = on, *HTTPS* = on (on models that support it). The owner registers the device's serial
  number in the app first (Settings → Biometric devices → Add device), otherwise its punches are rejected.
* Device clock: set time zone to UTC+05:30; the server also accepts the handshake `TimeZone` option.

### B. Devices without cloud push, or HTTP-only devices on the internet → local bridge

Older devices only speak the LAN SDK, and some ADMS firmware cannot do HTTPS. In that case a small **bridge**
runs on any always-on PC/Raspberry Pi in the building, reads punches from the device on the LAN, and forwards
them over HTTPS:

* `POST /api/v1/access/device-events` with header `X-Device-Key: <key shown once when the device was registered>`,
  body `{events:[{deviceUserId, occurredAt, direction?, method}]}` → per-event results. Idempotent (same unique key).
* `POST /api/v1/access/verify` (same auth) `{deviceUserId, direction}` → `{allowed, result, reason, residentName,
  entriesToday, limit}` — for door controllers that can ask the server before releasing the lock (this is how the
  entry *limit* can be enforced at the door; pure push devices can only record and alert).

### C. No hardware yet

`scripts/biometric-simulator.py` emulates a ZKTeco ADMS device (handshake, heartbeat, ATTLOG pushes) against a
running stack, so the whole flow can be tried without buying a device.

## API (JWT)

| Method & path | Roles | Purpose |
|---|---|---|
| `GET/PUT /api/v1/properties/{id}/access-settings` | admins (PUT), managers read | enable, exit mode, default daily entry limit |
| `GET/POST /api/v1/access/devices`, `PATCH /{id}`, `POST /{id}/rotate-key`, `DELETE /{id}` | admins, managers | register devices (API key returned once) |
| `GET/POST /api/v1/access/credentials`, `DELETE /{id}` | admins, managers | map device PIN ↔ resident/staff |
| `PUT /api/v1/tenants/{id}/access-limit` | admins, managers | per-resident daily entry limit (null = use property default) |
| `GET /api/v1/access/events` | operators (scoped), tenant (own) | filters: property, tenant, direction, result, date range |
| `GET /api/v1/access/presence?propertyId=` | operators | who is IN, since when |
| `POST /api/v1/access/exit` | tenant (self) / operators (`{tenantId}`) | button exit when exit mode allows |
| `POST /api/v1/access/manual-entry` | MANAGER, PROPERTY_ADMIN | override entry with reason |
| `GET /api/v1/access/me` | tenant | my status IN/OUT, entries today, remaining entries, whether exit button is available |

## Frontend

* Property → **Access control** tab: enable toggle, exit mode (Device / Button / Both), daily entry limit.
* **Biometric devices** page: add device (shows serial + one-time key + copyable setup instructions for local and
  published mode), status (online/offline, last seen).
* **Enrollment**: per resident "Fingerprint ID (device PIN)" + per-resident entry limit.
* **Gate log / presence** page: live list (auto-refresh) of entries/exits with result badges; "who's inside".
* Resident app: status card (IN/OUT, entries today / limit) and a big **"I'm leaving"** button when allowed.
