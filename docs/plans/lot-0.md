# Lot 0 · Foundations: implementation plan

As of 2026-11-06 · owner: tech-lead · status: **proposed**. The contract changes in §4 need founder approval before any builder starts.

## 1. Lot

**Lot 0 · Foundations.** No earlier lot exists. The repo has no code and no git.

Outside dependencies:

| Need | From | Blocks |
| --- | --- | --- |
| Payzone merchant kit and sandbox credentials (decision 2026-11-06: Payzone, not CMI) | Founder → Payzone | BE-7 only, which sits **outside Lot 0** until the kit arrives. Lot 0 runs entirely on the simulated gateway. |
| A public HTTPS URL for Payzone sandbox callbacks | Orchestrator (cloudflared or ngrok tunnel) or AWS staging, whichever exists when the kit lands | BE-7 sandbox run |
| Docker on dev machines (Testcontainers), JDK 25, Node 22.12+ | Dev machines | All tasks |

**How the exit criterion "Admin creates an event and sells a test ticket by card" is met without Lot 1:**

| Half | Client | Proof |
| --- | --- | --- |
| Admin creates an event | **Back-office (real UI)**: OTP login → B1 → B2 (Général, Tarifs, Publier) | Manual demo + BO tests |
| Sell a test ticket by card | **No fan UI.** API calls from `backend/http/lot0-sale.http` (IntelliJ HTTP client): OTP → `POST /fan/orders` → `POST /pay` → open `redirect_url` in a browser → click "Approuver" on the simulated gateway page → `GET /fan/orders/{id}` = `PAID` | `Lot0SaleFlowTest` (Testcontainers + simulated gateway) in CI, plus the manual demo. A real card sale on the Payzone sandbox follows when the kit arrives (BE-7). |
| Admin sees the sale | B2 Tarifs tab shows `sold = 1` (from `GET /org/events/{id}/prices`) | Manual demo |

Why this way:
- The fan app and the web shop are Lot 1. A throwaway buyer UI would cost 3–5 days and would then be discarded.
- The API is exactly what both Lot 1 clients will call, so proving the sale at API level proves what Lot 1 builds on.
- The simulated gateway goes through the same redirect → hosted page → callback → `PAID` path a real gateway will, so swapping in Payzone touches one adapter only. A browser plus a `.http` script is enough to drive it.
- Rejected: a back-office "test sale" button. It would be an organizer endpoint that fakes fan sales.

## 2. Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Stack, Repository layout, Non-negotiables, Conventions, Agent team |
| `docs/00-product.md` | §V1 in lots (Lot 0 row), §Open questions |
| `docs/01-architecture.md` | §Components, §Backend modules, §Key flows 1 (Purchase) |
| `docs/02-gate-validation-and-data-model.md` | §Ticket token & QR format: Keys ("K_e … generated server-side when the event is created"); §Data model; §Threat model (refund freeze T0 − 60 min) |
| `docs/03-schema.sql` | tenancy, catalog, sales; `audit_log` |
| `docs/04-api.md` | header (auth schemes, error shape), §Fan (auth, orders, pay, webhook), §Organizer (catalog, events, prices, publish) |
| `docs/06-glossary.md` | Order statuses |
| `design/DESIGN.md` | §Back-office (sidebar, B1, B2 details), §Theming per organizer |
| `design/mockups/html/b2-backoffice-evenement.html` | B2 layout, Règles card, gate → zone matrix |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default for Lot 0 |
| --- | --- | --- |
| Q1 | **Decided:** card gateway is Payzone; keep the simulated gateway until the kit arrives (confirmed 2026-11-06) | Simulated gateway behind the `CardGateway` port for Lot 0. Payzone adapter (BE-7) once the kit arrives. |
| Q2 | SMS/WhatsApp OTP provider | `OtpSender` port with a log adapter (dev/staging only; prod refuses to start with it). Choose the provider before Lot 1. |
| Q3 | `users` is global (one fan account across organizers). This breaks the letter of "every table carries `organizer_id`". | Global, written up as an explicit exception: `organizers`, `users`, `otp_challenges`. Organizer ownership of fans comes through `orders`/`tickets`. |
| Q4 | Back-office login method | Phone OTP, the same machinery as fans. A staff row links to a `users` row. TOTP/2FA for ADMIN in Lot 6. |
| Q5 | B2 says the gate → zone matrix is "modifiable par événement", but `gate_zones` is per venue | Lot 0: venue level only. B2 shows the matrix read-only. A per-event override (`event_gate_zones`) goes to Lot 2, where scanners use `allowed_zone_ids`. |
| Q6 | Refund cutoff offset | Fixed at T0 − 60 min (spec). No per-organizer setting. |
| Q7 | Time zone | **Decided 2026-11-06 (founder: expansion is an option):** per-venue IANA `venues.timezone`, default `Africa/Casablanca`. API stays UTC; events expose their venue's `timezone`; clients convert through the IANA zone, never a fixed offset. Changing a venue's zone is refused once events reference it (`VENUE_IN_USE`). Migration V5. |
| Q8 | Card success arrives after the 8 min hold expired | Expiry skips orders with a `PENDING` payment younger than 20 min. A later success re-reserves the quota atomically → `PAID`. If no quota is left, the order stays `EXPIRED`, the payment is `SUCCEEDED`, an `audit_log` row `payment.refund_required` is written, and the refund is manual in the gateway's merchant back-office. |
| Q9 | Re-entry policy (product open question) | `NONE` (schema default), editable in B2 Règles. |
| Q10 | Stadium plan (which gates serve which tribunes) | Placeholder from the mockup in the dev seed (ATLAS FC; zones NORD/EST/HONNEUR/SUD; gates P1–P7). Real data is entered at onboarding. |
| Q11 | Event series in Lot 0? | No. `series_id` stays nullable and the B2 "Série" field is hidden until Lot 5 (passes). |
| Q12 | First organizer and admin in staging/prod | A dev seed (dev profile only) plus a documented SQL snippet. No platform-admin API until Lot 6. |
| Q13 | JWT revocation and refresh | Lot 0: one HS256 access token (fan 30 d, org 12 h), no refresh. Revisit in Lot 1 before real K_t reaches phones. |
| Q14 | OTP abuse limits beyond 3 sends / 5 attempts per 10 min (BE-2 review): 15 guesses per 10 min on a staff phone is ~6 %/month for a targeted account; no per-IP cap means SMS pumping once a real provider is live | **Decided 2026-11-06: defaults accepted, implement in Lot 1 with the real `OtpSender`.** per-phone daily cap (10 sends, 15 failed attempts / 24 h → lockout), per-IP throttle on both `/*/auth/otp`, send after commit off the request thread. Log adapter only in Lot 0. Also covers a silent lockout: `/org/auth/otp` creates a challenge for any phone (needed so it can't reveal staff), so 3 calls / 10 min lock a fan out without any SMS; the per-IP throttle is the fix. |

## 4. Contract changes

### 4.1 Problems found in the current docs

| # | Where | Problem | Rule | Fix |
| --- | --- | --- | --- | --- |
| V1 | `03-schema.sql:46` `zones` | No `organizer_id` | Every table carries `organizer_id` | Add it (§4.2) |
| V2 | `:56` `gates` | No `organizer_id` | same | Add it |
| V3 | `:64` `gate_zones` | No `organizer_id` | same | Add it |
| V4 | `:101` `price_categories` | No `organizer_id` | same | Add it |
| V5 | `ticket_transfers`, `device_assignments`, `manifests`, `scans` | No `organizer_id` (Lot 2/4 tables) | same | Add the column now in the doc (§4.2 last block). Migrate in their own lots. |
| V6 | `:18` `users` | No `organizer_id` | same | Documented exception (Q3) |
| V7 | `:28` `staff` | No link to a login identity, so the back-office has no way to log in. `04-api.md` says "staff account" with no endpoint. | 04 header | `staff.user_id` + `/org/auth/*` |
| V8 | `04-api.md` pay row | `{redirect_url}` ("CMI 3DS") assumes a GET redirect to a known gateway. Hosted pages often need a POSTed form, and Payzone's mechanism is not known yet. | — | `redirect_url` points to an API page that either redirects or auto-submits a form, whichever the adapter returns. The response shape is unchanged. |
| V9 | `03` `orders` | No line items: a `RESERVED` order cannot say what it holds, so tickets cannot be issued on `PAID` | — | Add `order_items` with a unit price snapshot |
| V10 | `03` `price_categories.sold` | Meaning unclear when orders are only held | — | Define it as held + paid, released on expiry |
| V11 | `04` `POST /fan/orders` | `orders.channel` is `NOT NULL` but the request has no channel. `zone_id` duplicates `price_category_id`. | — | Add `channel`, drop `zone_id` |
| V12 | `04` | No error-code list, no payloads for catalog, events or prices | Conventions (contract tests) | §4.3 |
| — | match_id / football names | None in tables or columns. Football data is only in the `metadata` comment. | OK | — |
| — | floats for money | None. All `*_mad` are bigint. Gateway amounts may be decimal strings, so any conversion uses integer maths (Review focus). | OK | — |

Drift to fix later, not blocking Lot 0:
- `02` §Data model says `devices.status` is (ACTIVE, REVOKED) but 03 also has PENDING.
- `02` puts `reentry_allowed` per manifest row; `04` sends `reentry_policy` per manifest.
- `02` paths `/events/{id}/manifest` and `/events/{id}/scans` lack the `/gate` prefix used in `04`.
- `04` `scans` payload has no `gate_id`/`staff_id`, though 03 needs them (derive them from the assignment and the session).
- `02:32` links `docs/architecture.md` instead of `01-architecture.md`.
- The `02` §Data model table should get the new columns in the same docs change (03 stays normative for names).

### 4.2 `docs/03-schema.sql`: replacements and additions

```sql
-- Tenant rule: every table carries organizer_id, except platform identity tables
-- organizers, users, otp_challenges (one fan account across organizers).
-- audit_log.organizer_id is nullable for SYSTEM actions.
-- Every query on a tenant table filters by organizer_id (no composite FKs: app scoping
-- is needed anyway to return 404 on foreign ids; tested per endpoint).

-- ---------- tenancy (replace staff, add otp_challenges) ----------
create table staff (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  user_id       uuid references users(id),            -- back-office staff log in by phone OTP; null = PIN-only gate staff
  name          text not null,
  role          staff_role not null,
  pin_hash      text,                                 -- scanner PIN (Lot 2)
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (organizer_id, user_id)
);

create table otp_challenges (
  id            uuid primary key default gen_random_uuid(),
  phone_e164    text not null,
  code_hash     text not null,                        -- HMAC-SHA256(server secret, code); the code is never stored
  attempts      smallint not null default 0 check (attempts <= 5),
  expires_at    timestamptz not null,                 -- created_at + 5 min
  consumed_at   timestamptz,
  created_at    timestamptz not null default now()
);
create index on otp_challenges (phone_e164, created_at desc);   -- rate limit: max 3 per phone per 10 min

-- organizers.settings shape (no DDL): {"theme": {"accent": "#F5B700", "on_accent": "#0B0F1A",
--   "crest_url": "...", "display_font": null}}

-- ---------- catalog (replace zones, gates, gate_zones, price_categories) ----------
create table zones (
  id            smallserial primary key,              -- 2-byte zone_id in the QR; global sequence, ceiling 32,767 zones platform-wide
  organizer_id  uuid not null references organizers(id),
  venue_id      uuid not null references venues(id),
  code          text not null,                        -- 'NORD', 'EST'
  name          text not null,
  capacity      integer not null check (capacity >= 0),
  seated        boolean not null default false,
  unique (venue_id, code)
);

create table gates (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  venue_id      uuid not null references venues(id),
  code          text not null,                        -- 'P4'
  name          text not null,
  unique (venue_id, code)
);

create table gate_zones (
  organizer_id  uuid not null references organizers(id),
  gate_id       uuid not null references gates(id),
  zone_id       smallint not null references zones(id),
  primary key (gate_id, zone_id)
);

-- events: unchanged. metadata keys per event_type are listed in docs/04-api.md §Event metadata.

create table price_categories (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  zone_id       smallint not null references zones(id),   -- must belong to the event's venue (checked in the service)
  name          text not null,                        -- 'Plein tarif', 'Abonné'
  price_mad     bigint not null check (price_mad >= 0),  -- centimes
  quota         integer not null check (quota >= 0),
  sold          integer not null default 0 check (sold >= 0 and sold <= quota),
                -- held (RESERVED, AWAITING_CASH) + PAID; incremented atomically on reserve,
                -- decremented when an order expires or is cancelled
  unique (event_id, zone_id, name)
);

-- ---------- sales (add after orders) ----------
create table order_items (
  id                uuid primary key default gen_random_uuid(),
  organizer_id      uuid not null references organizers(id),
  order_id          uuid not null references orders(id),
  price_category_id uuid not null references price_categories(id),
  qty               smallint not null check (qty > 0),
  unit_price_mad    bigint not null check (unit_price_mad >= 0),  -- snapshot at reservation
  unique (order_id, price_category_id)
);
-- orders.payment_ref: provider_ref of the succeeding payment, or the cash code (Lot 3).

-- ---------- payments ----------
create type payment_status as enum ('PENDING','SUCCEEDED','FAILED');

create table payments (
  id            uuid primary key default gen_random_uuid(),   -- sent to the gateway as its order reference
  organizer_id  uuid not null references organizers(id),
  order_id      uuid not null references orders(id),
  method        payment_method not null,
  provider      text not null,                        -- 'FAKE' (Lot 0), 'PAYZONE'
  amount_mad    bigint not null check (amount_mad >= 0),
  status        payment_status not null default 'PENDING',
  provider_ref  text,                                 -- gateway transaction id from the callback
  callback      jsonb,                                -- last verified callback, card fields stripped
  created_at    timestamptz not null default now(),
  completed_at  timestamptz
);
create index on payments (order_id);
create unique index on payments (provider, provider_ref) where provider_ref is not null;

-- ---------- later lots: add to the doc now, migrate in their lot ----------
-- ticket_transfers   + organizer_id uuid not null references organizers(id)   (Lot 4)
-- device_assignments + organizer_id uuid not null references organizers(id)   (Lot 2)
-- manifests          + organizer_id uuid not null references organizers(id)   (Lot 2)
-- scans              + organizer_id uuid not null references organizers(id)   (Lot 2)
```

Flyway split for Lot 0:
- `V1__tenancy.sql`: organizers, users, staff, otp_challenges, audit_log
- `V2__catalog.sql`: venues, zones, gates, gate_zones, event_series, events, price_categories
- `V3__sales.sql`: orders, order_items, tickets
- `V4__payments.sql`: payments

Lot 0 migrates nothing else: no passes, devices, manifests, scans or settlements.

### 4.3 `docs/04-api.md`: additions and replacements

**Header, add:**

Errors used in Lot 0:

| Code | HTTP | When |
| --- | --- | --- |
| `VALIDATION_FAILED` | 422 | Bad body; `details.fields{}` |
| `UNAUTHORIZED` | 401 | Missing, invalid or wrong-audience token |
| `FORBIDDEN` | 403 | No active staff row for `X-Organizer-Id`, or role not allowed |
| `NOT_FOUND` | 404 | Unknown id, or an id of another organizer or user |
| `OTP_INVALID` / `OTP_EXPIRED` | 401 | Verify failed |
| `OTP_RATE_LIMITED` | 429 | > 3 sends per phone per 10 min |
| `EVENT_NOT_ON_SALE` | 409 | Order on an event that is not `ON_SALE` |
| `EVENT_NOT_EDITABLE` | 409 | Edit after `ON_SALE` (except allowed fields) or when `CLOSED` |
| `SOLD_OUT` | 409 | `details.price_category_id` |
| `PER_ACCOUNT_LIMIT` | 409 | `details.limit`, `details.held` |
| `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE` | 409 | Pay on a non-`RESERVED` order |
| `CODE_TAKEN` | 409 | Duplicate zone or gate code in a venue |
| `ZONE_IN_USE` / `GATE_IN_USE` / `PRICE_IN_USE` | 409 | Delete of a referenced row |
| `QUOTA_EXCEEDS_CAPACITY` / `QUOTA_BELOW_SOLD` | 422 | Price category quota rules |

No pagination in Lot 0. Lists are per organizer and small.

**§Fan, replace these rows:**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/fan/auth/otp` | `{phone}` (E.164) → `202`. 6-digit code, valid 5 min, 5 attempts. Max 3 sends per phone per 10 min (`OTP_RATE_LIMITED`). |
| POST | `/fan/auth/verify` | `{phone, code}` → `{token, expires_at, user:{id, phone, display_name, locale}}`. Creates the user on first login. JWT `aud=fan`, 30 days. |
| POST | `/fan/orders` | `{event_id, channel: APP\|WEB, items:[{price_category_id, qty}]}` → `201 {id, status: RESERVED, reserved_until, total_mad, items:[{price_category_id, zone_id, name, qty, unit_price_mad}]}`. Quota taken atomically. Σ qty over the user's `RESERVED`/`AWAITING_CASH`/`PAID` orders for the event ≤ `per_account_limit`. `holders[]` is added in Lot 1. |
| POST | `/fan/orders/{id}/pay` | Lot 0: `{method: CARD}` → `{payment_id, redirect_url}`. `redirect_url` is a browser page on this API that auto-submits to the card gateway. Each call creates a new payment attempt. CASH_AGENCY and WALLET come in Lot 3. |
| GET | `/fan/orders/{id}` | `{id, event_id, status, total_mad, reserved_until, paid_at, items[], ticket_ids[]}`. Owner only (404 otherwise). |

Deferred to Lot 1: `GET /fan/events`, `GET /fan/events/{id}`, `/fan/tickets*`. The Lot 0 demo reads price ids from `/org/events/{id}/prices`.

**§Payments (replaces the "Webhook in" line). Gateway-facing, no audience token:**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/payments/card/{payment_id}/redirect` | Sends the browser to the gateway: a 302 or an auto-submitting form, as the adapter decides. In Lot 0 this is the simulated gateway page (non-prod only). `410` if the payment is not `PENDING`. Never carries a merchant secret. |
| POST | `/payments/{provider}/callback` | Server to server. Lot 0: `provider = fake`, called by the simulated page. Later: `payzone`. The signature is verified before any state change. Idempotent on `payment_id`. On approval, in one transaction: payment `SUCCEEDED` → order `PAID` → tickets issued (one per unit, holder = buyer). The request format and the acknowledgement body come from the adapter. |
| GET, POST | `/payments/card/{payment_id}/return` | Browser landing (okUrl/failUrl). **Never changes state.** Lot 0: plain "paiement en cours de confirmation" page. Lot 1: redirects to a fixed client URL per `orders.channel` (no caller-supplied URL). |
| GET | `/payments/fake/{payment_id}` | Simulated gateway page: amount, "Approuver" / "Refuser". Each button posts a signed callback to `/payments/fake/callback`, then lands on `/return`. It also supports a delayed callback, to test Q8. Bean exists only in `dev`/`test`/`staging` profiles. Startup fails if `payments.card.provider=fake` under `prod`. |
| POST | `/payments/cash/callback` | Unchanged, Lot 3 |

**§Organizer, add before the table:** every `/org/*` call except `/org/auth/*` and `/org/me` needs `X-Organizer-Id`. The server checks for an active `staff` row (user_id = token subject). Lot 0 roles: `ADMIN`, `OPS` read and write; `FINANCE` read only; `GATE`, `SUPERVISOR` get 403.

**§Organizer, replace the catalog/events rows:**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/auth/otp` | `{phone}` → always `202` (no staff enumeration). The code is sent only if the phone belongs to a user with an active staff row. |
| POST | `/org/auth/verify` | `{phone, code}` → `{token, expires_at, memberships:[{organizer_id, organizer_name, role}]}`. JWT `aud=org`, 12 h. |
| GET | `/org/me` | `{user, memberships[]}`, no `X-Organizer-Id` (feeds the organizer switcher) |
| GET, POST | `/org/venues` | `{name, city?, capacity}` → `{id, name, city, capacity}` |
| GET, PATCH | `/org/venues/{id}` | GET returns `zones[]` and `gates[]` (each gate with `zone_ids[]`) |
| POST | `/org/venues/{id}/zones` | `{code, name, capacity, seated?}` → `{id, code, name, capacity, seated}` (`id` is an int) |
| PATCH, DELETE | `/org/venues/{id}/zones/{zone_id}` | DELETE only if unreferenced (`ZONE_IN_USE`) |
| POST | `/org/venues/{id}/gates` | `{code, name}` → `{id, code, name, zone_ids:[]}` |
| PATCH, DELETE | `/org/venues/{id}/gates/{gate_id}` | DELETE only if unreferenced (`GATE_IN_USE`) |
| PUT | `/org/gates/{id}/zones` | `{zone_ids:[...]}` replaces the set. Zones must belong to the gate's venue. |
| GET | `/org/events?status=&from=` | `[{id, title, event_type, starts_at, status, venue:{id, name}, quota, sold}]` by `starts_at` |
| POST | `/org/events` | `{venue_id, event_type, title, starts_at, doors_open_at?, reentry_policy?, per_account_limit?, metadata?}` → `201` event in `DRAFT`. **K_e (32 random bytes) is generated and encrypted in the same transaction and never returned.** |
| GET, PATCH | `/org/events/{id}` | Event `{id, venue_id, series_id, event_type, title, starts_at, doors_open_at, status, refund_cutoff_at, reentry_policy, per_account_limit, metadata, created_at}`. PATCH allowed in `DRAFT`/`ON_SALE`. `venue_id` and `event_type` are fixed after create. Changing `starts_at` while `ON_SALE` recomputes `refund_cutoff_at`. |
| GET, POST | `/org/events/{id}/prices` | `{zone_id, name, price_mad, quota}` → `{id, zone_id, name, price_mad, quota, sold}`. The zone must be in the event's venue; Σ quota per zone ≤ zone capacity. |
| PATCH, DELETE | `/org/events/{id}/prices/{price_id}` | `quota ≥ sold`. A price change applies to new orders only (snapshot in `order_items`). DELETE only if `sold = 0` (`PRICE_IN_USE`). |
| POST | `/org/events/{id}/publish` | `DRAFT` → `ON_SALE`. Needs ≥ 1 category with quota > 0 and `starts_at > now`. Sets `refund_cutoff_at = starts_at − 60 min`. Writes `audit_log` `event.publish`. |

No `DELETE /org/events` in Lot 0. Cancelling an event is a refund flow and belongs to a later lot.

**New §Event metadata:**

| `event_type` | Keys (sent inside `metadata`, never as top-level fields) |
| --- | --- |
| `FOOTBALL_MATCH` | `opponent` string, `competition` string, `matchday` int |
| `CONCERT`, `FESTIVAL_DAY` | `headliner` string |
| others | free |

The server requires a JSON object of at most 4 KB, type-checks the known keys and keeps unknown keys. The back-office validates per type with zod.

### 4.4 `design/DESIGN.md` §Back-office, add

| B10 | Lieux | — | `/settings/venues/:id` (zones table, gates table, gate → zone checkbox matrix) | 0 |

In Lot 0, B2 shows three tabs: Général, Tarifs & quotas, and Portes & zones (the venue's matrix, read-only).

The Règles card shows only three rules in Lot 0:
- ré-entrée
- billets max par compte
- gel des remboursements (read-only "T0 − 60 min")

Hidden until their lot:

| Item | Lot |
| --- | --- |
| nominatifs | 1 |
| file d'attente | 1/5 |
| Appareils tab | 2 |
| cash en agence | 3 |
| Série | 5 |

### 4.5 Card gateway: simulated now, Payzone later

Decision (2026-11-06): the card gateway is **Payzone**, not CMI. Until Payzone provides the kit, Lot 0 uses a simulated gateway.

| Item | Status |
| --- | --- |
| Card payment with 3DS through a hosted page, `redirect_url`, server callback idempotent on the payment ref | **Known**: these are our own contracts |
| Payzone's flow (redirect or form POST, hosted page or API), field names, signature scheme, callback acknowledgement, sandbox URLs and test cards, refund API, settlement file | **Unknown until the kit arrives. Do not code from guesses or public blog posts.** |

The simulated gateway is a real adapter, not a mock:
- It signs its callback with an HMAC secret from env.
- The callback goes through the same verify → lock → idempotent path.
- It can approve, decline, or send a late callback.

So the Payzone switch is BE-7 only: one adapter plus config. No change to orders, tickets or clients.

The port lives in `payments`. It has two adapters (simulated and Payzone), which is why an interface is worth having here:

```java
interface CardGateway {
  Checkout checkout(PaymentRef p);                       // redirect URL, or URL + POST fields; rendered by /payments/card/{id}/redirect
  CallbackVerdict verify(HttpCallback request);          // signatureOk, paymentId, approved, providerRef, amountMad (long)
  ResponseEntity<String> acknowledge(CallbackVerdict v); // body/status the gateway expects back
}
```

## 5. Tasks

### Wave 0: orchestrator (0.5 d, before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/03`, `docs/04`, `design/DESIGN.md` | §4 applied after the founder agrees |
| W0-2 | orchestrator | repo root | `git init`, root `.gitignore`, branch `feat/lot-0-foundations` |
| W0-3 | founder | — | Payzone kit and sandbox credentials requested (longest lead time; nothing in Lot 0 waits on it) |

### Waves 1–7

The backend is the serial critical path: one folder, one agent per wave. Each back-office task runs one wave behind the API it uses.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-1 | backend | `backend/` (Gradle single project, `ma.jomhor`, Spring Boot 4, Flyway, `compose.yaml` PG18), `.github/workflows/ci.yml` | Scaffold; migrations V1–V4; error handler (04 error shape); only the module packages Lot 0 uses (tenancy, catalog, sales, payments, tickets); CI with two path-filtered jobs (`./gradlew test`; `web/backoffice`: `npm ci && npm run check`) | `MigrationsTest`: V1–V4 apply on Testcontainers PG18; an `information_schema` query finds `organizer_id` on every table except `organizers`, `users`, `otp_challenges`, `flyway_schema_history`; `sold > quota` is rejected by CHECK | W0 | 2 |
| 1 ∥ | BO-1 | backoffice | `web/backoffice/` | Vite + React 19 + TS + Tailwind + shadcn + TanStack Query + RHF/zod + react-router + i18n (FR); sidebar shell per DESIGN (Événements and Paramètres › Lieux active, others disabled); API client (`/v1` via Vite proxy, Bearer, `X-Organizer-Id`, error shape); `npm run check` = typecheck + lint + test + build | `npm run check` green; a unit test shows the client sets both headers and parses the 04 error shape | W0 | 1.5 |
| 1 ∥ | FL-1 *(optional)* | flutter | `packages/core/` | Pure-Dart token codec (v1/v2, K_t derivation, slot) against 04 §Token. Not on the Lot 0 critical path. | `dart test` round-trips the layout; golden test marked pending (`token-vectors.json` arrives in Lot 1) | — | 2 |
| 2 | BE-2 | backend | `backend/…/tenancy` | OTP (hashed, rate limit, 5 attempts) + `OtpSender` port with a log adapter (refused under prod); JWT HS256 with `aud` fan/org, secret from env; `/fan/auth/*`, `/org/auth/*`, `/org/me`; `OrgContext` filter (`X-Organizer-Id` → staff row and role); dev seed (ATLAS FC, admin with a test phone, stadium) in a dev-only Flyway location | Contract tests for the 5 endpoints. Tenant test: staff of org A with header B → 403; fan token on `/org` → 401; 4th send in 10 min → 429; 6th wrong code → challenge dead; `/org/auth/otp` for an unknown phone → 202 and nothing sent | BE-1 | 2.5 |
| 3 | BE-3 | backend | `backend/…/catalog` (venues) | Venue, zone and gate CRUD; `PUT /org/gates/{id}/zones` | Contract test per row; every lookup scoped by `organizer_id` (another org's id → 404); `CODE_TAKEN`; `ZONE_IN_USE`; a zone of another venue in gate-zones → 422 | BE-2 | 1.5 |
| 3 ∥ | BO-2 | backoffice | `web/backoffice/src/features/auth`, shell | Login (phone → code), token in sessionStorage, `/org/me`, organizer switcher, `organizerId` in every query key, 401 → login | Tests: switching organizer clears or rescopes the cache; 401 redirects to login. Manual login against local BE-2 | BE-2 | 1.5 |
| 4 | BE-4 | backend | `backend/…/catalog` (events, prices) | Event CRUD + K_e (SecureRandom 32 B, AES-256-GCM under a master key from env behind a `KeyEncryptor` port; KMS adapter later), metadata checks, prices CRUD + rules, publish, `audit_log` | Contract tests. `EventKeyTest`: a new row has a 60-byte `event_key_enc`, decrypts to 32 B, and no `/org/events*` response contains a key field (JSON scan). Publish preconditions; `refund_cutoff_at = starts_at − 60 min`; quota > capacity → 422 | BE-3 | 2.5 |
| 4 ∥ | BO-3 | backoffice | `web/backoffice/src/features/venues` | B10 Lieux: zones table, gates table, gate → zone matrix (PUT per gate). **First thing to cut if late** (the dev seed covers the demo). | Component tests for the matrix diff → PUT payload | BE-3 | 2 |
| 5 | BE-5 | backend | `backend/…/sales` | `POST /fan/orders` (atomic `update … set sold = sold + n where sold + n <= quota`, per-user lock for the account limit, `order_items` snapshot, `reserved_until = now + 8 min`), `GET /fan/orders/{id}`, expiry job (skips orders with a recent `PENDING` payment, releases `sold`) | Contract tests. Concurrency test: 20 parallel orders on quota 5 → exactly 5 `RESERVED`, the rest `SOLD_OUT`. Limit test. Expiry test returns `sold` to its previous value. Another user's order → 404 | BE-4 | 2 |
| 5 ∥ | BO-4 | backoffice | `web/backoffice/src/features/events` | B1 list (status badge, sold/quota) and B2 Général (type, title, T0, doors, venue, billets max, metadata sub-form per `event_type`), create and edit; Africa/Casablanca ↔ UTC | Tests: football metadata is sent inside `metadata`; T0 converts to UTC; zod rejects a missing title or venue | BE-4 | 2 |
| 6 | BE-6 | backend | `backend/…/payments`, `…/tickets` | `CardGateway` port + simulated adapter (page `/payments/fake/{id}`, HMAC-signed callback to `/payments/fake/callback`, approve/decline/delayed); `POST /fan/orders/{id}/pay` CARD; `/payments/card/{id}/redirect`, `/return`, `/payments/{provider}/callback`; callback service (verify → lock payment → idempotent → `PAID` + ticket issue in one transaction; late-success rule Q8); `backend/http/lot0-sale.http` | **`Lot0SaleFlowTest`** (exit proof): seed org → publish event → OTP → order → pay → fake approve → order `PAID`, 1 ticket `VALID`, `sold = 1`. The same callback twice → one ticket. Decline → order still `RESERVED`, payable again. Amount mismatch → `FAILED`. Bad signature → rejected, no state change. Simulated gateway absent under `prod` (context test) | BE-5 | 2.5 |
| 6 ∥ | BO-5 | backoffice | `web/backoffice/src/features/events` | B2 Tarifs & quotas (inline table, DH → centimes in integer maths, total line, `sold` shown), Portes & zones (read-only venue matrix), Règles (ré-entrée, billets max, cutoff shown), "Publier · ouvrir la vente" with error display | Tests: `"50,5"` DH → `5050`, `"50"` → `5000`, no float path (string parse); publish error codes show FR copy | BE-4 | 2 |
| exit | — | orchestrator + founder | — | Demo per §1 on the simulated gateway | `Lot0SaleFlowTest` green in CI + manual demo: B2 publish → `.http` sale → "Approuver" → `PAID` → B2 shows `sold = 1` | BE-6, BO-5 | — |

**After Lot 0, when the Payzone kit arrives** (not scheduled; slot it into the lot that is running):

| # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- |
| BE-7 | backend | `backend/…/payments/adapters/payzone` | Payzone adapter per the kit: checkout, callback verify + ack, amount formatting by integer maths; config switch `payments.card.provider=payzone`; sandbox run | Unit tests against the kit's sample request/callback (or recorded sandbox ones); `Lot0SaleFlowTest` reruns unchanged on the simulated gateway; one manual sale on the Payzone sandbox with a test card | Payzone kit + credentials | 2 (re-estimate from the kit) |

`reviewer` runs after every builder task. `web-shop` has no Lot 0 task.

### Budget check (15 working days)

| Agent | Sum | Fits? |
| --- | --- | --- |
| backend | 2 + 2.5 + 1.5 + 2.5 + 2 + 2.5 = **13 d** | Yes, with 2 d of slack, about what the review loops (~10%) will use. BE-7 (Payzone) is outside the lot. |
| backoffice | 1.5 + 1.5 + 2 + 2 + 2 = 9 d | Yes. About 6 d of slack, which is why B10 Lieux is included. |
| flutter | 0 (2 d optional) | Idle in Lot 0. FL-1 is a cheap pull-forward from Lot 1/2. |

Verdict: 3 weeks holds. Lot 0 no longer waits on any third party.

If late, cut in this order:
1. BO-3 is dropped; the venue comes from the seed or `.http`.
2. B1 `quota`/`sold` aggregates are dropped.

Never cut:
- tenant tests
- the K_e no-leak test
- callback idempotency

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Tenant isolation | Every repository query on a tenant table filters by `organizer_id` (`findByIdAndOrganizerId`). Another org's or another user's id → 404, never 200. `MigrationsTest` allowlist is exactly 3 tables. The back-office query keys include `organizerId`. |
| K_e | Responses use controller-local DTO records, never the `Event` domain record (it carries `event_key_enc`; one added field would leak K_e). Generated at create in the same transaction; AES-GCM with a random nonce; master key from env only; byte[] field excluded from `toString` and logging; absent from every DTO (test exists). |
| Money | `long` / `bigint` everywhere. Any gateway amount string built with `/ 100` and `% 100`, no `BigDecimal(double)` or `double`. Back-office DH parsing is string/integer only. `order_items.unit_price_mad` snapshot is used for totals. |
| Payments | Signature verified before any state change, including the simulated gateway's. Callback idempotent (payment row lock + status check, unique `(provider, provider_ref)`). Amount check. `PAID` and ticket issue in one transaction. Late-success rule (Q8). `/return` never mutates. No caller-supplied redirect URL. Simulated gateway impossible under `prod`. No card data stored in `payments.callback`. |
| Quota | The conditional `UPDATE` is the only way `sold` grows. Expiry releases exactly once. The concurrency test is real (parallel threads, not sequential calls). |
| Auth | OTP hashed, compared in constant time, rate-limited, never logged outside the dev adapter. JWT `aud` enforced per prefix. `/org/auth/otp` does not reveal whether a staff member exists. JWT and OTP secrets from env. |
| Naming | `event_id` only; football fields only inside `metadata`; Flyway names match `docs/03-schema.sql` exactly. |
| Contract | Every Lot 0 row in `docs/04-api.md` has a contract test. No endpoint exists that is not in the doc. |
| UI | No hard-coded strings (FR via i18n); shadcn components only; organizers addressed as "vous". |

## 7. Carried forward (from Lot 0 reviews)

| Item | Lot | Note |
| --- | --- | --- |
| `GATE_IN_USE` on gate delete | 2 | Lot 0 has no table referencing gates; `device_assignments` and `scans` must make delete return 409, not 500 (`ponytail:` note in `VenueService`). |
| Edge forwards only `/v1/*` | 6 | Paths outside the `/v1` context path get Tomcat's default 404, not the 04 error shape. The ALB must route `/v1/*` only. |
| BE-6 from the BE-5 review | 0 | (1) move the Q8 `payments` read out of sales SQL into a payments query; (2) `POST /fan/orders/{id}/pay` locks the order row `FOR UPDATE` before inserting the PENDING payment, or a payment committed after the expiry snapshot is missed and the order expires anyway; (3) the late-success re-reserve takes the buyer lock and re-checks `per_account_limit`. |
| Duplicate `provider_ref` on a signed callback | BE-7 | A correctly signed callback reusing another payment's `provider_ref` hits the unique index and returns 500 (nothing written). Real gateways' refs are unique; map it to a clean refusal + audit when the Payzone adapter lands. |
| OTP abuse limits | 1 | Q14 decided: per-phone daily cap (10 sends, 15 failed attempts / 24 h → lockout), per-IP throttle on both `/*/auth/otp`, send after commit off the request thread. |
