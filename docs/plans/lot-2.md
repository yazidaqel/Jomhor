# Lot 2 · Gate: implementation plan

As of 2026-11-06 · owner: tech-lead · status: **approved 2026-11-06 under the founder's standing instruction** ("continue and do not stop"): every §3 default is taken; the founder can override any of them. §4 applied to the docs except the `CLAUDE.md` Stack line, which stays as the founder wrote it: Lot 2 reads the dashboard from the database (Q32) as a pilot-scale reading of "Kafka only where an async boundary is real", to revisit in Lot 6.

## 1. Lot

**Lot 2 · Gate.** Lot 1 is done on `feat/lot-1-sell-deliver` (PR #2, CI green, not merged yet). Lot 2 branches from `main` once PR #2 merges: `feat/lot-2-gate`.

Scope (`00-product.md` §V1 in lots): scanner app, offline validation, manifest + K_e distribution, sync and duplicate resolution, supervisor override, live gate dashboard, reconciliation report.

Exit ("first pilot match end to end"):

| Level | Proof |
| --- | --- |
| Demo-complete (Lot 2 done) | On staging, with the Lot 1 flow: the club publishes a fixture, fans buy tickets (app and web). Two Android scanners are enrolled from B6 (enrol QR, fingerprint approved), assigned to two gates in B2, and download the manifest + K_e at T0 − 3 h. Then, **in airplane mode**: device A admits a rotating app QR (green, "Validé hors ligne en {ms} ms") and a PDF QR; a wrong-gate ticket shows MAUVAISE PORTE with the correct gates; a supervisor admits a ticket by typed number (OVERRIDE, reason logged). **Duplicate across devices:** (1) the same PDF is scanned on A and B while both are offline: both green, and after reconnect B4 shows 1 entered + 1 late duplicate, the canonical entry being the earlier device time even if it synced second; (2) once A has synced, scanning that PDF on B gives DÉJÀ UTILISÉ with the gate and time of the first scan. B4 shows entries, rate, refusals and overrides per gate, refreshed within 5 s, and flags a device that has been without sync for more than 5 min. The admin closes the event. Both devices drain their queue, wipe K_e and the manifest, and ack. B5 shows the final reconciliation (sold / entered / no-show / refused by reason / overrides per zone and gate) and exports the CSV. `Lot2GateFlowTest` (backend) and `apps/scanner/test/gate_flow_test.dart` are green in CI. |
| Pilot match (live) | The same at the club stadium on a real fixture, after **one dry run at the venue** (≥ 4 devices, real manifest size, phones in airplane mode at the gates). Gated by Lot 1 live (Payzone, provider, infra Q24, store builds Q25), the device fleet (Q28), the APK channel (Q29) and staff PINs handed out. Schedule the fixture ≥ 1 week after Lot 2 ends. |

## 2. What exists, gaps, spec refs

### 2.1 What Lot 2 builds on

| Area | State on the Lot 1 branch | Lot 2 use |
| --- | --- | --- |
| Token codec | `TicketToken` (Java) and `packages/core` (Dart) both have v1/v2, K_t, slot and `verify` (steps 1–4, constant time). They are golden-tested against `docs/token-vectors.json` | The scanner validator calls `decode` + `verify` from core as they are |
| K_e | `EventService.withEventKey(org, id, fn)` is the only way to the key (AES-GCM at rest, zeroed after use) | The manifest endpoint base64-encodes K_e inside the lambda, for full manifests only |
| Catalog | venues, zones (smallint id), gates, `gate_zones` (venue level), events with `reentry_policy` (B2 lets you edit it), `refund_cutoff_at` | Manifest G/Z lines, WRONG_ZONE gate names |
| Tickets | Immutable rows with `status` and `holder_initials` (`Y. A.`) set at issue | Manifest T rows. `holder_name` never leaves the tickets module |
| Staff | `staff` with role `GATE`/`SUPERVISOR` and a `pin_hash` column (`V1`, unused). No staff API: Lot 0 Q12 seeds by SQL | PIN verifiers for the scanners |
| Security | 4 chains; `/gate/**` falls to `otherChain` = `denyAll` | New device-signature chain |
| Fan app | `apps/fan` (Riverpod, go_router, l10n ARB, tokens via `tool/gen_tokens.dart`, `ApiClient` with the 04 error shape) | Structure to mirror for `apps/scanner` |
| Back-office | Feature folders `auth`, `events`, `sales`, `venues`; shadcn components; i18n with the literal-strings test | New features `devices`, `live`, `reconciliation`; B2 tabs |
| CI | Path-filtered jobs backend, backoffice, core, fan, shop | + `scanner` job (W0) |

### 2.2 What does not exist

| Need | Plan |
| --- | --- |
| Migrations for `devices`, `device_assignments`, `manifests`, `scans` (Lot 0 §4.2 deferred them) | V11 in BE-17 |
| `gate` backend module, device auth, manifest signing key | BE-17, BE-19 |
| Staff (PIN) management API | BE-18 (none exists; P20) |
| `apps/scanner` | FL-10 to FL-14 |
| `/org/tickets/{id}/block`, `/unblock`, `/refund` (in 04, never built) | **Not Lot 2** (Q39). The scanner handles BLOCKED/CANCELLED rows from fixtures |
| Kafka | **Not Lot 2** (Q32) |
| Android test phones, APK channel, staging secret `GATE_MANIFEST_SIGNING_KEY` | Founder / orchestrator (W0) |

### 2.3 Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (no server call on the gate path, no PII in QR, scans append-only and idempotent on `scan_id`, `organizer_id`, Kafka only where async is real), Stack (Drift, `flutter_secure_storage` + Keystore), Agent team |
| `docs/00-product.md` | §V1 in lots (Lot 2 row), §Open questions (re-entry, stadium plan, devices) |
| `docs/01-architecture.md` | §Components (scanner, Kafka row), §Key flows 2 Gate and 3 Close |
| `docs/02-gate-validation-and-data-model.md` | All of: §Actors & trust boundaries, §Match manifest & key distribution, §Offline validation algorithm, §Scan log/sync/duplicates, §Data model (devices, assignments, manifests, scans, staff, SQLite), §Gate/zone/re-entry/override, §Threat model, §Capacity & runbook |
| `docs/03-schema.sql` | `staff`, gate section, `audit_log` |
| `docs/04-api.md` | header (Gate auth), errors, §Organizer (devices, live, close, reconciliation), §Gate (scanner), §Token, §Test vectors |
| `docs/05-scanner-sqlite.sql` | All |
| `docs/06-glossary.md` | Scan result codes, manifest, K_e, used-set |
| `design/DESIGN.md` | Component "Scanner result", screen 6, B2 Appareils tab, B4, B5, B6, §Accessibility (scanner never colour-only) |
| Mockups | `6-scanner.html` (`Scanner.dc.html`), `b4-backoffice-portes-direct.html` (`BoLive.dc.html`), `b2-backoffice-evenement.html` |
| Plans | lot-0 Q5 (per-event gate matrix), Q9 (re-entry); lot-1 Q17 (app-only tickets), §4.5 (Kafka deferred to Lot 2), §7 |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default for Lot 2 |
| --- | --- | --- |
| Q28 | Scanner hardware (who buys, which model; product open question) | **Pilot on Android 10+ phones with a camera**, using `mobile_scanner`. PDAs (Sunmi L2 / Zebra TC2x) work through **keyboard-wedge input** (DataWedge / Sunmi scan-to-keystroke), which needs no vendor SDK; intent APIs come in Lot 6. The founder decides who buys before the dry run. The device key is ECDSA P-256 because Android Keystore supports Ed25519 only from Android 13 (P15). |
| Q29 | Scanner app distribution | Play internal-testing track on the existing developer account (Q25). The fallback is a signed APK sideloaded by the gate lead. MDM/kiosk mode comes in Lot 6. |
| Q30 | Device enrolment method | B6 "Ajouter des appareils" shows an **enrol QR**: a JWT `aud=enrol` for the organizer, valid 30 min, reusable for a batch, no table. The scanner generates its Keystore key, scans the QR and types a label. The device lands in `PENDING` with a fingerprint (`AB12 CD34`) on its screen; the admin approves it in B6 after matching the fingerprint. Approval stays because a leaked QR alone must not be enough. |
| Q31 | Staff PIN check: online session (04 header) or on the device? | **On the device.** The gate has no signal from T0 − 60, so the auto-lock unlock, staff switch and supervisor override must all work offline (02:161–163). `GET /gate/events` ships the GATE/SUPERVISOR staff list with PBKDF2 verifiers. Unlock = pick a name, then type the PIN. `POST /gate/session` and its token are dropped (P17). A 4-digit verifier can be brute-forced offline: the PIN is a UI lock, and the device key + revocation are the boundary (§6). PINs are 4 random digits generated by the server and shown once. |
| Q32 | Dashboard transport: Kafka, SSE or DB polling? | **DB aggregate + back-office refetch every 5 s; no Kafka, no SSE.** At pilot scale (≤ 45 devices, ≤ 5 batches/s, a handful of dashboards) ingestion and the dashboard share one database, so there is no async boundary (CLAUDE.md: Kafka only where it is real). `/live` is a few indexed aggregate queries. Kafka returns when a real consumer appears (the fan "Tu es dedans" push in Lot 4, or Lot 6 load results). Needs edits to the `CLAUDE.md` Stack line, 01 and the backoffice agent rule (§4.6). |
| Q33 | Re-entry: NONE or ONE_EXIT (club question) | **NONE only in Lot 2.** POST/PATCH events with `ONE_EXIT` → 422 `NOT_SUPPORTED`; B2 shows "Ré-entrée · aucune" read-only. ONE_EXIT is worth about +2 d (scanner exit mode behind the supervisor PIN, a used-set per direction, IN/OUT pairing in server resolution). Add it if the club asks before the dry run. |
| Q34 | Duplicate resolution: relabel rows `DUPLICATE_LATE` (02:102) or derive it? | **Derive at read time; never UPDATE a scan.** Per (ticket, direction), the admitted (OK/OVERRIDE) row with the earliest `device_time` is canonical; later admitted rows are reported `DUPLICATE_LATE`. This is truly append-only and has no race between batches. `DUPLICATE_LATE` leaves the `scan_result` enum (P21). |
| Q35 | Per-event gate → zone override (lot-0 Q5, "modifiable par événement") | No. The venue matrix is used for every event and travels in the manifest. Add `event_gate_zones` when the club asks for a derby layout. |
| Q36 | App-only tickets per event (lot-1 Q17, derbies) | No. First scan wins already limits a printed PDF to one entry. Revisit with verified fans (Lot 5). |
| Q37 | Event close and reconciliation | ADMIN/OPS close manually from B4, only at or after T0. Reconciliation is **computed on read**, never frozen: late syncs keep correcting it. It is marked `final` once the event is `CLOSED` and every assigned device has acked its wipe. |
| Q38 | `GATES_OPEN` status | Not set in Lot 2. The enum exists, but flipping it at doors-open would stop sales before `refund_cutoff_at` (orders need `ON_SALE`). B4 shows "Portes ouvertes · T0 − n min" from the clock. Close works from `ON_SALE`. |
| Q39 | Block/unblock/refund tickets (in 04, never built) | Lot 3, with refunds and cash. Nothing in the Lot 2 demo needs it. The scanner still implements CANCELLED/BLOCKED (REFUNDED shows as ANNULÉ). |
| Q40 | Override without a manifest match | Not allowed. The ticket must be found in the manifest by scan or typed number (any status, including a DUPLICATE). Typed numbers collide: with 8 hex digits and 45,000 tickets, about 1 event in 4 has a pair, so the lookup lists every match with zone and initials. |
| Q41 | Lost device | Revoke in B6. Its next gate call gets 403 → the app wipes K_e, the manifest, staff and scans. Its unsynced scans are lost; accepted. Until it gets signal it keeps validating (02:174). |
| Q42 | Fan app "Tu es dedans" flip after scan sync (DESIGN screen 5) | Lot 4 (matchday companion). |
| Q43 | Manifest timing | v1 at T0 − `gate.manifest-lead` (config, default 3 h; the dry run may use 6 h), then the job checks every minute until T0 + 30 min and builds a version **only if the payload changed** (tickets, zones, gates or rules) and the previous one is at least 5 min old. Sales close at T0 − 60, so deltas are mostly late sales. |

## 4. Contract changes

### 4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P13 | 04:12, 04:185–198 | The device signature is named (Ed25519 over method+path+body+timestamp) but has no headers, string, encoding, time window or replay rule | §Gate auth (§4.3) + `docs/gate-vectors.json` |
| P14 | 04:189 `enrol_code` | Source undefined | `POST /org/devices/enrol-codes` (JWT `aud=enrol`, 30 min), Q30 |
| P15 | 03:274, 02:63 | The device key is "Ed25519 in Android Keystore": Keystore has Ed25519 only on Android 13+, and many PDAs run 10–12 | Device key = ECDSA P-256 (SPKI DER). The server manifest key stays Ed25519 (JDK, verified in pure Dart) |
| P16 | 04:192, 03:294 | "Ed25519 over the canonical body": canonical JSON is undefined, so Java and Dart would disagree | The server signs a **text payload** and sends it verbatim (§Manifest payload). The device verifies the bytes it received, then parses them |
| P17 | 04:190, 02:63, 02:163 | `POST /gate/session` checks the PIN on the server, but unlock after the 10-min idle lock and supervisor overrides happen with no signal | Drop the session; PIN verifiers go to the device (Q31). The scan row carries `staff_id` |
| P18 | 04:193, 03:304 | Scan payload has no `gate_id`/`staff_id`; a supervisor gate change makes the gate differ from the assignment | Each row carries `gate_id`, `staff_id` |
| P19 | 04:193, 04:198 | `cursor` is called a "server-side sequence", but `scan_id` (UUIDv7, device clock) is not arrival order, and concurrent inserts commit out of order, so a cursor can skip rows | `scans.seq` identity + ingestion serialised per event (`pg_advisory_xact_lock`) |
| P20 | 02:163, 03:42 | "4-digit PIN per staff member", but no API creates gate staff or sets PINs | `/org/staff` (list, create GATE/SUPERVISOR, PATCH, reset PIN) |
| P21 | 02:102, 03:300, 06:24 | Relabelling to `DUPLICATE_LATE` is an UPDATE on an append-only log | Derived at read (Q34); removed from the enum |
| P22 | 02:158, 04:191 | WRONG_ZONE must name the correct gates, but the device only receives its own `allowed_zone_ids` | The manifest carries every gate with its zones (G lines) and zone names (Z lines) |
| P23 | 02:70 vs 04:192 | `reentry_allowed` per row vs `reentry_policy` per manifest (lot-0 drift note) | Per event, in the payload header |
| P24 | 04:192 `removed[]` | Tickets are never deleted; a status change is a row update | Drop `removed` |
| P25 | 04:196 `errors[]` | If errored rows are not acked, the device retries them forever | `acked` = every processed row; `errors` ⊂ `acked`. Refused rows go to `audit_log` (a K_e-signed unknown ticket is a forensics signal) |
| P26 | 02:104 | "Refuse event mode if drift > 20 s" is ambiguous when the offset is applied anyway | Drift = abs(server offset) at the last sync. Above 20 s → refuse (the clock has been set by hand). The offset is applied to every time and slot either way |
| P27 | 04:167, DESIGN:199, `.claude/agents/backoffice.md:21` | SSE stream + Kafka for the dashboard | 5 s polling (Q32) |
| P28 | 04:168 | "Builds reconciliation" at close, but late syncs change it | Computed on read with a `final` flag (Q37) |
| P29 | 04:194 | `closed-ack` without rules | Only once `CLOSED`; sets `device_assignments.closed_ack_at` |
| P30 | 05 | No gates/zones/staff tables, no `ticket_no` for manual lookup, scans have no `gate_id`/`staff_id` | §4.4 |
| P31 | 02:65–66 | Paths lack `/gate`; v1 is built "at T0 − 3 h" and every 5 min even if nothing changes | §4.5 |
| — | Football names / floats / PII in QR / server on the gate path | None. The manifest carries initials only; validation is local; no money in Lot 2 | OK |

### 4.2 `docs/03-schema.sql`

```sql
-- ---------- tenancy: staff (replace the pin_hash line) ----------
  pin_hash      text,   -- scanner PIN verifier 'pbkdf2-sha256$10000$<salt>$<hash>' (docs/04-api.md §Gate PINs).
                        -- GATE/SUPERVISOR rows only; the 4-digit PIN is generated by the server and shown once.
                        -- Shipped to the organizer's scanners: a UI lock, brute-forceable offline by design.

-- ---------- gate (replace the whole section) ----------
create type device_status as enum ('PENDING','ACTIVE','REVOKED');

create table devices (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  label         text not null,                        -- typed at enrolment ('PDA 12'), 1-40 chars
  public_key    bytea not null unique,                -- ECDSA P-256 SubjectPublicKeyInfo (DER); private key non-exportable in Android Keystore
  status        device_status not null default 'PENDING',
  enrolled_at   timestamptz not null default now(),
  approved_at   timestamptz,
  revoked_at    timestamptz,
  last_seen_at  timestamptz                           -- last request with a valid device signature
);
create index on devices (organizer_id, status);

create table device_assignments (
  organizer_id      uuid not null references organizers(id),
  device_id         uuid not null references devices(id),
  event_id          integer not null references events(id),
  gate_id           uuid not null references gates(id),   -- a gate of the event's venue; the device's starting gate
  manifest_version  integer,                               -- last version this device downloaded (full or delta)
  closed_ack_at     timestamptz,                           -- device confirmed its wipe after close
  primary key (device_id, event_id)
);
create index on device_assignments (event_id);

create table manifests (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  version       integer not null check (version >= 1),
  built_at      timestamptz not null default now(),
  row_count     integer not null,
  signature     bytea not null,                       -- Ed25519 (server gate key) over the uncompressed full payload
  body          bytea not null,                       -- gzip of the full payload text (docs/04-api.md §Manifest payload)
  unique (event_id, version)
);
-- A version is built only when its T/G/Z lines differ from the previous one. A delta is the diff of two stored
-- versions, signed when served.

create type scan_direction as enum ('IN','OUT');
create type scan_result as enum ('OK','DUPLICATE','BAD_SIG','BAD_FORMAT','UNKNOWN',
                                 'CANCELLED','BLOCKED','WRONG_ZONE','WRONG_EVENT','EXPIRED_SLOT','OVERRIDE');
create type override_reason as enum ('ID_CHECKED','DEVICE_FAULT','DAMAGED_TICKET','OTHER');

create table scans (
  scan_id         uuid primary key,                   -- UUIDv7 generated on the device
  seq             bigint generated always as identity unique,  -- server arrival order: the used_since cursor
  organizer_id    uuid not null references organizers(id),
  event_id        integer not null references events(id),
  ticket_id       uuid references tickets(id),        -- a ticket of event_id; null when the token was not authenticated
                                                      -- (BAD_FORMAT, WRONG_EVENT, EXPIRED_SLOT, BAD_SIG)
  device_id       uuid not null references devices(id),
  gate_id         uuid not null references gates(id), -- the gate the device was on (a supervisor may move it)
  staff_id        uuid not null references staff(id), -- staff unlocked on the device
  direction       scan_direction not null default 'IN',  -- IN only while re-entry is NONE (plan lot-2 Q33)
  result          scan_result not null,               -- the device's verdict; never updated
  token_version   smallint,
  device_time     timestamptz not null,               -- device clock corrected by the server offset
  received_at     timestamptz not null default now(),
  override_reason override_reason,
  supervisor_id   uuid references staff(id),
  constraint scans_override check ((result = 'OVERRIDE') = (override_reason is not null and supervisor_id is not null))
);
create index on scans (event_id, ticket_id, device_time);   -- canonical admitted scan per ticket
create index on scans (event_id, seq);                      -- used_since
create index on scans (event_id, gate_id, device_time);     -- live dashboard per gate

-- Append-only: no UPDATE or DELETE on scans, ever. Canonical admitted scan per (ticket_id, direction): the OK or
-- OVERRIDE row with the earliest device_time (tie: lowest scan_id). Later OK/OVERRIDE rows are reported as
-- DUPLICATE_LATE (derived, never stored). Ingestion holds pg_advisory_xact_lock per event, so seq commits in
-- order and a cursor never skips a row.
```

Flyway: one migration, `V11__gate.sql` (BE-17), with the gate section above. `staff.pin_hash` already exists (V1). There is no new tenant-exempt table, so the `MigrationsTest` allowlist stays at 4. The `02` §Data model table gets the same columns in the same docs change.

### 4.3 `docs/04-api.md`

**Header, auth table: replace the Gate row**

| Audience | Auth | Prefix |
| --- | --- | --- |
| Gate (scanner) | Device signature, ECDSA P-256 key in Android Keystore (§Gate auth). No server session: staff PINs are checked on the device (§Gate PINs) | `/gate/...` |

**Errors: add, or extend the When column** (header: "Lot 0, Lot 1 and Lot 2")

| Code | HTTP | When |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | … ; gate: missing or bad device signature, unknown device, bad or expired `enrol_code` (fixed message, empty `details`) |
| `DEVICE_CLOCK_SKEW` | 401 | `X-Device-Time` more than 5 min from server time; `details.server_time`. The device fixes its offset and retries once |
| `DEVICE_NOT_ACTIVE` | 403 | Device `PENDING` or `REVOKED`; `details.status`. On `REVOKED` the scanner wipes K_e, manifest, staff list and scans |
| `DEVICE_REVOKED` | 409 | Approve on a `REVOKED` device |
| `DEVICE_IN_USE` | 409 | Assignment PUT that removes a device with scans on the event; `details.device_ids` |
| `MANIFEST_NOT_READY` | 409 | Before `manifest_available_at`, or no version built yet; `details.available_at` |
| `EVENT_CLOSED` | 409 | Manifest request on a `CLOSED` event |
| `EVENT_NOT_CLOSED` | 409 | `closed-ack` before the event is `CLOSED` |
| `EVENT_NOT_EDITABLE` | 409 | … ; close outside `ON_SALE`/`GATES_OPEN` or before `starts_at`; assignment PUT on a `CLOSED` event |
| `GATE_IN_USE` | 409 | … Lot 2: device assignments and scans reference gates |

**§Organizer: role line, append.** Lot 2: devices, staff, assignments and close are ADMIN/OPS; `/live` and `/reconciliation` are also FINANCE (read). GATE/SUPERVISOR still get 403 on `/org`: they only use the scanner.

**§Organizer: `/org/events` POST and PATCH, add.** `reentry_policy: ONE_EXIT` → 422 `VALIDATION_FAILED` on `reentry_policy`, `details.reason` `NOT_SUPPORTED` (re-entry not built; plan lot-2 Q33).

**§Organizer: replace rows `CRUD /org/devices` … `GET /org/events/{id}/reconciliation`**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/devices/enrol-codes` | → `201 {enrol_code, expires_at}`. JWT `aud=enrol`, `sub` = organizer, 30 min, reusable for a batch. B6 renders it as a QR. |
| GET | `/org/devices` | `[{id, label, status, fingerprint, enrolled_at, approved_at, last_seen_at}]`, `PENDING` first, then by label. `fingerprint` per §Gate auth. |
| POST | `/org/devices/{id}/approve` | `PENDING` → `ACTIVE`; `ACTIVE` → `200` unchanged; `REVOKED` → 409 `DEVICE_REVOKED`. Audit `device.approve`. → device. |
| POST | `/org/devices/{id}/revoke` | Any status → `REVOKED` (idempotent). Audit `device.revoke`. → device. |
| GET, PUT | `/org/events/{id}/devices` | GET `[{device_id, label, status, gate_id, gate_code, manifest_version, last_seen_at, closed_ack_at}]` by gate code, then label. PUT `{assignments:[{device_id, gate_id}]}` replaces the set. Devices must be `ACTIVE` and this organizer's; gates must be of the event's venue (422 on `assignments[i].device_id` / `.gate_id`). Removing a device that has scans on the event → 409 `DEVICE_IN_USE`. `CLOSED` → 409 `EVENT_NOT_EDITABLE`. → GET shape. |
| GET | `/org/staff` | `[{id, name, role, active, has_pin}]` by name (every staff row; back-office staff have `has_pin: false`). |
| POST | `/org/staff` | `{name, role: GATE\|SUPERVISOR}` → `201 {id, name, role, active, has_pin: true, pin}`. `name` per §Holder names. `pin` = 4 random digits, returned only here and by `/pin`. |
| PATCH | `/org/staff/{id}` | `{name?, active?}`; GATE/SUPERVISOR rows only (others → 404). |
| POST | `/org/staff/{id}/pin` | New random PIN → `{pin}`. Audit `staff.pin_reset`. Devices get it at their next `GET /gate/events`. |
| GET | `/org/events/{id}/live` | §Gate reports. B4 refetches every 5 s; no stream. |
| POST | `/org/events/{id}/close` | `ON_SALE`/`GATES_OPEN` and `now ≥ starts_at` → `CLOSED`, else 409 `EVENT_NOT_EDITABLE`. Audit `event.close`. → event (GET shape). From then on: no `k_t`, manifest 409 `EVENT_CLOSED`, devices drain and wipe; scans are still accepted. |
| GET | `/org/events/{id}/reconciliation` | §Gate reports. `.csv`: `;`-separated, UTF-8 with BOM, FR headers, one row per zone, then per gate, then the total, with a `kind` column (`zone`/`gate`/`total`). |

**§Gate (scanner): replace the whole section**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/gate/devices/enrol` | Signed with the new key, without `X-Device-Id` (verified against `public_key`). `{public_key, label, enrol_code}` → `201 {device_id, status: PENDING, fingerprint, manifest_public_key, server_time}`. `public_key` = base64url SPKI DER (P-256); `label` 1–40 chars; `enrol_code` binds the device to that organizer. A known `public_key` → `200` with the existing device. `manifest_public_key` = server Ed25519 key, base64url raw 32 bytes, pinned by the device. |
| GET | `/gate/events` | `ACTIVE` device (`PENDING` → 403 `DEVICE_NOT_ACTIVE`, which the app polls while it waits for approval). → `{server_time, device:{id, label, status}, events:[{id, title, event_type, status, starts_at, timezone, venue:{name}, gate:{id, code, name}, reentry_policy, manifest_available_at, manifest_version}], staff:[{staff_id, label, role, pin_verifier}]}`. `events` = this device's assignments with status `ON_SALE`/`GATES_OPEN`, or `CLOSED` and not yet acked; by `starts_at`. `manifest_available_at` = `starts_at − gate.manifest-lead`. `manifest_version` = latest built, or null. `staff` = this organizer's active GATE/SUPERVISOR staff with a PIN; `label` = holder_label of the name. `Cache-Control: no-store`. |
| GET | `/gate/events/{id}/manifest?since=` | Device `ACTIVE` and assigned to the event (else 404). `CLOSED` → 409 `EVENT_CLOSED`; before `manifest_available_at` or nothing built → 409 `MANIFEST_NOT_READY`. → gzip `{event_id, version, since, rows, payload, signature, event_key?, server_time}`. `since` absent, unknown or above the latest → full (`since: 0`) **with `event_key`** (base64url, 32 bytes); otherwise a delta from `since`, without `event_key`. `payload` = §Manifest payload; `signature` = base64url Ed25519 over the UTF-8 bytes of `payload`. Sets `device_assignments.manifest_version`. `Cache-Control: no-store`. |
| POST | `/gate/events/{id}/scans` | Device `ACTIVE` and assigned (any event status, `CLOSED` included). `{cursor, scans:[{scan_id, ticket_id?, gate_id, staff_id, direction, result, token_version?, device_time, override_reason?, supervisor_id?}]}`: 0–500 rows (more → 422; an empty batch is a poll). → `{acked:[scan_id], errors:[{scan_id, field}], used_since:[{ticket_id, direction, first_at, gate_code}], cursor, more, manifest_version, event_status, server_time}`. Rules below. |
| POST | `/gate/events/{id}/closed-ack` | Event `CLOSED` (else 409 `EVENT_NOT_CLOSED`) → sets `closed_ack_at` (idempotent) → `204`. Sent after the device has drained its queue and wiped. |

Sync rules:

| Rule | Value |
| --- | --- |
| Insert | `INSERT … ON CONFLICT (scan_id) DO NOTHING`, one transaction per batch under `pg_advisory_xact_lock` on the event |
| Row checks | `scan_id` a UUIDv7; `result` a device code; `direction` `IN`; `ticket_id` present iff the result comes after authentication (OK, DUPLICATE, UNKNOWN, CANCELLED, BLOCKED, WRONG_ZONE, OVERRIDE) and is a ticket of this event; `gate_id` a gate of the venue; `staff_id` staff of the organizer; `override_reason` + `supervisor_id` (a SUPERVISOR) iff `OVERRIDE`. A failing row is not stored; it goes in `errors` with the first failing field, and in `audit_log` (`scan.refused`: device, scan_id, field) |
| Ack | `acked` = every row of the batch, whether stored, already present or refused. The device marks them all synced; a batch is never refused for one row |
| `device_time` | Stored as sent, never rejected for skew; `received_at` sits next to it so drift stays visible |
| `used_since` | Admitted (OK, OVERRIDE) scans of this event from **other** devices with `seq > cursor`, by `seq`, at most 5,000. `first_at` = their `device_time`; `cursor` = the last `seq` returned (or unchanged); `more = true` → the device calls again at once |
| `event_status` | `CLOSED` → the device sends every unsynced row, wipes K_e, manifest, used-set and synced scans, then calls `closed-ack` |

**New §Gate auth**

```
X-Device-Id:        <device uuid>        (absent only on POST /gate/devices/enrol)
X-Device-Time:      <unix ms>            (device clock + server offset)
X-Device-Signature: base64url (no padding) of the DER ECDSA P-256 / SHA-256 signature over:

"JMH-GATE-1" LF METHOD LF PATH LF X-Device-Time LF lowercase-hex(SHA-256(body))
```

| Rule | Value |
| --- | --- |
| PATH | Raw path + query exactly as sent, including `/v1` (`/v1/gate/events/12/manifest?since=3`) |
| body | Raw request bytes (requests are not compressed); empty body → SHA-256 of the empty string |
| Server order | Device known (enrol: key from the body) → signature → \|time − now\| ≤ 5 min (else `DEVICE_CLOCK_SKEW`) → status `ACTIVE` (except enrol, and `GET /gate/events` which answers 403 for `PENDING`). Then `devices.last_seen_at = now()` |
| Replay | Accepted within the 5-min window: GETs are reads, scans are idempotent, enrol is idempotent per key; TLS throughout |
| `server_time` | In every gate JSON response (RFC 3339, ms). The device offset = `server_time` − the midpoint of request send and response receive |
| Fingerprint | First 8 hex chars of SHA-256(SPKI DER), uppercase, grouped 4-4 (`AB12 CD34`) |

**New §Gate PINs**

| Rule | Value |
| --- | --- |
| PIN | 4 random digits from the server (`POST /org/staff`, `/pin`), shown once, never stored in clear |
| Verifier | `pbkdf2-sha256$10000$<salt>$<hash>`: PBKDF2-HMAC-SHA256 of the UTF-8 PIN, 16-byte random salt, 10,000 iterations, 32 bytes, base64url without padding |
| Device | Unlock = pick a name from `staff`, then the PIN. 5 wrong PINs → locked 5 min. 10 min idle → locked. Override and gate change = a SUPERVISOR picks their name, then types their PIN |

**New §Manifest payload** (UTF-8 text, LF line ends, TAB-separated; the server replaces any TAB, CR or LF inside a text field with a space)

```
JMH-MANIFEST	1
event	{event_id}
version	{version}
since	{0 for full, else the base version}
built_at	{RFC 3339}
reentry	{NONE|ONE_EXIT}
rows	{ticket rows in this version, full count}
Z	{zone_id}	{code}	{name}                         every zone of the venue, by zone_id
G	{gate_id}	{code}	{name}	{zone_id,zone_id,...}   every gate of the venue, by code; zone ids ascending
T	{ticket_id}	{zone_id}	{status}	{holder_initials or empty}
```

Full: one `T` per ticket of the event, by `ticket_id`. Delta: `T` only for tickets added or whose status changed since `since` (Z and G lines always complete). The device applies a delta only if its version equals `since`; afterwards its row count must equal `rows`, otherwise it refetches the full manifest. Status → result: VALID → continue, CANCELLED/REFUNDED → `CANCELLED`, BLOCKED → `BLOCKED`. No name, phone or ID number ever goes in the payload. 45,000 rows ≈ 2.6 MB of text, about 1.2 MB gzipped (02:66 says under 1 MB: accepted).

**New §Gate reports**

Admitted = `OK` or `OVERRIDE`. Canonical = per ticket, the admitted scan with the earliest `device_time` (tie: lowest `scan_id`). Refused = any other device result. `sold` = tickets `VALID` (Lot 3 revisits once refunds exist).

| Endpoint | Shape and definitions |
| --- | --- |
| `GET /org/events/{id}/live` | `{event_id, status, starts_at, server_time, sold, entered, rate_per_min, rate_per_min_prev, refused:{total, by_result:{DUPLICATE: n, …}}, duplicates_late, overrides:{total, cap_per_device: 20, by_device:[{device_id, label, count}]}, gates:[{gate_id, code, zone_codes[], devices_total, devices_online, entered, refused, overrides, rate_per_min, last_sync_at}], devices_stale:[{device_id, label, gate_code, last_seen_at}]}`. `entered` = tickets with a canonical scan (per gate: canonical scans at that gate). `duplicates_late` = admitted − canonical. `rate_per_min` = admitted scans with `device_time` in the last 5 min ÷ 5 (integer); `_prev` = the 5 min before that. Online = `last_seen_at` within 2 min; stale = assigned and silent for more than 5 min (or never seen). `gates` = gates with an assigned device or a scan, by code. `by_device` = devices with ≥ 1 override, by count desc. |
| `GET /org/events/{id}/reconciliation` | `{event_id, status, generated_at, final, devices:{assigned, acked, unsynced:[{device_id, label, last_seen_at}]}, totals:{sold, entered, no_show, refused_by_result{}, duplicates_late, overrides_by_reason{}}, zones:[{zone_id, code, name, sold, entered, no_show, overrides}], gates:[{gate_id, code, entered, refused_by_result{}, duplicates_late, overrides}]}`. Zones by the ticket's zone, gates by the scan's gate. `no_show` = sold tickets with no canonical scan. `final` = `CLOSED` and every assigned device acked. `unsynced` = assigned devices not acked. |

**New §Gate vectors.** `GateVectorsTest` (backend) writes `docs/gate-vectors.json` under the same rules as the token vectors (CI fails if it is missing or differs): `{signing:[{method, path, time, body, expected_string, expected_body_sha256}], fingerprints:[{public_key, fingerprint}], manifest:[{public_key, payload, signature}], pins:[{pin, verifier, ok}]}`. Ed25519 is deterministic, so a fixed test key gives fixed signatures. `apps/scanner` golden-tests against it.

### 4.4 `docs/05-scanner-sqlite.sql`: replace the file

```sql
-- Jomhor scanner app: SQLite schema (Drift, own isolate). K_e is NOT here: flutter_secure_storage
-- (Android Keystore-wrapped), loaded into memory only in event mode.

create table config (
  id                    integer primary key check (id = 1),
  device_id             text,                    -- null until enrolled
  device_status         text,                    -- PENDING | ACTIVE
  manifest_public_key   text,                    -- server Ed25519 key, pinned at enrolment
  server_time_offset_ms integer not null default 0,   -- server - device wall clock, from every response
  last_server_time_at   integer,                 -- epoch ms (device) of the last offset update
  event_id              integer,
  event_title           text,
  starts_at             integer,                 -- epoch ms
  event_status          text,
  gate_id               text,                    -- current gate: assignment, or a supervisor gate change
  manifest_version      integer not null default 0,
  reentry_policy        text not null default 'NONE',
  sync_cursor           integer not null default 0,
  staff_id              text,                    -- unlocked staff; null = locked
  mode                  text not null default 'IN'
);

create table zones (zone_id integer primary key, code text not null, name text not null);
create table gates (gate_id text primary key, code text not null, name text not null,
                    zone_ids text not null);   -- JSON array
create table staff (staff_id text primary key, label text not null, role text not null,
                    pin_verifier text not null);

create table manifest_tickets (
  ticket_id        text primary key,           -- UUID string
  ticket_no        text not null,              -- last 8 hex, uppercase, no space (manual lookup)
  zone_id          integer not null,
  status           text not null,              -- VALID | CANCELLED | REFUNDED | BLOCKED
  holder_initials  text
);
create index idx_manifest_ticket_no on manifest_tickets (ticket_no);

create table used (
  ticket_id   text not null,
  direction   text not null,
  first_at    integer not null,                -- epoch ms, corrected device time
  gate_code   text,
  source      text not null,                   -- LOCAL | REMOTE
  primary key (ticket_id, direction)
);

create table scans (
  scan_id          text primary key,           -- UUIDv7
  ticket_id        text,                       -- null for BAD_FORMAT, WRONG_EVENT, EXPIRED_SLOT, BAD_SIG
  gate_id          text not null,
  staff_id         text not null,
  direction        text not null,
  result           text not null,
  token_version    integer,
  device_time      integer not null,           -- epoch ms, corrected
  synced           integer not null default 0,
  override_reason  text,
  supervisor_id    text
);
create index idx_scans_unsynced on scans (synced) where synced = 0;

-- Validation writes the scan row and the used row in ONE transaction.
-- A full manifest replaces zones, gates and manifest_tickets and sets manifest_version in ONE transaction,
-- only after its signature verified. A delta is applied in one transaction, then the row count is checked.
-- Sync marks synced=1 only for acked scan_ids. A remote used row keeps the earliest first_at.
-- On CLOSED: push every unsynced row, then delete manifest_tickets, used, zones, gates and synced scans,
-- wipe K_e, reset config.event_id, POST closed-ack. On REVOKED: delete everything and wipe K_e.
```

### 4.5 `docs/02-gate-validation-and-data-model.md`

| Line | Change |
| --- | --- |
| 25–26 | Scanner trusts "server (signed manifest), enrolled device key, staff PIN checked locally". Gate sync API trusts "device signature". |
| 63 | "The scanner generates an ECDSA P-256 keypair in Android Keystore and scans an enrol QR from the back-office. The admin approves the device after matching its fingerprint. Every gate call is signed by the device key (docs/04-api.md §Gate auth). Staff PINs are checked on the device." |
| 65 | "…v1 at T0 − 3 h (config), then checked every 5 minutes until T0 + 30 min; a version is built only when rows changed. Each version is Ed25519-signed by the server over its text payload." |
| 66 | Paths `/gate/events/{id}/manifest`. K_e is stored with `flutter_secure_storage` (Keystore-wrapped). |
| 70 | Manifest row: ticket_id, zone_id, status, holder_initials. The re-entry policy and the venue gates and zones come in the payload. |
| 95–97 | "Sync loop (off the scan path: Drift runs in its own isolate and the loop never blocks a scan)". Path `/gate/events/{id}/scans`. |
| 98 | `used_since` cursor = server arrival sequence (`scans.seq`) |
| 102 | "…the canonical entry is the admitted (OK/OVERRIDE) scan with the earliest device_time; later admitted scans are **reported** as DUPLICATE_LATE. Scans are never updated." |
| 104 | "…drift = abs(server offset); above 20 s the app refuses event mode until the device clock is fixed. The offset is applied to every timestamp and slot." |
| 131–135, 147–150 | §Data model: devices (status PENDING/ACTIVE/REVOKED, P-256 key), device_assignments (+ manifest_version, closed_ack_at), scans (+ seq, staff_id), staff (PIN verifier); SQLite rows as in 05 |
| 159 | "Lot 2 implements `NONE`; the API refuses `ONE_EXIT` until it is built." |
| 160 | "…changing gate requires a supervisor PIN and works offline (gates come in the manifest)." |
| 161 | "…the ticket must be found in the manifest (scanned, or a typed number that lists every match)…" |
| 163 | "4-digit PIN per staff member, generated by the server, checked on the device…" |
| 174 | Lost scanner: "…the P-256 device key is non-exportable; revoke → next call 403 → wipe." |

### 4.6 `docs/01`, `docs/06`, `design/DESIGN.md`, `CLAUDE.md`, agent files

| Where | Change |
| --- | --- |
| 01:42 Kafka row | "Lot 2: the dashboard reads the scans table directly (polled every 5 s). Kafka arrives with its first real async consumer (Lot 4 fan push, or Lot 6 load results)." The 01:16 diagram stays (target). |
| 01:59 | "…reconciliation report (computed on read, final once every device acked)" |
| 06:24 | DUPLICATE_LATE: "Derived in reports: a later admitted scan of an already admitted ticket. Never stored." Add terms: enrol QR (`QR d'enrôlement`), fingerprint (`empreinte`), canonical scan (`entrée retenue`). |
| DESIGN, new "Lot 2 adjustments" | **Screen 6:** mode label ENTRÉE only (Q33). WRONG_ZONE lists the gate codes from the manifest. Supervisor sheet: pick supervisor → PIN → action (Dérogation · Changer de porte · Recherche billet), override count "n / 20". Dérogation: reason → ticket (scan, or type 8 characters; several matches → rows with zone + initials) → OVERRIDE blue. **Scanner screens not mocked**, same dark components: Enrôlement (scan QR, label, fingerprint at 28 px, "En attente d'approbation"), Événement (assignment, download progress, drift check result), Déverrouillage (name list + 44 px PIN pad), Recherche (ticket no → zone, status, initials only), Clôture (drain progress, "Données effacées"). **B4:** data from `/live` refetched every 5 s (no SSE). Amber row = `devices_stale` (no "scans en attente" figure: the server can't know it). "Clôturer l'événement" opens a confirm dialog with the stale-device count. "Exporter CSV" = reconciliation CSV. Charge = `rate_per_min` ÷ (`devices_total` × 24). **B5:** "Provisoire" / "Définitive" badge (`final`), totals cards, zone table, gate table, unsynced devices, CSV. **B6:** tabs Appareils (list with status badge, fingerprint, last seen; "Ajouter des appareils" dialog with the QR + countdown; Approuver; Révoquer with confirm) and Équipe de porte (staff list, add → "PIN : 4821, affiché une seule fois" dialog, reset PIN, deactivate). **B2:** Appareils tab (device → gate select, manifest version, last seen, wipe ack); Règles "Ré-entrée · aucune" read-only. |
| DESIGN:199 | "…`GET /org/events/{id}/live`, refetched every 5 s." |
| `CLAUDE.md` Stack line (founder only) | "Kafka only where an async boundary is real (notifications at scale, fan push; the gate dashboard polls the DB until Lot 6 shows it can't)". Agent table: `backend` also owns `docs/gate-vectors.json`. |
| `.claude/agents/backoffice.md:21` (orchestrator) | "Live gate page: refetch every 5 s; …" |
| `.claude/agents/flutter.md` (orchestrator) | Add: "`apps/scanner` golden-tests the device signature string, the manifest Ed25519 check and PIN verifiers against `docs/gate-vectors.json`." |

## 5. Tasks

### Wave 0: orchestrator and founder (0.5 d, before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/02`, `03`, `04`, `05`, `01`, `06`, `design/DESIGN.md`, agent files | §4 applied after the founder agrees (the CLAUDE.md line only with explicit founder consent) |
| W0-2 | orchestrator | `.github/workflows/ci.yml` | `scanner` job (`apps/scanner/**`, `packages/core/**`, `docs/gate-vectors.json`, `docs/token-vectors.json`, `design/tokens.json`): format + `flutter analyze --fatal-infos` + `flutter test`; the `backend` filter adds `docs/gate-vectors.json` |
| W0-3 | orchestrator | git | `feat/lot-2-gate` from `main` after PR #2 merges |
| W0-4 | founder | — | Q28 hardware, Q29 channel; 2 Android phones (10+) for staging; `GATE_MANIFEST_SIGNING_KEY` (Ed25519 PKCS#8) in staging secrets (Q24); ask the club about re-entry (Q33) and the stadium gate plan |

### Tasks

`backend` is serial and starts first. `flutter` is the critical path (13.5 d). Rows marked ∥ run alongside the backend task of their wave, in disjoint folders. A client task marked *(contract-first)* may start on the §4 shapes with fixtures; it is done only after one run against the real endpoint.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-17 | backend | `backend/…/gate` (new module), `…/tenancy/adapters/web/SecurityConfig` (gate chain before `otherChain`), `…/catalog/application/VenueService` (`GATE_IN_USE`), V11, `GateVectorsTest`, `docs/gate-vectors.json` (signing + fingerprints) | V11. Device-signature filter per §Gate auth (cached body, JDK `SHA256withECDSA`, ±5 min, `last_seen_at`). Enrol codes via the existing `JwtEncoder` (`aud=enrol`). `POST /gate/devices/enrol`. `GET /org/devices`, `POST …/enrol-codes`, `/approve`, `/revoke` with audit | `DeviceApiTest` (software P-256 keys): valid signature → 200; one changed byte in path, query, time or body → 401; time +6 min → 401 `DEVICE_CLOCK_SKEW` with `server_time`; `PENDING` → 403 on a manifest call; revoke → 403 `DEVICE_NOT_ACTIVE`; enrol twice with one key → same id; expired or `aud=org` code → 401; org B's device → 404 on approve; approve revoked → 409. `GATE_IN_USE` once an assignment exists. `MigrationsTest`: V11 applies, allowlist still 4. `GateVectorsTest` writes, then a rerun leaves `docs/gate-vectors.json` unchanged | W0 | 2.5 |
| 1 ∥ | FL-10 | flutter | `apps/scanner/` (incl. `android/` Kotlin channel) | Scaffold mirroring `apps/fan`: Android only (minSdk 29), Riverpod, go_router, l10n FR (+ar/en with FR fallback), theme from `design/tokens.json` (run `apps/fan/tool/gen_tokens.dart` with an output path, no copy), Drift schema per 05 in its own isolate, `jomhor_core` path dep. Kotlin `MethodChannel` `DeviceKey`: generate a non-exportable P-256 key (`PURPOSE_SIGN`), return SPKI, sign bytes. Signed HTTP client per §Gate auth, offset from `server_time`, one retry on `DEVICE_CLOCK_SKEW`. `FLAG_SECURE` on the activity | Tests: canonical string and body hash = `gate-vectors.json` `signing` (pending until BE-17 lands, then required); the client sends the 3 headers; offset = midpoint maths; skew → offset fixed + one retry; Drift creates every table of 05; no hard-coded strings or colours (reuse the fan app's tests) | W0 | 3 |
| 1 ∥ | BO-12 | backoffice | `web/backoffice/src/features/events` (Règles) | Ré-entrée shown as read-only "Aucune"; the form never sends `ONE_EXIT` | Test: the PATCH payload has no `reentry_policy`, or `NONE` | W0 | 0.5 |
| 2 | BE-18 | backend | `backend/…/tenancy` (staff API, PIN), `…/gate` (assignments, `/gate/events`), `…/catalog` (`ONE_EXIT` 422), `docs/gate-vectors.json` (pins) | `/org/staff` (list, create GATE/SUPERVISOR with a `SecureRandom` PIN and a PBKDF2 verifier, PATCH, `/pin` + audit). `GET/PUT /org/events/{id}/devices`. `GET /gate/events` with the staff list. Re-entry guard | Tests: create → `pin` of 4 digits, `has_pin`; GET never shows `pin` or the verifier; the stored verifier accepts the PIN and refuses PIN+1; GATE role → 403 on `/org/staff`; PUT with another org's device or a gate of another venue → 422 at the right index; removing a device with a scan → 409 `DEVICE_IN_USE`; `/gate/events` lists only this device's assignments and staff of its organizer only, with no name beyond `label`; `ONE_EXIT` → 422 `NOT_SUPPORTED` | BE-17 | 2 |
| 2 ∥ | FL-11 | flutter | `apps/scanner/lib/features/scan` | Pure validator (steps 1–8, 02 order) over Drift lookups + in-memory K_e; scan screen: `mobile_scanner` camera + keyboard-wedge field (PDA); result screens per DESIGN 6 (green auto-clears after 1.5 s, red stays until dismissed, WRONG_ZONE gate codes, DUPLICATE time + gate, "Validé hors ligne en {ms} ms"); counters (entered here, refused, rate/min) | Tests: one case per result code with tokens from core (`encodeStatic`/`encodeRotating`); order (a wrong-event token with a bad tag → WRONG_EVENT; a bad tag on an unknown ticket → BAD_SIG); slot −2/−1/+1/+2 with offset; REFUNDED → CANCELLED; scan + used in one transaction (a forced failure leaves neither); a second OK of a ticket → DUPLICATE with the first gate; the validator built with an HTTP client that throws on any call still works; 45,000-row manifest: p95 per scan < 50 ms on CI | FL-10 | 3 |
| 2 ∥ | BO-8 | backoffice | `web/backoffice/src/features/devices` | B6 Appareils tab: list, "Ajouter des appareils" (enrol code → QR with one QR library at its latest version, countdown to `expires_at`), Approuver (fingerprint shown), Révoquer (confirm). Sidebar "Appareils" enabled | Tests: the QR encodes exactly `enrol_code`; PENDING first; revoke asks for confirmation; query keys include the organizer | BE-17 | 2 |
| 3 | BE-19 | backend | `backend/…/gate` (manifest), `…/tickets/application` (manifest rows read: id, zone, status, initials only), `application.yml` (`gate.manifest-lead`, `GATE_MANIFEST_SIGNING_KEY`), `docs/gate-vectors.json` (manifest) | Ed25519 key from env (startup fails outside dev/test if missing; fixed dev key in `application-dev.yml`). Payload builder per §Manifest payload. Build job every 60 s: events `ON_SALE` with `now ∈ [T0 − lead, T0 + 30 min]`, last version ≥ 5 min old, rows changed → insert (unique `(event_id, version)` absorbs a race). `GET /gate/events/{id}/manifest` full/delta; K_e via `withEventKey` on full only | Tests: payload = fixture byte for byte; signature verifies with the public key; a sale after v1 → the v2 delta has exactly that `T`; `since` = latest → no `T`; `since` = 99 → full with `event_key`; `event_key` decodes to K_e (a token signed with it verifies through `TicketToken`); no change → no new version; two jobs in parallel → one version; before the lead → 409 `MANIFEST_NOT_READY` + `available_at`; CLOSED → 409; unassigned → 404; the payload and JSON contain no `holder_name` (fixture name searched); `manifest_version` recorded; gzip response < 1.5 MB for 45,000 rows | BE-18 | 3 |
| 3 ∥ | FL-12 | flutter | `apps/scanner/lib/features/enrol`, `…/features/event`, `…/features/staff` | Enrolment (key, scan QR, label, fingerprint, poll `/gate/events` until ACTIVE); staff unlock (name list + PIN, PBKDF2 via `cryptography`, 5 failures → 5 min, 10 min idle lock); event mode: pick the assignment, full download, Ed25519 verify with the pinned key (`cryptography`), atomic replace, K_e into `flutter_secure_storage`, delta apply + rows check, drift > 20 s → refused | Tests: `gate-vectors` `manifest` and `pins` golden; a tampered payload → tables and version unchanged ("last good kept"); a delta with the wrong `since` or a rows mismatch → full refetch; the SQLite file bytes never contain K_e; offset 21 s → event mode refused, 19 s → allowed; idle lock at 10 min (fake clock); unlock offline | BE-18, BE-19 *(manifest part contract-first)* | 3 |
| 3 ∥ | BO-9 | backoffice | `web/backoffice/src/features/devices` (Équipe tab), `…/features/events` (B2 Appareils tab) | Équipe de porte: list, add → PIN dialog shown once, reset PIN, deactivate. B2 Appareils: assignment table (ACTIVE devices × venue gates select), manifest version, last seen, wipe ack; `DEVICE_IN_USE` copy | Tests: the PIN never reaches the query cache (dialog state only, cleared on close); the PUT payload sends every row; 409 names the devices | BE-18 | 2 |
| 4 | BE-20 | backend | `backend/…/gate` (ingestion, closed-ack), `…/catalog` (`/close`) | `POST /gate/events/{id}/scans` per the sync rules (advisory lock, ON CONFLICT, row checks, audit of refused rows, `used_since` + cursor + `more`). `POST /org/events/{id}/close`. `POST /gate/events/{id}/closed-ack` | `ScanIngestionTest`: the same batch twice → one row each, both acked; 501 rows → 422; one bad row among 3 → 2 stored, 3 acked, 1 in `errors` with its field + an audit row; `ticket_id` of another event → refused; OVERRIDE by a GATE staff → refused; `used_since` excludes the caller's own rows; **cursor safety:** 3 devices posting 30 batches in parallel while a 4th polls → the 4th receives every admitted ticket exactly once; 6,000 remote admits → `more`, then complete; scans after close → stored, `event_status: CLOSED`; closed-ack before close → 409; close before T0 → 409; no UPDATE/DELETE on `scans` (grep test on the source) | BE-19 | 2.5 |
| 4 ∥ | BO-10 | backoffice | `web/backoffice/src/features/live` | B4 per DESIGN + §4.6: KPIs, gate table with charge bar, stale-device amber row, close dialog; `refetchInterval` 5 s; route `/events/:id/live` + sidebar "Portes en direct" (→ events list when no event is chosen) | Tests: charge maths and colours (< 70 % green, < 90 % amber, else red); the stale row is a warning, not an error; close confirm → POST → status badge; polling pauses when the tab is hidden (TanStack default kept) | BE-20 (close); `/live` *(contract-first)* until BE-21 | 2 |
| 5 | BE-21 | backend | `backend/…/gate` (read models), `backend/src/test/…/Lot2GateFlowTest`, `backend/http/lot2-gate.http` | `GET /org/events/{id}/live`, `/reconciliation`, `.csv` per §Gate reports (a window function picks the canonical scan). Exit test | `GateReportsTest`: A OK at 20:00:10 synced second, B OK at 20:00:05 synced first → canonical = B, `entered` 1, `duplicates_late` 1; rates over both 5-min windows; stale/online thresholds; `final` false until both acks; CSV BOM, `;`, FR headers; FINANCE 200, GATE 403, another org 404. **`Lot2GateFlowTest`:** sell 3 tickets (Lot 1 flow) → enrol 2 devices → approve → assign → manifest v1 verified → A: OK t1, WRONG_ZONE t2 → B: OK t1 (earlier device time, synced later), OVERRIDE t3 → B's poll returns t1 from A → close → both drain + ack → reconciliation `final`, sold 3, entered 2 (t1, t3), refused WRONG_ZONE 1, duplicates_late 1, overrides 1 | BE-20 | 3 |
| 5 ∥ | FL-13 | flutter | `apps/scanner/lib/features/sync` | Sync loop: every 10 s and right after a scan, ≤ 500 rows, backoff 2 → 60 s, mark acked (errored ones too), merge `used_since` (earliest `first_at`), loop on `more`, delta when `manifest_version` is ahead (+ `/gate/events` refresh), CLOSED → drain → wipe → closed-ack; REVOKED → wipe all. Top line: online/offline + unsynced count | Tests (fake server): 1,200 offline scans → 3 batches, none lost, none resent after ack; repeated 500s → 2, 4, 8 … 60 s; kill/restart keeps unsynced rows; wipe only once unsynced = 0; REVOKED wipes K_e + tables; a sync tick during a scan keeps the scan inside the p95 budget | BE-20 *(contract-first until then)*, FL-12 | 2.5 |
| 5 ∥ | BO-11 | backoffice | `web/backoffice/src/features/reconciliation` | B5 per §4.6, CSV download through the API client (Bearer + `X-Organizer-Id`, blob) | Tests: Provisoire/Définitive badge from `final`; totals add up from fixtures; the CSV request carries the organizer header | BE-21 *(contract-first)* | 1.5 |
| 6 | FL-14 | flutter | `apps/scanner/lib/features/supervisor`, `…/features/lookup`, `apps/scanner/test/gate_flow_test.dart` | Supervisor sheet (SUPERVISOR name + PIN → Dérogation / Changer de porte / Recherche), override with reason + ticket (scan or 8 characters, multiple matches), local cap of 20 per event; gate change from the manifest gates; lookup (zone, status, initials only). Exit test | Tests: the OVERRIDE row carries `supervisor_id` + reason and marks used; the 21st is refused with copy; GATE staff can't open the sheet; 2 matches → picker; gate change → allowed zones follow; **`gate_flow_test`:** fake server serving a vectors-signed manifest → enrol, unlock, scan offline (OK, WRONG_ZONE, OVERRIDE), reconnect → batch acked, a remote used row → DUPLICATE, CLOSED → wiped + ack | FL-11, FL-12, FL-13 | 2 |
| exit | — | orchestrator + founder | — | Demo per §1 on staging with 2 phones | `Lot2GateFlowTest` + `gate_flow_test` + all client checks green; manual airplane-mode demo; dry-run date set | all above | — |

`reviewer` runs after every builder task.

### Lanes and critical path (working days, before review loops)

| Agent | Sequence | Ends |
| --- | --- | --- |
| backend | BE-17 0–2.5 → BE-18 2.5–4.5 → BE-19 4.5–7.5 → BE-20 7.5–10 → BE-21 10–13 | d13 |
| flutter (critical) | FL-10 0–3 → FL-11 3–6 → FL-12 6–9 (the manifest part needs BE-19 at d7.5) → FL-13 9–11.5 (real endpoint at d10) → FL-14 11.5–13.5 | d13.5 |
| backoffice | BO-12 0–0.5 → BO-8 2.5–4.5 → BO-9 4.5–6.5 → BO-10 7.5–9.5 → BO-11 9.5–11 → smoke against BE-21 at d13 | d13 |

### Budget check (3 weeks = 15 working days)

| Agent | Sum | ×1.1 review | Fits? |
| --- | --- | --- | --- |
| backend | 13 | 14.3 | Yes, 0.7 d slack |
| flutter | 13.5 | 14.9 | Just. **No slack**: any Keystore or camera surprise on the real phones spills into week 4 |
| backoffice | 8 | 8.8 | Yes, about 6 d free |

Verdict: 3 weeks holds only if the cuts below are taken at the first slip. The pilot itself is gated by the dry run and the hardware (Q28), not only by build time.

If late, cut in this order: (1) offline gate change (re-assign in B2 while there is signal); (2) keyboard-wedge input, if Q28 says phones only; (3) `rate_per_min_prev` and the `by_device` override list (keep the total + cap warning); (4) reconciliation CSV (the B5 page stays); (5) "deactivate" in the B6 Équipe tab (keep add + reset PIN).

Never cut: no network on the scan path; manifest signature check with a pinned key; scan + used in one transaction; idempotent ingestion with `acked` covering errors; the per-event ingestion lock (cursor safety); append-only scans; wipe on close and on revoke; supervisor PIN + reason + cap; first scan wins by device time; the vectors golden tests on both sides.

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Gate path offline | `features/scan` imports no HTTP client. The validator gets K_e, the manifest DAO and the clock injected, and runs with a client that throws. Validation order exactly 02:78–85. The slot window uses the **corrected** clock, `abs(slot − now) ≤ 1`. Tag compare goes through core (constant time). Every outcome is logged, red or green. |
| K_e on devices | Only in `flutter_secure_storage`, in memory only in event mode, never in SQLite, logs, crash reports or `toString`. Wiped on CLOSED (after the drain) and on REVOKED. Server side: base64 only inside `withEventKey`, full manifest only, `no-store`, never in `audit_log` or errors. `FLAG_SECURE` on the scanner. Residual risk, written down: a rooted, unlocked device can extract K_e and clone manifest tickets until close; first scan wins and the close limit it. |
| Device key and revocation | P-256 key generated in Keystore with `PURPOSE_SIGN`, never exported, regenerated on re-enrol. Server: signature checked over the exact string (path with query, raw body hash), ±5 min, status checked after the signature. REVOKED → 403 on every gate path, scans included. Approve needs ADMIN/OPS and a device of the same organizer. The enrol JWT has `aud=enrol`: it can't be used as an org token and vice versa. |
| Manifest integrity | Signed bytes = the payload sent (no re-serialisation). The pinned key comes from enrolment, never from the manifest response. A failed verify changes nothing locally. The delta chain checks `since == local version` and the `rows` count. The payload holds initials only (search for `holder_name` and phone). Text fields are stripped of TAB/CR/LF. |
| Replay | Gate GETs are harmless; scans are idempotent on `scan_id`; enrol is idempotent on the key; no state change keyed on a request without `scan_id`. `closed-ack` is idempotent. |
| Clock skew | The offset is applied to `device_time`, the slot and the signature time. Drift > 20 s refuses event mode. The server never rejects a row for skew and stores `received_at`. Canonical = device time, so a device with a bad clock can steal "first": visible in reports, accepted. |
| Sync tampering and correctness | `organizer_id`/event/device scoping on every row; `ticket_id` belongs to the event; the supervisor is a SUPERVISOR; refused rows audited. The advisory lock is held for the whole insert transaction; `used_since` goes by `seq` only. The device marks synced only acked ids and never deletes unsynced rows. A compromised enrolled device can post fake OKs (DoS: legit fans get DÉJÀ UTILISÉ elsewhere): every row carries `device_id`, so revoke + audit. |
| Append-only | No `UPDATE`/`DELETE` on `scans` in the code (grep); `DUPLICATE_LATE` never written; check constraint on the override columns. |
| PINs | PIN generated with `SecureRandom`, returned once, never logged, never in the back-office query cache; verifier format exact; only GATE/SUPERVISOR rows shipped; lockout on the device. |
| Tenancy | Every new table has `organizer_id` and every query filters on it; devices, staff, assignments and reports of org A → 404 for org B; the `/gate/events` staff list holds the device's organizer only. |
| Contract | Every new or changed 04 row has a contract test; error codes exactly as 04; `V11` names = 03; Drift tables = 05; both vectors files unchanged unless the layout changed. |
| Residual risks (accepted) | The PIN lockout runs on the corrected wall clock: moving the system clock forward ends it early (Q31: the PIN is a UI lock, the device key is the boundary). Casablanca offsets follow the bundled tzdata (JDK and the scanner's `timezone` package, 2026e = UTC+0 in Nov 2026): keep both current. |
| Clients | No hard-coded strings (FR via l10n/i18n); the scanner result is never colour-only (word + icon); touch targets ≥ 44 px; B4 stale devices use warning copy; organizers addressed with "vous". |

## 7. Carried forward

| Item | Lot | Note |
| --- | --- | --- |
| `GATE_IN_USE` on gate delete (lot-0 §7) | 2 | BE-17 |
| Kafka for the dashboard (lot-1 §4.5) | 4 / 6 | Q32: DB polling in Lot 2 |
| App-only tickets flag (lot-1 Q17) | 5 | Q36 |
| Re-entry `ONE_EXIT` | when the club asks (+2 d) | Q33; the API refuses it meanwhile |
| Per-event gate → zone override (lot-0 Q5) | when asked | Q35 |
| `/org/tickets/{id}/block`, `/unblock`, `/refund` | 3 | Q39 |
| `GATES_OPEN` status transition | 3 | Q38: the sales window must accept it first |
| Fan app "Tu es dedans" after scan sync | 4 | Q42; first real Kafka consumer candidate |
| Bluetooth used-set gossip | post-V1 | 02:106 |
| K_e as a non-exportable Keystore HMAC key | 6 | A Keystore HMAC per scan over a platform channel; measure against 150 ms first |
| Manifest key rotation (re-enrol today) | 6 | `manifest_public_key` pinned at enrolment |
| PDA vendor intents (Sunmi, Zebra DataWedge intent) | 6 | Keyboard wedge in Lot 2 (Q28) |
| Scanner kiosk / MDM, device fleet runbook | 6 | Q29 |
| Retention: `manifests.body` after close, refused-row audits, scans | 6 | ~1 MB per version per event; CNDP |
| Back-office staff (OTP users) in `/org/staff` | 5 | Lot 2 creates GATE/SUPERVISOR only; others still by SQL (lot-0 Q12) |
| "Scans en attente" per device on B4 | 6 | The device could report its unsynced count on each sync; dropped from Lot 2 |
| `sold` definition once refunds and blocks exist | 3 | §Gate reports uses VALID only |
| Deploy env added in Lot 2 | Q24 | `GATE_MANIFEST_SIGNING_KEY` (Ed25519 PKCS#8, base64; required outside dev/test), optional `GATE_MANIFEST_LEAD` (default `3h`) |
