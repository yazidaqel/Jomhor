# API contracts (V1)

Base: `https://api.jomhor.ma/v1`. JSON, UTF-8. Money in integer centimes (`_mad`).
Times in RFC 3339 UTC. Errors: `{ "code": "ORDER_EXPIRED", "message": "...", "details": {} }`.

Three audiences, three auth schemes:

| Audience | Auth | Prefix |
| --- | --- | --- |
| Fan (app, web shop) | Bearer JWT from phone-OTP login | `/fan/...` |
| Organizer (back-office) | Bearer JWT, staff account, `X-Organizer-Id` | `/org/...` |
| Gate (scanner) | Device signature (Ed25519 over method+path+body+timestamp) + staff PIN session token | `/gate/...` |
| Card gateway | Gateway signature, verified per provider | `/payments/...` |

Error codes (Lot 0; later lots add theirs here):

| Code | HTTP | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 422 | Bad body; `details.fields{}` |
| `BAD_REQUEST` | 4xx | Request rejected by the framework before reaching a handler (bad path/query type, missing header, wrong method or media type); HTTP status kept |
| `INTERNAL_ERROR` | 500 | Anything unexpected; fixed message, `details: {}`, logged server-side, never echoes the exception |
| `UNAUTHORIZED` | 401 | Missing, invalid or wrong-audience token |
| `FORBIDDEN` | 403 | No active staff row for `X-Organizer-Id`, or role not allowed |
| `NOT_FOUND` | 404 | Unknown id, or an id of another organizer or user |
| `OTP_INVALID` / `OTP_EXPIRED` | 401 | Verify failed |
| `OTP_RATE_LIMITED` | 429 | > 3 sends per phone per 10 min |
| `EVENT_NOT_ON_SALE` | 409 | Order on an event that is not `ON_SALE` |
| `EVENT_NOT_EDITABLE` | 409 | Event PATCH, price write or publish when the status doesn't allow it (PATCH and prices: only `DRAFT`/`ON_SALE`; publish: only `DRAFT`, so the losing side of a double publish gets it too) |
| `SOLD_OUT` | 409 | `details.price_category_id` |
| `PER_ACCOUNT_LIMIT` | 409 | `details.limit`, `details.held` |
| `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE` | 409 | Pay on a non-`RESERVED` order |
| `CODE_TAKEN` | 409 | Duplicate zone or gate code in a venue |
| `ZONE_IN_USE` / `GATE_IN_USE` / `PRICE_IN_USE` | 409 | Delete of a referenced row. `gate_zones` mappings do not count: they are removed with their zone or gate. References are price categories, order items, tickets (Lot 2: device assignments, scans). |
| `QUOTA_EXCEEDS_CAPACITY` / `QUOTA_BELOW_SOLD` | 422 | Price category quota rules |
| `REFUND_FROZEN` | 409 | Refund or cancel after `refund_cutoff_at` |

No pagination in Lot 0. Lists are per organizer and small, in a stable order: by name for
venues, by code for zones and gates, by `starts_at` for events, by `zone_id` then `name` for prices,
ids ascending inside arrays such as `zone_ids`. Integer fields reject floats and strings (`50.5`,
`5000.0`, `"5000"` → 422).

Conventions: create → `201` with the resource; delete → `204`; PATCH is partial (a missing or
`null` field is left unchanged; send `""` to clear an optional text field such as `city`) and
returns the same representation as the matching GET.

## Fan

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/fan/auth/otp` | `{phone}` (E.164) → `202`; sends OTP (SMS / WhatsApp). 6-digit code, valid 5 min, 5 attempts. Max 3 sends per phone per 10 min (`OTP_RATE_LIMITED`). |
| POST | `/fan/auth/verify` | `{phone, code}` → `{token, expires_at, user:{id, phone, display_name, locale}}`. Creates the user on first login. JWT `aud=fan`, 30 days. |
| GET | `/fan/events?organizer=&from=` | Upcoming events with `from_price_mad`, `on_sale`, `sold_out` |
| GET | `/fan/events/{id}` | Event detail: zones with price, remaining, seated flag, gates per zone |
| POST | `/fan/orders` | `{event_id, channel: APP\|WEB, items:[{price_category_id, qty}], holders:[{phone?}] (Lot 1)}` → `201 {id, status: RESERVED, reserved_until, total_mad, items:[{price_category_id, zone_id, name, qty, unit_price_mad}]}`. Held 8 min. Quota taken atomically. Σ qty over the user's `RESERVED`/`AWAITING_CASH`/`PAID` orders for the event ≤ `per_account_limit`. |
| POST | `/fan/orders/{id}/pay` | `{method: CARD}` → `{payment_id, redirect_url}`: `redirect_url` is a browser page on this API (§Payments) that sends the buyer to the card gateway; each call creates a new payment attempt · `{method: CASH_AGENCY}` → `{cash_code, expires_at}` (Lot 3) · `{method: WALLET, phone}` → `{status}` (Lot 3) |
| GET | `/fan/orders/{id}` | `{id, event_id, status, total_mad, reserved_until, paid_at, items[], ticket_ids[]}`. Status polling after payment. Owner only (404 otherwise). |
| GET | `/fan/tickets` | My tickets (valid, upcoming first). Each: `{id, event, zone, gate_codes[], seat_label, k_t (base64url, only for the holder), ticket_no}` |
| GET | `/fan/tickets/{id}/pdf` | Static-QR PDF |
| POST | `/fan/tickets/{id}/transfer` | `{to_phone}` → ticket re-issued to recipient (new K_t), sender's QR invalid |
| GET | `/fan/gates/{event_id}/load` | Live gate load `[{gate_code, wait_min}]` (from scan rate) |

## Payments

Gateway-facing, no audience token. Card gateway: Payzone; until its kit arrives, a simulated
gateway (`provider = fake`) that exists only outside prod.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/payments/card/{payment_id}/redirect` | Sends the browser to the gateway: a 302 or an auto-submitting form, as the adapter decides. `410` if the payment is not `PENDING`. Never carries a merchant secret. |
| POST | `/payments/{provider}/callback` | Server to server (`fake` now, `payzone` later). Signature verified before any state change. Idempotent on `payment_id`. On approval, in one transaction: payment `SUCCEEDED` → order `PAID` → tickets issued (one per unit, holder = buyer). Request format and acknowledgement body come from the adapter. |
| GET, POST | `/payments/card/{payment_id}/return` | Browser landing after the gateway. **Never changes state.** Lot 0: plain "paiement en cours de confirmation" page. Lot 1: redirects to a fixed client URL per `orders.channel` (no caller-supplied URL). |
| GET | `/payments/fake/{payment_id}` | Simulated gateway page: amount, "Approuver" / "Refuser". Each button posts a signed callback to `/payments/fake/callback`, then lands on `/return`. Supports a delayed callback. Exists only in `dev`/`test`/`staging`; startup fails if `payments.card.provider=fake` under `prod`. |
| POST | `/payments/cash/callback` | Agency network (Lot 3), idempotent on `payment_ref` |

Late success: expiry skips orders with a `PENDING` payment younger than 20 min. A success after
expiry re-reserves the quota atomically → `PAID`; if no quota is left the order stays `EXPIRED`,
the payment is `SUCCEEDED`, and `audit_log` gets `payment.refund_required` (manual refund).

## Organizer

Every `/org/*` call except `/org/auth/*` and `/org/me` needs `X-Organizer-Id`. The server checks
for an active `staff` row (user_id = token subject). Lot 0 roles: `ADMIN`, `OPS` read and write;
`FINANCE` read only; `GATE`, `SUPERVISOR` get 403. A missing or malformed `X-Organizer-Id` → 400
`BAD_REQUEST`; no active staff row for it → 403 `FORBIDDEN`.

Fans and staff share one OTP mechanism (`otp_challenges` has no audience): a code proves the
caller holds the phone; the endpoint decides the token audience.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/auth/otp` | `{phone}` → always `202` (no staff enumeration). The code is sent only if the phone belongs to a user with an active staff row. Over the rate limit it still answers `202` and sends nothing (a 429 would reveal staff phones). |
| POST | `/org/auth/verify` | `{phone, code}` → `{token, expires_at, memberships:[{organizer_id, organizer_name, role}]}`. JWT `aud=org`, 12 h. A phone with no active staff row → `OTP_INVALID`, nothing created. |
| GET | `/org/me` | `{user:{id, phone, display_name, locale}, memberships:[{organizer_id, organizer_name, role}]}`, no `X-Organizer-Id` (feeds the organizer switcher) |
| GET, POST | `/org/venues` | `{name, city?, capacity}` → `{id, name, city, capacity}` |
| GET, PATCH | `/org/venues/{id}` | GET returns `zones[]` and `gates[]` (each gate with `zone_ids[]`) |
| POST | `/org/venues/{id}/zones` | `{code, name, capacity, seated?}` → `{id, code, name, capacity, seated}` (`id` is an int) |
| PATCH, DELETE | `/org/venues/{id}/zones/{zone_id}` | DELETE only if unreferenced (`ZONE_IN_USE`) |
| POST | `/org/venues/{id}/gates` | `{code, name}` → `{id, code, name, zone_ids:[]}` |
| PATCH, DELETE | `/org/venues/{id}/gates/{gate_id}` | DELETE only if unreferenced (`GATE_IN_USE`) |
| PUT | `/org/gates/{id}/zones` | `{zone_ids:[...]}` replaces the set. Zones must belong to the gate's venue. |
| GET | `/org/events?status=&from=` | `[{id, title, event_type, starts_at, status, venue:{id, name}, quota, sold}]` by `starts_at` |
| POST | `/org/events` | `{venue_id, event_type, title, starts_at, doors_open_at?, reentry_policy?, per_account_limit?, metadata?}` → `201` event in `DRAFT`. **K_e (32 random bytes) is generated and encrypted in the same transaction and never returned.** |
| GET, PATCH | `/org/events/{id}` | Event `{id, venue_id, series_id, event_type, title, starts_at, doors_open_at, status, refund_cutoff_at, reentry_policy, per_account_limit, metadata, created_at}`. PATCH allowed in `DRAFT`/`ON_SALE`. `venue_id` and `event_type` are fixed after create (a different value → 422). Changing `starts_at` while `ON_SALE` recomputes `refund_cutoff_at`. `metadata` in a PATCH replaces the whole object. `doors_open_at` cannot be cleared in Lot 0. |
| GET, POST | `/org/events/{id}/prices` | `{zone_id, name, price_mad, quota}` → `{id, zone_id, name, price_mad, quota, sold}`. The zone must be in the event's venue; Σ quota per zone ≤ zone capacity. A name already used for that zone in the event → 422 `VALIDATION_FAILED` on `name`. |
| PATCH, DELETE | `/org/events/{id}/prices/{price_id}` | PATCH `{name?, price_mad?, quota?}`; `zone_id` is fixed after create (a different value → 422). `quota ≥ sold`. A price change applies to new orders only (snapshot in `order_items`). DELETE only if `sold = 0` (`PRICE_IN_USE`). |
| POST | `/org/events/{id}/publish` | `DRAFT` → `ON_SALE`. Needs ≥ 1 category with quota > 0 and `starts_at > now`. Sets `refund_cutoff_at = starts_at − 60 min`. Writes `audit_log` `event.publish`. → `200` with the event (GET shape). Unmet precondition → 422 `VALIDATION_FAILED` with `details.reason` `NO_QUOTA` (`fields.prices`) or `STARTS_IN_PAST` (`fields.starts_at`). |
| GET | `/org/events/{id}/sales` | Sold / remaining per zone, by channel, revenue |
| POST | `/org/tickets/{id}/block` · `/unblock` · `/refund` | Refused after `refund_cutoff_at` (`REFUND_FROZEN`) |
| POST | `/org/events/{id}/invitations` | Bulk accreditations `[{phone?, name, zone_id, count}]` → tickets with method FREE |
| CRUD | `/org/devices` | Enrol (approve `PENDING` → `ACTIVE`), revoke |
| PUT | `/org/events/{id}/devices` | Assignments `[{device_id, gate_id}]` |
| GET | `/org/events/{id}/live` | Dashboard: per gate `{entered, rejected_by_reason, overrides, rate_per_min, last_sync_at}`; SSE at `/org/events/{id}/live/stream` |
| POST | `/org/events/{id}/close` | Marks `CLOSED`, triggers device wipe on next sync, builds reconciliation |
| GET | `/org/events/{id}/reconciliation` | Sold / scanned / rejected / overrides per zone and gate; CSV at `.csv` |
| GET | `/org/finance/settlements` | Settlement statements, VAT invoices |
| GET | `/org/crm/export.csv` | Fan base export (holders who opted in) |

## Event metadata

| `event_type` | Keys (sent inside `metadata`, never as top-level fields) |
| --- | --- |
| `FOOTBALL_MATCH` | `opponent` string, `competition` string, `matchday` int |
| `CONCERT`, `FESTIVAL_DAY` | `headliner` string |
| others | free |

The server requires a JSON object of at most 4 KB, type-checks the known keys and keeps unknown
keys. A known key sent as `null` fails the type check: leave empty keys out. The back-office
validates per type with zod.

## Gate (scanner)

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/gate/devices/enrol` | `{public_key, label, enrol_code}` → `{device_id}` (status `PENDING` until approved) |
| POST | `/gate/session` | `{device_id, staff_pin}` → `{session_token, staff_id, server_time}` |
| GET | `/gate/events` | Events this device is assigned to, with `gate` and `allowed_zone_ids` |
| GET | `/gate/events/{id}/manifest?since={version}` | Full manifest when `since` absent, else delta. Response `{version, signature, rows:[{ticket_id, zone_id, status, holder_initials}], removed:[ticket_id], event_key (base64, full response only), server_time, reentry_policy}` — gzipped |
| POST | `/gate/events/{id}/scans` | `{device_id, cursor, scans:[{scan_id, ticket_id?, direction, result, device_time, token_version?, override_reason?, supervisor_id?}]}` (≤500) → `{acked:[scan_id], manifest_version, used_since:[{ticket_id, direction, first_at, gate_code}], cursor, server_time}` |
| POST | `/gate/events/{id}/closed-ack` | Device confirms it wiped keys and logs |

Sync rules: idempotent on `scan_id`; server never rejects a batch for one bad row (it
acks the valid ones and returns `errors[]`); `used_since` is bounded by `cursor`
(server-side sequence) so a device catches up in one call.

## Token (shared by `packages/core` and the backend)

```
bytes:  version(1) | event_id(4, BE) | ticket_id(16) | zone_id(2, BE) | [slot(4, BE)] | tag(16)
v1 static:   tag = HMAC-SHA256(K_e, version|event_id|ticket_id|zone_id)[0:16]
v2 rotating: K_t = HMAC-SHA256(K_e, ticket_id); slot = floor(unix/30)
             tag = HMAC-SHA256(K_t, version|event_id|ticket_id|zone_id|slot)[0:16]
encoding: base64url, no padding
```

Acceptance on the scanner: v2 valid if `|slot - now_slot| <= 1` (server-time-corrected clock).

### Test vectors (to be generated by the backend and committed here)

The backend ships `TokenVectorsTest` that writes `docs/token-vectors.json`:
`[{k_e, event_id, ticket_id, zone_id, slot?, expected_token}]` for 10 static and 10
rotating tokens. `packages/core` golden-tests against the same file. Until generated,
both sides must agree on the byte layout above.
