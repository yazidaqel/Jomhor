# Lot 3 · Moroccan payments: implementation plan

As of 2026-11-07 · owner: tech-lead · status: **approved 2026-11-07 under the founder's standing instruction**: every §3 default is taken (the founder can override any); §4 applied to the docs. Q51 (installments → Lot 5) also edits `docs/00-product.md`, flagged to the founder. Read on `feat/lot-2-gate` (worktree `C:\Users\aqely\IdeaProjects\jomhor-lot2`, migrations up to V12).

## 1. Lot

**Lot 3 · Moroccan payments.** Branch `feat/lot-3-payments` from `main` once the Lot 2 PR merges.

Scope (`00-product.md:53`): cash at agencies (reserve → pay code → QR unlocks), mobile wallet, POS thermal print, cash reconciliation, installments for passes. **Installments move to Lot 5 (Q51)** because `passes` are built in Lot 5 (`00-product.md:55`). Nothing in Lot 3 can attach an installment to them.

**Kafka: none in Lot 3.** Cash and wallet callbacks are synchronous request/response, and ticket delivery stays on the `ticket_deliveries` outbox. No new async consumer exists.

Exit ("every Moroccan fan can pay, online or offline"):

| Level | Proof |
| --- | --- |
| Demo-complete (Lot 3 done) | On staging, with the Lot 1 and Lot 2 flows. **(1) Cash code at a club guichet:** a fan on the web shop chooses "Cash en agence". W4 shows `JMH 4F7K 29`, the exact amount, a countdown (24 h, capped at sales close) and where to pay (the organizer's `cash_points`). A cashier logs into the back-office (role CASHIER), opens a till ("Guichet Nord"), types the code in lowercase with a typo-safe `o`, sees event, amount and holder labels, takes the cash and clicks "Encaisser". Within 10 s W4 switches to the tickets; the WhatsApp delivery row is `SENT` (log adapter). **(2) Walk-up sale:** the same cashier sells 2 places with no buyer account. One 80 mm slip per ticket prints from Chrome. After a manifest delta, the Lot 2 scanner admits that paper offline (OK). **(3) Simulated agency network:** a second fan's code is paid on the simulated agent terminal (`/payments/cash/fake`). The signed callback is replayed and has no second effect. **(4) Wallet:** an app fan pays by "Wallet mobile" (simulated hosted page, webview) and holds a rotating QR. **(5) Block/refund:** OPS blocks a ticket in B12, and the scanner shows BLOQUÉ after the next delta. ADMIN refunds another ticket: its place returns to sale, and FINANCE marks the refund done with a reference. **(6) Reconciliation:** the cashier closes the till declaring 10 DH less than expected. B8 Encaissements shows expected, declared and the −10,00 DH gap, plus totals per day × collector, with CSV. B3 shows "En attente cash", the split by payment method and the refunded places. `Lot3PaymentsFlowTest` (backend), `apps/fan/test/cash_order_test.dart`, the shop `orders-pages.test.tsx` and the back-office `PosPage.test.tsx` are green in CI. |
| Live (real cash) | The same with real money at the club's office and stadium kiosks (needs connectivity, sales close at T0 − 60). Gated by: Payzone kit (BE-7) for card; Q2 messaging provider (prod refuses to start without a real messenger, Lot 1); one 80 mm printer + a laptop per till (Q48); the `cash_points` list (Q54). **Third-party agencies** (Wafacash / Cash Plus / Payzone agents) are gated by a signed contract and its spec (BE-27). **Wallet** is gated by a provider (Q47). Until those exist, live cash = club guichets only, and prod hides the wallet. |

## 2. What exists, gaps, spec refs

### 2.1 What Lot 3 builds on

| Area | State on `feat/lot-2-gate` | Lot 3 use |
| --- | --- | --- |
| Schema | `order_status` already has `AWAITING_CASH`; `payment_method` has `CASH_AGENCY`, `WALLET`, `POS_CASH`; `sales_channel` has `POS`; `orders.user_id` and `tickets.holder_user_id` are nullable; `price_categories.sold` already counts AWAITING_CASH (03:153) | No new enum values except `staff_role` CASHIER |
| Payments | `PaymentService` does verify → payment row lock → idempotent → `OrderService.settle` (late success Q8, `payment.refund_required` audit). `CardGateway` port plus `FakeCardGateway` / `FakeGatewayController` (signed HMAC, profiles dev/test/staging, refused under prod) | Wallet = same hosted-page port with a `method`. A new `CashNetwork` port copies the same verify → lock → settle path |
| Sales | `OrderService.place / lockForPayment / settle / expireDue`; buyer advisory lock; `Inventory.reserve / tryReserve / release` | Walk-up POS sale reuses `place` checks. Settle accepts AWAITING_CASH. Expiry covers AWAITING_CASH |
| Hard-coded spots to change | `JdbcOrders.markPaid` writes `payment_method = 'CARD'`; `Order.Channel` is `{APP, WEB}`; `lockForPayment` accepts only RESERVED; `claimDue` / `expire` handle RESERVED only; `PaymentController` refuses non-CARD; `PAYMENT_GRACE` 20 min applies to every method; `SalesReport.CHANNELS` has POS but no method split | BE-22, BE-23 |
| Tickets | `TicketService` builds the static v1 token inside `withEventKey` (PDF) | POS print endpoint reuses it |
| Notifications | `TicketDeliveryService.deliver` does `tickets.holder(...).orElseThrow()` | Must skip tickets with no holder account (POS paper tickets) |
| Gate | Manifest builds a new version when ticket status changes; the scanner handles BLOCKED/REFUNDED | Block/refund reach scanners with no scanner change |
| Clients | Fan app `features/checkout` (card only, `card_payment.dart` webview), shop `orders/new/CheckoutForm.tsx`, `orders/[id]/OrderStatus.tsx`, `lib/order-client.ts` (`canRetryPay`), back-office `features/sales` (B3), `features/devices/StaffTab.tsx`, `react-qr-code` already installed | Extended; no new client dependency |

### 2.2 What does not exist

| Need | Plan |
| --- | --- |
| Pay code (generation, format, normalisation, uniqueness) | BE-22, V13 |
| `/pay` for CASH_AGENCY and WALLET, AWAITING_CASH expiry, fan orders list | BE-22, BE-24 |
| Point-of-sale role, till sessions, walk-up sale, code collect, print data | BE-23, V14 |
| Cash network callback (`04:119` names a path with no rules) | BE-24 (simulated); real adapter BE-27 (blocked) |
| Block / unblock / refund (`04:170`, never built; lot-2 Q39) and a refunds ledger | BE-25, V15 |
| Cash report | BE-26 |
| `passes` | Lot 5. So installments move there (Q51) |
| Real cash network, real wallet, Payzone, messaging provider | Founder (Q44, Q47, BE-7, Q2) |

### 2.3 Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (centimes, `organizer_id`, tickets immutable except `status`, no PII in QR, Kafka only where async is real), Conventions (web shop never exposes keys), Agent team |
| `docs/00-product.md` | §Why it can win (cash, finance rows), §V1 in lots (Lot 3 and Lot 5 rows), §Open questions ("Cash on match day") |
| `docs/01-architecture.md` | §Components, §Key flows 1 Purchase ("cash code async") |
| `docs/02-gate-validation-and-data-model.md` | §Scope (three sales channels incl. POS thermal print, static QR: line 10), §Threat model (refund freeze line 178, POS collusion line 177), §Manifest (status changes → deltas) |
| `docs/03-schema.sql` | `staff` / `staff_role` (34–48), `organizers.settings` (23–24), `price_categories.sold` (152–154), sales enums (159–161), `orders` (163–178), `tickets` (192–209), `payments` (233–250), `audit_log` |
| `docs/04-api.md` | Errors (17–46), §Fan `/pay` (68), GET order (69), order rules (84–87), §Payments (107–132, incl. late success 127–132), §Organizer roles (136–139), `/sales` (169), block/unblock/refund (170), staff (177–180), finance (184), §Gate reports `sold` (270), §Token |
| `docs/06-glossary.md` | Order statuses (40–43) |
| `design/DESIGN.md` | Screen 4 (95–107), W3/W4 (143–161), back-office sidebar and page table (171–193), Lot 1 adjustments W3/W4 (223) |
| Mockups | `4-paiement.html` (`Checkout.dc.html`), `w4-shop-commande-cash.html` (`ShopOrderStatus.dc.html`) |
| Plans | lot-0 Q1, Q8, §4.5 (CardGateway), §7 duplicate `provider_ref`; lot-1 Q2, Q15, Q20, Q21, §7 (AWAITING_CASH in sales, `/pay` after the hold, `payment_status`); lot-2 Q38, Q39, §7 (`sold` definition) |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default for Lot 3 |
| --- | --- | --- |
| Q44 | Agency network: which partner, and how agents confirm cash (agent back-office screen vs partner API)? | **Both, behind one code. No partner is needed for the pilot.** (1) **Club guichets** (ticket office, club shop, stadium kiosks, and any small partner shop the club trusts) use a back-office "Guichet" page with role CASHIER and an OTP login. They confirm in our system and are reconciled per till session. This is real from day one. (2) **Third-party networks** (Wafacash, Cash Plus, Payzone's own proximity agents) go behind a `CashNetwork` port. Their agents use their own network terminal; their confirmation reaches us as a signed server-to-server callback. Lot 3 ships a **simulated network** (`FAKE_CASH`: agent-terminal page + HMAC-signed callback, dev/test/staging only), the same way the card gateway is simulated. **Founder: ask Payzone first whether the kit covers cash at its agents (and wallets), since there is already a merchant relationship.** The real adapter (BE-27) is built only once a contract and its spec exist. Do not code from guesses. |
| Q45 | Pay code format and validity | `JMH` + 6 Crockford base32 symbols (30 bits, `SecureRandom`), shown `JMH 4F7K 29` (matches the W4 mockup). Unique forever, never reused. Input ignores case, spaces, hyphens and the `JMH` prefix, and maps O→0 and I/L→1. Valid until `least(issue + 24 h, refund_cutoff_at)`: the glossary's 24 h, but never past sales close, because a later ticket would be missing from the manifests (lot-1 Q20). Refused if that leaves less than 2 h (`payments.cash.min-window`). Exact amount only. No check digit: a lookup shows event + amount, so a typo that hits another live code is caught by the agent (about 1e-5 odds with 10,000 live codes). |
| Q46 | Cash holds can be used to sit on inventory for 24 h (reserve, never pay) | `per_account_limit` already counts AWAITING_CASH. **Plus a soft cap per event:** places in live AWAITING_CASH orders ≤ `payments.cash.max-held-percent` (default 20) % of the event quota. Above it → 409 `METHOD_NOT_AVAILABLE` `CASH_CAP`, and card/wallet remain. No lock, like `SALE_BUSY`. |
| Q47 | Mobile wallet provider(s); simulate like Payzone? | **Simulate.** WALLET is a hosted-page method through the same port as card (redirect + signed callback). `FakeCardGateway` shows "Wallet simulé". Config `payments.wallet.provider` (staging `fake`; prod unset → WALLET missing from `payment_methods` and `/pay` gives 409 `NOT_CONFIGURED`). First choice: Payzone's hosted page, if it takes wallets (zero extra adapter). Otherwise the founder picks the wallet the club's fans actually use (candidates to verify with providers: Orange Money, inwi money, Attijariwafa's Jibi, CIH's wallet, or a PSP offering the interoperable m-wallet scheme). One provider for card and wallet for now; a split is a `ponytail:` note until a second provider exists. |
| Q48 | POS printer model and protocol | **80 mm ESC/POS thermal printer with an OS driver, printed from Chrome on a laptop or PC.** Epson TM-T20III / TM-m30III or Xprinter XP-80 class; USB or LAN. Back-office print view with `@page { size: 80mm auto }`, one slip per ticket, QR 38 mm (v1 static, ECC M). Chrome `--kiosk-printing` skips the dialog. No raw ESC/POS, WebUSB or vendor SDK. Sunmi handhelds with built-in printers → Lot 6. The slip is a ticket, not a fiscal invoice (VAT invoices: Lot 5). Founder buys one printer for the staging demo. |
| Q49 | POS rules (club question "Cash on match day: kiosks or agencies only?") | Kiosks may sell **in the same window as online** (`sale_starts_at` to `refund_cutoff_at` = T0 − 60) with a connection (4G router at the kiosk). No walk-up sales after T0 − 60: scanners are offline from then on and the ticket would be UNKNOWN (lot-1 Q20). Cash only at the till (no card terminal). Names stay required per place (lot-1 Q15); the form has "même nom pour toutes les places". Phone optional: with a phone the holder gets the ticket on WhatsApp and in the app; without one it is paper only (`holder_user_id` null). Σ qty per sale ≤ `per_account_limit`. Cashiers can't refund, void or block. |
| Q50 | Cash reconciliation depth | **Till sessions + a cash report.** A cashier opens a session, every POS payment carries it, and at close they declare the counted cash. `expected_mad` is frozen; the gap is shown and audited. B8 "Encaissements" lists collected cash per day × collector (POS, each network) and every session with its gap, plus CSV. Network statements are matched by hand against that report; file import and bank deposits come with the Lot 5 finance module. |
| Q51 | Installments for passes | **Moved to Lot 5**, with `passes`: there is nothing to pay in installments before passes exist. Proposed Lot 5 shape: an installment plan = N scheduled payments on one pass order. The pass stays ACTIVE while payments are on time; a missed one → `SUSPENDED` → future tickets BLOCKED (manifest deltas already carry that). About +4 d backend and +2 d per client in Lot 5. Needs a `00-product.md` row edit (founder). |
| Q52 | Refunds and blocks scope (lot-2 Q39) | Per ticket, before `refund_cutoff_at` only (`REFUND_FROZEN`, 02:178). **Block / unblock:** ADMIN, OPS, with a reason. **Refund:** ADMIN. The ticket goes to REFUNDED, its place returns to sale, and a `refunds` row `REQUIRED` is created with the unit price snapshot. Money goes back **by hand** (gateway back-office for card/wallet; club office cash for cash, never out of a till). FINANCE marks it `DONE` with a reference. The order stays PAID. No gateway refund API (unknown until the kit), no fan self-refund, no whole-event cancellation (Lot 5). Residual: refunded places still count in the buyer's `per_account_limit` (`ponytail:` note). |
| Q53 | "Recevoir sur WhatsApp" for the pay code (W4 mockup) | Blocked on Q2. **Hidden in Lot 3.** The code stays reachable through W4, the app's cash order screen and the new "Commandes en attente" list (`GET /fan/orders`). An automatic code message arrives with the real provider (BE-15 / Lot 4 messaging). |
| Q54 | Where-to-pay list shown to fans | `organizers.settings.cash_points: [{name, address, hours}]`, set by ops via SQL for the pilot (as lot-0 Q12 did for staff). A back-office editor comes in Lot 5. Plus the display names of enabled networks (`cash.networks`). Cash is offered only if the organizer has at least one cash point or a network is enabled. |
| Q55 | Can a fan cancel an AWAITING_CASH order? | No. It expires at the code deadline and the places return. The fan can pay by card or wallet from W4 meanwhile. |
| Q56 | `GATES_OPEN` transition (lot-2 §7) | Not in Lot 3: nothing reads it. Carry to Lot 6, or drop. |

## 4. Contract changes

### 4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P32 | 04:68 | `/pay` says "CASH_AGENCY and WALLET in Lot 3" but has no response, rules or status change | §4.3 `/pay` row |
| P33 | 04:119 | `/payments/cash/callback`: no network in the path, no auth, no amount or double-payment rule; "idempotent on `payment_ref`" mixes our code with the network's reference | `/payments/cash/{network}/callback` + §Cash codes; idempotent per code; network ref unique |
| P34 | 03:172 | The cash code sits in `orders.payment_ref`, but it belongs to one payment attempt (an order can hold a code and a card attempt at once). `markPaid` hard-codes CARD | `payments.pay_code`. `orders.payment_ref` = the succeeding payment's ref, or the code. `payment_method` comes from the payment |
| P35 | 04:69 | `payment_status` reports any PENDING attempt older than 20 min as FAILED: wrong for a 24 h cash code | The 20 min rule applies to CARD/WALLET only |
| P36 | 06:42 | AWAITING_CASH "24 h" ignores sales close at T0 − 60 | `least(24 h, refund_cutoff_at)`, min 2 h (Q45) |
| P37 | 04:86 | The expiry job is defined for RESERVED only; AWAITING_CASH never expires | Expiry covers AWAITING_CASH; the code payment stays PENDING so a racing confirmation follows late success |
| P38 | lot-1 §7, 04:127 | A `/pay` whose redirect is never opened keeps the order (and the buyer's limit) for 20 min | `payments.redirected_at`; expiry waits only for attempts whose hosted page was opened |
| P39 | 04:169 | `/sales` is silent on AWAITING_CASH (lot-1 §7), has no split by method and no refunds | `awaiting_cash`, `methods[]`, `refunded`, `refunded_mad` |
| P40 | 04:170 | Block/unblock/refund: no roles, body, status rules, quota or money effect | §4.3 Tickets rows + `refunds` |
| P41 | 03:34, 04:139 | No point-of-sale role; POS channel and POS_CASH exist with no API (04:85 "POS is Lot 3") | `staff_role` CASHIER, §Point of sale |
| P42 | 04:184 | Cash reconciliation has no endpoint (settlements are Lot 5) | `GET /org/finance/cash` |
| P43 | 00-product:53 | "Installments for passes" in Lot 3, but `passes` is Lot 5 | Move to Lot 5 (Q51; founder edits 00) |
| P44 | 04:270 | "`sold` = VALID (Lot 3 revisits once refunds exist)" | Decided: unchanged (VALID only; blocked, refunded and cancelled excluded) |
| P45 | DESIGN:103–107, 156–161, 176–193 | W4 names Wafacash/Cash Plus and a WhatsApp button that don't exist yet; there is no POS, ticket lookup or cash report page | §4.6 |
| P46 | lot-0 §7 | A signed callback reusing a `provider_ref` → 500 | For cash callbacks: clean refusal + audit `payment.duplicate_ref`. Card stays with BE-7 |
| — | Football names / floats / PII in QR / server on the gate path | None. Codes and slips carry no name or phone (holder label only, as the PDF); all money is `bigint` centimes; the scanner is untouched | OK |

### 4.2 `docs/03-schema.sql`

```sql
-- ---------- tenancy ----------
create type staff_role as enum ('ADMIN','OPS','GATE','SUPERVISOR','FINANCE','CASHIER');
-- CASHIER: back-office OTP login (staff.user_id set), point of sale only (docs/04-api.md §Point of sale). No PIN.

-- organizers.settings shape (append):
--   "cash_points": [{"name": "Guichet Stade de l'Atlas", "address": "Porte 1", "hours": "Lun–Sam 10:00–19:00"}]
--   where fans pay a cash code in person; shown on the cash order page. Set by ops until a back-office editor exists.

-- ---------- sales: orders (replace the payment_ref line, add the comments) ----------
  payment_ref     text,   -- on PAID: the succeeding payment's provider_ref; for a cash code, the code
-- AWAITING_CASH: a cash code was issued; reserved_until = least(issue + 24 h, events.refund_cutoff_at).
-- POS sale: channel POS, user_id null, created PAID. Ticket refunds leave the order PAID
-- (order REFUNDED = whole-order refund, Lot 5).

-- ---------- point of sale (new, before payments) ----------
create table pos_sessions (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  staff_id      uuid not null references staff(id),      -- the cashier (CASHIER, OPS or ADMIN)
  label         text not null,                          -- 'Guichet Nord', 1-40 chars
  opened_at     timestamptz not null default now(),
  closed_at     timestamptz,
  expected_mad  bigint,                                 -- Σ SUCCEEDED payments of the session, frozen at close (centimes)
  declared_mad  bigint check (declared_mad >= 0),       -- cash counted by the cashier at close (centimes)
  constraint pos_sessions_close check ((closed_at is null) = (expected_mad is null)
                                       and (closed_at is null) = (declared_mad is null))
);
create unique index on pos_sessions (organizer_id, staff_id) where closed_at is null;   -- one open till per cashier

-- ---------- payments (replace the table) ----------
create table payments (
  id              uuid primary key default gen_random_uuid(),   -- sent to the gateway as its order reference
  organizer_id    uuid not null references organizers(id),
  order_id        uuid not null references orders(id),
  method          payment_method not null,
  provider        text not null,        -- CARD/WALLET: 'FAKE' (simulated, non-prod), 'PAYZONE'. POS_CASH: 'POS'.
                                        -- CASH_AGENCY: 'CASH' while PENDING, then the collector: 'POS' (club till),
                                        -- 'FAKE_CASH' (simulated network) or the network's name.
  amount_mad      bigint not null check (amount_mad >= 0),
  status          payment_status not null default 'PENDING',
  provider_ref    text,                 -- gateway or network transaction id from the callback; null for till payments
  pay_code        text unique,          -- CASH_AGENCY: 6 Crockford base32 symbols (docs/04-api.md §Cash codes), never reused
  pos_session_id  uuid references pos_sessions(id),   -- cash taken at a club till (POS sale or code collected there)
  redirected_at   timestamptz,          -- CARD/WALLET: hosted page opened; the expiry waits only for these (20 min)
  callback        jsonb,                -- last verified callback, card fields stripped
  created_at      timestamptz not null default now(),
  completed_at    timestamptz,
  constraint payments_pay_code check ((method = 'CASH_AGENCY') = (pay_code is not null))
);
create index on payments (order_id);
create unique index on payments (provider, provider_ref) where provider_ref is not null;
create index on payments (organizer_id, completed_at) where status = 'SUCCEEDED';   -- cash report
create index on payments (pos_session_id) where pos_session_id is not null;

-- ---------- refunds (new) ----------
create type refund_status as enum ('REQUIRED','DONE');

create table refunds (                                 -- money owed back for a refunded ticket; paid back by hand
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  ticket_id     uuid not null unique references tickets(id),
  order_id      uuid not null references orders(id),
  payment_id    uuid references payments(id),           -- the order's succeeding payment (its method says how to pay back)
  amount_mad    bigint not null check (amount_mad >= 0),  -- the item's unit_price_mad snapshot
  reason        text not null,                          -- 1-200 chars
  status        refund_status not null default 'REQUIRED',
  requested_by  uuid not null references staff(id),
  requested_at  timestamptz not null default now(),
  done_by       uuid references staff(id),
  done_at       timestamptz,
  reference     text,                                   -- how it was paid back: transfer, gateway refund id, receipt no
  constraint refunds_done check ((status = 'DONE') = (done_at is not null and done_by is not null and reference is not null))
);
create index on refunds (organizer_id, status, requested_at);
```

Flyway, one migration per task:

| File | Task | Content |
| --- | --- | --- |
| `V13__cash_codes.sql` | BE-22 | `payments.pay_code` + unique + `payments_pay_code` check, `payments.redirected_at`, the cash-report index |
| `V14__point_of_sale.sql` | BE-23 | `alter type staff_role add value 'CASHIER'` (unused inside V14), `pos_sessions`, `payments.pos_session_id` + index |
| `V15__refunds.sql` | BE-25 | `refund_status`, `refunds` |

The tenant-exempt allowlist stays at 4. The `02` §Data model table gets `pos_sessions`, `refunds` and the payments columns in the same docs change.

### 4.3 `docs/04-api.md`

**Errors: add or replace** (header: "Lot 0 to Lot 3")

| Code | HTTP | When |
| --- | --- | --- |
| `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE` | 409 | Pay on an order that isn't `RESERVED` or `AWAITING_CASH`, or whose hold has passed |
| `METHOD_NOT_AVAILABLE` | 409 | `/pay` with a method this order can't use now. `details.reason`: `TOO_LATE` (cash: the code would be valid less than `payments.cash.min-window`; + `details.sales_close_at`), `CASH_CAP` (§Cash codes cap), `NOT_CONFIGURED` (no provider for WALLET; for CASH_AGENCY: no cash point and no network) |
| `CODE_NOT_PAYABLE` | 409 | Collect on a code that isn't payable (§Cash codes); `details.status` = the order status, or `EXPIRED` when the hold has passed |
| `POS_SESSION_REQUIRED` | 409 | POS sale, collect or print without an open till session of the caller |
| `POS_SESSION_CLOSED` | 409 | Close of a session already closed |
| `TICKET_STATUS` | 409 | Block of a non-`VALID` ticket, unblock of a non-`BLOCKED` one, refund of a ticket that is neither; `details.status` |
| `REFUND_FROZEN` | 409 | Block, unblock or refund at or after `refund_cutoff_at` |

**§Fan: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/fan/events/{id}` | … existing fields + `payment_methods`: subset of `["CARD","WALLET","CASH_AGENCY"]` in that order. `WALLET` iff a wallet provider is configured. `CASH_AGENCY` iff the organizer has a cash point or a network is enabled, and `now < refund_cutoff_at − payments.cash.min-window`. The cash cap is not reflected (`/pay` answers it). |
| POST | `/fan/orders/{id}/pay` | `{method: CARD\|WALLET\|CASH_AGENCY}`. Buyer only (404). Order `RESERVED` or `AWAITING_CASH` with `reserved_until > now()`, else 409 `ORDER_EXPIRED` / `ORDER_NOT_PAYABLE`; the order row is locked first. **CARD, WALLET** → `200 {payment_id, method, redirect_url}`: a new PENDING attempt per call; `redirect_url` = `/payments/card/{payment_id}/redirect` for both (the hosted page; the path keeps its Lot 0 name). On an `AWAITING_CASH` order the code stays payable until one method succeeds. **CASH_AGENCY** → `200 {payment_id, method, pay_code, reserved_until, amount_mad}`: `RESERVED` → `AWAITING_CASH`, `reserved_until = least(now + 24 h, refund_cutoff_at)`, one PENDING payment (provider `CASH`) with a new code (§Cash codes). An `AWAITING_CASH` order that already has a PENDING code → the same answer (no new code, hold unchanged). Refused with `METHOD_NOT_AVAILABLE`: `TOO_LATE` if the new `reserved_until` is less than `payments.cash.min-window` (config, default 2 h) away; `CASH_CAP`; `NOT_CONFIGURED`. |
| GET | `/fan/orders/{id}` | Lot 1 shape + `payment_method` (the SUCCEEDED attempt's method, else the latest attempt's, null before `/pay`) + `cash`: `{pay_code, amount_mad, points:[{name, address, hours}], networks:[name]}` while the order is `AWAITING_CASH` with a PENDING code, else null. `points` = `organizers.settings.cash_points`; `networks` = display names of enabled cash networks. `payment_status` (replace the parenthesis): `SUCCEEDED` if any attempt succeeded; otherwise the latest attempt's status, a PENDING **CARD/WALLET** attempt older than 20 min being reported `FAILED`; a PENDING cash code is `PENDING`. Clients: on `AWAITING_CASH` show the code and offer card/wallet unless the latest attempt is a CARD/WALLET still `PENDING`; on `RESERVED` the Lot 1 "payer à nouveau" rule stands. |
| GET | `/fan/orders` | The buyer's orders → `{orders:[{id, event:{id, title, starts_at, timezone}, channel, status, total_mad, reserved_until, payment_method, created_at}]}`: `AWAITING_CASH` first, then by `created_at` desc, at most 20. `Cache-Control: no-store`. |

**§Fan order rules (04:84–87): replace the last two sentences.** "`channel` accepts `APP` and `WEB` (POS orders are made by `POST /org/pos/orders`). The limit counts `RESERVED` and `AWAITING_CASH` orders until the expiry job (every 30 s) moves them to `EXPIRED`."

**New §Cash codes**

| Rule | Value |
| --- | --- |
| Symbols | Crockford base32 `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, 6 from `SecureRandom` (30 bits) |
| Display | `JMH 4F7K 29`: `JMH`, space, 4 symbols, space, 2 symbols |
| Stored | The 6 symbols, uppercase, in `payments.pay_code`, unique forever; a collision on insert draws again (at most 5 times, then 500) |
| Input | Case-insensitive; spaces, hyphens and a leading `JMH` are ignored; `O` → `0`, `I`/`L` → `1`; anything left that isn't a symbol, or a length ≠ 6 → not found |
| Payable | Order `AWAITING_CASH` with `reserved_until > now()` (database clock), code payment `PENDING`. Exact `total_mad` only: no partial payment, no overpayment |
| Expiry | The expiry job moves `AWAITING_CASH` orders past `reserved_until` to `EXPIRED` and releases their places once. The code payment stays `PENDING`, so a network confirmation racing the expiry follows §Late success (re-reserve, or `payment.refund_required`) |
| Cap | Places of the event's `AWAITING_CASH` orders still held + this order's places ≤ `payments.cash.max-held-percent` (config, default 20) % of the event's total quota. Counted without a lock (soft) |
| Reveals | Staff lookup: event, amount, places, holder labels. Simulated network terminal: event title and amount. Never a name or phone |

**§Payments: replace the `/payments/cash/callback` row, add the terminal row, replace the late-success paragraph**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/payments/cash/{network}/callback` | A cash network confirms a code paid at one of its agents (server to server; `fake` now, a real network with BE-27). The adapter verifies the signature first (bad → 401 `UNAUTHORIZED`, nothing written). Then, under the code payment's row lock (found by normalised `pay_code`, method `CASH_AGENCY`): unknown code → the adapter's "unknown" answer, nothing written. Already SUCCEEDED with this network and ref → acknowledged again, no effect. Already SUCCEEDED otherwise (paid twice: guichet, another ref) → `payment.refund_required` `NOT_PAYABLE`, acknowledged. Amount ≠ `amount_mad` → payment `FAILED` + `refund_required` `AMOUNT_MISMATCH`. Otherwise payment `SUCCEEDED` (`provider` = the network, `provider_ref` = its ref), then settle as a card success (`AWAITING_CASH` → `PAID` + tickets + deliveries; an `EXPIRED` order per §Late success; a `PAID` one → `NOT_PAYABLE`). A ref already used by another payment of that network → refused, audit `payment.duplicate_ref`, nothing else written (never a 500). Wire format and acknowledgement come from the adapter. |
| GET, POST | `/payments/cash/fake` | Simulated agent terminal, only with `payments.cash.networks` containing `fake` in `dev`/`test`/`staging` (startup fails under `prod`). A form takes a code and shows event title + amount, or "code inconnu ou non payable"; "Encaisser" posts the signed callback (HMAC-SHA256 with `PAYMENTS_FAKE_SECRET` over `pay_code\|amount_mad\|network_ref`) to `/payments/cash/fake/callback`. |

Late success (replace 04:127's first sentence): "Expiry skips orders with a `PENDING` CARD/WALLET payment whose hosted page was opened (`redirected_at`, set by `/payments/card/{id}/redirect`) less than 20 min ago; an attempt never redirected doesn't hold the order. A success after expiry (card, wallet or cash network) re-reserves …" (rest unchanged).

`/payments/card/{payment_id}/redirect` row, append: "Serves CARD and WALLET attempts. Sets `redirected_at` on first use."

**§Organizer: role line, append.** "Lot 3: `CASHIER` may call `/org/pos/**`, `GET /org/events` and `GET /org/events/{id}/prices` only (403 elsewhere). Block and unblock: ADMIN, OPS. Refund: ADMIN. `GET /org/refunds` and `/org/events/{id}/tickets`: ADMIN, OPS, FINANCE. `POST /org/refunds/{id}/done`: ADMIN, FINANCE (FINANCE's first write). `/org/finance/cash`: ADMIN, OPS, FINANCE. `/org/pos/**`: ADMIN, OPS, CASHIER."

**§Organizer: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/staff` | `{name, role: GATE\|SUPERVISOR\|CASHIER, phone?}`. GATE/SUPERVISOR unchanged. CASHIER: `phone` (E.164) required (422 otherwise); creates or links the user; a phone already staff of this organizer → 422 on `phone`, `details.reason` `TAKEN` → `201 {id, name, role, active, has_pin: false}` (no `pin`). PATCH covers CASHIER rows too; `/pin` on a CASHIER → 404. |
| GET | `/org/events/{id}/sales` | Lot 1 shape + `awaiting_cash` (places in `AWAITING_CASH` orders still held; total and per zone), `refunded` (tickets `REFUNDED`; total and per zone), `refunded_mad` (Σ `refunds.amount_mad` of the event, any status), `methods:[{method, paid, revenue_mad}]` for every `payment_method` (zeros included), by `PAID` orders' `payment_method`. `held` stays `RESERVED` only. `paid` and `revenue_mad` keep counting refunded tickets (gross); net = `revenue_mad − refunded_mad`. |
| GET | `/org/events/{id}/tickets?q=` | Lookup. `q` = a ticket number (8 hex, spaces ignored; every ticket of the event ending so), a holder phone (E.164, exact) or a cash code (§Cash codes input). Else 422 on `q`. → `[{id, ticket_no, status, zone:{id, code, name}, holder_name, holder_label, order_id, channel, payment_method, issued_at, refund}]` (`refund` = `{id, status, amount_mad}` or null), by `ticket_no`, at most 50. |
| POST | `/org/tickets/{id}/block` | `{reason}` (1–200). `VALID` → `BLOCKED` (else 409 `TICKET_STATUS`); at or after `refund_cutoff_at` → 409 `REFUND_FROZEN`. Audit `ticket.block` (reason). The next manifest version carries it; no `k_t` and no PDF from then on. → lookup row. |
| POST | `/org/tickets/{id}/unblock` | `{reason}`. `BLOCKED` → `VALID`, same rules. Audit `ticket.unblock`. → lookup row. |
| POST | `/org/tickets/{id}/refund` | `{reason}`. `VALID` or `BLOCKED` → `REFUNDED`, same freeze. In one transaction: ticket `REFUNDED`; its place released (`sold − 1`); a `refunds` row `REQUIRED` (`amount_mad` = the item's `unit_price_mad`, `payment_id` = the order's succeeding payment). The order stays `PAID`. Audit `ticket.refund`. → lookup row. Money goes back by hand, then `/done`. |
| GET | `/org/refunds?status=&event_id=` | `[{id, event_id, ticket_id, ticket_no, order_id, amount_mad, method, reason, status, requested_by:{id, name}, requested_at, done_by, done_at, reference}]`: `REQUIRED` first, then newest. `method` = the payment's method (null if none). |
| POST | `/org/refunds/{id}/done` | `{reference}` (1–100). `REQUIRED` → `DONE`; already `DONE` → 200 unchanged. Audit `refund.done`. → row. |
| GET | `/org/finance/cash?from=&to=&timezone=` | Cash collected: payments `SUCCEEDED` with method `POS_CASH` or `CASH_AGENCY`, `completed_at` in [`from`, `to`) (RFC 3339; at most 92 days, else 422). → `{from, to, timezone, days:[{day, collector, method, payments, amount_mad}], sessions:[{id, label, staff:{id, name}, opened_at, closed_at, orders, tickets, prints, expected_mad, declared_mad, variance_mad}], totals:{collected_mad, declared_mad, variance_mad, open_sessions}}`. `day` = local date in `timezone` (IANA, default `Africa/Casablanca`); `collector` = the payment's `provider`; `days` by day then collector. `sessions` = opened in the range, by `opened_at`; an open one has `closed_at`, `declared_mad` and `variance_mad` null and `expected_mad` = collected so far; `variance_mad = declared − expected`. `/org/finance/cash.csv`: `;`, UTF-8 BOM, CRLF, `kind` (`jour`/`caisse`) column, amounts as DH with a comma (`1250,50`, integer maths). |

**New §Point of sale** (`/org/pos/...`; ADMIN, OPS, CASHIER; organizer-scoped like every `/org` call)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/org/pos/session` | `{session: {id, label, opened_at, orders, tickets, prints, collected_mad} \| null}` for the caller. `collected_mad` = Σ `SUCCEEDED` payments of the session. |
| POST | `/org/pos/sessions` | `{label}` (1–40) → `201` session. The caller already has one open → `200` with it (label unchanged). Audit `pos.open`. |
| POST | `/org/pos/sessions/{id}/close` | `{declared_mad}` (int ≥ 0). The caller's own session (ADMIN: any of the organizer; else 404). Closed → 409 `POS_SESSION_CLOSED`. Freezes `expected_mad` → `200` (finance session shape). Audit `pos.close` (expected, declared). |
| POST | `/org/pos/orders` | Walk-up cash sale. `{sale_id, event_id, items:[{price_category_id, qty, holders:[{name, phone?}]}]}`; `sale_id` = a client-generated UUID that becomes `orders.id`. The same `sale_id` again from the same session → `200` with that order; from elsewhere → 422 on `sale_id` (`TAKEN`). Needs the caller's open session (409 `POS_SESSION_REQUIRED`). Checks: event (404), sale window (409 `EVENT_NOT_ON_SALE`), items and holders (422, same field paths as `/fan/orders`), Σ qty ≤ `per_account_limit` (409 `PER_ACCOUNT_LIMIT`, `held: 0`), quota (409 `SOLD_OUT`); no `SALE_BUSY`. In one transaction: order `PAID` (channel `POS`, `user_id` null, `payment_method` `POS_CASH`); payment `SUCCEEDED` (`POS_CASH`, provider `POS`, `pos_session_id`); tickets issued. A holder with `phone` → that account holds the ticket and gets the WhatsApp delivery; without one → `holder_user_id` null, paper only, no delivery. Audit `pos.sale`. → `201 {id, status, channel, total_mad, items[], ticket_ids[]}`. |
| GET | `/org/pos/codes/{code}` | → `{order_id, status, payable, reserved_until, amount_mad, places, event:{id, title, starts_at, timezone}, holders:[label]}`. Unknown or another organizer's → 404. |
| POST | `/org/pos/codes/{code}/collect` | `{amount_mad}` = the amount the cashier confirmed (≠ `total_mad` → 422 on `amount_mad`). Needs an open session. Payable → in one transaction (payment row, then order): payment `SUCCEEDED` (provider `POS`, `pos_session_id`, `completed_at`), order `PAID` (`payment_method` `CASH_AGENCY`, `payment_ref` = code), tickets + deliveries → `200 {order_id, status: PAID, ticket_ids}`. Already collected by this session → `200` same. Otherwise 409 `CODE_NOT_PAYABLE`. Audit `pos.collect`. |
| GET | `/org/pos/orders/{id}/tickets` | Print data for an order paid in a till session of this organizer (POS sale or collected code; else 404); needs an open session. → `{order_id, event:{id, title, starts_at, timezone, venue:{name}}, tickets:[{id, ticket_no, status, zone:{code, name}, gate_codes[], holder_label, unit_price_mad, qr}]}`. `qr` = the static v1 token (as on the PDF), built inside `withEventKey`, for `VALID` tickets only (else null). `Cache-Control: no-store`. Each call is a print: audit `pos.print` (session, order, ticket count), counted in the session's `prints`. |

**§Gate reports (04:270):** replace "(Lot 3 revisits once refunds exist)" with "(blocked, refunded and cancelled tickets are not sold)".

**`CashNetwork` port (plan note, in `payments`; two adapters: simulated now, real network in BE-27):**

```java
interface CashNetwork {
  String provider();                                   // 'FAKE_CASH'; payments.provider once collected
  String displayName();                                // fans see it in cash.networks
  CashVerdict verify(HttpCallback request);            // signatureOk, payCode, amountMad (long), networkRef
  ResponseEntity<String> acknowledge(CashVerdict v, Outcome o);   // ACCEPTED, ALREADY, UNKNOWN, REFUSED
}
```

`CardGateway.PaymentRef` gains `method` (CARD/WALLET); one gateway serves both (`ponytail:` split when a wallet provider differs).

### 4.4 `docs/06-glossary.md`

- Order statuses: "RESERVED (8 min hold) → PAID · RESERVED → AWAITING_CASH (cash code issued; held until least(24 h, sales close)) → PAID · EXPIRED · CANCELLED · REFUNDED (whole-order refund, Lot 5; ticket refunds keep the order PAID)."
- New terms: pay code · `code de paiement` · رمز الأداء · "`JMH 4F7K 29`, paid in cash at a guichet or agency". Till session · `caisse` · الصندوق · "one cashier's shift; expected vs declared cash". Guichet · `guichet` · الشباك · "club point of sale (back-office POS page)". Collector · `encaisseur` · — · "who took the cash: POS or a network". Refund · `remboursement` · استرجاع.
- Payment methods (FR UI): CARD Carte bancaire · WALLET Wallet mobile · CASH_AGENCY Cash en agence · POS_CASH Espèces au guichet · FREE Gratuit.

### 4.5 `docs/02`, `docs/01`, `docs/00`

| Where | Change |
| --- | --- |
| 02:177 (threat model) | Add row: "Cash confirmed without cash taken \| Signed network callbacks, exact amount, unique network ref; club tills reconciled per session (expected vs declared) \| A cashier's gap is found at close, not prevented". |
| 02 §Data model | `pos_sessions`, `refunds`, payments `pay_code`, `pos_session_id`, `redirected_at` |
| 01 §Key flows 1 | "…pay (card/wallet: hosted page + callback; cash: AWAITING_CASH + pay code, collected at a club till or by a network callback) → `order.paid` …" |
| 00:53, 00:55 (founder) | Lot 3 row: drop "installments for passes". Lot 5 row: "season passes (`passes`) with installments" (Q51) |

### 4.6 `design/DESIGN.md`: new "Lot 3 adjustments"

| Where | Change |
| --- | --- |
| Screen 4 / W3 | Rows from `payment_methods` (Carte bancaire · Wallet mobile · Cash en agence; hints "Instantané" / "Instantané" / "Avant {deadline}"). A missing Cash row shows disabled "Trop tard pour le cash". CTA per method (DESIGN:104). `METHOD_NOT_AVAILABLE` copy per reason. Wallet = same webview / redirect as card. |
| App cash order screen (not mocked; W4 layout in app components) | Code card (display 44 px, letter-spacing 3, white paper), exact amount, "Copier le code", countdown to `reserved_until`, "Où payer" (points + networks), 3 steps, "Payer maintenant ?" card / wallet (rule in 04 GET order), polls every 10 s → PAID → screen 5 slide-in. EXPIRED → "Code expiré · places libérées" + back to the event. |
| Screen 1 | Amber "Commande en attente de paiement" strip from `GET /fan/orders` (AWAITING_CASH, upcoming event) → cash order screen. |
| W4 | As mockup, except: order label = "Commande {last 8 hex of id, 4-4}"; step 1 copy from `cash.points` / `cash.networks` (no hard-coded Wafacash/Cash Plus); "Recevoir sur WhatsApp" hidden (Q53); holder rows show `label` only; EXPIRED state as the app. |
| W5 | "Commandes en attente de paiement" section above the tickets. |
| B11 Guichet `/pos` (Lot 3, new) | Session bar (label, opened at, encaissé, "Clôturer la caisse" → declared amount dialog → expected / declared / gap). Tabs **Vente** (event select of ON_SALE events, zone/price rows with remaining, stepper, holder names + "même nom pour toutes les places", optional phone, total, "Encaisser {total}", change calculator received − total on the client, then print) and **Code cash** (input masked `JMH ____ __`, lookup card with event, amount, places, holders, status; "Encaisser {amount}"; on 409 the copy is "Code déjà payé ou expiré · ne gardez pas l'argent"). Print view `/pos/print/:orderId`. CASHIER sees only this page. |
| POS slip (80 mm) | One slip per ticket (page break): club name, title, date · time (venue time zone), venue, ZONE and PORTE(S), holder label, QR 38 mm + ticket no under it, price, short order no, "Une entrée · Propulsé par Jomhor". Black only, no logo raster. |
| B12 Billets `/events/:id/tickets` (Lot 3, new) | Search (n° billet, téléphone, code cash), results table, actions Bloquer / Débloquer / Rembourser (confirm + reason), disabled after the cutoff with "Gel des remboursements". |
| B3 | KPIs + "En attente cash"; table "Par moyen de paiement"; "Remboursés"; "Remboursements à faire" = the Lot 1 gateway flags + ticket refunds (`/org/refunds`, "Marquer remboursé" → reference dialog). |
| B8 Finance `/finance/cash` (Lot 3: Encaissements tab only) | Date range, per day × encaisseur table, sessions table (gap in red when ≠ 0), totals, CSV. |
| B6 Équipe | Add "Caissier" (name + phone, no PIN). |
| B2 Règles | "Cash en agence · jusqu'à {sales_close_at − 2 h}" read-only. |
| Sidebar | Jour J + "Guichet"; Fans & finance: "Finance" enabled. |

## 5. Tasks

### Wave 0: orchestrator and founder (0.5 d, before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/02`, `03`, `04`, `06`, `01`, `design/DESIGN.md` | §4 applied after the founder agrees (`00-product.md` edit only by the founder) |
| W0-2 | orchestrator | git | `feat/lot-3-payments` from `main` after the Lot 2 PR merges. No new CI job (no new package) |
| W0-3 | founder | — | Ask Payzone whether the kit covers cash at its agents and wallets (Q44, Q47). Buy one 80 mm printer (Q48). Give the club's `cash_points` (Q54). Ask the club about cash on match day (Q49). Agree to moving installments (Q51). Q2 provider still pending |

### Tasks

`backend` is serial and on the critical path. Rows marked ∥ run alongside the backend task of their wave, in disjoint folders. A client task marked *(contract-first)* starts on the §4 shapes with fixtures and is done only after one run against the real endpoint.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-22 | backend | `backend/…/payments` (`/pay` methods, code generator, `redirected_at`), `…/sales` (AWAITING_CASH settle/expiry, `markPaid` method, `GET /fan/orders`, cap), `…/catalog` (`payment_methods`, `cash_points` read), V13 | §Cash codes (generator + normaliser in `payments/domain`), `/pay` CASH_AGENCY (hold cap, min window, `CASH_CAP`, idempotent), CARD on AWAITING_CASH, settle from AWAITING_CASH, expiry of AWAITING_CASH, P38 expiry skip only for redirected attempts, `payment_status` rule, GET order `payment_method` + `cash`, `GET /fan/orders`, `payment_methods` | `CashCodeTest`: alphabet, display, `jmh 4f7k-2o` → `4F7K20`, `U`/7 chars → not found. `CashOrderApiTest`: pay CASH → AWAITING_CASH, `reserved_until` = min(24 h, cutoff); twice → same code; cutoff in 1 h 59 → 409 `TOO_LATE`; quota 10, two live 1-place cash orders → 3rd → 409 `CASH_CAP`; no cash point + no network → `NOT_CONFIGURED` and absent from `payment_methods`; the limit counts AWAITING_CASH; CARD on AWAITING_CASH → `redirect_url`, then fake approve → PAID with `payment_method` CARD; expiry at deadline → EXPIRED, `sold` back once (two parallel passes); a 3 h-old code → `payment_status` PENDING; `cash.points` from settings; `/fan/orders` AWAITING first, another user's absent; generator forced to collide 2× → third draw stored. P38: never-redirected card → order expires at 8 min; redirected → kept (all Lot 1 Q8 tests still green) | W0 | 3 |
| 1 ∥ | FL-15 | flutter | `apps/fan/lib/features/checkout` | Method rows from `payment_methods`; wallet through `card_payment.dart`; cash order screen (DESIGN §4.6); the "payer maintenant" rule; `METHOD_NOT_AVAILABLE` copy | Tests: `/pay` body per method; code shown `JMH 4F7K 29`, copy puts that string on the clipboard; card/wallet hidden while the latest CARD attempt is PENDING; poll every 10 s stops on PAID/EXPIRED; EXPIRED copy; `TOO_LATE`/`CASH_CAP` copy; no hard-coded strings or colours; `cash_order_test.dart` (fake API: reserve → cash → poll → PAID → ticket) | BE-22 *(contract-first)* | 3 |
| 1 ∥ | WS-5 | web-shop | `web/shop/app/(public)/orders`, `app/api/orders`, `lib/order-client.ts` | W3 method picker; pay handler forwards `method`; W4 per mockup + §4.6 (SSR code, client poll 10 s, EXPIRED, alternatives); `canRetryPay` for AWAITING_CASH | Tests: handler forwards `method` and never returns the cookie token; W4 HTML has the grouped code and exact amount (integer formatting); no WhatsApp button; poll swaps to the PAID view; alternatives hidden while a CARD attempt is PENDING; `orders-pages.test.tsx` cash path | BE-22 *(contract-first)* | 3 |
| 1 ∥ | BO-13a | backoffice | `web/backoffice/src/features/pos` | B11 layout on fixtures: session bar, Vente, Code cash, print view (`react-qr-code`, `@page 80mm`) | Tests: print view = one slip per ticket, QR value = `qr` exactly, no `holder_name` rendered; change calculator in integer centimes; code mask display | BE-23 *(contract-first)* | 2 |
| 2 | BE-23 | backend | `backend/…/sales` (POS sale), `…/payments` (till sessions, collect), `…/tenancy` (CASHIER role, `/org/staff` phone, interceptor), `…/tickets` (print data), `…/notifications` (skip holder-less tickets), V14 | §Point of sale; CASHIER confinement; `/org/staff` CASHIER | `PosApiTest`: CASHIER → 403 on `/org/venues`, 200 on `/org/pos/session`; open twice → same session; sale without session → 409 `POS_SESSION_REQUIRED`; sale → PAID, payment POS_CASH SUCCEEDED with session, tickets; no-phone holder → `holder_user_id` null and no delivery row (the delivery job never throws); phone holder → one delivery; same `sale_id` twice → one order; `sale_id` of another session → 422; Σ qty > limit → 409; after cutoff → 409; lookup normalised; org B code → 404; wrong `amount_mad` → 422; collect → PAID, `payment_method` CASH_AGENCY, `payment_ref` = code, provider POS; repeat → 200 same; expired → 409 `CODE_NOT_PAYABLE` `EXPIRED`; two parallel collects from two sessions → one 200, one 409; print → `qr` decodes and verifies with K_e through `TicketToken`, `prints` +1, `no-store`; close → expected = Σ, variance; close twice → 409; another cashier's session → 404, ADMIN → 200; CASHIER with phone → `has_pin: false`, OTP login works; `MigrationsTest` V14, allowlist 4 | BE-22 | 3 |
| 2 ∥ | BO-13b | backoffice | `web/backoffice/src/features/pos`, `src/app` (CASHIER shell, route) | B11 against fixtures: session open/close dialog, sale form with `sale_id` from `crypto.randomUUID()` kept across retries, collect flow, CASHIER sees only Guichet | Tests: a retried sale sends the same `sale_id`; close dialog shows the gap; CASHIER sidebar = Guichet only; query keys include the organizer; 409 copy on collect | BE-23 *(contract-first)* | 2 |
| 3 | BE-24 | backend | `backend/…/payments` (`CashNetwork` port, `adapters/fakecash`, cash callback, wallet method on `CardGateway`), `application*.yml` | Simulated network (terminal page + signed callback) and §Payments cash callback rules; WALLET through the hosted gateway (`payments.wallet.provider`) | `CashCallbackTest`: bad signature → 401, nothing written; good → PAID + tickets; same callback twice → one effect; amount mismatch → FAILED + `refund_required` AMOUNT_MISMATCH; after expiry with quota → PAID, without → `refund_required` SOLD_OUT; after a guichet collect → `refund_required` NOT_PAYABLE; network ref reused on another code → refused + `payment.duplicate_ref`, no 500; terminal page shows title + amount only (fixture name searched); `fake` network under `prod` → startup fails. Wallet: `/pay` WALLET → `redirect_url`; fake approve → PAID, `payment_method` WALLET; provider unset → 409 `NOT_CONFIGURED`, absent from `payment_methods` | BE-23 | 2 |
| 3 ∥ | FL-16 | flutter | `apps/fan/lib/features/events` (home strip), `…/features/checkout` (orders list repo) | Screen 1 amber strip from `GET /fan/orders` → cash order screen | Tests: strip only for AWAITING_CASH of an upcoming event; tap routes to the order; offline → no strip, no crash | BE-22, FL-15 | 1 |
| 3 ∥ | WS-6 | web-shop | `web/shop/app/(public)/me`, `app/api/orders/route.ts` (GET) | W5 "Commandes en attente" section | Tests: GET handler proxies `/fan/orders` with the cookie; only AWAITING_CASH rows shown, linking to `/orders/[id]` | BE-22, WS-5 | 1 |
| 3 ∥ | BO-16 | backoffice | `web/backoffice/src/features/devices/StaffTab.tsx` | Add "Caissier" (name + phone), no PIN dialog | Tests: payload `{name, role: CASHIER, phone}`; `TAKEN` → field error; no PIN dialog for CASHIER | BE-23 | 0.5 |
| 4 | BE-25 | backend | `backend/…/tickets` (block/unblock/refund, lookup), `…/payments` (refunds ledger, `/org/refunds`), `…/catalog` (Inventory release of one place), V15 | 04 Tickets + refunds rows | `TicketAdminApiTest`: block → BLOCKED, the next manifest build has `T … BLOCKED`, `/fan/tickets` has no `k_t`, PDF 409; unblock → VALID; block BLOCKED → 409 `TICKET_STATUS`; after cutoff → 409 `REFUND_FROZEN`; refund → REFUNDED, `sold − 1`, refunds row = unit price + payment, order still PAID; refund twice → 409; OPS refund → 403; FINANCE `/done` → DONE, again → 200 unchanged; CASHIER/GATE → 403; org B ticket → 404; lookup by `4D5C 6B7A`, by phone, by code; 60 matches → 50 | BE-24 | 2 |
| 4 ∥ | BO-14 | backoffice | `web/backoffice/src/features/tickets` (new), `…/features/sales` (refund to-do) | B12 + B3 "Remboursements à faire" with "Marquer remboursé" | Tests: confirm dialogs require a reason; actions disabled after cutoff; `REFUND_FROZEN`/`TICKET_STATUS` copy; mark done sends `reference` | BE-25 *(contract-first)* | 2 |
| 5 | BE-26 | backend | `backend/…/sales` (`/sales` fields), `…/payments` (`/org/finance/cash` + CSV), `backend/src/test/…/Lot3PaymentsFlowTest`, `backend/http/lot3-payments.http` | Report changes + exit test | `SalesReportApiTest`: AWAITING_CASH in `awaiting_cash` not `held`; `methods` zeros included; refunded counts + `refunded_mad`. `CashReportTest`: a payment at 23:30 UTC lands on the next local day; open session `expected` so far; variance; CSV BOM `;` CRLF `1250,50`; 93-day range → 422; FINANCE 200, CASHIER 403. **`Lot3PaymentsFlowTest`:** fan A reserves → CASH → cashier opens till → collects A's code → PAID + tickets + deliveries; fan B's code paid via the fake network callback (sent twice); walk-up sale of 2 (one without phone) → print → both `qr` verify with K_e; manifest next version contains them; fan C pays WALLET; ADMIN blocks one ticket (manifest BLOCKED) and refunds another (place back, refund REQUIRED → FINANCE DONE); fan D's code expires → places back; till closed with declared = expected − 1000 → cash report variance −1000; `/sales` awaiting_cash 0, methods CASH_AGENCY 2 orders, POS_CASH 1, WALLET 1, refunded 1 | BE-25 | 2.5 |
| 5 ∥ | BO-15 | backoffice | `web/backoffice/src/features/finance` (new), `…/features/sales` (B3 KPIs and methods) | B8 Encaissements + B3 updates | Tests: local-date range → RFC 3339 in the venue zone; variance red only when ≠ 0; CSV via the API client with `X-Organizer-Id`; DH formatting by integer maths | BE-26 *(contract-first)* | 2 |
| blocked | BE-27 | backend | `backend/…/payments/adapters/<network>` | Real cash network adapter per its spec | Unit tests against the network's documented samples; `Lot3PaymentsFlowTest` unchanged on the fake | **Q44 contract + spec** | 2 (re-estimate) |
| blocked | BE-7 | backend | `…/payments/adapters/payzone` | As in Lot 0, now also WALLET if Payzone takes wallets | As in Lot 0 | **Payzone kit** | 2 |
| blocked | BE-15 | backend | messaging adapters | As in Lot 1; then the automatic pay-code message (Q53) | As in Lot 1 | **Q2** | 2 |
| exit | — | orchestrator + founder | — | Demo per §1 on staging with a laptop + 80 mm printer and a Lot 2 scanner | `Lot3PaymentsFlowTest` + client checks green; manual demo | all non-blocked | — |

`reviewer` runs after every builder task.

### Lanes and critical path (working days, before review loops)

| Agent | Sequence | Ends |
| --- | --- | --- |
| backend (critical) | BE-22 0–3 → BE-23 3–6 → BE-24 6–8 → BE-25 8–10 → BE-26 10–12.5 | d12.5 |
| flutter | FL-15 0–3 (real endpoint from d3) → FL-16 6–7 | d7 (free for Lot 4 prep) |
| web-shop | WS-5 0–3 → WS-6 6–7 | d7 |
| backoffice | BO-13a 0–2 → BO-13b 3–5 (real at d6) → BO-16 6–6.5 → BO-14 8–10 → BO-15 10–12 → smoke at d12.5 | d12.5 |

### Budget check (3 weeks = 15 working days)

| Agent | Sum | ×1.1 review | Fits? |
| --- | --- | --- | --- |
| backend | 12.5 | 13.75 | Yes, about 1 d slack |
| flutter | 4 | 4.4 | Yes, about 10 d free |
| web-shop | 4 | 4.4 | Yes, about 10 d free |
| backoffice | 8.5 | 9.35 | Yes |

Verdict: 3 weeks holds **because installments move to Lot 5** (Q51) and the real providers stay out (BE-27, BE-7, BE-15 are not counted). Live use beyond club guichets depends on contracts, not build time.

If late, cut in this order: (1) the simulated network in BE-24 (keep wallet; the guichet carries the cash demo, and the callback contract waits for BE-27); (2) refunds (keep block/unblock; refunds stay manual through the Lot 1 B3 list); (3) the cash CSV (the B8 page stays); (4) ticket lookup by phone or code (keep ticket number); (5) the B3 methods table.

Never cut: signature check before any state change; idempotency (callbacks per code + unique network ref, collect per session, `sale_id`); exact-amount rule; payable check under the payment and order locks; expiry releasing places exactly once; `expected_mad` frozen at close; codes never reused; CASHIER confined to POS; tenant scoping on code lookup; centimes end to end; nothing on the scanner path.

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Code guessing | `SecureRandom`, 6 Crockford symbols, unique index, collision redraw; normalisation exactly per §Cash codes. Lookup is organizer-scoped (org B → 404) and reveals only event, amount, places and labels; the fake terminal shows title + amount only. A guessed code only lets someone pay for someone else's places (no ticket, no PII). |
| Forged or replayed confirmations | Network callback verified before any read or write (constant-time HMAC in the fake); fake network and fake wallet impossible under `prod`; unique `(provider, provider_ref)` with a clean refusal + `payment.duplicate_ref` (no 500); a second identical callback is acknowledged with no effect. |
| Double settlement | One payment row per code, locked before the order (lock order payment → order → buyer lock → categories, as in Lot 0). Guichet + network, card + cash, two guichets: exactly one PAID; every other money-in path writes `payment.refund_required` and never silently acks a collected amount. Collect refuses (409) before cash is kept; the BO copy says to give the money back. |
| Expiry and holds | AWAITING_CASH expiry flips the status and releases in the same transaction, once; `reserved_until` is extended only under the order lock; the code payment stays PENDING (late success path); cap and min window use the DB clock; P38 skip only for `redirected_at` attempts. |
| Cashier fraud | CASHIER is limited to `/org/pos/**` + two event reads (interceptor test per role); a cashier can't refund, block, void, close another's till or set a price (totals from snapshots on the server). `sale_id` replay limited to the same session. Every sale, collect, print and close is audited with the session; prints counted; `expected_mad` computed by the server and frozen; declared and variance audited. |
| Money | `bigint` / `long` centimes everywhere: `amount_mad`, `declared_mad`, `expected_mad`, `variance_mad`, `refunds.amount_mad`. No float in Java, TS or Dart; DH↔centimes by integer maths in the CSV, BO inputs, change calculator, W4 and the app. Exact-amount equality, no rounding. |
| Tickets immutable | Block, unblock and refund change `status` only (plus the refunds row); no other ticket column is updated; place release goes through `Inventory`. Refund freeze at `refund_cutoff_at`, enforced on the server. |
| QR / PII | Slips carry the v1 static token from `TicketToken` inside `withEventKey` (same as the PDF): no name in the token, holder label only on paper, `no-store`, no K_e or K_t in any response. Holder-less POS tickets never enqueue a delivery. |
| Tenancy | `pos_sessions`, `refunds` and payments queries filter `organizer_id`; codes are global but lookup and collect are scoped; reports per organizer. |
| Contract | Every new or changed 04 row has a contract test; error codes and `details.reason` exactly as 04; V13–V15 names = 03; `payment_status` rule for cash; `/redirect` sets `redirected_at` once and is still unauthenticated and harmless. |
| Clients | No hard-coded strings; fans "tu", organizers "vous"; no WhatsApp button for codes; "pay again" never offered while a CARD/WALLET attempt may still be confirmed; the print view has `@page 80mm` and no full names; query keys include the organizer. |

## 7. Carried forward

| Item | Lot | Note |
| --- | --- | --- |
| AWAITING_CASH in sales figures (lot-1 §7) | 3 | `awaiting_cash` in `/sales` (BE-26) |
| `/pay` answer after the hold (lot-1 §7) | 3 | P38, `redirected_at` (BE-22) |
| Block / unblock / refund (lot-2 Q39) | 3 | BE-25, Q52 |
| `sold` once refunds exist (lot-2 §7) | 3 | Decided: VALID only (P44) |
| Duplicate `provider_ref` on a signed callback (lot-0 §7) | 3 (cash) / BE-7 (card) | Cash: BE-24. Card: with Payzone |
| Payzone adapter | BE-7 | Kit; covers WALLET too if Payzone takes wallets |
| Real cash network adapter | BE-27 | Q44 contract + spec |
| Wallet provider other than Payzone | when chosen | Q47; split the gateway then |
| Messaging provider; automatic pay-code message | BE-15 / 4 | Q2, Q53 |
| Installments | 5 | Q51, with `passes` |
| `cash_points` editor in the back-office | 5 | Q54, SQL until then |
| Network statement import, bank deposits, settlements, VAT invoices | 5 | Q50, finance module |
| Whole-event cancellation (order REFUNDED) | 5 | Q52 |
| Refunded places counting in `per_account_limit` | 5 | Q52 `ponytail:` |
| Gateway refund API | BE-7 | Unknown until the kit |
| Sunmi built-in printers / raw ESC/POS | 6 | Q48 |
| Card terminal at the till | post-V1 | Q49 |
| `GATES_OPEN` transition (lot-2 Q38) | 6 or drop | Q56 |
| Fan cancel of an AWAITING_CASH order | when asked | Q55 |
| Deploy env added in Lot 3 | Q24 | `PAYMENTS_CASH_NETWORKS` (staging `fake`; `fake` refused under prod), `PAYMENTS_WALLET_PROVIDER` (staging `fake`), optional `PAYMENTS_CASH_MIN_WINDOW` (`2h`), `PAYMENTS_CASH_MAX_HELD_PERCENT` (`20`); the fake network reuses `PAYMENTS_FAKE_SECRET` |

