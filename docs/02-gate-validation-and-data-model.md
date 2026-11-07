# Gate validation protocol & data model

As of 2026-11-05 · source of truth: this file (mirrored from the Claude Doc "Spec — Offline gate validation & data model").

## Scope & assumptions

V1 validates every ticket at the gate with no server round-trip: the scanner holds the event's ticket list and signing key, and syncs scans in the background whenever it has signal. The persistence layer is event-agnostic: a football match, a concert and a conference day are all rows in `events`; football lives only in UI, theming and per-type rules.

- First customer: one club, one home stadium, all home matches; attendance up to 45,000 per match.
- Three sales channels, all producing the same ticket record: fan app (rotating QR), web shop (PDF, static QR), cash points of sale (thermal print, static QR).
- Scanners are Android phones or Android PDAs (Sunmi L2 / Zebra TC2x class) running one Flutter scanner app. No turnstiles, no fixed network at the gates.
- Design assumption: zero usable connectivity at the gates from 60 min before the event start (T0) to 30 min after. Nothing on the critical path may call the server.
- Server side: Spring Boot 4 / Java 25, PostgreSQL 18. Scanner side: Flutter + SQLite, keys in Android Keystore.
- Out of scope for v1: resale, away matches, NFC season cards, turnstile integration.

## Actors, devices & trust boundaries

The server is the only source of truth for tickets; a scanner is a trusted-but-losable device that holds one event's key and ticket list for a few hours.

| Actor / component | Role | Trusts | Holds |
| --- | --- | --- | --- |
| Ticketing API (Spring Boot) | Issues tickets, builds manifests, receives scans, resolves duplicates | Nothing external | All ticket records, master key, event keys |
| Fan app (Flutter) | Shows rotating QR, works offline once the ticket is loaded | Server over TLS, logged-in user | Per-ticket key for the user's own tickets |
| Web shop / PDF, POS print | Static QR tickets | Server | Nothing after issue |
| Scanner app (Flutter) | Validates offline, logs scans, syncs | Server (signed manifest), enrolled device key, staff PIN checked locally | Event key, manifest, local scan log |
| Gate sync API | Enrols devices, serves manifest + deltas, ingests scans | Device signature | Device registry |
| Back-office (React) | Event setup, gate/zone mapping, live counts, reconciliation | Organizer admin login | Reports |
| Supervisor | Overrides at the gate with PIN + reason | Organizer | Override PIN |

Trust rules: a scanner never decides a ticket is valid from the QR alone — the ticket must also be in the signed manifest. A lost scanner is revoked server-side; its event key is useless after the event closes.

See `docs/01-architecture.md` for the system diagram.

Sales channels and the back-office talk to the API online; the scanner is the one component built to work with the server unreachable.

## Ticket token & QR format

Two token types share one binary layout; the scanner tells them apart by the version byte and verifies both offline with the event key.

| Field | Size | Static (PDF / print) | Rotating (fan app) |
| --- | --- | --- | --- |
| version | 1 byte | 0x01 | 0x02 |
| event\_id | 4 bytes | yes | yes |
| ticket\_id | 16 bytes (UUID) | yes | yes |
| zone\_id | 2 bytes | yes | yes |
| slot | 4 bytes | absent | floor(unix\_time / 30) |
| tag | 16 bytes | HMAC-SHA256(K\_e, fields), truncated | HMAC-SHA256(K\_t, fields incl. slot), truncated |

Encoding: base64url of the 39 or 43 bytes, 52–58 characters, QR version 4 at error correction M. Short payload = fast decode on a phone camera and a small, high-contrast code on a thermal print.

Keys:

- K\_e = per-event key, 32 bytes, generated server-side when the event is created, delivered only to enrolled scanners.
- K\_t = per-ticket key = HMAC-SHA256(K\_e, ticket\_id). Delivered to the fan app with the ticket, only to the owning account. The scanner recomputes it from K\_e offline, so no per-ticket keys travel in the manifest.
- Scanner accepts slot ± 1 (a 90-second window), which absorbs clock skew and makes a screenshot useless within a minute or two.

Rules: no personal data in the QR. The ticket number (last 8 chars of ticket\_id, grouped 4-4) is printed under every QR for manual lookup. The fan app shows the rotating QR offline, since K\_t is already on the phone.

## Match manifest & key distribution

Every scanner downloads a signed snapshot of the event's tickets before it loses signal; later changes arrive as small deltas while signal lasts. T0 below = the event's `starts_at`.

1. Device enrolment (once per device): the scanner generates an ECDSA P-256 keypair in Android Keystore and scans an enrol QR from the back-office. The admin approves the device after matching its fingerprint. Every gate call is signed by the device key (docs/04-api.md §Gate auth). Staff PINs are checked on the device.
2. Event assignment: an admin assigns devices to an event and a gate. Only assigned devices can pull that event's manifest and K\_e.
3. Manifest build: the server builds manifest v1 at T0 − 3 h (config), then checks every minute until T0 + 30 min; a new version is built only when the payload changed (tickets, zones, gates or rules) and the previous one is at least 5 minutes old. Each version is Ed25519-signed by the server over its text payload.
4. Download: the scanner pulls the full manifest once (45,000 rows × \~40 bytes ≈ 1.8 MB before compression, under 1 MB gzipped), then `GET /gate/events/{id}/manifest?since=vN` for deltas. K\_e travels in the same authenticated response and is stored with `flutter_secure_storage` (Keystore-wrapped).
5. Verify: the scanner refuses any manifest whose signature fails and keeps the last good one.
6. After event close: the server marks the event closed; on next sync the scanner wipes K\_e, the manifest and the synced scan log.

Manifest row: ticket\_id, zone\_id, status (VALID, CANCELLED, REFUNDED, BLOCKED), holder\_initials. The re-entry policy and the venue gates and zones come in the payload. No full names, no ID numbers.

Accepted risk: a ticket cancelled after a scanner's last delta still scans green at that gate. This is bounded by freezing refunds and cancellations at T0 − 60 min, the moment signal is assumed to be gone.

## Offline validation algorithm

A scan is a straight run of checks; the first failure stops it with a reason code, and every outcome, green or red, is written to the local log. Target: under 150 ms from decode to beep.

1. Decode base64url; reject malformed input (`BAD_FORMAT`).
2. Read the version byte; unknown version → `BAD_FORMAT`.
3. Compare event\_id with the loaded event → `WRONG_EVENT`.
4. Verify the tag. Static: HMAC(K\_e, fields). Rotating: derive K\_t = HMAC(K\_e, ticket\_id), check slot is within ±1 of the device's current slot (`EXPIRED_SLOT`), then HMAC(K\_t, fields). Constant-time compare → `BAD_SIG`.
5. Look up ticket\_id in the local manifest. Absent → `UNKNOWN`. Status not VALID → `CANCELLED` / `BLOCKED`.
6. Check zone\_id against the gate's allowed zones → `WRONG_ZONE`.
7. Check the local used-set (own scans merged with scans received from other devices). Already entered and re-entry not allowed → `DUPLICATE`, showing the first scan's time and gate on screen.
8. Write the scan row (UUIDv7, ticket\_id, device\_id, gate\_id, device\_time, result, direction) in the same SQLite transaction that adds the ticket to the used-set. Then green screen, long beep, haptic.

Result codes: `OK`, `DUPLICATE`, `BAD_SIG`, `BAD_FORMAT`, `UNKNOWN`, `CANCELLED`, `BLOCKED`, `WRONG_ZONE`, `WRONG_EVENT`, `EXPIRED_SLOT`, `OVERRIDE`.

UI rule for reds: one short word in large type plus the reason, and the ticket number, so the supervisor can act without re-scanning. Red screens stay until dismissed; green screens auto-clear after 1.5 s.

## Scan log, sync protocol & cross-gate duplicates

Scans are append-only on the device and idempotent on the server, so a sync can be retried any number of times and a device can be lost mid-event without corrupting the record.

Sync loop (off the scan path: Drift runs in its own isolate and the loop never blocks a scan; it runs whenever the device has any connectivity):

1. Every 10 s, or immediately after a scan if online: `POST /gate/events/{id}/scans` with up to 500 unsynced rows, signed by the device key.
2. Server inserts with `ON CONFLICT (scan_id) DO NOTHING` and returns: acknowledged scan\_ids, the current manifest version, and `used_since=<cursor>` — the ticket\_ids first-scanned on other devices since this device's last cursor (the server arrival sequence, `scans.seq`).
3. Device marks rows synced, merges the remote used-set into its local one, and pulls the manifest delta if its version is behind.
4. On failure: exponential backoff from 2 s to 60 s, never drops rows. Rows survive app restarts; the local log is only truncated after the server closes the event.

Duplicate resolution on the server: the canonical entry for a ticket is the admitted (OK/OVERRIDE) scan with the earliest device\_time (tie: lowest scan\_id; a device that merges remote admits keeps its local row on an exact tie, so its screen may show the other gate in that rare case); later admitted scans for the same ticket and direction are **reported** as `DUPLICATE_LATE`. Scans are never updated. Both the device clock and the server receive time are stored, so clock drift is visible rather than hidden.

Clocks: devices sync time from the server at every sync and at enrolment; drift = abs(server offset); above 20 s the app refuses event mode until the device clock is fixed. The offset is applied to every timestamp and slot.

V2 option, not in v1: Bluetooth gossip of the used-set between scanners at the same gate, so duplicates propagate even with zero mobile signal.

End of event: once all assigned devices have synced, the back-office shows per zone and per gate: tickets sold, scanned in, rejected by reason, overrides. That report is the organizer's attendance figure and the basis for settlement.

## Data model

Tickets are immutable once issued; state lives in `status` and in the scans table, never by editing a ticket row. Nothing in the schema is football-specific: an event carries an `event_type` and a `metadata jsonb` for type-specific fields, and every table carries `organizer_id` so the second organizer is a config change, not a migration.

Server (PostgreSQL):

| Table | Purpose | Key columns |
| --- | --- | --- |
| organizers | Tenant (club, promoter, venue operator) | id, name, kind (SPORTS\_CLUB, PROMOTER, VENUE, OTHER), settings jsonb |
| venues | Stadium, hall, open-air site | id, organizer\_id, name, capacity |
| zones | Tribune / sector / standing area | id, organizer\_id, venue\_id, code, name, capacity |
| gates | Physical entrance | id, organizer\_id, venue\_id, code, name |
| gate\_zones | Which zones a gate serves | organizer\_id, gate\_id, zone\_id |
| event\_series | Season, festival edition, tour | id, organizer\_id, name, starts\_on, ends\_on |
| events | One ticketed occurrence | id, organizer\_id, venue\_id, series\_id (nullable), event\_type (FOOTBALL\_MATCH, CONCERT, FESTIVAL\_DAY, CONFERENCE, OTHER), title, starts\_at, doors\_open\_at, status (DRAFT, ON\_SALE, GATES\_OPEN, CLOSED), event\_key\_enc, refund\_cutoff\_at, sale\_starts\_at, reentry\_policy, metadata jsonb (opponent, competition, headliner…) |
| price\_categories | Price per zone per event | id, organizer\_id, event\_id, zone\_id, name, price\_mad, quota, sold |
| orders | Purchase | id, organizer\_id, event\_id, user\_id, channel (APP, WEB, POS), total\_mad, payment\_ref, status |
| order\_items | Lines of an order | id, organizer\_id, order\_id, price\_category\_id, qty, unit\_price\_mad, holders jsonb (name, user\_id per place) |
| payments | Payment attempts | id, organizer\_id, order\_id, method, provider, amount\_mad, status, provider\_ref |
| tickets | One seat / admission | id (UUID), order\_id, event\_id, zone\_id, seat\_label, status (VALID, CANCELLED, REFUNDED, BLOCKED), holder\_user\_id, holder\_name (never in a manifest), holder\_initials, issued\_at |
| passes | Season ticket / multi-event bundle | id, series\_id, user\_id, zone\_id, status; materialised as one ticket per event |
| devices | Enrolled scanners | id, organizer\_id, label, public\_key (ECDSA P-256 SPKI DER), status (PENDING, ACTIVE, REVOKED), enrolled\_at, approved\_at, revoked\_at, last\_seen\_at |
| device\_assignments | Device ↔ event ↔ gate | organizer\_id, device\_id, event\_id, gate\_id, manifest\_version, closed\_ack\_at |
| manifests | Signed snapshots | id, organizer\_id, event\_id, version, built\_at, row\_count, signature (Ed25519 over the text payload), body (gzip) |
| scans | Append-only gate log | scan\_id (UUIDv7, PK), seq (server arrival order), organizer\_id, event\_id, ticket\_id, device\_id, gate\_id, staff\_id, direction (IN, OUT), result, token\_version, device\_time, received\_at, override\_reason, supervisor\_id |
| staff | Back-office staff, gate staff & supervisors | id, organizer\_id, user\_id (OTP login, nullable), name, role, pin\_hash (PIN verifier, GATE/SUPERVISOR) |
| users | Fan / staff identity (platform-wide, no organizer\_id) | id, phone\_e164, display\_name, locale |
| otp\_challenges | Phone OTP (platform-wide) | id, phone\_e164, audience (fan, org), ip, code\_hash, attempts, expires\_at |
| fan\_sessions | Revocable fan login sessions (platform-wide) | id (JWT sid), user\_id, expires\_at, revoked\_at |
| ticket\_deliveries | WhatsApp delivery outbox | id, organizer\_id, ticket\_id, status (PENDING, SENT, FAILED), attempts, next\_attempt\_at |

Indexes that matter: `scans (event_id, ticket_id, device_time)` for canonical-first resolution; `scans (event_id, seq)` for `used_since`; `scans (event_id, gate_id, device_time)` for the live dashboard; `tickets (event_id, status)` for manifest builds; a GIN index on `events.metadata` only if type-specific search is needed. A partial unique index on `scans (ticket_id, direction) WHERE result = 'OK'` is deliberately not used, because the first `OK` must be chosen by device\_time after the fact, not by arrival order.

Scanner (SQLite):

| Table | Purpose | Key columns |
| --- | --- | --- |
| config | One row | device\_id, device\_status, manifest\_public\_key, server\_time\_offset\_ms, event\_id, starts\_at, event\_status, gate\_id, manifest\_version, reentry\_policy, sync\_cursor, staff\_id, mode |
| zones, gates | Venue copy from the manifest | zone\_id, code, name · gate\_id, code, name, zone\_ids |
| staff | Gate staff and PIN verifiers | staff\_id, label, role, pin\_verifier |
| manifest\_tickets | Local copy | ticket\_id (PK), ticket\_no, zone\_id, status, holder\_initials |
| used | Used-set, own + remote | ticket\_id, direction, first\_at, gate\_code, source (LOCAL, REMOTE) |
| scans | Local log | scan\_id (PK), ticket\_id, gate\_id, staff\_id, direction, result, token\_version, device\_time, synced (0/1), override\_reason, supervisor\_id |

K\_e never sits in SQLite; it lives in `flutter_secure_storage` (Android Keystore-wrapped) and is wiped at event close.

## Gate, zone & re-entry rules; supervisor override

A ticket opens any gate that serves its zone, once, unless the event's re-entry policy says otherwise; everything outside that rule goes through a logged supervisor override.

- Zone binding: `gate_zones` decides acceptance. A Tribune Nord ticket at a Virage gate is `WRONG_ZONE`, with the correct gates named on screen.
- Re-entry policy per event: `NONE` (default), or `ONE_EXIT` (one `OUT` scan then one `IN` scan allowed, within the event window). An `OUT` scan requires the scanner to be in exit mode, so a fan cannot trigger it by accident. Lot 2 implements `NONE`; the API refuses `ONE_EXIT` until it is built.
- Device mode is locked to a gate at event start; changing gate requires a supervisor PIN and works offline (gates come in the manifest).
- Supervisor override: PIN, a reason from a fixed list (ID\_CHECKED, DEVICE\_FAULT, DAMAGED\_TICKET, OTHER), and the ticket must be found in the manifest (scanned, or a typed number that lists every match). Produces a scan row with result `OVERRIDE`; capped at 20 per device per event, with the count shown live in the back-office.
- Manual lookup: by the printed ticket number, shows zone, status and holder initials only. No name search at the gate.
- Staff sessions: 4-digit PIN per staff member, generated by the server, checked on the device, auto-lock after 10 min idle, every scan carries the staff\_id of the open session.

## Threat model & mitigations

The design accepts one bounded risk (a cancelled ticket passing at a stale scanner) and closes the rest with keys, the manifest and first-scan-wins.

| Threat | Mitigation | Residual risk |
| --- | --- | --- |
| Screenshot of an app ticket shared with friends | Rotating QR, 90 s acceptance window; K\_t only on the buyer's logged-in device | Two people at two gates within 90 s: the second is `DUPLICATE` after sync, or passes if both gates are offline |
| PDF ticket printed twice | First scan wins, second is `DUPLICATE` once gates have synced | Same offline window as above; mitigated by naming the gate on the red screen so staff can escalate |
| Forged QR | 128-bit HMAC tag with K\_e, which never leaves enrolled devices | None practical |
| Lost or stolen scanner | Keystore-wrapped K\_e, staff PIN lock, key wiped at event close; the P-256 device key is non-exportable; revoke → next call 403 → wipe | Attacker with an unlocked device during the event could read the manifest (no PII in it) |
| Insider issues free tickets | Every ticket comes from an order; manifests are signed; sold vs scanned reconciled per zone after every event | Collusion with POS cash sales: covered by POS reconciliation against cash deposits |
| Ticket cancelled after last delta | Refund/cancel freeze at T0 − 60 min | Cancellations in the last hour are not possible, by policy |
| Fan phone clock tampered | Server time offset pushed to the app; ±1 slot tolerance | None practical |
| Replay of an old static QR at the next event | event\_id in the token and a new K\_e per event | None |
| Bots on on-sale day | Not a gate problem: on-sale queue, per-account limits, payment-first reservation. Separate spec | — |

## Capacity sizing & match-day runbook

Size devices from the peak half-hour, not from total attendance: assume 60% of attendees arrive in the 30 minutes before T0 and one device clears about 720 people in that window at 2.5 s per scan.

```latex
\text{devices} = \left\lceil \frac{\text{attendance} \times 0.6}{720} \times 1.2 \right\rceil
```

| Attendance | Devices needed (incl. 20% spare) |
| --- | --- |
| 15,000 | 15 |
| 30,000 | 30 |
| 45,000 | 45 |

PDAs with a hardware imager scan a dim phone screen in under 300 ms; phone cameras take 1–2 s and stall on glare. Put PDAs on the busiest gates first.

Runbook:

| When | Action | Who |
| --- | --- | --- |
| T0 − 24 h | Assign devices to gates; confirm gate\_zones for this event | Organizer ops |
| T0 − 3 h | Manifest v1 built; devices charged, enrolled, in event mode, manifest + K\_e downloaded; drift check passed | Gate lead |
| T0 − 90 min | Gates open; scanners in IN mode | Gate staff |
| T0 − 60 min | Refunds and cancellations frozen | System |
| T0 + 30 min | Last manifest build; devices sync when they can | System |
| T0 + 2 h | Event closed; final sync; keys wiped; reconciliation report issued | Organizer ops |

## Open questions to settle with the club

- [ ] Does the league or FRMF require named tickets with an ID check at the gate? If yes, holder\_initials becomes a hashed ID fragment and manual lookup changes.
- [ ] Re-entry: NONE or ONE\_EXIT for the first season?
- [ ] Stadium plan: which gates serve which tribunes, and how many gates are actually staffed on a normal match vs a derby?
- [ ] Cash on match day: do kiosks at the stadium print tickets, or is cash only sold in advance at agencies?
- [ ] Who buys the devices: the club, or rented per match as part of the fee?
- [ ] Season tickets: app-only in v1, or does the club insist on a physical card?

## Fan app: design direction

The ticket is the hero object: one full-screen, club-coloured ticket card with the rotating QR, the fan's name, zone and gate, and a live countdown to kickoff. Everything else in the app exists to get the fan to that screen in one tap.

- Matchday mode: from kickoff − 4 h the app opens straight on the ticket, screen brightness forced to max, no login prompts, works with airplane mode on.
- The club's identity, not a generic ticketing look: club colours, crest, typography, and a different hero treatment per opponent (derby vs regular match).
- Motion with meaning: the QR card flips to show gate directions; a confirmed purchase animates the ticket sliding into the wallet; a successful gate scan (when online) flips the card to "You're in".
- One-thumb reach: all primary actions in the bottom 40% of the screen; seat/zone picking on a stadium map, not a dropdown.
- Sharing built in: transfer a ticket to a friend by phone number; the recipient gets their own K\_t and the sender's QR dies.
- Dark theme by default (stadiums at night), haptics on every confirmation, Arabic / French / English with RTL done properly.

Next step: mock the five core screens (home, match, seat pick, checkout, ticket) as a design the club can react to before any code.

## V1 scope, delivered in lots

One V1 covers the full platform (gate, Moroccan payments, fan platform, organizer tools); it ships in seven lots, each ending with something the club can use at a real match. Durations assume a team of 3 developers (1 backend, 1 Flutter, 1 React) plus Yazid; they are estimates to be re-cut after Lot 0.

| Lot | Scope | Usable at the end | Est. |
| --- | --- | --- | --- |
| 0 · Foundations | Tenancy (`organizers`), `events` model with `metadata jsonb`, venues/zones/gates, pricing, auth (phone OTP), card payment (Payzone; simulated gateway until the kit arrives), back-office skeleton, event creation | Admin can create an event and sell a test ticket by card | 3 wk |
| 1 · Sell & deliver | Fan app + web shop purchase flow, named tickets and per-account limits, static and rotating QR, PDF + WhatsApp delivery, ticket wallet screen, basic on-sale queue | Fans can buy and hold a ticket for a real fixture | 4 wk |
| 2 · Gate | Scanner app, offline validation, manifest + K\_e distribution, sync and duplicate resolution, supervisor override, live gate dashboard, reconciliation report | First pilot match end to end (sell → scan → reconcile) | 3 wk |
| 3 · Moroccan payments | Cash at agencies (reserve → pay code → QR unlocks), mobile wallet, POS thermal print at kiosks, cash reconciliation, installments for passes | Every Moroccan fan can pay, online or offline | 3 wk |
| 4 · Fan platform | Group buying with split pay, ticket transfer (new K\_t, old QR dies), membership + loyalty, matchday companion (gate directions, live gate load), push and WhatsApp campaigns, Darija/Arabic/French with RTL | The app people open between matches, not only on match day | 4 wk |
| 5 · Organizer platform | Accreditations and invitations (press, VIP, sponsors), promo codes, season passes (`passes`), finance module (daily settlement, VAT invoices, 48 h payouts), fan CRM export, fair on-sale (virtual queue, verified fans) | The club runs the whole season without us in the loop | 3 wk |
| 6 · Hardening & launch | On-sale load test (derby scenario), pen test on QR and sync, device fleet and runbooks, monitoring, second organizer onboarded on config only | Public launch and second customer | 2 wk |

Order matters: Lots 0–2 are the pilot match (about 10 weeks); Lots 3–5 can run partly in parallel once Lot 2 is in production; Lot 6 gates the public launch. Official resale with a price cap is the first post-V1 item.
