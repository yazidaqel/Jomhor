# Lot 1 · Sell & deliver: implementation plan

As of 2026-11-06 · owner: tech-lead · status: **approved 2026-11-06**; §4 applied to the docs.

## 1. Lot

**Lot 1 · Sell & deliver.** Lot 0 is merged (PR #1). It provides tenancy, OTP and JWT, venues, zones, gates, events with K_e, prices, publish, fan orders (atomic quota, per-account limit, expiry), the simulated card gateway and ticket issue (holder = buyer).

What Lot 1 needs that does not exist yet:

| Need | State | Plan |
| --- | --- | --- |
| Server-side token codec (Java) | None. Only Dart `packages/core` | BE-8 |
| `docs/token-vectors.json` | Missing. The Dart golden test is skipped | BE-8, then FL-2 |
| Public event read API | `/fan/**` requires a token | BE-10 |
| `apps/fan`, `web/shop` | Don't exist | FL-3, WS-1 |
| Real OTP / WhatsApp provider | Log adapter only (Q2 not decided) | Ports now (BE-9, BE-14). Adapter BE-15 once the founder picks |
| Payzone | Simulated only | BE-7, only if the kit arrives |
| Hosting (AWS eu-west-3), store accounts | None. **No agent owns infra** | Founder / orchestrator (Q24, Q25) |

Exit ("fans buy and hold a ticket for a real fixture"):

| Level | Proof |
| --- | --- |
| Demo-complete (Lot 1 done) | On staging, the club publishes the real fixture with an opening time. A fan buys 2 places on the web shop and in the app (simulated card or Payzone sandbox), the second holder named with a friend's phone. Both hold their ticket: rotating QR offline in the app, PDF on W5. The WhatsApp delivery row is `SENT` (log adapter, or the real provider if BE-15 is done). B3 shows the sale. `Lot1SaleFlowTest` is green in CI. |
| Live (real money) | The same in prod. Gated by the Payzone kit (BE-7), the provider (BE-15), infra (Q24) and store builds (Q25). Pick a fixture at least 3 weeks after Lot 1 ends, so Lot 2 (gate) ships before its T0. |

## 2. Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (no PII in QR, K_t only to owner, money), Conventions (web shop: never expose K_t; Flutter golden tests), Agent team |
| `docs/plans/lot-0.md` | §3 Q1, Q2, Q7, Q13, Q14; §4.5 CardGateway; §7 carried forward |
| `docs/00-product.md` | §V1 in lots (Lot 1 row), §Why it can win (WhatsApp, fairness), §Open questions (named tickets) |
| `docs/01-architecture.md` | §Components (fan app holds K_t; web shop holds nothing), §Key flows 1 Purchase |
| `docs/02-gate-validation-and-data-model.md` | §Ticket token & QR format (layout, K_t, ticket number), §Match manifest (`holder_initials`, no full names), §Threat model (screenshot, PDF printed twice, server time offset, refund freeze, bots), §Data model, §Fan app design direction (matchday mode) |
| `docs/03-schema.sql` | events, order_items, tickets, users, otp_challenges, tenant rule header |
| `docs/04-api.md` | header (errors), §Fan (all rows), §Payments (`/return` Lot 1 line), §Organizer (events, `/sales`), §Token, §Test vectors |
| `docs/06-glossary.md` | K_t, Order statuses |
| `design/DESIGN.md` | Fan screens 1–5, Web shop W1–W6, B2 Règles, B3, §Theming, §Accessibility |
| Mockups | `1-accueil`, `2-match`, `3-zones`, `4-paiement`, `5-billet`, `w2-shop-evenement`, `w4-shop-commande-cash` (status layout only), `b2-backoffice-evenement` |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default for Lot 1 |
| --- | --- | --- |
| Q2 | SMS/WhatsApp provider (still open since Lot 0) | Ports plus log adapters (dev/staging). Prod refuses to start without a real adapter. **Founder picks by day 10.** Criteria: SMS to IAM/Orange/inwi numbers, sender ID (ANRT), WhatsApp Business templates with a document header, one provider for both if possible. Meta business verification and template approval take days to weeks, so start in W0. BE-15 is built once credentials exist. |
| Q13 | JWT revocation before K_t reaches phones | **Decide now:** fan JWT keeps 30 d but carries `sid`. A `fan_sessions` row is checked on every `/fan` call; `POST /fan/me/logout {all?}` revokes. No refresh token (the matchday flow needs "no login prompts", and K_t works offline anyway). Org tokens unchanged: the per-request staff-row check already cuts off removed staff. Residual risk: a stolen unlocked phone keeps its K_t until the ticket is re-issued (Lot 4 transfer/re-issue). |
| Q14 | OTP abuse limits (decided) | Implemented in BE-9. **Plus one change:** codes are counted per audience (`otp_challenges.audience`), see P7. Per-IP defaults (config knobs): fan 20 sends/10 min and 200/24 h; org 5/10 min and 20/24 h. Moroccan mobile CGNAT puts many fans behind one IP, so keep the fan limits loose and watch them on on-sale day. |
| Q15 | Named tickets: ID check at the gate (club/FRMF question) | A holder name for every place, required. Initials go in the manifest (Lot 2). No ID check at the gate. "CIN vérifiée" badge → Lot 5 (verified fans). No per-event "nominatifs" toggle: always named (one customer; add a toggle when a promoter asks). |
| Q16 | Holder name script | Latin letters only in Lot 1 (matches the Latin line of the CIN). PDF fonts and the scanner's initials stay simple. Arabic-script names come with Lot 4 (AR/RTL); the PDF needs shaping. |
| Q17 | App tickets can also get a static PDF (v1)? | Yes, every holder can get the PDF (spec: one ticket record, all channels). First scan wins limits it to one entry. An app-only flag in the manifest is a Lot 2 option if the club wants it for derbies. |
| Q18 | A cap per holder phone (not just per buyer)? | No. The buyer's `per_account_limit` (exists) is the only cap. A holder cap belongs with verified fans in Lot 5. |
| Q19 | "Basic on-sale queue" | **Scheduled opening + busy throttle, no ranking.** `events.sale_starts_at`; when the event's active holds ≥ `sales.max-active-holds-per-event` (config, default 2000), `POST /fan/orders` → 429 `SALE_BUSY {retry_after_s}`. Clients show "File d'attente" and retry with jitter, with honest copy and no position number. The ranked virtual queue stays in Lot 5; load testing in Lot 6. |
| Q20 | When do sales close? | At `refund_cutoff_at` (T0 − 60 min): scanners are assumed offline from then on, so a later ticket would be `UNKNOWN`. Walk-up sales: Lot 3 POS question. |
| Q21 | WhatsApp opt-in and CNDP (law 09-08) | The purchase is the opt-in for the transactional ticket message (caption "Billets envoyés dans l'app et sur WhatsApp"). The buyer confirms that friends agree to receive theirs. The founder files the CNDP declaration (holder names and phones). Not blocking dev. |
| Q22 | Stadium map on screen 3 | Lot 1: zone list + stepper (zones have no geometry). Map from a per-club SVG keyed by `zones.code` is optional FL-9, cut first. |
| Q23 | Organizer theme from the server | Build-time tokens (`design/tokens.json`, one club). `organizers.settings.theme` over the API when a second club arrives. |
| Q24 | Hosting for "real fixture" | **No agent owns infra.** Minimum: API container + RDS PG18 + Node runtime for the shop + Secrets Manager (JWT/OTP/master key, provider credentials), about 3 d. Default: founder/orchestrator. If given to `backend`, Lot 1 slips about 3 d. |
| Q25 | App distribution | Play closed testing + TestFlight. The web shop is the fallback for everyone. Founder opens both developer accounts in W0 (Apple lead time). |
| Q26 | Copy languages | FR complete. AR/EN ARB and dictionary files exist with FR fallback. Real AR/RTL in Lot 4. |
| Q27 | Payzone in a webview (app 3DS) | `webview_flutter` that catches the return URL. If the kit forbids webviews, switch to Custom Tabs + deep link (same return URL). |
| Q1 | Payzone | Unchanged: simulated until the kit arrives. BE-7 only if it arrives during Lot 1. |

## 4. Contract changes

### 4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P1 | 04 `GET /fan/events*` | Under `/fan/**`, which needs a token. SSR/SEO pages and shared links need them public | Public, no token (§4.3) |
| P2 | 04 `holders:[{phone?}]`, mockup 4 | No name, so `holder_initials` can't be set at issue (tickets are immutable). The ticket card shows "TITULAIRE Yazid A." | Holders nested per item `{name, phone?}`; `tickets.holder_name` |
| P3 | 04 `/fan/tickets` | No `server_time`; the threat model says the server time offset is pushed to the app | `{server_time, tickets[]}` |
| P4 | 04 `/fan/tickets` | `k_t` goes to any caller, including web shop server code | Only with `?with_keys=true` (the app); the shop never asks |
| P5 | 04 orders / 02 threat model | Orders accepted until `CLOSED`; tickets sold after T0 − 60 aren't in any scanner's manifest → `UNKNOWN` at the gate | Orders refused from `refund_cutoff_at` |
| P6 | DESIGN screen 1 "Vente le {date}" | No field for a scheduled opening | `events.sale_starts_at` |
| P7 | `otp_challenges` shared by fan and org; Q14 | `/org/auth/otp` creates a challenge for any phone (no staff enumeration), so it uses up the fan's per-phone count: 3 calls lock a fan out for 10 min; with the new daily cap, 10 calls lock them out for 24 h. The per-IP throttle doesn't prevent this. | `otp_challenges.audience`; limits and the latest challenge are per (phone, audience); a code verifies only on its own audience |
| P8 | Q13 | A 30 d bearer token with no revocation. Once K_t is served, a leaked token = stolen ticket | `fan_sessions` + `sid` |
| P9 | 01 §Components (Kafka `order.paid` → WhatsApp) | No Kafka exists; one delivery outbox table is enough | 01 note: Lot 1 uses a DB outbox (`ticket_deliveries`); Kafka when the dashboard needs it (Lot 2) |
| P10 | 04 transfer row "new K_t" | K_t = HMAC(K_e, ticket_id): a new K_t means a new `ticket_id` | Reword now: transfer = cancel + new ticket (Lot 4) |
| P11 | 04 `/payments/card/{id}/return` | "Lot 1: redirects to a fixed client URL per channel", but URLs are not defined | Config keys + 302 (§4.3) |
| P12 | Tenant rule | `fan_sessions` is per user (platform), like `users` | 4th documented exception; `MigrationsTest` allowlist goes from 3 to 4 tables |
| — | Football names / floats / PII in QR | None. The holder name never enters the token; only initials go in the Lot 2 manifest | OK |

### 4.2 `docs/03-schema.sql`: replacements and additions

```sql
-- Tenant rule (replace header line): every table carries organizer_id, except platform identity tables
-- organizers, users, otp_challenges, fan_sessions (one fan account across organizers).

-- ---------- tenancy (replace otp_challenges, add fan_sessions) ----------
create table otp_challenges (
  id            uuid primary key default gen_random_uuid(),
  phone_e164    text not null,
  audience      text not null default 'fan' check (audience in ('fan','org')),  -- a code verifies only on its own audience
  ip            inet,                                 -- requester (trusted-proxy resolved), for the per-IP throttle
  code_hash     text not null,                        -- HMAC-SHA256(server secret, code); the code is never stored
  attempts      smallint not null default 0 check (attempts <= 5),
  expires_at    timestamptz not null,                 -- created_at + 5 min
  consumed_at   timestamptz,
  created_at    timestamptz not null default now()
);
create index on otp_challenges (phone_e164, audience, created_at desc);
  -- per phone+audience: 3 sends / 10 min, 10 sends / 24 h; sum(attempts) of failed codes >= 15 in 24 h -> locked
create index on otp_challenges (ip, created_at desc);   -- per IP: docs/04-api.md §Fan auth

create table fan_sessions (
  id            uuid primary key default gen_random_uuid(),   -- JWT `sid`
  user_id       uuid not null references users(id),
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,                 -- = JWT exp (30 d)
  revoked_at    timestamptz
);
create index on fan_sessions (user_id) where revoked_at is null;

-- ---------- catalog: events, add ----------
--   sale_starts_at    timestamptz,   -- null = on sale from publish; must be < starts_at - 60 min
-- orders are accepted while ON_SALE, now >= coalesce(sale_starts_at, -inf) and now < refund_cutoff_at.

-- ---------- sales: order_items, add ----------
--   holders  jsonb not null default '[]'::jsonb  -- [{"name": "Yazid Aqel", "user_id": "<uuid>"}], one per place
--   constraint order_items_holders_len check (jsonb_array_length(holders) = qty) not valid  -- Lot 0 rows keep []
-- user_id is the account that will hold the ticket (buyer, or the account of the phone given).
-- No phone is stored here.

-- ---------- sales: tickets, add ----------
--   holder_name  text,   -- full name as entered (Latin, docs/04-api.md §Holder names); never in a QR or manifest
-- holder_initials ('Y. A.') is derived from holder_name at issue. Both are null on Lot 0 tickets.

-- ---------- notifications ----------
create type delivery_status as enum ('PENDING','SENT','FAILED');

create table ticket_deliveries (                       -- outbox: WhatsApp ticket messages (only channel in Lot 1)
  id               uuid primary key default gen_random_uuid(),
  organizer_id     uuid not null references organizers(id),
  ticket_id        uuid not null references tickets(id),  -- recipient = tickets.holder_user_id, phone read at send time
  status           delivery_status not null default 'PENDING',
  attempts         smallint not null default 0,
  next_attempt_at  timestamptz not null default now(),
  last_error       text,                               -- provider error code only; no phone, no message body
  created_at       timestamptz not null default now(),
  sent_at          timestamptz
);
create index on ticket_deliveries (next_attempt_at) where status = 'PENDING';
create index on ticket_deliveries (ticket_id, created_at desc);   -- re-send limit
```

Flyway (one migration per task, in task order):

| File | Task | Content |
| --- | --- | --- |
| `V6__event_sale_window.sql` | BE-10 | `events.sale_starts_at` |
| `V7__named_tickets.sql` | BE-11 | `order_items.holders` + constraint `not valid`, `tickets.holder_name` |
| `V8__fan_sessions_and_otp_limits.sql` | BE-9 | `otp_challenges.audience`, `.ip`, the two indexes (drop the old phone index), `fan_sessions` |
| `V9__ticket_deliveries.sql` | BE-14 | `delivery_status`, `ticket_deliveries` |

The `02` §Data model table gets the same columns and rows in the same docs change.

### 4.3 `docs/04-api.md`: additions and replacements

**Errors table: add or replace**

| Code | HTTP | When |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | Missing, invalid or wrong-audience token; fan token whose session is revoked or expired |
| `OTP_RATE_LIMITED` | 429 | Any OTP limit in §Fan auth (per phone, per IP, or phone locked after failed codes) |
| `EVENT_NOT_ON_SALE` | 409 | Order outside the sale window. `details.reason`: `NOT_ON_SALE` (status), `NOT_STARTED` (+ `details.sale_starts_at`), `CLOSED` (at or after `refund_cutoff_at`) |
| `SALE_BUSY` | 429 | Basic on-sale queue is full; `details.retry_after_s` (int, 5–30, with jitter). Retry the same request. |
| `TICKET_NOT_VALID` | 409 | PDF or WhatsApp re-send for a ticket that isn't `VALID` |
| `DELIVERY_RATE_LIMITED` | 429 | More than 3 WhatsApp re-sends of one ticket in 24 h |

**§Fan: replace the whole table**

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

**New §Holder names**

| Rule | Value |
| --- | --- |
| Input | NFC-normalised, trimmed, inner spaces collapsed; 2–60 chars; `^\p{IsLatin}[\p{IsLatin} '.\-]{1,59}$` (Latin letters with accents, space, apostrophe, dot, hyphen). Arabic script: Lot 4. |
| `holder_label` (ticket card, PDF) | first word + space + first letter of the last word + "." → `Yazid A.`; one word → as is |
| `holder_initials` (Lot 2 manifest) | first letter of the first and last words, uppercase → `Y. A.`; one word → `Y.` |
| `ticket_no` | last 8 hex chars of `ticket_id`, uppercase, grouped 4-4 → `4D5C 6B7A` |

**New §On-sale (basic queue)**

| Rule | Value |
| --- | --- |
| Opening | `sale_starts_at` (null = at publish). Before it: `EVENT_NOT_ON_SALE` `NOT_STARTED`. |
| Closing | `refund_cutoff_at` (T0 − 60 min). From then on: `EVENT_NOT_ON_SALE` `CLOSED`. |
| Busy | Active holds of the event (`RESERVED`, `reserved_until > now`) ≥ `sales.max-active-holds-per-event` (config, default 2000) → 429 `SALE_BUSY`. A soft cap: two racing orders can each pass it. |
| Clients | Show "File d'attente" with the retry countdown and retry automatically. No position number is shown or implied. The ranked virtual queue is Lot 5. |

**§Payments: replace the `/return` row**

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/payments/card/{payment_id}/return` | Browser landing after the gateway. **Never changes state.** Reads the payment's order and `302`s to a fixed URL by `orders.channel`. WEB: `payments.return-url.web` (`{SHOP_BASE_URL}/orders/{order_id}`). APP: `payments.return-url.app` (`jomhor-fan://orders/{order_id}`). No caller-supplied URL. Unknown `payment_id` → the FR "paiement en cours de confirmation" page. |

**§Organizer: replace these rows**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/events` | Lot 0 body + `sale_starts_at?`. Must be before `starts_at − 60 min` (422 on `sale_starts_at`); checked on every write that changes either field. |
| GET, PATCH | `/org/events/{id}` | Lot 0 shape + `sale_starts_at`. PATCH may move it while `DRAFT`/`ON_SALE`; it can't be cleared (to open now, set it to now). |
| GET | `/org/events/{id}/sales` | ADMIN, OPS, FINANCE. `{event_id, quota, paid, held, revenue_mad, zones:[{zone_id, code, name, quota, paid, held, revenue_mad}], channels:[{channel, paid, revenue_mad}], refunds_required:[{payment_id, order_id, amount_mad, reason, at}]}`. `paid` = places in `PAID` orders; `held` = places in `RESERVED` orders still held; `revenue_mad` = Σ qty × `unit_price_mad` of `PAID` orders (gross, no fees); `refunds_required` = this event's `payment.refund_required` audit rows, newest first. |

**§Test vectors: replace the first sentence**

`TokenVectorsTest` (backend) builds 10 static and 10 rotating vectors from fixed inputs, including the edge values and the 6 interim cases of `packages/core/tool/interim_vectors.py`. If the file is absent, or `-Djomhor.writeVectors=true` is set, it writes `docs/token-vectors.json`; otherwise it fails when the committed file differs. `packages/core` fails, rather than skipping, when the file is missing.

### 4.4 `design/DESIGN.md`

| Where | Change |
| --- | --- |
| Screen 1 | "Vente le {date}" comes from `sale_starts_at` (venue time zone). "File d'attente" chip/CTA state = the client is retrying after `SALE_BUSY`. "Liste d'attente" (sold out) → Lot 5. Bottom nav in Lot 1: Accueil · Billets · Profil (Club in Lot 4). |
| Screen 2 | "Dernières places" when `remaining ≤ 50`. |
| Screen 3 | Lot 1: zone row cards (same as screen 2) + stepper; the stadium map comes later (Q22). |
| Screen 4 | "BILLETS NOMINATIFS": each row has a **name** input (required, Latin) and an optional WhatsApp-number input. Row 1 is prefilled from `display_name` with no phone. Lot 1 shows only Carte bancaire; Cash and Wallet in Lot 3. Busy state: "Beaucoup de monde en ce moment · nouvel essai dans {s} s". |
| Screen 5 | "TITULAIRE" = `holder_label`. Lot 1 actions: WhatsApp (re-send PDF) only; Transférer (Lot 4) and Apple/Google Wallet (Lot 4) are hidden. |
| W3 / W4 | W3 Lot 1: card only. `/orders/[id]` in Lot 1 shows "paiement en cours" + polling → `PAID` → link to W5; the cash code layout is Lot 3. |
| W5 / W6 | W6 is a route handler proxying `/fan/tickets/{id}/pdf`. |
| B2 | Général: "Ouverture de la vente" (venue time zone). Règles: "Billets nominatifs · toujours" (read-only). |
| B3 | Lot 1: Ventes (KPIs, per zone, per channel, "Remboursements à faire"). |

### 4.5 `docs/01-architecture.md`

Add under §Components: "Lot 1: `order.paid` → WhatsApp delivery goes through a DB outbox (`ticket_deliveries`) polled by `notifications`. Kafka comes in when the scan dashboard needs it (Lot 2)."

## 5. Tasks

### Wave 0: orchestrator and founder (0.5 d, before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/03`, `docs/04`, `docs/01`, `docs/02` §Data model, `design/DESIGN.md` | §4 applied after the founder agrees |
| W0-2 | orchestrator | `.github/workflows/ci.yml` | Path-filtered jobs added: `fan` (`apps/fan/**`, `packages/core/**`: `flutter analyze` + `flutter test`) and `shop` (`web/shop/**`: `npm ci && npm run check`) |
| W0-3 | orchestrator | git | Branch `feat/lot-1-sell-deliver` from `main` after PR #1 merges (done) |
| W0-4 | founder | — | Start: provider choice + Meta business verification (Q2), Payzone kit chase (Q1), Apple/Google developer accounts (Q25), infra owner (Q24), CNDP (Q21) |

### Tasks

`backend` is the serial critical path. Each client task starts once the backend task it reads is reviewed. Rows marked ∥ run at the same time as the backend task of their wave, in disjoint folders.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-10 | backend | `backend/…/catalog`, `…/sales` (sale-window check), `…/tenancy/adapters/web/SecurityConfig` (public GET `/fan/events/**`), V6 | `sale_starts_at` on org events (POST/GET/PATCH + rule). Public `GET /fan/events`, `/fan/events/{id}` via controller-local DTOs. Orders refused outside the window (`NOT_STARTED`/`CLOSED`). | `FanEventApiTest`: no token → 200; DRAFT absent / 404; `remaining`, `from_price_mad`, `sold_out` after an order; `gate_codes` from `gate_zones`; `timezone`; a JSON scan finds no `key`/`k_e`/`sold`/`quota` field. Order before the opening → 409 `NOT_STARTED`, at the cutoff → `CLOSED`. `sale_starts_at` ≥ T0 − 60 → 422. | W0 | 2 |
| 1 ∥ | FL-3 | flutter | `apps/fan/` | Scaffold: Flutter app on `packages/core` (path dep), Riverpod, go_router, freezed/json, `http`, `flutter_secure_storage`, l10n (FR full, ar/en files with FR fallback), theme from `design/tokens.json`, API client (Bearer, 04 error shape), `--dart-define` API base + `ORGANIZER_ID` | `flutter analyze` clean; tests: client sends Bearer and parses `{code, message, details}`; a theme test reads the accent from tokens; a missing AR string falls back to FR | W0 | 2 |
| 1 ∥ | WS-1 | web-shop | `web/shop/` | Scaffold: Next.js 16 App Router, TS, Tailwind from tokens, FR dictionary (+ ar/en stubs, no new i18n dependency), `server-only` API module (env `API_BASE_URL`, `ORGANIZER_ID`), route handlers `/api/auth/otp`, `/api/auth/verify` (token → httpOnly cookie), `/api/auth/logout`, Origin check on every mutating handler, `/login?next=` page. `npm run check` = typecheck + lint + test + build. | Tests: verify sets `HttpOnly; Secure; SameSite=Lax`; a POST with a foreign Origin → 403; `next` accepts same-site paths only (no open redirect); centimes → "50,50 DH" by integer maths | W0 | 2 |
| 2 | BE-11 | backend | `backend/…/sales`, `…/tickets`, `…/tenancy` (`/fan/me`), `…/payments` (return), V7, `backend/http/lot1-sale.http` | Nested holders (validation, holder user upsert by phone, snapshot in `order_items.holders`). Settle issues `holder_user_id/name/initials` (late success Q8 too). `SALE_BUSY` soft cap (config). `GET/PATCH /fan/me`. `/return` 302 per channel. `Lot0SaleFlowTest` → `Lot1SaleFlowTest`. | `OrderApiTest`: holders length ≠ qty → 422 with field path; non-Latin name → 422; friend phone → after PAID the friend's user holds ticket 2 and the buyer holds ticket 1; initials `Y. A.`; label `Yazid A.`. Cap = 2 → 3rd order 429 with `retry_after_s`. `/return` → `Location` = configured WEB/APP URL + order id, and no state change. Q8 late success issues to the holders. | BE-10 | 2.5 |
| 2 ∥ | FL-4 | flutter | `apps/fan/lib/features/auth` | OTP login (phone → code), token in secure storage, 401 → login, `OTP_RATE_LIMITED` copy | Widget tests: happy path stores the token; 429 shows copy; 401 on any call routes to login | FL-3 | 1 |
| 2 ∥ | FL-5 | flutter | `apps/fan/lib/features/events` | Screens 1 (hero, countdown, "Vente le", "Mon billet" strip hidden until FL-7) and 2 (zone rows, Dernières places / Complet, rules banner). Times through the venue's IANA zone (`timezone` package). | Tests: states on_sale / not started / sold out / closed; Africa/Casablanca conversion across the Ramadan DST change; metadata read by `event_type` | BE-10, FL-3 | 2 |
| 2 ∥ | WS-2 | web-shop | `web/shop/app/(public)` | W1 (SSR, revalidate 60), W2 `/events/[slug]` with slug `{id}-{title}` (canonical redirect, revalidate 15), `opengraph-image`, zone grid + summary card as client components, sticky bar at phone width | Tests: wrong slug → 308 to canonical; Complet card disabled; "Vente le" state; HTML has no `key` field; build green | BE-10, WS-1 | 3 |
| 2 ∥ | BO-6 | backoffice | `web/backoffice/src/features/events` | B2 Général "Ouverture de la vente" (venue time zone ↔ UTC); Règles "Billets nominatifs · toujours" read-only | Tests: field converts to UTC; 422 on `sale_starts_at` shows FR copy | BE-10 | 1 |
| 3 | BE-9 | backend | `backend/…/tenancy`, V8 | Q13 + Q14: OTP per audience, daily caps, 15-failure lockout, per-IP throttle (Tomcat RemoteIp with internal proxies from config; a client-sent header is never trusted otherwise), send after commit on an executor (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`), `fan_sessions` + `sid` validator, `POST /fan/me/logout` | Tests: 11th fan send in 24 h → 429; after 15 failures a correct code → 429; 10 `/org/auth/otp` calls for a fan's phone leave fan `/otp` working; 21st fan send from one IP → 429 and a spoofed `X-Forwarded-For` from an untrusted peer doesn't bypass it; org over the IP limit → 202 and sender not called; rollback → nothing sent, send runs off the request thread; token without `sid` → 401; logout → same token 401; `all` → the other session's token 401; `MigrationsTest` allowlist = 4 | BE-11 | 2 |
| 3 ∥ | FL-6 | flutter | `apps/fan/lib/features/checkout` | Screen 3 (list + stepper ≤ `per_account_limit`), screen 4 (holders name + phone, row 1 = me via PATCH `/fan/me`, countdown + expiry modal, card via `webview_flutter` catching `jomhor-fan://orders/{id}`, `SALE_BUSY` retry with jitter, poll order → PAID → slide-in) | Tests: request nests `holders` per item; name/phone validation mirrors §Holder names; retry waits `retry_after_s` (fake clock); return-URL matcher; expiry modal at 0 | BE-11, FL-5 | 3 |
| 3 ∥ | WS-3 | web-shop | `web/shop/app/orders`, `app/api/orders` | W3 (holders, card only, countdown, busy retry, route handlers for order + pay → `location = redirect_url`), `/orders/[id]` (SSR + polling every 3 s → PAID → link to W5) | Tests: handler forwards the cookie token as Bearer and never returns it; holders payload; polling stops on PAID/EXPIRED | BE-11, WS-2 | 2.5 |
| 4 | BE-8 | backend | `backend/…/tickets/domain/TicketToken.java`, `backend/src/test/…/TokenVectorsTest.java`, `docs/token-vectors.json` | Java codec (v1, v2, K_t, slot, `ticket_no`) per 04 §Token, constant-time compare; vectors per 04 §Test vectors | `TokenVectorsTest` green; it asserts the 6 Python interim tokens; a second run leaves `docs/token-vectors.json` unchanged (`git diff --exit-code`); 20 vectors incl. edge values | BE-9 | 1 |
| 4 ∥ | FL-2 | flutter | `packages/core/test`, `packages/core/tool` | Golden test: drop `skip`; a missing file fails with "run backend TokenVectorsTest". Delete the interim group and `tool/interim_vectors.py` (their values now live in BE-8). | `dart test` green with the file; renaming the file → red | BE-8 | 0.25 |
| 5 | BE-12 | backend | `backend/…/tickets` | `GET /fan/tickets?with_keys` (holder only, `server_time`, `no-store`). K_t computed inside `EventService.withEventKey`, once per (organizer, event); the key never leaves the lambda except as derived K_t | Tests: the buyer doesn't see the friend's ticket; no `k_t` without `with_keys` or when not VALID / event CLOSED; `k_t` = HMAC(K_e, id) (a v2 token built with it verifies against K_e via `TicketToken`); two organizers' tickets each use their own key; a JSON scan finds no `k_e`/`event_key`; `ticket_no` format | BE-8 | 1.5 |
| 5 ∥ | FL-7 | flutter | `apps/fan/lib/features/tickets` | Billets tab + screen 5: rotating v2 QR (`qr_flutter`, ECC M) from cached `k_t` at `slotAt(now + server_offset)`, progress bar, pager, matchday mode (auto-open from T0 − 4 h, brightness max), offline cache in secure storage, "Mon billet" strip on screen 1, WhatsApp re-send button (after BE-14) | Tests: QR string = `encodeRotating(k_t, …, slot)` from core; with a client that throws on any call the ticket still renders and rotates; offset applied; auto-open boundary at T0 − 4 h; `with_keys=true` sent | BE-12 | 3 |
| 6 | BE-13 | backend | `backend/…/tickets` (PDF adapter), `build.gradle.kts` (PDFBox, ZXing core, latest stable) | `GET /fan/tickets/{id}/pdf` per 04 | Tests: the PDF parses and contains title, zone, `ticket_no`, `holder_label`; the QR image decodes (ZXing) to a v1 token that verifies with K_e and the right event/ticket/zone; non-holder → 404; BLOCKED → 409 `TICKET_NOT_VALID`; `no-store` | BE-12 | 2 |
| 6 ∥ | WS-4 | web-shop | `web/shop/app/me`, `app/tickets`, `app/api/tickets` | W5 (client, via `/api/tickets` which calls `/fan/tickets` **without** `with_keys` and maps to a type without `k_t`), W6 PDF proxy (streamed, `no-store`), "Ouvrir dans l'app" deep link | Tests: `/api/tickets` output has no `k_t` even when the upstream fixture contains one; PDF proxy keeps 404/409; `no-store` header | BE-12, BE-13, WS-3 | 1.5 |
| 7 | BE-14 | backend | `backend/…/notifications` (new module), `…/sales` (enqueue in settle), V9 | `TicketMessenger` port + log adapter (dev/staging; prod refuses to start without a real one); outbox rows in the settle transaction; job (every 10 s, `for update skip locked`, batch 50, backoff 1 m / 5 m / 30 m / 2 h, `FAILED` after 5); `POST /fan/tickets/{id}/whatsapp` | Tests: PAID → one row per ticket in the same transaction (rollback → none); job sends each once and marks `SENT`; provider error → backoff, `FAILED` after 5; two parallel job runs never send one row twice; 4th re-send → 429; the message carries a PDF whose QR decodes; prod context without an adapter fails | BE-13 | 2 |
| 7 ∥ | FL-8 | flutter | `apps/fan/lib/features/profile` | Profile: name (PATCH `/fan/me`), language (FR only active), logout (+ "tous mes appareils") wiping the token and ticket cache | Tests: logout calls the API and wipes secure storage even when offline | BE-9, FL-7 | 1 |
| 8 | BE-16 | backend | `backend/…/sales` (read model), `…/catalog` | `GET /org/events/{id}/sales` | Tests: 1 PAID + 1 RESERVED + 1 EXPIRED → paid/held/revenue; by channel; revenue from snapshots; refunds from `audit_log`; FINANCE 200, GATE 403, another org → 404 | BE-14 | 1 |
| 8 ∥ | FL-9 *(optional)* | flutter | `apps/fan/lib/features/checkout/map` | Stadium SVG for the placeholder club keyed by `zones.code`; list fallback | Tests: unknown codes → list; disabled zones not tappable | FL-6 | 2 |
| 9 | BO-7 | backoffice | `web/backoffice/src/features/sales` | B3 Ventes (KPIs, per zone, per channel, Remboursements à faire); sidebar "Ventes" enabled | Tests: centimes formatting; FINANCE can open the page; empty refunds state | BE-16 | 1.5 |
| 9 | BE-15 | backend | `backend/…/tenancy/adapters/otp`, `…/notifications/adapters/<provider>` | Real `OtpSender` + `TicketMessenger` adapters, credentials from env, provider error codes → `last_error` | Unit tests against the provider's documented request/response samples (JDK `HttpServer` stub, no new dependency); one manual OTP and one ticket PDF to a real phone on staging | **Q2 decision + credentials** | 2 |
| 10 | BE-7 | backend | `backend/…/payments/adapters/payzone` | As in the Lot 0 plan | As in the Lot 0 plan; `Lot1SaleFlowTest` reruns unchanged on the simulated gateway | **Payzone kit** | 2 (re-estimate) |
| exit | — | orchestrator + founder | — | Demo per §1 | `Lot1SaleFlowTest` + all client checks green in CI; manual demo web + app | all above except FL-9 | — |

`reviewer` runs after every builder task.

### Lanes and critical path (working days, before review loops)

| Agent | Sequence | Ends |
| --- | --- | --- |
| backend (critical) | BE-10 0–2 → BE-11 2–4.5 → BE-9 4.5–6.5 → BE-8 6.5–7.5 → BE-12 7.5–9 → BE-13 9–11 → BE-14 11–13 → BE-16 13–14 → BE-15 14–16 → BE-7 16–18 | d14 core, d18 with both third-party tasks |
| flutter | FL-3 0–2 → FL-4 2–3 → FL-5 3–5 → FL-6 5–8 → FL-2 8 → FL-7 9–12 → FL-8 12–13 → FL-9 13–15 | d13 (d15 with the map) |
| web-shop | WS-1 0–2 → WS-2 2–5 → WS-3 5–7.5 → (idle) → WS-4 11–12.5 | d12.5 |
| backoffice | BO-6 2–3 → BO-7 14–15.5 | d15.5 |

### Budget check (4 weeks = 20 working days)

| Agent | Sum | ×1.1 review | Fits? |
| --- | --- | --- | --- |
| backend | 14 core (+2 BE-15, +2 BE-7) | 15.4 (19.8 with both) | Yes, with no slack once both third-party tasks land. **If infra (Q24) goes to backend: +3 d → 4.5 weeks.** |
| flutter | 12.25 (+2 FL-9) | 13.5 (15.7) | Yes |
| web-shop | 9 | 9.9 | Yes, about 10 d of slack |
| backoffice | 2.5 | 2.75 | Mostly idle; free for Lot 2 B6 Appareils prep |

Verdict: 4 weeks holds for the builders. "Live on a real fixture" depends on the Payzone kit, the provider choice, infra and store accounts, not on build time.

If late, cut in this order: (1) FL-9 map; (2) `POST /fan/tickets/{id}/whatsapp` re-send and its buttons (the automatic send stays); (3) `refunds_required` in `/sales` and B3; (4) BO-6 Règles read-only row.

Never cut: K_t only to the holder behind `with_keys` and absent from the shop; `fan_sessions` revocation; OTP per audience and the caps; the vectors golden test on both sides; the sale close at T0 − 60; the idempotent outbox claim.

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| K_t / K_e | K_t is computed only inside `withEventKey`; no K_e reference escapes the lambda; `k_t` only for `holder_user_id = sub`, only with `with_keys=true`, only for VALID tickets of a non-CLOSED event. No key in logs, `toString`, audit payloads or errors. `/fan/events*` and `/fan/tickets` use controller-local DTOs. `no-store` on tickets and PDF. Web shop: `/api/tickets` maps to a type without `k_t`, never sends `with_keys`, no key in any SSR HTML or client props; the PDF proxy streams bytes only. |
| QR / PII | The token holds only version, event_id, ticket_id, zone_id, [slot], tag. No name anywhere in a token. The PDF QR is v1 signed with K_e via the shared Java codec (vectors match Dart). `holder_name` never goes into a manifest-bound column; only `holder_initials`. |
| Holders | A friend's ticket is never readable by the buyer (tickets, PDF, re-send all 404). Holder user upsert can't be used to probe whether a phone exists (same response). No phone stored in `order_items.holders` or `ticket_deliveries`. Name regex is applied on the server, not only in clients. |
| Auth | Per-audience OTP counting (P7), daily caps, the 15-failure lock, per-IP from a trusted-proxy-resolved address only. Codes sent after commit, never inside the request transaction. Org IP throttle stays silent (202). `sid` checked on every `/fan` call. Logout revokes. Allowlist of tenant-exempt tables is exactly 4. |
| On-sale | Window checks use the DB clock (`now()`). Sales close at `refund_cutoff_at`. `SALE_BUSY` is evaluated before quota and limit and never takes a lock. Clients retry with jitter and show no fake position. |
| Delivery | Outbox rows are written in the settle transaction. Claim uses `for update skip locked`; send happens outside the claim transaction; at-least-once is accepted and commented `ponytail:`. Backoff and `FAILED` cap. The log adapter is impossible under prod. Provider secrets from env. |
| Payments | `/return` reads only and redirects to config URLs (no query-supplied target). Late success (Q8) issues to the stored holders. Lot 0 callback guarantees unchanged. |
| Money | Centimes end to end; shop and app format with integer maths; `/sales` revenue from `unit_price_mad` snapshots. |
| Contract | Every new or changed row in 04 has a contract test; no endpoint outside 04; error codes and `details.reason` exactly as 04; Flyway names match 03. |
| Clients | No hard-coded strings (FR via l10n/dictionary); fans addressed with "tu"; dates through the venue's IANA zone, never a fixed offset; the ticket screen makes no network call; touch targets ≥ 44 px; shop route handlers check Origin and set httpOnly cookies. |

## 7. Carried forward

| Item | Lot | Note |
| --- | --- | --- |
| `GATE_IN_USE` on gate delete | 2 | Unchanged from Lot 0 |
| Edge forwards only `/v1/*` | 6 | Unchanged |
| Duplicate `provider_ref` on a signed callback | BE-7 | Unchanged |
| OTP abuse limits (Q14) | 1 | BE-9, plus per-audience counting (P7) |
| K_e master key from Secrets Manager / KMS adapter | 6 (env from Secrets Manager at first prod deploy, Q24) | The `KeyEncryptor` port exists |
| App-only tickets (no static PDF) per event | 2 | Q17, a manifest flag if the club asks |
| Transfer = cancel + new ticket | 4 | P10 |
| Arabic-script holder names + AR PDF shaping | 4 | Q16 |
| Ranked virtual queue, verified fans, holder caps | 5 | Q18, Q19 |
| `otp_challenges` / `ticket_deliveries` retention purge | 6 | IP addresses and delivery rows are personal data (CNDP) |
| Lost-phone re-issue | 4 | Residual risk of Q13 |
| Client IP trust chain (WS-1 review) | BE-9 + Q24 | The shop forwards exactly one `X-Forwarded-For` value (the entry `TRUSTED_PROXY_HOPS` from the right, default 1 = ALB) or none. Backend: `server.forward-headers-strategy=native`, `server.tomcat.remoteip.remote-ip-header=X-Forwarded-For`, `server.tomcat.remoteip.internal-proxies` set **explicitly** to the shop and ALB private subnets (never Spring's default private-range regex). Infra: shop and API targets accept traffic only from the ALB security group; ALB XFF mode `append`. BE-9 test adds: a shop-shaped peer forwarding one entry is bucketed by that entry. |
| Phone input strips U+202A–U+202E | BO-6 | The back-office phone normaliser misses these bidi controls (WS-1 review); fix with BO-6. |
