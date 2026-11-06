# API contracts (V1)

Base: `https://api.jomhor.ma/v1`. JSON, UTF-8. Money in integer centimes (`_mad`).
Times in RFC 3339 UTC; clients show and enter them in the venue's `timezone` (IANA), never a fixed offset. Errors: `{ "code": "ORDER_EXPIRED", "message": "...", "details": {} }`.

Three audiences, three auth schemes:

| Audience | Auth | Prefix |
| --- | --- | --- |
| Fan (app, web shop) | Bearer JWT from phone-OTP login, carrying a revocable session `sid`; `GET /fan/events*` is public | `/fan/...` |
| Organizer (back-office) | Bearer JWT, staff account, `X-Organizer-Id` | `/org/...` |
| Gate (scanner) | Device signature (Ed25519 over method+path+body+timestamp) + staff PIN session token | `/gate/...` |
| Card gateway | Gateway signature, verified per provider | `/payments/...` |

Error codes (Lot 0 and Lot 1; later lots add theirs here):

| Code | HTTP | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 422 | Bad body; `details.fields{}` |
| `BAD_REQUEST` | 4xx | Request rejected by the framework before reaching a handler (bad path/query type, missing header, wrong method or media type); HTTP status kept |
| `INTERNAL_ERROR` | 500 | Anything unexpected; fixed message, `details: {}`, logged server-side, never echoes the exception |
| `UNAUTHORIZED` | 401 | Missing, invalid or wrong-audience token; fan token whose session is revoked or expired |
| `FORBIDDEN` | 403 | No active staff row for `X-Organizer-Id`, or role not allowed |
| `NOT_FOUND` | 404 | Unknown id, or an id of another organizer or user |
| `OTP_INVALID` / `OTP_EXPIRED` | 401 | Verify failed |
| `OTP_RATE_LIMITED` | 429 | Any OTP limit in §Fan auth (per phone, per IP, or phone locked after failed codes) |
| `EVENT_NOT_ON_SALE` | 409 | Order outside the sale window. `details.reason`: `NOT_ON_SALE` (status), `NOT_STARTED` (+ `details.sale_starts_at`), `CLOSED` (at or after `refund_cutoff_at`) |
| `EVENT_NOT_EDITABLE` | 409 | Event PATCH, price write or publish when the status doesn't allow it (PATCH and prices: only `DRAFT`/`ON_SALE`; publish: only `DRAFT`, so the losing side of a double publish gets it too) |
| `SOLD_OUT` | 409 | `details.price_category_id` |
| `PER_ACCOUNT_LIMIT` | 409 | `details.limit`, `details.held` |
| `SALE_BUSY` | 429 | Basic on-sale queue is full; `details.retry_after_s` (int, 5–30, with jitter). Retry the same request. |
| `TICKET_NOT_VALID` | 409 | PDF or WhatsApp re-send for a ticket that isn't `VALID` |
| `DELIVERY_RATE_LIMITED` | 429 | More than 3 WhatsApp re-sends of one ticket in 24 h |
| `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE` | 409 | Pay on a non-`RESERVED` order |
| `CODE_TAKEN` | 409 | Duplicate zone or gate code in a venue |
| `VENUE_IN_USE` | 409 | Changing a venue's `timezone` once events reference the venue |
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
| POST | `/fan/auth/otp` | `{phone}` (E.164) → `202`. 6-digit code, valid 5 min, 5 attempts per code. Fan codes are limited per phone to 3 sends per 10 min and 10 per 24 h. After 15 failed verifications in 24 h the phone is locked for fan login until the oldest failure is 24 h old. Per IP (resolved through trusted proxies only): 20 sends per 10 min, 200 per 24 h. Over any limit → 429 `OTP_RATE_LIMITED`. The code is sent after commit, off the request thread: `202` means accepted, not delivered. |
| POST | `/fan/auth/verify` | `{phone, code}` → `{token, expires_at, user:{id, phone, display_name, locale}}`. Creates the user on first login and a fan session every time. JWT `aud=fan`, `sid` = session id, 30 days. Only a fan code verifies here. Locked phone → 429 `OTP_RATE_LIMITED`. |
| GET, PATCH | `/fan/me` | `{id, phone, display_name, locale}`. PATCH `{display_name?, locale?}`: `display_name` per §Holder names, `locale` ∈ `fr`, `ar`, `en`. |
| POST | `/fan/me/logout` | `{all?: boolean}` → `204`. Revokes this session, or all the user's sessions when `all`. The app wipes its cached tickets and K_t. |
| GET | `/fan/events?organizer=&from=` | **Public: send no `Authorization` header.** Events with status `ON_SALE` or `GATES_OPEN` and `starts_at >= from` (default now), by `starts_at`, at most 50. Each: `{id, organizer_id, event_type, title, starts_at, doors_open_at, timezone, venue:{name, city}, metadata, sale_starts_at, sales_close_at, on_sale, sold_out, from_price_mad, per_account_limit}`. `sales_close_at` = `refund_cutoff_at`. `on_sale` = ON_SALE and inside [`sale_starts_at`, `sales_close_at`). `sold_out` = every category has `remaining = 0`. `from_price_mad` = lowest `price_mad` among categories with `remaining > 0`, null if none. |
| GET | `/fan/events/{id}` | **Public.** Same fields + `zones:[{id, code, name, seated, gate_codes[], prices:[{price_category_id, name, price_mad, remaining}]}]`; zones by `id`, prices by `name`, `gate_codes` ascending. `remaining = quota − sold`. `DRAFT` (or unknown) → 404; `CLOSED` is returned with `on_sale = false`. No key, no `sold`/`quota` fields. |
| POST | `/fan/orders` | `{event_id, channel: APP\|WEB, items:[{price_category_id, qty, holders:[{name, phone?}]}]}` → `201 {id, status: RESERVED, reserved_until, total_mad, items:[{price_category_id, zone_id, name, qty, unit_price_mad, holders:[{label}]}]}`. Held 8 min. Checks in order: event (404), sale window (409 `EVENT_NOT_ON_SALE`), items and holders (422), queue (429 `SALE_BUSY`), per-account limit (409), quota (409 `SOLD_OUT`). `holders.length = qty` (else 422 on `items[i].holders`). `name` per §Holder names (422 on `items[i].holders[j].name`). `phone` absent or equal to the buyer's → the buyer holds that ticket. Another E.164 phone → that phone's account holds it (created if new); it gets the ticket on WhatsApp and in the app or W5 after OTP login, and the buyer never gets its K_t or PDF. Σ qty over the buyer's `RESERVED`/`AWAITING_CASH`/`PAID` orders for the event ≤ `per_account_limit`. |
| POST | `/fan/orders/{id}/pay` | Unchanged (`{method: CARD}` → `{payment_id, redirect_url}`; CASH_AGENCY and WALLET in Lot 3) |
| GET | `/fan/orders/{id}` | `{id, event_id, channel, status, total_mad, reserved_until, paid_at, items[], ticket_ids[]}`. `items[]` as in the POST response, ordered by `price_category_id`. Owner (buyer) only, 404 otherwise. |
| GET | `/fan/tickets?with_keys=` | Tickets **held** by the caller (`holder_user_id`), upcoming events first, then past. → `{server_time, tickets:[{id, ticket_no, status, holder_label, seat_label, event:{id, title, event_type, starts_at, doors_open_at, timezone, venue:{name, city}, metadata}, zone:{id, code, name}, gate_codes[], k_t?}]}`. `k_t` (base64url, 32 bytes) only when `with_keys=true`, status `VALID` and the event is not `CLOSED`. The fan app sends `with_keys=true`; the web shop never does. `Cache-Control: no-store`. |
| GET | `/fan/tickets/{id}/pdf` | Holder only (404 otherwise); `VALID` only (409 `TICKET_NOT_VALID`). `application/pdf`, `billet-{ticket_no}.pdf`: A4 page with a wallet-size cut-out. Static v1 QR (ECC M) signed with K_e, ticket number under it, event, date in the venue's time zone, venue, zone, gates, holder label. `Cache-Control: no-store`. |
| POST | `/fan/tickets/{id}/whatsapp` | Holder only, `VALID` only. Queues the PDF to the holder's phone → `202`. More than 3 per ticket per 24 h → 429 `DELIVERY_RATE_LIMITED`. |
| POST | `/fan/tickets/{id}/transfer` | Lot 4. `{to_phone}`. K_t = HMAC(K_e, ticket_id), so a new K_t needs a new ticket: the ticket becomes `CANCELLED` and a new ticket is issued to the recipient. |
| GET | `/fan/gates/{event_id}/load` | Lot 4 |

On payment success every holder's ticket is issued with `holder_user_id`, `holder_name` and `holder_initials`. One WhatsApp delivery is queued per ticket, to the holder's phone (an outbox, sent after commit, at least once).

Order rules: item validation errors use `details.fields["items[i].price_category_id"]`-style
keys; `channel` accepts `APP` and `WEB` only (`POS` is Lot 3); a `qty` above the account limit
is answered with `PER_ACCOUNT_LIMIT`. The limit counts `RESERVED` orders until the expiry job
(every 30 s) moves them to `EXPIRED`.

## Holder names

| Rule | Value |
| --- | --- |
| Input | NFC-normalised, trimmed, inner spaces collapsed; 2–60 chars; `^\p{IsLatin}[\p{IsLatin} '.\-]{1,59}$` (Latin letters with accents, space, apostrophe, dot, hyphen). Arabic script: Lot 4. |
| `holder_label` (ticket card, PDF) | first word + space + first letter of the last word + "." → `Yazid A.`; one word → as is |
| `holder_initials` (Lot 2 manifest) | first letter of the first and last words, uppercase → `Y. A.`; one word → `Y.` |
| `ticket_no` | last 8 hex chars of `ticket_id`, uppercase, grouped 4-4 → `4D5C 6B7A` |

## On-sale (basic queue)

| Rule | Value |
| --- | --- |
| Opening | `sale_starts_at` (null = at publish). Before it: `EVENT_NOT_ON_SALE` `NOT_STARTED`. |
| Closing | `refund_cutoff_at` (T0 − 60 min). From then on: `EVENT_NOT_ON_SALE` `CLOSED`. |
| Busy | Active holds of the event (`RESERVED`, `reserved_until > now`) ≥ `sales.max-active-holds-per-event` (config, default 2000) → 429 `SALE_BUSY`. A soft cap: two racing orders can each pass it. |
| Clients | Show "File d'attente" with the retry countdown and retry automatically. No position number is shown or implied. The ranked virtual queue is Lot 5. |

## Payments

Gateway-facing, no audience token. Card gateway: Payzone; until its kit arrives, a simulated
gateway (`provider = fake`) that exists only outside prod.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/payments/card/{payment_id}/redirect` | Sends the browser to the gateway: a 302 or an auto-submitting form, as the adapter decides. `410` if the payment is not `PENDING`. Never carries a merchant secret. |
| POST | `/payments/{provider}/callback` | Server to server (`fake` now, `payzone` later). Signature verified before any state change. Idempotent on `payment_id`. On approval, in one transaction: payment `SUCCEEDED` → order `PAID` → tickets issued (one per unit, holder = buyer). Request format and acknowledgement body come from the adapter. |
| GET, POST | `/payments/card/{payment_id}/return` | Browser landing after the gateway. **Never changes state.** Reads the payment's order and `302`s to a fixed URL by `orders.channel`. WEB: `payments.return-url.web` (`{SHOP_BASE_URL}/orders/{order_id}`). APP: `payments.return-url.app` (`jomhor-fan://orders/{order_id}`). No caller-supplied URL. Unknown `payment_id` → the FR "paiement en cours de confirmation" page. |
| GET | `/payments/fake/{payment_id}` | Simulated gateway page: amount, "Approuver" / "Refuser". Each button posts a signed callback to `/payments/fake/callback`, then lands on `/return`. Supports a delayed callback. Exists only in `dev`/`test`/`staging`; startup fails if `payments.card.provider=fake` under `prod`. |
| POST | `/payments/fake/{payment_id}/late` | Simulated gateway only (same profiles): schedules the signed approval after `payments.fake.late-delay`; `410` if the payment isn't `PENDING`. |
| POST | `/payments/cash/callback` | Agency network (Lot 3), idempotent on `payment_ref` |

Answers: `POST /fan/orders/{id}/pay` → `200`. A callback with a missing, bad or tampered signature →
401 `UNAUTHORIZED` with a fixed message and empty `details`, nothing written. `/redirect`, `/return`
and the simulated gateway page are browser pages (FR HTML, e.g. the 410), not the JSON error shape;
an unknown `payment_id` on `/redirect` is the JSON 404. A success on a `RESERVED` order whose hold
has passed but that the expiry job hasn't swept yet is a normal `PAID` (the places are still held).

Late success: expiry skips orders with a `PENDING` payment younger than 20 min. A success after
expiry re-reserves the quota atomically → `PAID`; if no quota is left the order stays `EXPIRED`,
the payment is `SUCCEEDED`, and `audit_log` gets `payment.refund_required` (manual refund) with reason `SOLD_OUT`,
`PER_ACCOUNT_LIMIT`, `NOT_PAYABLE` (a second success for an order already `PAID`), or
`AMOUNT_MISMATCH` (an approved callback whose amount differs from the payment: payment `FAILED`,
order stays payable, the charged amount must be refunded).

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
| GET, POST | `/org/venues` | `{name, city?, capacity, timezone?}` → `{id, name, city, capacity, timezone}`. `timezone` is an IANA region id (`Area/Location`, e.g. `Africa/Casablanca`, the default); unknown ids and fixed-offset ids (`UTC`, `GMT`, `Etc/*`, `+01:00`) → 422. |
| GET, PATCH | `/org/venues/{id}` | GET returns `zones[]` and `gates[]` (each gate with `zone_ids[]`). `timezone` can change only while no event references the venue (`VENUE_IN_USE`); re-sending the current value is not a change. |
| POST | `/org/venues/{id}/zones` | `{code, name, capacity, seated?}` → `{id, code, name, capacity, seated}` (`id` is an int) |
| PATCH, DELETE | `/org/venues/{id}/zones/{zone_id}` | DELETE only if unreferenced (`ZONE_IN_USE`) |
| POST | `/org/venues/{id}/gates` | `{code, name}` → `{id, code, name, zone_ids:[]}` |
| PATCH, DELETE | `/org/venues/{id}/gates/{gate_id}` | DELETE only if unreferenced (`GATE_IN_USE`) |
| PUT | `/org/gates/{id}/zones` | `{zone_ids:[...]}` replaces the set. Zones must belong to the gate's venue. |
| GET | `/org/events?status=&from=` | `[{id, title, event_type, starts_at, status, venue:{id, name, timezone}, quota, sold}]` by `starts_at` |
| POST | `/org/events` | `{venue_id, event_type, title, starts_at, doors_open_at?, reentry_policy?, per_account_limit?, metadata?}` → `201` event in `DRAFT`. **K_e (32 random bytes) is generated and encrypted in the same transaction and never returned.** A `venue_id` that is unknown or another organizer's → 422 `VALIDATION_FAILED` on `venue_id`. Lot 0 has no version field: concurrent edits of one event are last-write-wins. | **Lot 1:** + `sale_starts_at?`, which must be before `starts_at − 60 min` (422 on `sale_starts_at`), checked on every write that changes either field.
| GET, PATCH | `/org/events/{id}` | Event (`id` is an int, the 4-byte `event_id` of the QR) `{id, venue_id, timezone, series_id, event_type, title, starts_at, doors_open_at, status, refund_cutoff_at, reentry_policy, per_account_limit, metadata, created_at}`. PATCH allowed in `DRAFT`/`ON_SALE`. `venue_id` and `event_type` are fixed after create (a different value → 422). Changing `starts_at` while `ON_SALE` recomputes `refund_cutoff_at`. `metadata` in a PATCH replaces the whole object. `doors_open_at` cannot be cleared in Lot 0. | **Lot 1:** the shape adds `sale_starts_at`; PATCH may move it while `DRAFT`/`ON_SALE`; it can't be cleared (to open now, set it to now).
| GET, POST | `/org/events/{id}/prices` | `{zone_id, name, price_mad, quota}` → `{id, zone_id, name, price_mad, quota, sold}`. The zone must be in the event's venue; Σ quota per zone ≤ zone capacity. A name already used for that zone in the event → 422 `VALIDATION_FAILED` on `name` with `details.reason` `NAME_TAKEN` (clients match the reason, never the message text). |
| PATCH, DELETE | `/org/events/{id}/prices/{price_id}` | PATCH `{name?, price_mad?, quota?}`; `zone_id` is fixed after create (a different value → 422). `quota ≥ sold`. A price change applies to new orders only (snapshot in `order_items`). DELETE only if `sold = 0` (`PRICE_IN_USE`). |
| POST | `/org/events/{id}/publish` | `DRAFT` → `ON_SALE`. Needs ≥ 1 category with quota > 0 and `starts_at > now`. Sets `refund_cutoff_at = starts_at − 60 min`. Writes `audit_log` `event.publish`. → `200` with the event (GET shape). Unmet precondition → 422 `VALIDATION_FAILED` with `details.reason` `NO_QUOTA` (`fields.prices`) or `STARTS_IN_PAST` (`fields.starts_at`). |
| GET | `/org/events/{id}/sales` | ADMIN, OPS, FINANCE. `{event_id, quota, paid, held, revenue_mad, zones:[{zone_id, code, name, quota, paid, held, revenue_mad}], channels:[{channel, paid, revenue_mad}], refunds_required:[{payment_id, order_id, amount_mad, reason, at}]}`. `paid` = places in `PAID` orders; `held` = places in `RESERVED` orders still held; `revenue_mad` = Σ qty × `unit_price_mad` of `PAID` orders (gross, no fees); `refunds_required` = this event's `payment.refund_required` audit rows, newest first. |
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

`TokenVectorsTest` (backend) builds 10 static and 10 rotating vectors from fixed inputs, including the edge values and the 6 interim cases of `packages/core/tool/interim_vectors.py`. If the file is absent, or `-Djomhor.writeVectors=true` is set, it writes `docs/token-vectors.json`; otherwise it fails when the committed file differs. `packages/core` fails, rather than skipping, when the file is missing.
Format:
`[{k_e, event_id, ticket_id, zone_id, slot?, expected_token}]` for 10 static and 10
rotating tokens. Field encodings: `k_e` lowercase hex (64 chars); `event_id`, `zone_id`,
`slot` JSON integers (`slot` absent for v1); `ticket_id` canonical lowercase UUID string;
`expected_token` base64url without padding. Include the edge values (`event_id` 0xFFFFFFFF,
`zone_id` 0xFFFF, `slot` 0xFFFFFFFF). `packages/core` golden-tests against the same file. Until generated,
both sides must agree on the byte layout above.
