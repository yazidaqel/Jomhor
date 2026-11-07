# API contracts (V1)

Base: `https://api.jomhor.ma/v1`. JSON, UTF-8. Money in integer centimes (`_mad`).
Times in RFC 3339 UTC; clients show and enter them in the venue's `timezone` (IANA), never a fixed offset. Errors: `{ "code": "ORDER_EXPIRED", "message": "...", "details": {} }`.

Three audiences, three auth schemes:

| Audience | Auth | Prefix |
| --- | --- | --- |
| Fan (app, web shop) | Bearer JWT from phone-OTP login, carrying a revocable session `sid`; `GET /fan/events*` is public | `/fan/...` |
| Organizer (back-office) | Bearer JWT, staff account, `X-Organizer-Id` | `/org/...` |
| Gate (scanner) | Device signature, ECDSA P-256 key in Android Keystore (§Gate auth). No server session: staff PINs are checked on the device (§Gate PINs) | `/gate/...` |
| Payment providers (card/wallet gateway, cash networks) | Provider signature, verified per provider | `/payments/...` |

Error codes (Lot 0 to Lot 4; later lots add theirs here):

| Code | HTTP | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 422 | Bad body; `details.fields{}` |
| `BAD_REQUEST` | 4xx | Request rejected by the framework before reaching a handler (bad path/query type, missing header, wrong method or media type); HTTP status kept |
| `INTERNAL_ERROR` | 500 | Anything unexpected; fixed message, `details: {}`, logged server-side, never echoes the exception |
| `UNAUTHORIZED` | 401 | Missing, invalid or wrong-audience token; fan token whose session is revoked or expired; gate: missing or bad device signature, unknown device, bad or expired `enrol_code` (fixed message, empty `details`) |
| `DEVICE_CLOCK_SKEW` | 401 | `X-Device-Time` more than 5 min from server time; `details.server_time`. The device fixes its offset and retries once |
| `FORBIDDEN` | 403 | No active staff row for `X-Organizer-Id`, or role not allowed |
| `DEVICE_NOT_ACTIVE` | 403 | Device `PENDING` or `REVOKED`; `details.status`. On `REVOKED` the scanner wipes K_e, manifest, staff list and scans |
| `NOT_FOUND` | 404 | Unknown id, or an id of another organizer or user |
| `OTP_INVALID` / `OTP_EXPIRED` | 401 | Verify failed |
| `OTP_RATE_LIMITED` | 429 | Any OTP limit in §Fan auth (per phone, per IP, or phone locked after failed codes) |
| `EVENT_NOT_ON_SALE` | 409 | Order outside the sale window. `details.reason`: `NOT_ON_SALE` (status), `NOT_STARTED` (+ `details.sale_starts_at`), `CLOSED` (at or after `refund_cutoff_at`) |
| `EVENT_NOT_EDITABLE` | 409 | Event PATCH, price write or publish when the status doesn't allow it (PATCH and prices: only `DRAFT`/`ON_SALE`; publish: only `DRAFT`, so the losing side of a double publish gets it too); close outside `ON_SALE`/`GATES_OPEN` (`details.reason` `NOT_OPEN`) or before `starts_at` (`NOT_STARTED` + `details.starts_at`); assignment PUT on a `CLOSED` event |
| `SOLD_OUT` | 409 | `details.price_category_id` |
| `PER_ACCOUNT_LIMIT` | 409 | `details.limit`, `details.held`; **Lot 4:** + `details.field` (the holder path) when a split-pay member is over their own limit |
| `SALE_BUSY` | 429 | Basic on-sale queue is full; `details.retry_after_s` (int, 5–30, with jitter). Retry the same request. |
| `TICKET_NOT_VALID` | 409 | PDF, WhatsApp re-send, transfer or re-issue of a ticket that isn't `VALID` |
| `TRANSFER_CLOSED` | 409 | Transfer or re-issue at or after the ticket's `transfer_until` (`refund_cutoff_at − tickets.transfer.close-before-cutoff`, default 30 min; no cutoff set = closed); `details.transfer_until` |
| `DELIVERY_RATE_LIMITED` | 429 | More than 3 WhatsApp re-sends of one ticket in 24 h |
| `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE` | 409 | Pay on an order that isn't `RESERVED` or `AWAITING_CASH`, or whose hold has passed |
| `METHOD_NOT_AVAILABLE` | 409 | `/pay` with a method this order can't use now. `details.reason`: `TOO_LATE` (cash: the code would be valid less than `payments.cash.min-window`; + `details.sales_close_at`), `CASH_CAP` (§Cash codes cap), `NOT_CONFIGURED` (no provider for WALLET; for CASH_AGENCY: no cash point and no network) |
| `CODE_NOT_PAYABLE` | 409 | Collect on a code that isn't payable (§Cash codes); `details.status` = the order status, or `EXPIRED` when the hold has passed |
| `POS_SESSION_REQUIRED` | 409 | POS sale, collect or print without an open till session of the caller |
| `POS_SESSION_CLOSED` | 409 | Close of a session already closed |
| `CODE_TAKEN` | 409 | Duplicate zone or gate code in a venue |
| `VENUE_IN_USE` | 409 | Changing a venue's `timezone` once events reference the venue |
| `ZONE_IN_USE` / `GATE_IN_USE` / `PRICE_IN_USE` | 409 | Delete of a referenced row. `gate_zones` mappings do not count: they are removed with their zone or gate. References are price categories, order items, tickets. Lot 2: device assignments and scans reference gates. |
| `QUOTA_EXCEEDS_CAPACITY` / `QUOTA_BELOW_SOLD` | 422 | Price category quota rules |
| `TICKET_STATUS` | 409 | Block of a non-`VALID` ticket, unblock of a non-`BLOCKED` one, refund of a ticket that is neither; `details.status` |
| `REFUND_FROZEN` | 409 | Block, unblock or refund at or after `refund_cutoff_at` (or with no cutoff set); checked before the status, so it wins over `TICKET_STATUS` |
| `DEVICE_REVOKED` | 409 | Approve on a `REVOKED` device |
| `DEVICE_IN_USE` | 409 | Assignment PUT that removes a device with scans on the event; `details.device_ids` |
| `MANIFEST_NOT_READY` | 409 | Before `manifest_available_at`, or no version built yet; `details.available_at` |
| `EVENT_CLOSED` | 409 | Manifest request on a `CLOSED` event |
| `EVENT_NOT_CLOSED` | 409 | `closed-ack` before the event is `CLOSED` |
| `CAMPAIGN_STATUS` | 409 | Campaign PATCH or send outside `DRAFT`, cancel outside `SCHEDULED`; `details.status` |

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
| POST | `/fan/me/logout` | `{all?: boolean}` (body optional; absent = `all: false`) → `204`. Revokes this session, or all the user's sessions when `all`. The app wipes its cached tickets and K_t. |
| GET | `/fan/events?organizer=&from=` | **Public: send no `Authorization` header.** Events with status `ON_SALE` or `GATES_OPEN` and `starts_at >= from` (default now), by `starts_at`, at most 50. Each: `{id, organizer_id, status, event_type, title, starts_at, doors_open_at, timezone, venue:{name, city}, metadata, sale_starts_at, sales_close_at, on_sale, sold_out, from_price_mad, per_account_limit}`. `sales_close_at` = `refund_cutoff_at`. `status` = the event status (`ON_SALE`, `GATES_OPEN`, `CLOSED`), so clients never infer it. `on_sale` = ON_SALE and inside [`sale_starts_at`, `sales_close_at`). `sold_out` = every category has `remaining = 0`. `from_price_mad` = lowest `price_mad` among categories with `remaining > 0`, null if none. |
| GET | `/fan/events/{id}` | **Public.** Same fields + `zones:[{id, code, name, seated, gate_codes[], prices:[{price_category_id, name, price_mad, remaining}]}]`; zones by `id`, prices by `name`, `gate_codes` ascending. `remaining = quota − sold`. `DRAFT` (or unknown) → 404; `CLOSED` is returned with `on_sale = false`. No key, no `sold`/`quota` fields. **Lot 3:** + `payment_methods`: subset of `["CARD","WALLET","CASH_AGENCY"]` in that order. `WALLET` iff a wallet provider is configured. `CASH_AGENCY` iff the organizer has a cash point or a network is enabled, and `now < refund_cutoff_at − payments.cash.min-window`. Plus `cash_until`: `refund_cutoff_at − payments.cash.min-window` when cash is set up for the organizer (null otherwise), so clients show cash as "Trop tard pour le cash" after it and hide it when null; a code bought now is valid until `least(now + 24 h, refund_cutoff_at)`. The cash cap is not reflected (`/pay` answers it). |
| POST | `/fan/orders` | `{event_id, channel: APP\|WEB, items:[{price_category_id, qty, holders:[{name, phone?}]}]}` → `201 {id, status: RESERVED, reserved_until, total_mad, items:[{price_category_id, zone_id, zone_name, name, qty, unit_price_mad, holders:[{label}]}]}`. Held 8 min. Item `name` = the price category, `zone_name` = the zone. Checks in order: event (404), sale window (409 `EVENT_NOT_ON_SALE`), items and holders (422), queue (429 `SALE_BUSY`), per-account limit (409), quota (409 `SOLD_OUT`). `holders.length = qty` (else 422 on `items[i].holders`). `name` per §Holder names (422 on `items[i].holders[j].name`). `phone` absent or equal to the buyer's → the buyer holds that ticket. Another E.164 phone → that phone's account holds it (created if new); it gets the ticket on WhatsApp and in the app or W5 after OTP login, and the buyer never gets its K_t or PDF. Σ qty over the buyer's `RESERVED`/`AWAITING_CASH`/`PAID` orders for the event ≤ `per_account_limit`. **Lot 4:** `holders:[{name, phone?, pays?}]`; `pays: true` = split pay (§Split pay). The response + `group: {members:[{order_id, holder_label, places, total_mad, status, reserved_until, pay_url}]} \| null`. |
| POST | `/fan/orders/{id}/pay` | `{method: CARD\|WALLET\|CASH_AGENCY}`. Buyer only (404). Order `RESERVED` or `AWAITING_CASH` with `reserved_until > now()`, else 409 `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE`; the order row is locked first. **CARD, WALLET** → `200 {payment_id, method, redirect_url}`: a new PENDING attempt per call; `redirect_url` = `/payments/card/{payment_id}/redirect` for both (the hosted page; the path keeps its Lot 0 name). On an `AWAITING_CASH` order the code stays payable until one method succeeds. **CASH_AGENCY** → `200 {payment_id, method, pay_code, reserved_until, amount_mad}`: `RESERVED` → `AWAITING_CASH`, `reserved_until = least(now + 24 h, refund_cutoff_at)`, one PENDING payment (provider `CASH`) with a new code (§Cash codes). An `AWAITING_CASH` order that already has a PENDING code → the same answer (no new code, hold unchanged). Refused with `METHOD_NOT_AVAILABLE`: `TOO_LATE` if the new `reserved_until` is less than `payments.cash.min-window` (config, default 2 h) away; `CASH_CAP`; `NOT_CONFIGURED`. |
| GET | `/fan/orders/{id}` | `{id, event_id, channel, status, payment_status, total_mad, reserved_until, paid_at, items[], ticket_ids[]}` (`payment_status`: `SUCCEEDED` if any attempt succeeded; otherwise the latest attempt's status, a `PENDING` **CARD/WALLET** attempt older than 20 min (the late-success window, counted from `redirected_at` when set, else `created_at`) being reported `FAILED`; a `PENDING` cash code is `PENDING`; null before any `/pay`. Clients offer "payer à nouveau" only when `status` is `RESERVED`, `payment_status` is `FAILED` or null, and `reserved_until` is still in the future — otherwise they say the reservation expired and offer a new order) (`ticket_ids` = only the tickets the caller holds; a friend's ticket is never listed to the buyer). `items[]` as in the POST response, ordered by `price_category_id`. Owner (buyer) only, 404 otherwise. **Lot 3:** + `payment_method` (the SUCCEEDED attempt's method, else the latest attempt's, null before `/pay`) + `cash`: `{pay_code, amount_mad, points:[{name, address?, hours?}], networks:[name]}` (`pay_code` on the wire = the 6 stored symbols, uppercase, e.g. `4F7K29`; clients display `JMH 4F7K 29`) while the order is `AWAITING_CASH` with a PENDING code, else null. `points` = `organizers.settings.cash_points`; `networks` = display names of enabled cash networks. Clients: on `AWAITING_CASH` show the code and offer card/wallet unless the latest attempt is a CARD/WALLET still `PENDING`; on `RESERVED` the Lot 1 "payer à nouveau" rule stands. **Lot 4:** + `group`, one of: null; `{role: INITIATOR, members:[{order_id, holder_label, places, total_mad, status, reserved_until, pay_url}]}` (members by `order_id`); or `{role: MEMBER, initiator_label, initiator_status}`. `pay_url` = `{payments.return-url.web}` filled with the member order id (the shop's `/orders/{id}`). Labels only: never a member's phone or full name. |
| GET | `/fan/orders` | The buyer's orders → `{orders:[{id, event:{id, title, starts_at, timezone}, channel, status, total_mad, reserved_until, payment_method, pay_code, created_at}]}` (`pay_code` = the PENDING code of an `AWAITING_CASH` order, 6 symbols, else null): `AWAITING_CASH` first, then by `created_at` desc, at most 20. `Cache-Control: no-store`. **Lot 4:** + per row `group_role` (`INITIATOR`, `MEMBER` or null). Order: `AWAITING_CASH` orders and **member** orders still `RESERVED` and held come first, then by `created_at` desc. |
| GET | `/fan/tickets?with_keys=` | Tickets **held** by the caller (`holder_user_id`), upcoming events first (by `starts_at`), then past (event `CLOSED` or `starts_at` more than 12 h ago, latest first). `holder_label` may be null for tickets issued before named tickets (Lot 0). → `{server_time, tickets:[{id, ticket_no, status, holder_label, seat_label, event:{id, title, event_type, status, starts_at, doors_open_at, timezone, venue:{name, city}, metadata}, zone:{id, code, name}, gate_codes[], k_t?}]}`. `k_t` (base64url, 32 bytes) only when `with_keys=true`, status `VALID` and the event is not `CLOSED`. The fan app sends `with_keys=true`; the web shop never does. `Cache-Control: no-store`. **Lot 4:** each row + `gates:[{code, name, directions, latitude, longitude}]` (the gates serving the ticket's zone, by code; coordinates as JSON numbers with at most 6 decimals, or null; `gate_codes` stays). Each row also gets `transfer_until` (RFC 3339, or null when the event has no cutoff) and `transferred: {to_label, at} \| null`, set on a ticket this holder transferred away (its status is `CANCELLED`). Tickets cancelled by a re-issue are not listed. |
| GET | `/fan/tickets/{id}/pdf` | Holder only (404 otherwise); `VALID` only (409 `TICKET_NOT_VALID`). `application/pdf`, attachment `billet-{ticket_no}.pdf` with the space in ticket_no replaced by `-` (e.g. `billet-4D5C-6B7A.pdf`); a malformed id answers 400: A4 page with a wallet-size cut-out. Static v1 QR (ECC M) signed with K_e, ticket number under it, event, date in the venue's time zone, venue, zone, gates, holder label. `Cache-Control: no-store`. |
| POST | `/fan/tickets/{id}/whatsapp` | Holder only, `VALID` only. Queues the PDF to the holder's phone → `202`, empty body. More than 3 re-sends per ticket per 24 h → 429 `DELIVERY_RATE_LIMITED` (the automatic delivery on payment does not count). A ticket no longer `VALID` when its turn comes is marked `FAILED` without retry. |
| POST | `/fan/tickets/{id}/transfer` | `{to_phone, name}`. Holder only (404). `VALID` and never admitted (no canonical `OK`/`OVERRIDE` scan on the server, checked under the ticket-row lock; else 409 `TICKET_NOT_VALID`, an admitted ticket with `details.reason` `ADMITTED`) and `now < transfer_until` (else 409 `TRANSFER_CLOSED`). `to_phone` E.164, not the caller's (422 on `to_phone`, `details.reason` `SELF`: use re-issue). `name` per §Holder names (422 on `name`). In one transaction, with the ticket row locked first: the ticket becomes `CANCELLED`, and a **new ticket** is issued in the same order, event, zone and price category. Its `holder_user_id` = the `to_phone` account (created if new), with `holder_name` / `holder_initials` from `name`. A `ticket_transfers` row is written, plus one WhatsApp delivery to the recipient (as on payment) and one `TICKET_RECEIVED` push (§Notifications). Audit `ticket.transfer` (ids only). → `201 {transfer_id, to_label, transferred_at}`. The next manifest version carries both rows: the old QR scans `ANNULÉ`. `price_categories.sold`, `/sales` and the gate `sold` are unchanged. The order's buyer never sees the new ticket. |
| POST | `/fan/tickets/{id}/reissue` | No body. Lost or replaced phone: the same checks and transaction as a transfer, to the caller with the same name. No delivery, no push. Audit `ticket.reissue`. → `201 {ticket_id}`. The app then refetches `/fan/tickets?with_keys=true`. The old K_t is dead from the next manifest version. |
| GET | `/fan/gates/{event_id}/load` | The caller holds a ticket of the event, any status (else 404). → `{event_id, server_time, gates:[{code, level}], entries:[{ticket_id, entered_at, gate_code}]}`. `gates` = the venue's gates with an assigned device, by code. `level`: `UNKNOWN` when none of the gate's assigned `ACTIVE` devices was seen in the last 2 min. Otherwise charge = `rate_per_min ÷ (devices_online × 24)` (§Gate reports `/live` figures for that gate): `LOW` < 0.5 ≤ `MEDIUM` < 0.85 ≤ `HIGH`. The gate part is computed once per event per 15 s (it may be 15 s old). `entries` = the caller's held tickets of the event that have a canonical admitted scan (`entered_at` = its `device_time`, `gate_code` = its gate). `Cache-Control: no-store`. Clients poll every 60 s ± 10 s, only while screen 5 or the matchday strip is visible, from T0 − 3 h to T0 + 1 h. They stop polling once the shown ticket is entered. |
| GET | `/fan/me/membership?organizer=` | → `{member: {number, joined_at, marketing_push, marketing_whatsapp} \| null, loyalty: {events_attended, tier:{code, name}, next_tier:{code, name, min_events} \| null, last_events:[{event_id, title, event_type, starts_at, timezone, attended}]}}`. `events_attended` = distinct events of this organizer where the caller holds a ticket with a canonical admitted scan. `tier` per `organizers.settings.loyalty` (`name` in the caller's `locale`, falling back to `fr`). `last_events` = the organizer's 11 latest `CLOSED` events, oldest first. Loyalty is returned for non-members too. A membership with `left_at` set → `member: null`. |
| PUT | `/fan/me/membership` | `{organizer_id, marketing_push, marketing_whatsapp}` (booleans, required). Not a member → join, `201`: the next `number` for the organizer (unique index; a collision draws the next), or the old number if they left before. Already a member → the opt-ins are updated, `200`. Audit `membership.join` / `membership.consent` (the two booleans, nothing else). → GET shape. |
| DELETE | `/fan/me/membership?organizer=` | Leave: `left_at = now()`, both opt-ins false → `204` (idempotent). Campaign rows not yet sent to this user are skipped (`OPTED_OUT`). Audit `membership.leave`. |
| PUT | `/fan/me/push-token` | `{organizer_id, token, platform: android\|ios}` → `204`. Upsert by `token`: the row moves to the caller and the current session. Tokens of revoked or expired sessions are never used. `POST /fan/me/logout` deletes the tokens of the sessions it revokes. |
| GET | `/fan/me/inbox?organizer=` | PUSH campaigns sent to the caller, newest first, at most 30 → `{messages:[{id, title, body, event_id, sent_at}]}` (`id` = the campaign). `Cache-Control: no-store`. |

Public event reads: an `Authorization` header sent anyway is ignored (never a 401); `organizer` is
optional (absent = every organizer's listed events, each row carries `organizer_id`; the shop and the
app always send it); the detail lists only zones that have at least one price category for the event;
`gate_codes` sort byte-wise; no cache headers (the shop revalidates on its side). The org event list
row does not carry `sale_starts_at` (only GET/PATCH `/org/events/{id}` do).

On payment success every holder's ticket is issued with `holder_user_id`, `holder_name` and `holder_initials`. One WhatsApp delivery is queued per ticket, to the holder's phone (an outbox, sent after commit, at least once).

Order rules: item validation errors use `details.fields["items[i].price_category_id"]`-style
keys; `channel` accepts `APP` and `WEB` (POS orders are made by `POST /org/pos/orders`); a `qty` above the account limit
is answered with `PER_ACCOUNT_LIMIT`. The limit counts `RESERVED` and `AWAITING_CASH` orders until the expiry job
(every 30 s) moves them to `EXPIRED`. Split pay (Lot 4) adds member orders (§Split pay). Their places count toward
the member's limit, not the initiator's, once the order exists.

## Split pay

| Rule | Value |
| --- | --- |
| Request | In `POST /fan/orders`, a holder with `pays: true` pays for their own place. They need a `phone` that isn't the buyer's (422 on `items[i].holders[j].pays`, `details.reason` `PHONE_REQUIRED`). At least one place has no `pays` (422 on `items`, `NO_BUYER_PLACE`). Refused when `now + sales.split.hold` (config, default 2 h) is at or after `refund_cutoff_at` (422 on the first `pays`, `TOO_LATE`). |
| Orders | One transaction and one quota reservation for every place. The buyer's order holds the places without `pays`, and its `total_mad` counts only those. There is one **member order** per distinct `pays` phone. Its `user_id` is that phone's account (created if new). It has the same `channel` and event, `group_order_id` = the buyer's order, status `RESERVED` and `reserved_until` = the buyer's. Its items carry that member's places, held by the member (`name` as typed). |
| Limits | At request time the buyer's check counts every place of the request (as today). Each member's `RESERVED`/`AWAITING_CASH`/`PAID` places for the event, plus their new ones, must be ≤ `per_account_limit`, else 409 `PER_ACCOUNT_LIMIT` with `details.field` = that member's first holder path. |
| Hold | The expiry job skips member orders while the buyer's order is `RESERVED` or `AWAITING_CASH`. When the buyer's order expires, its member orders expire in the same pass. When the buyer's order becomes `PAID`, every member order still `RESERVED` gets `reserved_until = least(paid_at + sales.split.hold, refund_cutoff_at)` and one `SPLIT_INVITE` (§Notifications), in the settle transaction. From then on each member order expires on its own. |
| Paying | A member pays their order with `/fan/orders/{id}/pay` like any order (card, wallet or cash code), before or after the buyer. Tickets go to the member only. The buyer never pays for a member's place and never sees a member's ticket. |
| Late | A buyer's success after expiry (§Late success) doesn't revive member orders that have expired. |

## Holder names

| Rule | Value |
| --- | --- |
| Input | Input containing a letter-number (`\p{Nl}`) is refused first. Then: NFKC normalisation (fullwidth → ASCII, Arabic presentation forms → letters); typographic apostrophe `’` → `'`; Arabic tatweel U+0640 and Arabic combining marks (U+0610–U+061A, U+064B–U+065F, U+0670, U+06D6–U+06ED) removed from every name, Latin included (`Yazid` + U+064E → `Yazid`); runs of whitespace (the Unicode `White_Space` property, `\p{White_Space}`: no-break spaces included, U+FEFF excluded) collapsed to one space; trimmed. Then 2–60 **code points** in **one script**. **Latin** (Script=Latin, not Script_Extensions): `^[\p{IsLatin}&&\p{L}][[\p{IsLatin}&&\p{L}] '.\-]{1,59}$`. **Arabic:** the 36 base letters U+0621–U+063A and U+0641–U+064A only (the letters of the CIN Arabic line, and the ones the PDF shapes): `^[\x{0621}-\x{063A}\x{0641}-\x{064A}][[\x{0621}-\x{063A}\x{0641}-\x{064A}] \-]{1,59}$` (Java class syntax; JS and Dart use the `u` / `unicode: true` flag). Extended letters (ڭ, ڤ, گ, پ, ک, ی…) are refused. Mixed scripts, digits of any script and format characters (U+200B–U+200F, U+061C, U+202A–U+202E, U+2066–U+2069, U+FEFF) → refused. Applies to holders, transfer `name` and `display_name`. Java, Dart and TS share one list of cases. |
| `holder_label` (ticket card, PDF) | first word as typed + space + first letter of the last word, uppercased, + "." → `Yazid A.` (also for `Yazid aqel`); one word → as is |
| `holder_initials` (Lot 2 manifest) | first letter of the first and last words, uppercase → `Y. A.`; one word → `Y.` |
| `ticket_no` | last 8 hex chars of `ticket_id`, uppercase, grouped 4-4 → `4D5C 6B7A` |
| Arabic labels | `holder_label` = first word + space + first letter of the last word + "." → `يزيد ع.`. `holder_initials` → `ي. ع.`. No case mapping. Clients render names and labels in a bidi isolate. |
| PDF | Arabic text (title, venue, holder label) is shaped and drawn with an embedded font; the labels stay FR. |

## Notifications

Transactional; `fan_messages` outbox, sent after commit with the `ticket_deliveries` retry policy.

| Kind | Trigger | Channels and content |
| --- | --- | --- |
| `TICKET_RECEIVED` | Transfer | PUSH to the recipient: "Tu as reçu un billet · {event title}" → `/tickets/{new_ticket_id}`. The PDF goes on WhatsApp through `ticket_deliveries` |
| `SPLIT_INVITE` | The buyer's order becomes `PAID` | WHATSAPP (template `split_invite`) and PUSH to each member: "{initiator_label} t'a gardé une place · {event title} · {amount} · avant {deadline in venue tz}" + `pay_url` (push → `/orders/{member_order_id}`) |
| `PAY_CODE` | First code of a `CASH_AGENCY` `/pay` | WHATSAPP (template `pay_code`) to the buyer: code `JMH 4F7K 29`, exact amount, deadline (lot-3 Q53; the W4 button stays hidden) |
| `ORDER_PAID` | An `AWAITING_CASH` order becomes `PAID` (guichet or network) | PUSH to the buyer: "Paiement reçu · tes billets sont prêts" → `/orders/{id}` |

Rules: copy comes from backend message bundles in the recipient's `users.locale` (fr, ar, en; fr fallback). Push payloads carry a title, a body and an app path only: no phone, no full name (labels only), no K_t, no QR, no amount beyond the invite's. PUSH goes to the user's tokens for the message's organizer with a live session. No token → `FAILED` `NO_DEVICE`. A token the provider reports as unregistered is deleted. No push provider configured → `FAILED` `NOT_CONFIGURED`. Each (order, kind, channel) is queued once.

## On-sale (basic queue)

| Rule | Value |
| --- | --- |
| Opening | `sale_starts_at` (null = at publish). Before it: `EVENT_NOT_ON_SALE` `NOT_STARTED`. |
| Closing | `refund_cutoff_at` (T0 − 60 min). From then on: `EVENT_NOT_ON_SALE` `CLOSED`. |
| Busy | Active holds of the event (`RESERVED`, `reserved_until > now`) ≥ `sales.max-active-holds-per-event` (config, default 2000) → 429 `SALE_BUSY`. A soft cap: two racing orders can each pass it. |
| Clients | Show "File d'attente" with the retry countdown and retry automatically. No position number is shown or implied. The ranked virtual queue is Lot 5. |

## Cash codes

| Rule | Value |
| --- | --- |
| Symbols | Crockford base32 `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, 6 from `SecureRandom` (30 bits) |
| Display | `JMH 4F7K 29`: `JMH`, space, 4 symbols, space, 2 symbols |
| Stored | The 6 symbols, uppercase, in `payments.pay_code`, unique forever; a collision on insert draws again (at most 5 times, then 500); a code starting with `JMH` is never issued (drawn again), so stripping a typed `JMH` prefix is never ambiguous |
| Input | Case-insensitive; spaces, hyphens and a leading `JMH` are ignored; `O` → `0`, `I`/`L` → `1`; anything left that isn't a symbol, or a length ≠ 6 → not found |
| Payable | Order `AWAITING_CASH` with `reserved_until > now()` (database clock), code payment `PENDING`. An `AWAITING_CASH` order whose code is no longer `PENDING` (e.g. a network amount mismatch) gets a new code on the next CASH `/pay`, hold unchanged, refused with `TOO_LATE` if `reserved_until − now()` is under `payments.cash.min-window`. Exact `total_mad` only: no partial payment, no overpayment |
| Expiry | The expiry job moves `AWAITING_CASH` orders past `reserved_until` to `EXPIRED` and releases their places once. The code payment stays `PENDING`, so a network confirmation racing the expiry follows §Late success (re-reserve, or `payment.refund_required`) |
| Cap | Places of the event's `AWAITING_CASH` orders still held + this order's places ≤ `payments.cash.max-held-percent` (config, default 20) % of the event's total quota. Counted without a lock (soft) |
| Reveals | Staff lookup: event, amount, places, holder labels. Simulated network terminal: event title and amount. Never a name or phone |

## Payments

Gateway-facing, no audience token. Card gateway: Payzone; until its kit arrives, a simulated
gateway (`provider = fake`) that exists only outside prod.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/payments/card/{payment_id}/redirect` | Sends the browser to the gateway: a 302 or an auto-submitting form, as the adapter decides. `410` if the payment is not `PENDING` or the order's hold has passed (then `redirected_at` is not set). Never carries a merchant secret. Serves CARD and WALLET attempts. Sets `redirected_at` on first use. |
| POST | `/payments/{provider}/callback` | Server to server (`fake` now, `payzone` later). Signature verified before any state change. Idempotent on `payment_id`. On approval, in one transaction: payment `SUCCEEDED` → order `PAID` → tickets issued to the stored holders (one per place; Lot 0 orders without holders → buyer). Request format and acknowledgement body come from the adapter. |
| GET, POST | `/payments/card/{payment_id}/return` | Browser landing after the gateway. **Never changes state.** Reads the payment's order and `302`s to a fixed URL by `orders.channel`. WEB: `payments.return-url.web` (`{SHOP_BASE_URL}/orders/{order_id}`). APP: `payments.return-url.app` (`jomhor-fan://orders/{order_id}`). No caller-supplied URL. Unknown `payment_id` → the FR "paiement en cours de confirmation" page. |
| GET | `/payments/fake/{payment_id}` | Simulated gateway page: amount, "Approuver" / "Refuser". Each button posts a signed callback to `/payments/fake/callback`, then lands on `/return`. Supports a delayed callback. Exists only in `dev`/`test`/`staging`; startup fails if `payments.card.provider=fake` under `prod`. |
| POST | `/payments/fake/{payment_id}/late` | Simulated gateway only (same profiles): schedules the signed approval after `payments.fake.late-delay`; `410` if the payment isn't `PENDING`. |
| POST | `/payments/cash/{network}/callback` | A cash network confirms a code paid at one of its agents (server to server; `fake` now, a real network with BE-27). The adapter verifies the signature first (bad → 401 `UNAUTHORIZED`, nothing written). Then, under the code payment's row lock (found by normalised `pay_code`, method `CASH_AGENCY`): unknown code → the adapter's "unknown" answer, nothing written. Already SUCCEEDED with this network and ref → acknowledged again, no effect. Already SUCCEEDED otherwise (paid twice: guichet, another ref) → `payment.refund_required` `NOT_PAYABLE`, acknowledged. Amount ≠ `amount_mad` → payment `FAILED` + `refund_required` `AMOUNT_MISMATCH`. Otherwise payment `SUCCEEDED` (`provider` = the network, `provider_ref` = its ref), then settle as a card success (`AWAITING_CASH` → `PAID` + tickets + deliveries; an `EXPIRED` order per §Late success; a `PAID` one → `NOT_PAYABLE`). A ref already used by another payment of that network → refused, audit `payment.duplicate_ref`, nothing else written (never a 500). A code no longer `PENDING` (collected at a guichet, paid under another ref, or `FAILED` after a mismatch) → `payment.refund_required` `NOT_PAYABLE`, once per network ref; the cash is acknowledged, the refund is ours. `orders.payment_ref` = the code; the network ref stays in `payments.provider_ref`. Wire format and acknowledgement come from the adapter. |
| GET, POST | `/payments/cash/fake` | Simulated agent terminal, only with `payments.cash.networks` containing `fake` in `dev`/`test`/`staging` (startup fails under `prod`). A form takes a code and shows event title + amount, or "code inconnu ou non payable"; "Encaisser" posts the signed callback (HMAC-SHA256 with `PAYMENTS_FAKE_SECRET` over `pay_code\|amount_mad\|network_ref`) to `/payments/cash/fake/callback`. |

Answers: `POST /fan/orders/{id}/pay` → `200`. A callback with a missing, bad or tampered signature →
401 `UNAUTHORIZED` with a fixed message and empty `details`, nothing written. `/redirect`, `/return`
and the simulated gateway page are browser pages (FR HTML, e.g. the 410), not the JSON error shape;
an unknown `payment_id` on `/redirect` is the JSON 404. A success on a `RESERVED` order whose hold
has passed but that the expiry job hasn't swept yet is a normal `PAID` (the places are still held).

Late success: expiry skips orders with a `PENDING` CARD/WALLET payment whose hosted page was opened (`redirected_at`, set by `/payments/card/{id}/redirect`) less than 20 min ago; an attempt never redirected doesn't hold the order. A success after
expiry (card, wallet or cash network) re-reserves the quota atomically → `PAID`; if no quota is left the order stays `EXPIRED`,
the payment is `SUCCEEDED`, and `audit_log` gets `payment.refund_required` (manual refund) with reason `SOLD_OUT`,
`PER_ACCOUNT_LIMIT`, `NOT_PAYABLE` (a second success for an order already `PAID`), or
`AMOUNT_MISMATCH` (an approved callback whose amount differs from the payment: payment `FAILED`,
order stays payable, the charged amount must be refunded).

## Organizer

Every `/org/*` call except `/org/auth/*` and `/org/me` needs `X-Organizer-Id`. The server checks
for an active `staff` row (user_id = token subject). Lot 0 roles: `ADMIN`, `OPS` read and write;
`FINANCE` read only; `GATE`, `SUPERVISOR` get 403. A missing or malformed `X-Organizer-Id` → 400
`BAD_REQUEST`; no active staff row for it → 403 `FORBIDDEN`. Lot 2: devices, staff, assignments and close are ADMIN/OPS; `/live` and `/reconciliation` are also FINANCE (read). GATE/SUPERVISOR still get 403 on `/org`: they only use the scanner. Lot 3: `CASHIER` may call `/org/pos/**`, `GET /org/events`, `GET /org/events/{id}` (for `per_account_limit`, `refund_cutoff_at`, `timezone`) and `GET /org/events/{id}/prices` only (403 elsewhere). Block and unblock: ADMIN, OPS. Refund: ADMIN. `GET /org/refunds` and the ticket lookup (`POST /org/events/{id}/tickets/search`): ADMIN, OPS, FINANCE. `POST /org/refunds/{id}/done`: ADMIN, FINANCE (FINANCE's first write). `/org/finance/cash`: ADMIN, OPS, FINANCE. `/org/pos/**`: ADMIN, OPS, CASHIER. Lot 4: `/org/campaigns/**` ADMIN, OPS (write), FINANCE (read). `/org/members/summary` ADMIN, OPS, FINANCE. CASHIER, GATE, SUPERVISOR get 403.

Fans and staff use the same OTP mechanism, counted per audience (`otp_challenges.audience`): a
code verifies only on the endpoint family that requested it, and staff requests never use up a fan's
limits. Staff codes get the same per-phone limits as fans (3 per 10 min, 10 per 24 h, lockout after 15
failures in 24 h — founder decision Q14) plus the org per-IP limit, all **silent**: over a send limit
`/org/auth/otp` still answers 202 and sends nothing; a locked phone on `/org/auth/verify` answers
`OTP_INVALID` like a wrong code. Anyone can lock a staff phone out for 24 h; that cost is accepted.
IPv6 clients are bucketed per /64 for the per-IP limits. A bearer token sent
to `/fan/auth/**` or `/org/auth/**` is ignored. The failure lockout (fan and staff) counts failures on challenges
created in the last 24 h, so it can lift up to 5 min before the oldest failure is 24 h old.

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/auth/otp` | `{phone}` → always `202` (no staff enumeration). The code is sent only if the phone belongs to a user with an active staff row. Over the rate limit it still answers `202` and sends nothing (a 429 would reveal staff phones). |
| POST | `/org/auth/verify` | `{phone, code}` → `{token, expires_at, memberships:[{organizer_id, organizer_name, role}]}`. JWT `aud=org`, 12 h. A phone with no active staff row → `OTP_INVALID`, nothing created. |
| GET | `/org/me` | `{user:{id, phone, display_name, locale}, memberships:[{organizer_id, organizer_name, role}]}`, no `X-Organizer-Id` (feeds the organizer switcher) |
| GET, POST | `/org/venues` | `{name, city?, capacity, timezone?}` → `{id, name, city, capacity, timezone}`. `timezone` is an IANA region id (`Area/Location`, e.g. `Africa/Casablanca`, the default); unknown ids and fixed-offset ids (`UTC`, `GMT`, `Etc/*`, `+01:00`) → 422. |
| GET, PATCH | `/org/venues/{id}` | GET returns `zones[]` and `gates[]` (each gate with `zone_ids[]`). `timezone` can change only while no event references the venue (`VENUE_IN_USE`); re-sending the current value is not a change. |
| POST | `/org/venues/{id}/zones` | `{code, name, capacity, seated?}` → `{id, code, name, capacity, seated}` (`id` is an int) |
| PATCH, DELETE | `/org/venues/{id}/zones/{zone_id}` | DELETE only if unreferenced (`ZONE_IN_USE`) |
| POST | `/org/venues/{id}/gates` | `{code, name, directions?, latitude?, longitude?}` → `{id, code, name, directions, latitude, longitude, zone_ids:[]}`. `directions` 0–200 chars (`""` clears it on PATCH). Coordinates as JSON numbers, both or neither (else 422 on `latitude`), ranges −90..90 / −180..180, at most 6 decimals (422). These fields are not in the manifest payload, so they never build a new version. PATCH takes the same fields; the one exception to the null-unchanged rule: PATCH with both `latitude: null` and `longitude: null` present clears the location (one null alone → 422 on `latitude`); `GET /org/venues/{id}` gates carry them. |
| PATCH, DELETE | `/org/venues/{id}/gates/{gate_id}` | DELETE only if unreferenced (`GATE_IN_USE`) |
| PUT | `/org/gates/{id}/zones` | `{zone_ids:[...]}` replaces the set. Zones must belong to the gate's venue. |
| GET | `/org/events?status=&from=` | `[{id, title, event_type, starts_at, status, venue:{id, name, timezone}, quota, sold}]` by `starts_at` |
| POST | `/org/events` | `{venue_id, event_type, title, starts_at, doors_open_at?, reentry_policy?, per_account_limit?, metadata?}` → `201` event in `DRAFT`. **K_e (32 random bytes) is generated and encrypted in the same transaction and never returned.** A `venue_id` that is unknown or another organizer's → 422 `VALIDATION_FAILED` on `venue_id`. Lot 0 has no version field: concurrent edits of one event are last-write-wins. | **Lot 1:** + `sale_starts_at?`, which must be before `starts_at − 60 min` (422 on `sale_starts_at`), checked on every write that changes either field. **Lot 2:** `reentry_policy: ONE_EXIT` → 422 `VALIDATION_FAILED` on `reentry_policy`, `details.reason` `NOT_SUPPORTED` (re-entry not built; plan lot-2 Q33).
| GET, PATCH | `/org/events/{id}` | Event (`id` is an int, the 4-byte `event_id` of the QR) `{id, venue_id, timezone, series_id, event_type, title, starts_at, doors_open_at, status, refund_cutoff_at, reentry_policy, per_account_limit, metadata, created_at}`. PATCH allowed in `DRAFT`/`ON_SALE`. `venue_id` and `event_type` are fixed after create (a different value → 422). Changing `starts_at` while `ON_SALE` recomputes `refund_cutoff_at`. `metadata` in a PATCH replaces the whole object. `doors_open_at` cannot be cleared in Lot 0. | **Lot 1:** the shape adds `sale_starts_at`; PATCH may move it while `DRAFT`/`ON_SALE`; it can't be cleared (to open now, set it to now). **Lot 2:** `reentry_policy: ONE_EXIT` → 422 `VALIDATION_FAILED` on `reentry_policy`, `details.reason` `NOT_SUPPORTED` (re-entry not built; plan lot-2 Q33).
| GET, POST | `/org/events/{id}/prices` | `{zone_id, name, price_mad, quota}` → `{id, zone_id, zone:{code, name}, name, price_mad, quota, sold}` (`zone` read-only, Lot 3). The zone must be in the event's venue; Σ quota per zone ≤ zone capacity. A name already used for that zone in the event → 422 `VALIDATION_FAILED` on `name` with `details.reason` `NAME_TAKEN` (clients match the reason, never the message text). |
| PATCH, DELETE | `/org/events/{id}/prices/{price_id}` | PATCH `{name?, price_mad?, quota?}`; `zone_id` is fixed after create (a different value → 422). `quota ≥ sold`. A price change applies to new orders only (snapshot in `order_items`). DELETE only if `sold = 0` (`PRICE_IN_USE`). |
| POST | `/org/events/{id}/publish` | `DRAFT` → `ON_SALE`. Needs ≥ 1 category with quota > 0 and `starts_at > now`. Sets `refund_cutoff_at = starts_at − 60 min`. Writes `audit_log` `event.publish`. → `200` with the event (GET shape). Unmet precondition → 422 `VALIDATION_FAILED` with `details.reason` `NO_QUOTA` (`fields.prices`) or `STARTS_IN_PAST` (`fields.starts_at`). |
| GET | `/org/events/{id}/sales` | ADMIN, OPS, FINANCE. `{event_id, quota, paid, held, revenue_mad, zones:[{zone_id, code, name, quota, paid, held, revenue_mad}], channels:[{channel, paid, revenue_mad}], refunds_required:[{payment_id, order_id, amount_mad, reason, at}]}`. `paid` = places in `PAID` orders; `held` = places in `RESERVED` orders still held; `revenue_mad` = Σ qty × `unit_price_mad` of `PAID` orders (gross, no fees); `refunds_required` = this event's `payment.refund_required` audit rows, newest first. `zones` = the zones with at least one price category on the event, by `code`; `channels` = every `sales_channel` (`APP`, `WEB`, `POS`), zeros included. Types: `zone_id` int, `payment_id`/`order_id` UUID, `reason` one of `SOLD_OUT`, `PER_ACCOUNT_LIMIT`, `NOT_PAYABLE`, `AMOUNT_MISMATCH` (§Payments). Unknown or other organizer's event → 404. **Lot 3:** + `awaiting_cash` (places in `AWAITING_CASH` orders still held; total and per zone), `refunded` (tickets `REFUNDED`; total and per zone), `refunded_mad` (Σ `refunds.amount_mad` of the event, any status), `methods:[{method, paid, revenue_mad}]` for every `payment_method` (zeros included), by `PAID` orders' `payment_method`. `held` stays `RESERVED` only. `paid` and `revenue_mad` keep counting refunded tickets (gross); net = `revenue_mad − refunded_mad`. |
| POST | `/org/events/{id}/tickets/search` | Lookup, `{q}` in the body (a phone never goes in a URL). Unknown or another organizer's event → 404. `q` = a ticket number (8 hex, spaces ignored; every ticket of the event ending so), a holder phone (E.164, exact) or a cash code (§Cash codes input). Else 422 on `q`. → `[{id, ticket_no, status, zone:{id, code, name}, holder_name, holder_label, unit_price_mad, order_id, channel, payment_method, issued_at, refund}]` (`ticket_no` spaced, `4D5C 6B7A`) (`refund` = `{id, status, amount_mad}` or null), by `ticket_no`, at most 50. **Lot 4:** + per row `transferred_to` (`ticket_no` of the new ticket, or null) and `transferred_from` (`ticket_no` of the ticket it replaced, or null). |
| POST | `/org/tickets/{id}/block` | `{reason}` (1–200). `VALID` → `BLOCKED` (else 409 `TICKET_STATUS`); at or after `refund_cutoff_at` → 409 `REFUND_FROZEN`. Audit `ticket.block` (reason). The next manifest version carries it; no `k_t` and no PDF from then on. → lookup row. |
| POST | `/org/tickets/{id}/unblock` | `{reason}`. `BLOCKED` → `VALID`, same rules. Audit `ticket.unblock`. → lookup row. |
| POST | `/org/tickets/{id}/refund` | `{reason}`. `VALID` or `BLOCKED` → `REFUNDED`, same freeze. In one transaction: ticket `REFUNDED`; its place released (`sold − 1`); a `refunds` row `REQUIRED` (`amount_mad` = the item's `unit_price_mad`, `payment_id` = the order's succeeding payment). The order stays `PAID`. Audit `ticket.refund`. → lookup row. Money goes back by hand, then `/done`. |
| GET | `/org/refunds?status=&event_id=` | `[{id, event_id, ticket_id, ticket_no, order_id, amount_mad, method, reason, status, requested_by:{id, name}, requested_at, done_by:{id, name}|null, done_at, reference}]` (`reason` = the free text typed on refund): `REQUIRED` first, then newest. `method` = the payment's method (null if none). |
| POST | `/org/refunds/{id}/done` | `{reference}` (1–100). `REQUIRED` → `DONE`; already `DONE` → 200 unchanged. Audit `refund.done`. → row. |
| POST | `/org/events/{id}/invitations` | Bulk accreditations `[{phone?, name, zone_id, count}]` → tickets with method FREE |
| POST | `/org/devices/enrol-codes` | → `201 {enrol_code, expires_at}`. JWT `aud=enrol`, `sub` = organizer, 30 min, reusable for a batch. B6 renders it as a QR. |
| GET | `/org/devices` | `[{id, label, status, fingerprint, enrolled_at, approved_at, last_seen_at}]`, `PENDING` first, then by label. `fingerprint` per §Gate auth. |
| POST | `/org/devices/{id}/approve` | `PENDING` → `ACTIVE`; `ACTIVE` → `200` unchanged; `REVOKED` → 409 `DEVICE_REVOKED`. Audit `device.approve`. → device. |
| POST | `/org/devices/{id}/revoke` | Any status → `REVOKED` (idempotent). Audit `device.revoke`. → device. |
| GET, PUT | `/org/events/{id}/devices` | GET `[{device_id, label, status, gate_id, gate_code, manifest_version, last_seen_at, closed_ack_at}]` by gate code, then label. PUT `{assignments:[{device_id, gate_id}]}` replaces the set. Devices must be this organizer's and `ACTIVE`, except a device already assigned to this event that is kept on the same gate (a device revoked mid-event stays listed); gates must be of the event's venue (422 on `assignments[i].device_id` / `.gate_id`). Removing a device that has scans on the event, or that has downloaded its manifest (it may hold scans not yet synced), → 409 `DEVICE_IN_USE`. `CLOSED` → 409 `EVENT_NOT_EDITABLE`. → GET shape. |
| GET | `/org/staff` | `[{id, name, role, active, has_pin}]` by name (every staff row; back-office staff have `has_pin: false`). |
| POST | `/org/staff` | `{name, role: GATE\|SUPERVISOR\|CASHIER, phone?}`. GATE/SUPERVISOR → `201 {id, name, role, active, has_pin: true, pin}`. `name` per §Holder names. `pin` = 4 random digits, returned only here and by `/pin`. CASHIER: `phone` (E.164) required (422 otherwise); creates or links the user; a phone already staff of this organizer → 422 on `phone`, `details.reason` `TAKEN` → `201 {id, name, role, active, has_pin: false}` (no `pin`). PATCH covers CASHIER rows too; `/pin` on a CASHIER → 404. |
| PATCH | `/org/staff/{id}` | `{name?, active?}`; GATE/SUPERVISOR/CASHIER rows only (others → 404). |
| POST | `/org/staff/{id}/pin` | New random PIN → `{pin}`. Audit `staff.pin_reset`. Devices get it at their next `GET /gate/events`. |
| GET | `/org/events/{id}/live` | §Gate reports. B4 refetches every 5 s; no stream. |
| POST | `/org/events/{id}/close` | `ON_SALE`/`GATES_OPEN` and `now ≥ starts_at` → `CLOSED`, else 409 `EVENT_NOT_EDITABLE` with `details.reason` `NOT_STARTED` (before T0, plus `details.starts_at`) or `NOT_OPEN` (DRAFT or already `CLOSED`). Audit `event.close`. → event (GET shape). From then on: no `k_t`, manifest 409 `EVENT_CLOSED`, devices drain and wipe; scans are still accepted. |
| GET | `/org/events/{id}/reconciliation` | §Gate reports. `/org/events/{id}/reconciliation.csv` (`Content-Disposition: attachment; filename="reconciliation-{id}.csv"`): `;`-separated, UTF-8 with BOM, FR headers, one row per zone, then per gate, then the total, with a `kind` column (`zone`/`gate`/`total`). |
| GET | `/org/finance/cash?from=&to=&timezone=` | Cash collected: payments `SUCCEEDED` with method `POS_CASH` or `CASH_AGENCY`, `completed_at` within the local days `from`…`to` (both `YYYY-MM-DD`, inclusive) in `timezone`; the server turns them into instants with its own tzdata, so a client's out-of-date zone rules can't shift the days. At most 92 local days, else 422. → `{from, to, timezone, days:[{day, collector, collector_name, method, payments, amount_mad}], sessions:[{id, label, staff:{id, name}, opened_at, closed_at, orders, tickets, prints, expected_mad, declared_mad, variance_mad}], totals:{collected_mad, declared_mad, variance_mad, open_sessions}}`. `day` = local date in `timezone` (IANA, default `Africa/Casablanca`); `collector` = the payment's `provider`, `collector_name` its display name ("Guichet" for `POS`, the network's name otherwise); `days` by day then collector. `sessions` = opened in the range, by `opened_at`; an open one has `closed_at`, `declared_mad` and `variance_mad` null and `expected_mad` = collected so far; `variance_mad = declared − expected`; `totals.declared_mad` and `totals.variance_mad` cover closed sessions only. Clients pass the venue's `timezone` (one zone per organizer's venues; else the default). CSV file name `encaissements-{from}-{to}.csv` (local dates). `/org/finance/cash.csv`: `;`, UTF-8 BOM, CRLF, columns `kind;jour;encaisseur;moyen;paiements;montant;caisse;caissier;ouverture;cloture;attendu;declare;ecart` (`jour` rows fill the first six, `caisse` rows `caisse`…`ecart`), times local `yyyy-MM-dd HH:mm`, amounts as DH with a comma (`1250,50`, `-10,00`, integer maths), text cells quoted per RFC 4180. |
| GET | `/org/finance/settlements` | Settlement statements, VAT invoices |
| GET | `/org/crm/export.csv` | Lot 5. Fan base export (holders who opted in; opt-ins from `memberships`) |
| GET | `/org/members/summary` | `{members, joined_7d, marketing_push, marketing_whatsapp, reachable_push, tiers:[{code, name, members}]}`: active members (`left_at` null). `reachable_push` = members with push opt-in and a live token. `tiers` by `min_events`. Counts only: no name or phone (the CRM export is Lot 5). |
| GET | `/org/campaigns` | `[{id, channel, audience, event_id, title, status, scheduled_at, started_at, finished_at, created_by:{id, name}, recipients, sent, failed}]`, newest first, at most 100. `recipients` / `sent` / `failed` count `fan_messages` rows (0 before the snapshot). |
| POST | `/org/campaigns` | `{channel: PUSH\|WHATSAPP, audience: MEMBERS\|EVENT_HOLDERS, event_id?, title, body}` → `201` (GET shape), status `DRAFT`. `title` 1–60 chars, `body` 1–500 chars (trimmed, any script). `event_id` is required for `EVENT_HOLDERS` and optional for `MEMBERS` (the push deep link); it must be an event of this organizer that isn't `DRAFT` (422 on `event_id`). A channel with no provider configured → 422 on `channel`, `details.reason` `NOT_CONFIGURED`. Audit `campaign.create`. |
| GET, PATCH | `/org/campaigns/{id}` | GET: the list row + `body` + `audience_estimate` (recipients if sent now, computed on read). PATCH: the POST fields, `DRAFT` only (409 `CAMPAIGN_STATUS`). |
| POST | `/org/campaigns/{id}/send` | `{scheduled_at?}` (RFC 3339; absent or past = now; at most 30 days ahead, else 422). `DRAFT` → `SCHEDULED`. Audit `campaign.schedule`. → GET shape. |
| POST | `/org/campaigns/{id}/cancel` | `SCHEDULED` → `CANCELLED`, else 409 `CAMPAIGN_STATUS`. Audit `campaign.cancel`. → GET shape. |

## Campaigns

| Rule | Value |
| --- | --- |
| Audience | `MEMBERS`: members with `left_at` null and the channel's opt-in (`marketing_push` / `marketing_whatsapp`); marketing. `EVENT_HOLDERS`: users holding a `VALID` ticket of `event_id`, member or not; service information about that event only (lot-4 Q62). PUSH also needs ≥ 1 live push token of this organizer. |
| Send | Every 30 s the job takes due `SCHEDULED` campaigns (row lock, skip locked). It sets `SENDING` and `started_at` and snapshots the audience into `fan_messages`, one row per user (`on conflict do nothing`), in one transaction. The outbox drains at most `notifications.send-rate-per-second` (default 20) across kinds, transactional first. When no row of the campaign is `PENDING`, the campaign becomes `SENT` and `finished_at` is set. A user who has opted out or left by send time is skipped: `FAILED` `OPTED_OUT`. |
| Content | PUSH: `title` and `body` as typed; tapping opens `/events/{event_id}`, or the home screen. WHATSAPP: template `club_news` (MEMBERS) or `event_info` (EVENT_HOLDERS) with `title` and `body` as parameters, in the recipient's locale (lot-4 Q63). Nothing about the recipient goes in the payload. |
| Inbox | PUSH campaign rows sent to a user are listed by `GET /fan/me/inbox`. |

## Point of sale

`/org/pos/...`; ADMIN, OPS, CASHIER; organizer-scoped like every `/org` call.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/org/pos/session` | `{session: {id, label, opened_at, orders, tickets, prints, collected_mad} \| null}` for the caller. `collected_mad` = Σ `SUCCEEDED` payments of the session. |
| POST | `/org/pos/sessions` | `{label}` (1–40) → `201` session. The caller already has one open → `200` with it (label unchanged). Audit `pos.open`. |
| POST | `/org/pos/sessions/{id}/close` | `{declared_mad}` (int ≥ 0). The caller's own session (ADMIN: any of the organizer; else 404). Closed → 409 `POS_SESSION_CLOSED`. Freezes `expected_mad` → `200` (finance session shape). Audit `pos.close` (expected, declared). |
| POST | `/org/pos/orders` | Walk-up cash sale. `{sale_id, event_id, items:[{price_category_id, qty, holders:[{name, phone?}]}]}`; `sale_id` = a client-generated UUID that becomes `orders.id`. The same `sale_id` again from the same session → `200` with that order; from elsewhere → 422 on `sale_id` (`TAKEN`). Needs the caller's open session (409 `POS_SESSION_REQUIRED`). Checks: event (404), sale window (409 `EVENT_NOT_ON_SALE`), items and holders (422, same field paths as `/fan/orders`), Σ qty ≤ `per_account_limit` (409 `PER_ACCOUNT_LIMIT`, `held: 0`), quota (409 `SOLD_OUT`); no `SALE_BUSY`. In one transaction: order `PAID` (channel `POS`, `user_id` null, `payment_method` `POS_CASH`); payment `SUCCEEDED` (`POS_CASH`, provider `POS`, `pos_session_id`); tickets issued. A holder with `phone` → that account holds the ticket and gets the WhatsApp delivery; without one → `holder_user_id` null, paper only, no delivery. Audit `pos.sale`. → `201 {id, status, channel, total_mad, items[], ticket_ids[]}`. |
| GET | `/org/pos/codes/{code}` | → `{order_id, status, payable, reserved_until, amount_mad, places, event:{id, title, starts_at, timezone}, holders:[label]}`. Unknown or another organizer's → 404. |
| POST | `/org/pos/codes/{code}/collect` | `{amount_mad}` = the amount the cashier confirmed (≠ `total_mad` → 422 on `amount_mad`). Needs an open session. Payable → in one transaction (payment row, then order): payment `SUCCEEDED` (provider `POS`, `pos_session_id`, `completed_at`), order `PAID` (`payment_method` `CASH_AGENCY`, `payment_ref` = code), tickets + deliveries → `200 {order_id, status: PAID, ticket_ids}`. Already collected by this session → `200` same. Otherwise 409 `CODE_NOT_PAYABLE`. Audit `pos.collect`. |
| GET | `/org/pos/orders/{id}/tickets` | Print data for an order paid in the caller's current open session (POS sale or collected code); ADMIN/OPS may reprint any order paid in a till session of the organizer; else 404. Needs an open session (ADMIN/OPS: no). → `{order_id, event:{id, title, starts_at, timezone, venue:{name}}, tickets:[{id, ticket_no, status, zone:{code, name}, gate_codes[], holder_label, unit_price_mad, qr}]}`. `qr` = the static v1 token (as on the PDF), built inside `withEventKey`, for `VALID` tickets only (else null). `Cache-Control: no-store`. Each call is a print: audit `pos.print` (session, order, ticket count), counted in the `prints` of the session the order was paid in (a manager's reprint shows on the till that sold it, so a closed session's `prints` can still grow; the reprinting actor is audited). |

| Rule | Value |
| --- | --- |
| Sale answer | The `POST /fan/orders` order shape (`items[]` with `price_category_id, zone_id, zone_name, name, qty, unit_price_mad, holders:[{label}]`), status `PAID`, channel `POS`, no buyer. `201`, or `200` on a replay |
| `sale_id` | Replay only for a POS order whose till payment is in the caller's current open session; any other existing order id → 422 `VALIDATION_FAILED` `details.reason: TAKEN` on `sale_id` |
| Session figures | `orders` = distinct orders with a `SUCCEEDED` payment in the session; `tickets` = their tickets; `prints` = `pos.print` audit rows of the session |
| Code lookup | Needs no open session |
| Collect order | Open session (409) → code unknown or another organizer's (404) → already collected in this session (`200` replay) → not payable (409 `CODE_NOT_PAYABLE`, `EXPIRED` once the hold passed, else the order status) → amount ≠ total (422). With no open session a repeat is 409 `POS_SESSION_REQUIRED`; from a later session it is 409 `CODE_NOT_PAYABLE` with status `PAID`, plus `details.collected_by_you: true` when the code was collected in an earlier session of the same cashier (the cash is in that till: keep it) |
| Locks | A sale or collect locks the caller's open session row first (one till at a time; close waits for in-flight sales), then payment → order → buyer → categories |
| Staff | CASHIER `phone` (E.164) already staff of this organizer → 422 `details.reason: TAKEN` on `phone`; `phone` is ignored for GATE/SUPERVISOR; `/pin` on a CASHIER → 404 |
| Audits | `pos.open` only when a session is created; `pos.sale`, `pos.collect`, `pos.print`, `pos.close` (expected, declared, variance) carry ids and amounts only |

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
| POST | `/gate/devices/enrol` | Signed with the new key, without `X-Device-Id` (verified against `public_key`). `{public_key, label, enrol_code}` → `201 {device_id, status: PENDING, fingerprint, manifest_public_key, server_time}`. `public_key` = base64url SPKI DER (P-256); `label` 1–40 chars; `enrol_code` binds the device to that organizer. A known `public_key` → `200` with the existing device. `manifest_public_key` = server Ed25519 key, base64url raw 32 bytes, pinned by the device. |
| GET | `/gate/events` | `ACTIVE` device (`PENDING` → 403 `DEVICE_NOT_ACTIVE`, which the app polls while it waits for approval). → `{server_time, device:{id, label, status}, events:[{id, title, event_type, status, starts_at, timezone, venue:{name}, gate:{id, code, name}, reentry_policy, manifest_available_at, manifest_version}], staff:[{staff_id, label, role, pin_verifier}]}`. `events` = this device's assignments with status `ON_SALE`/`GATES_OPEN`, or `CLOSED` and not yet acked; by `starts_at`. `manifest_available_at` = `starts_at − gate.manifest-lead`. `manifest_version` = latest built, or null. `staff` = this organizer's active GATE/SUPERVISOR staff with a PIN; `label` = holder_label of the name; when several share a label, each gets ` · 2`, ` · 3`… in name order (the first keeps the plain label). `Cache-Control: no-store`. |
| GET | `/gate/events/{id}/manifest?since=` | Device `ACTIVE` and assigned to the event (else 404). `CLOSED` → 409 `EVENT_CLOSED`; before `manifest_available_at` or nothing built → 409 `MANIFEST_NOT_READY`. → gzip `{event_id, version, since, rows, payload, signature, event_key?, server_time}`. `since` absent, unknown or above the latest → full (`since: 0`) **with `event_key`** (base64url, 32 bytes); otherwise a delta from `since`, without `event_key`. `payload` = §Manifest payload; `signature` = base64url Ed25519 over the UTF-8 bytes of `payload`. Sets `device_assignments.manifest_version`. `Cache-Control: no-store`. |
| POST | `/gate/events/{id}/scans` | Device `ACTIVE` and assigned (any event status, `CLOSED` included; not assigned → 404). The 20-overrides-per-device cap is enforced on the device and shown on `/live`; the server stores every valid override. `{cursor, scans:[{scan_id, ticket_id?, gate_id, staff_id, direction, result, token_version?, device_time, override_reason?, supervisor_id?}]}`: 0–500 rows (more → 422; an empty batch is a poll). → `{acked:[scan_id], errors:[{scan_id, field}], used_since:[{ticket_id, direction, first_at, gate_code}], cursor, more, manifest_version, event_status, server_time}`. Rules below. |
| POST | `/gate/events/{id}/closed-ack` | Device assigned (else 404). Event `CLOSED` (else 409 `EVENT_NOT_CLOSED`) → sets `closed_ack_at` (idempotent) → `204`. Sent after the device has drained its queue and wiped. |

Sync rules:

| Rule | Value |
| --- | --- |
| Insert | `INSERT … ON CONFLICT (scan_id) DO NOTHING`, one transaction per batch under `pg_advisory_xact_lock` on the event |
| Times | `device_time` and `first_at`: RFC 3339 in UTC; devices send milliseconds (`2026-11-07T20:00:10.000Z`), the server may omit a zero fraction (`…:10Z`). `gate_code` in `used_since` may be null. |
| Row checks | `scan_id` a UUIDv7; `result` a device code; `direction` `IN`; `ticket_id` present iff the result comes after authentication (OK, DUPLICATE, UNKNOWN, CANCELLED, BLOCKED, WRONG_ZONE, OVERRIDE) and is a ticket of this event; `gate_id` a gate of the venue; `staff_id` staff of the organizer; `override_reason` + `supervisor_id` (a SUPERVISOR) iff `OVERRIDE`. A failing row is not stored; it goes in `errors` with the first failing field, and in `audit_log` (`scan.refused`: device, scan_id, field) |
| Ack | `acked` = every row of the batch, whether stored, already present or refused. The device marks them all synced; a batch is never refused for one row |
| `device_time` | Stored as sent, never rejected for skew; `received_at` sits next to it so drift stays visible |
| `used_since` | Admitted (OK, OVERRIDE) scans of this event from **other** devices with `seq > cursor`, by `seq`, at most 5,000. `first_at` = their `device_time`; `cursor` = the last `seq` returned (or unchanged); `more = true` → the device calls again at once |
| `event_status` | `CLOSED` → the device sends every unsynced row, wipes K_e, manifest, used-set and synced scans, then calls `closed-ack` |

## Gate auth

```
X-Device-Id:        <device uuid>        (absent only on POST /gate/devices/enrol)
X-Device-Time:      <unix ms>            (device clock + server offset)
X-Device-Signature: base64url (no padding) of the DER ECDSA P-256 / SHA-256 signature over:

"JMH-GATE-1" LF METHOD LF PATH LF X-Device-Time LF lowercase-hex(SHA-256(body))
```

| Rule | Value |
| --- | --- |
| PATH | Raw path + query exactly as sent, including `/v1` (`/v1/gate/events/12/manifest?since=3`) |
| X-Device-Time | Decimal digits, no leading zero (`0` or `[1-9]` followed by up to 14 digits), signed exactly as sent; anything else → 401 |
| body | Raw request bytes (requests are not compressed), at most 1 MiB (larger → 413 `BAD_REQUEST` before any signature check); empty body → SHA-256 of the empty string |
| Server order | Device known (enrol: key from the body) → signature → \|time − now\| ≤ 5 min (else `DEVICE_CLOCK_SKEW`) → status `ACTIVE` (except enrol, and `GET /gate/events` which answers 403 for `PENDING`). Then `devices.last_seen_at = now()` |
| Replay | Accepted within the 5-min window: GETs are reads, scans are idempotent, enrol is idempotent per key; TLS throughout |
| `server_time` | In every gate JSON response (RFC 3339, ms). The device offset = `server_time` − the midpoint of request send and response receive |
| Fingerprint | First 8 hex chars of SHA-256(SPKI DER), uppercase, grouped 4-4 (`AB12 CD34`) |

## Gate PINs

| Rule | Value |
| --- | --- |
| PIN | 4 random digits from the server (`POST /org/staff`, `/pin`), shown once, never stored in clear |
| Verifier | `pbkdf2-sha256$10000$<salt>$<hash>`: PBKDF2-HMAC-SHA256 of the UTF-8 PIN, 16-byte random salt, 10,000 iterations, 32 bytes, base64url without padding |
| Device | Unlock = pick a name from `staff`, then the PIN. 5 wrong PINs → locked 5 min. 10 min idle → locked. Override and gate change = a SUPERVISOR picks their name, then types their PIN |

## Manifest payload

UTF-8 text, LF line ends, TAB-separated; the server replaces any TAB, CR or LF inside a text field with a space.

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

## Gate reports

Admitted = `OK` or `OVERRIDE`. Canonical = per ticket, the admitted scan with the earliest `device_time` (tie: lowest `scan_id`). Refused = any other device result. `sold` = tickets `VALID` (blocked, refunded and cancelled tickets are not sold).

| Endpoint | Shape and definitions |
| --- | --- |
| `GET /org/events/{id}/live` | `{event_id, status, starts_at, server_time, sold, entered, rate_per_min, rate_per_min_prev, refused:{total, by_result:{DUPLICATE: n, …}}, duplicates_late, overrides:{total, cap_per_device: 20, by_device:[{device_id, label, count}]}, gates:[{gate_id, code, zone_codes[], devices_total, devices_online, entered, refused, overrides, rate_per_min, last_sync_at}], devices_stale:[{device_id, label, gate_id, gate_code, last_seen_at}]}`. `by_result` lists refused result codes (never `OK`/`OVERRIDE`) with a count > 0 only. `entered` = tickets with a canonical scan (per gate: canonical scans at that gate). `duplicates_late` = admitted − canonical. `rate_per_min` = admitted scans with `device_time` in the last 5 min ÷ 5 (integer); `_prev` = the 5 min before that. Online = `last_seen_at` within 2 min; stale = assigned, `ACTIVE`, not yet acked the close, and silent for more than 5 min (or never seen). `gates[].devices_total`/`devices_online` count the gate's assigned `ACTIVE` devices. `gates[].last_sync_at` = the latest `last_seen_at` among the gate's assigned devices (null if none). `devices_stale` by gate code then label; `by_device` ties by label. `gates` = gates with an assigned device or a scan, by code. `by_device` = devices with ≥ 1 override, by count desc. |
| `GET /org/events/{id}/reconciliation` | `{event_id, status, generated_at, final, devices:{assigned, acked, revoked, unsynced:[{device_id, label, gate_code, status, last_seen_at}]}, totals:{sold, entered, no_show, refused_by_result{}, duplicates_late, overrides_by_reason{}}, zones:[{zone_id, code, name, sold, entered, no_show, overrides}], gates:[{gate_id, code, name, zone_codes[], entered, refused_by_result{}, duplicates_late, overrides}]}`. Zones by the ticket's zone (zones with a price category on the event, by code), gates by the scan's gate (gates with an assigned device or a scan, by code). Here `entered` = sold (`VALID`) tickets with a canonical scan and `no_show` = sold tickets with none, so `entered + no_show = sold` (per zone and in total); an override that admits a non-`VALID` ticket counts in `overrides`, not in `entered`. `final` = `CLOSED` and every assigned `ACTIVE` device acked (a revoked device can never ack: its unsynced scans are lost, Q41). `unsynced` = assigned devices that have not acked the close (before the close: every assigned device), revoked ones included with `status: REVOKED`; `revoked` = assigned devices now `REVOKED`. `refused_by_result` lists refused codes only (never `OK`/`OVERRIDE`/`DUPLICATE_LATE`), `overrides_by_reason` the `override_reason` codes; both with counts > 0 only. CSV columns: `kind;code;nom;vendus;entres;absents;refuses;doublons_tardifs;derogations` (gate rows leave `vendus`/`absents` empty, zone rows leave `refuses`/`doublons_tardifs` empty, the total row leaves `code`/`nom` empty); CRLF line ends; cells with `;` or `"` are quoted. `unsynced` by gate code then label; `zone_codes` by zone id. |

## Token (shared by `packages/core` and the backend)

```
bytes:  version(1) | event_id(4, BE) | ticket_id(16) | zone_id(2, BE) | [slot(4, BE)] | tag(16)
v1 static:   tag = HMAC-SHA256(K_e, version|event_id|ticket_id|zone_id)[0:16]
v2 rotating: K_t = HMAC-SHA256(K_e, ticket_id); slot = floor(unix/30)
             tag = HMAC-SHA256(K_t, version|event_id|ticket_id|zone_id|slot)[0:16]
encoding: base64url, no padding
```

Acceptance on the scanner: v2 valid if `|slot - now_slot| <= 1` (server-time-corrected clock).

### Test vectors (`docs/token-vectors.json`, generated by the backend)

`TokenVectorsTest` (backend) builds 10 static and 10 rotating vectors from fixed inputs, including the edge values and the 6 interim cases (hard-coded in the test). Locally, if the file is absent or `-Djomhor.writeVectors=true` is set, it writes `docs/token-vectors.json`; in CI (`CI` set) a missing file fails; otherwise it fails when the committed file differs. `packages/core` fails, rather than skipping, when the file is missing.
Format:
`[{k_e, event_id, ticket_id, zone_id, slot?, expected_token}]` for 10 static and 10
rotating tokens. Field encodings: `k_e` lowercase hex (64 chars); `event_id`, `zone_id`,
`slot` JSON integers (`slot` absent for v1); `ticket_id` canonical lowercase UUID string;
`expected_token` base64url without padding. Include the edge values (`event_id` 0xFFFFFFFF,
`zone_id` 0xFFFF, `slot` 0xFFFFFFFF). `packages/core` golden-tests against the same file. Regenerate with `./gradlew test -Djomhor.writeVectors=true` (backend) only when
the layout changes; both `TokenVectorsTest` and the `packages/core` golden test read the committed file.

## Gate vectors

`GateVectorsTest` (backend) writes `docs/gate-vectors.json` under the same rules as the token vectors (CI fails if it is missing or differs): `{signing:[{method, path, time, body, expected_string, expected_body_sha256}], fingerprints:[{public_key, fingerprint}], manifest:[{public_key, payload, signature}], pins:[{pin, verifier, ok}]}`. Ed25519 is deterministic, so a fixed test key gives fixed signatures. `apps/scanner` golden-tests against it.
