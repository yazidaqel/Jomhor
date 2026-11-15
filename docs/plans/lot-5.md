# Lot 5 · Organizer platform: implementation plan

As of 2026-11-13 · owner: tech-lead · status: **agreed 2026-11-13** by the founder, with three amendments: no dependency on AWS or any hosting provider for now; Lot 5 stays one lot (steps 1–5); the capacity test is deferred (step 1 is proven locally and in CI). §4 as amended here is applied to `docs/` and `design/`. V22 is the latest migration (`V22__campaigns.sql`), so Lot 5 starts at V23. Next ids (after step 2, §10): BE-51, FL-29, WS-15, BO-22, Q184, P129; next migration V25 (step 2 takes V25–V28). Only step 1 is detailed here; steps 2 to 5 are an outline (§9).

## 1. Lot

**Lot 5 · Organizer platform, step 1: the high-demand on-sale.** Branch `feat/lot-5-onsale` from `main` (Lot 4 merged in #16).

Scope (`00-product.md:55`, `02:253`):

- a ranked virtual waiting room. Early arrivals are drawn in random order, then it is first come, first served. Fans see their position and wait. An admission token is required to reserve;
- admission control before the database;
- the popular-zone hot spot;
- bot and per-device limits;
- verified-fan access per event;
- a multi-instance backend;
- derby capacity targets (§5.4), proven by a two-instance correctness test in CI (§5.5); the local and full runs are deferred (Q157, founder 2026-11-15).

Carried in from earlier plans:

- the ranked queue, verified fans and holder caps (lot-1 Q18, Q19);
- the `GateLoad` per-instance memo note (lot-4 §7);
- the "Liste d'attente" mention (DESIGN screen 1). Its waitlist moves to step 2.

Not in step 1:

- presale phases and members-only prices (step 2);
- passes, paid membership and installments (step 3);
- accreditations and promo codes (step 4);
- finance and the CRM export (step 5);
- the full launch-scale load test on the production setup (Lot 6);
- the §5.4 capacity run on a production-class setup (deferred until hosting is chosen, Q159);
- any hosting-provider dependency: no CDN, WAF, managed load balancer, managed database or secret store is assumed. A CDN/WAF (rate rules, Bot Control, CAPTCHA) can go in front later with no API change (Lot 6, Q147).

**No new runtime component.** The room lives in Postgres and in signed tokens (Q139). Kafka stays out (Q161). Payzone stays simulated. The admission rate is a live knob, sized from the Payzone limits once the kit arrives (Q142).

**The core idea.** Polls never touch the database:

- The queue token is a signed JWT. It carries the fan's rank once the rank is known.
- Each instance reads every live room's state once a second into a memo.
- So `GET /fan/queue` costs one HMAC check and one map lookup.
- `POST /fan/orders` rejects a missing, foreign or expired admission token by signature, before any repository call.

Postgres sees only these:

- joins (one row per account);
- one rank write per room per second;
- one primary-key read per fan after ranking;
- the orders of admitted fans, bounded by the admission rate.

Exit ("the club runs a derby on-sale alone"):

| Level | Proof |
| --- | --- |
| Demo-complete (step 1 done) | Locally, provider-free: one Postgres and **2 API instances** (`backend/compose.yaml` profile `smoke`, ports 8081/8082); the app and the shop use `api-1`. **(1) Set-up:** In B13, OPS configures a room for a test derby: pre-queue 30 min, 500 fans/min, "Fans vérifiés" with account verified ≥ 7 days before the sale. W2 and screen 2 show "File d'attente dès 15:30". **(2) Pre-queue:** At 15:30, Yazid joins from the app. Karim is a cold visitor from a WhatsApp link on the shop: W2 → "Rejoindre la file" → OTP login → W8. Both see "Tirage au sort à 16:00" and a rolling "{n} fans dans la file". A 2-day-old account gets "Vente réservée aux fans vérifiés · compte vérifié avant le 6 nov." A 4th account on one phone gets the device limit. **(3) Draw:** At 16:00, every pre-queue entry is ranked at random, and positions appear within seconds. Joins after 16:00 queue behind them, in arrival order. **(4) Admission:** Positions fall at about 500/min. Yazid's 12-dot row fills. At his turn, the amber twelfth swells, a haptic fires, and "C'est ton tour · 10:00" appears. He buys with the token. **(5) Guard:** A `POST /fan/orders` without a token gets 409 `ADMISSION_REQUIRED`, and the test shows no SQL statement ran beyond the session check. **(6) Controls:** OPS pauses (positions freeze, "en pause"), resumes, and moves the rate to 800. `api-2` is killed mid-sale: the room keeps ticking on `api-1`, and no order is lost. A turn left unused for 10 min → "Ton tour est passé" → "Reprendre une place" puts the fan at the back. OPS ends the room: orders go through without a token. **(7) Correctness:** `check.sql` finds no oversell, no double charge and no limit breach (the local capacity smoke was dropped 2026-11-15). CI is green on `Lot5OnSaleFlowTest`, `MultiInstanceTest`, `OnSaleTwoInstanceTest`, `apps/fan/test/queue_test.dart`, shop `queue.test.tsx` and back-office `room.test.tsx`. |
| Live | The same in prod, gated by five things. **Hosting** chosen and the deferred §5.4 capacity run passed on it, with the in-app per-IP limits checked against the instance count (Q147, Q159). The **CNDP** declaration covering on-sale anti-fraud processing: device-id and IP hashes, account age, attendance (Q144, Q146, Q147). The **Payzone** rate limit, which sets the admission ceiling (Q142). The **runbook**: raise or lower the rate, pause, end. Until Payzone is confirmed, prod rooms run at ≤ 300/min. |

## 2. What exists, gaps, spec refs

### 2.1 What step 1 builds on

| Area | State on `main` (9612017) | Step 1 use |
| --- | --- | --- |
| Sales checks | `OrderService.place`: event (404) → window → items/holders (422) → `SALE_BUSY` → buyer advisory locks `pg_advisory_xact_lock(hashtext(user), event)` → per-account limit (P83 counts unpaid split members) → `Inventory.reserve` → order insert | Admission becomes the **first** check (memo + signature, no DB). The rest is unchanged. `SALE_BUSY` stays as a safety net |
| Busy throttle | `JdbcOrders.activeHolds`: a `count(*)` of RESERVED holding orders on every POST, no lock (`ponytail:` "ranked queue is Lot 5") | Reused by the room tick: it admits only while holds are under 90 % of `sales.max-active-holds-per-event` |
| Quota | `JdbcEvents.take`: `update price_categories set sold = sold + :qty where … and sold + :qty <= quota`, categories in UUID order. The row lock is held until the place transaction commits. Expiry releases one update per category per batch | Hot row: measured, not rebuilt (Q151) |
| Jobs | `OrderExpiryJob` 30 s (skip locked). `TicketDeliveryJob` 10 s (skip locked + advisory). `FanMessageJob` 5 s (skip locked, rate cap **per run**). `CampaignJob` 30 s (skip locked). `ManifestBuildJob` 1 min (`pg_try_advisory_xact_lock`) | All are multi-instance safe except the fan-message rate (P105) |
| In-memory state | `GateLoad` memo (`ConcurrentHashMap`, 15 s, per instance). `FakeGatewayController` late callback on the local `TaskScheduler` (staging only). `otpSendExecutor` queue (4 threads, 1,000 per instance; full = code dropped) | Listed in §4.5 (01 §Deployment) with what changes |
| Fan auth | Fan JWT + one `fan_sessions` exists query per authenticated request. OTP: per phone 3/10 min, 10/24 h; per IP 20/10 min, 200/24 h | Polls take no JWT (queue token only). Per-IP defaults raised (Q148) |
| Users | `users.created_at` is set when a buyer types a holder's phone (`UserService.holder`), so it is not an account age | `users.verified_at` (P108) |
| Loyalty / membership | `crm` `Memberships` (joined_at, left_at), `events_attended` computed on read | Eligibility rules (Q144) |
| Ops | No health endpoint, no graceful shutdown, no CORS, Hikari default pool (10) | BE-43, BE-42 |
| Shop | Event detail cached 15 s (`DETAIL_REVALIDATE_S`), lists 60 s. Route handlers forward the cookie token as Bearer and one `X-Forwarded-For`. `CheckoutForm` retries `SALE_BUSY` | W2 room panel, W8, direct poll (Q152) |
| Fan app | `checkout_controller.dart` retries `SALE_BUSY`. `wakelock_plus`, `SharedPreferencesAsync`, `ui/motion.dart` present | Screen 12 |
| Back-office | `features/events` (B2), `features/sales` (B3). shadcn and TanStack Query; no chart library | B13 |
| Design | No waiting-room screen. "File d'attente" = the Lot 1 retry chip. Screen 4 "CIN vérifiée" badge (DESIGN:117) | Screens 12, W8, B13; badge replaced (P109) |

### 2.2 What does not exist

| Need | Plan |
| --- | --- |
| Room config, org endpoints, `room` on fan events, `users.verified_at` | BE-40, V23 |
| Join, poll, tokens, room tick (draw, ranking, admission), retention purge | BE-41, V24 |
| Admission enforcement, eligibility of split `pays` members, POS bypass, CORS on the poll | BE-42 |
| Multi-instance fixes and proof, health, graceful shutdown, pool size | BE-43 |
| Exit flow test | BE-46 |
| Per-IP join limit, per-entry poll limit (in the app) | BE-41, BE-42 (Q147) |
| Load harness (seeder, k6 scripts, `check.sql`, local two-instance compose) | BE-44 |
| Two-instance correctness test (CI), local capacity smoke, tuning | BE-45 |
| Staging and the §5.4 run | Deferred (Q159) |

### 2.3 Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (no PII in QR or tokens, `organizer_id` on every table, centimes, gate never calls the server, strings never hard-coded), Stack (Kafka only for a real async boundary; AWS eu-west-3 is the stated infra, but step 1 takes no dependency on it: founder 2026-11-13), Conventions |
| `docs/00-product.md` | §Why it can win ("Fairness" row), §Demand anchor (45,000), §V1 in lots (Lot 5 and Lot 6 rows) |
| `docs/01-architecture.md` | §Components (API, Kafka row), §Key flows 1 |
| `docs/02-gate-validation-and-data-model.md` | §Threat model ("Bots on on-sale day … separate spec", line 191), §Data model, lots table (line 253) |
| `docs/03-schema.sql` | tenant rule header, `users`, `fan_sessions`, `otp_challenges`, `events` (`sale_starts_at`, `refund_cutoff_at`, `per_account_limit`), `price_categories.sold`, `orders`, `order_items`, `memberships` |
| `docs/04-api.md` | Errors (`SALE_BUSY`, `PER_ACCOUNT_LIMIT`), §Fan (`/fan/auth/otp`, `/fan/events*`, `POST /fan/orders`, order rules), §Split pay Limits, §On-sale (basic queue), §Payments (late success), §Organizer (roles line, events, `/sales`), §Point of sale (no `SALE_BUSY`) |
| `docs/06-glossary.md` | order statuses, terms |
| `design/DESIGN.md` | Principles 4, 6, 7; screens 1, 2, 3, 4; W2, W3; B2 (Règles: "file d'attente"), B3; Lot 1 adjustments (screen 1 "File d'attente", screen 4 busy state); Lot 4 "wow" table; §Accessibility |
| `design/BRAND.md` | 12-dot idea (the amber twelfth completes the team), mark motion rules |
| Plans | lot-1 Q14, Q18, Q19, Q24; lot-3 Q46 (cash cap); lot-4 Q57, Q60, §7 (`/load` memo), §8 |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default for step 1 |
| --- | --- | --- |
| Q139 | Where does the waiting room live: Postgres, or Redis/ElastiCache? | **Postgres + signed tokens.** Polls don't read any store: rank is in the token, room state is in a 1 s memo per instance. So the store only sees joins, one rank write per room per second, and one primary-key read per fan after ranking. That is well within one mid-size Postgres 18 server. A Redis/Valkey store would add a second source of truth (a lost node loses the order), a component to run, patch and watch, a Testcontainers dependency, and a failover mode to test. That is a lot for a three-developer team. **Revisit only if the BE-45 smoke or the deferred §5.4 run shows joins under target.** The upgrade path (`ponytail:` note in the room service): sorted set per room, same token contract. |
| Q140 | Does joining need a login? | **Yes: phone OTP session, one entry per account per room.** Without login, bots could take thousands of positions for free. App fans hold 30-day sessions already. The shop tells cold visitors "Connecte-toi avant {heure}" from `opens_at`. Risk: an OTP surge at the pre-queue, covered by Q148 and §8. |
| Q141 | Draw window | **Pre-queue opens `pre_queue_min` (default 30) before `sale_starts_at`.** Every entry that joins before `sale_starts_at` is ranked by a CSPRNG draw at `sale_starts_at`; later joins rank by arrival. So arriving at 15:30 or at 15:59:59 gives the same chance, and there is no stampede at T0. `0` = no draw (pure first come, first served). A join that commits while the draw runs ranks right after the drawn ones (documented). |
| Q142 | Admission rate and Payzone | **Default 500 fans/min**, live-editable from 1 to 6,000. The tick admits only while active holds stay under 90 % of `sales.max-active-holds-per-event` (2,000) and the event is not sold out. **Payzone:** about 70 % of admitted fans start a payment and about 1.2 hosted-page attempts are made per order, so payment sessions per second ≈ rate × 0.84 / 60. With P = Payzone's allowed new sessions per second and a 70 % safety margin, **rate ≤ 50 × P**. The default assumes P ≥ 10. Founder: ask Payzone for the max new hosted sessions/s per merchant, the 3DS/CMI ceiling, the callback retry policy, and whether the sandbox allows a load test. Until answered, prod rooms run at ≤ 300/min. At 500/min a 36,000-place derby sells in about 40 min (36,000 ÷ 2.6 places per order ÷ 350 orders/min). |
| Q143 | When does a turn start? | **At the first poll that sees `rank ≤ admitted_through`**, which sets `admitted_at`. The admission token is valid for `rooms.admission-window` (10 min, capped at sales close) and can be used for several orders inside it (`per_account_limit` caps them). A fan who closed the app keeps their turn until they come back. After the window: `EXPIRED`, and a new join goes to the back. |
| Q144 | What does "verified fan" mean in Morocco? | **Per-event eligibility, built from data we already hold. No new personal data.** A room may require any of these (ANDed; null = not required): (a) **account verified before `sale_starts_at − min_account_age_days`**, where `users.verified_at` = first fan OTP login (SIMs are ID-registered in Morocco, so an old verified number ≈ an identified person; farming phones right before a derby is useless); (b) **member of the club since before `member_joined_before`** (free membership, Lot 4); (c) **≥ `min_events_attended` events attended** at this organizer (the loyalty definition, all time). Fans see "Fan vérifié" when they pass the rule. Back-office preset "Fans vérifiés" = (a) with 7 days. Checked at join (fans who fail never take a place) and for split `pays` members at place time (Q149). **CNDP:** account age and attendance are existing purchase-relationship data used for fraud prevention. Founder: add "on-sale anti-fraud" to the declaration. |
| Q145 | CIN as a verification factor? | **No CIN is collected in V1.** National ID numbers likely need a CNDP **authorisation**, not just a declaration. No public registry API exists (the DGSN digital identity service is for licensed partners), and a typed CIN proves nothing. If the league requires ID checks (open club question), stewards compare the physical CIN with the ticket's holder name at the gate. Revisit post-V1 with a licensed verification provider. DESIGN's "CIN vérifiée" badge is dropped (P109). |
| Q146 | Per-device limit | **3 accounts per device per room** (`rooms.device-limit`), soft (no lock). `device_id` = a random install id: app → generated once in `SharedPreferencesAsync`; shop → cookie `__Host-jdid` (HttpOnly, Secure, SameSite=Lax, Path=/, 1 year; the `__Host-` prefix stops a sibling subdomain planting one). Stored only as HMAC-SHA256(rooms secret, id). This is friction against one-phone account farms, not a guarantee: a reinstall resets it. Purged with the entries (Q155). |
| Q147 | Per-IP limits | **Decided 2026-11-13 by the founder: in the app, no provider.** Each limit sits where its state already lives, so it holds with N instances without Redis. **(a) OTP sends:** already counted in Postgres (`otp_challenges.ip`, index `(ip, created_at desc)`); only the fan defaults move (Q148). Verify needs no IP limit (5 attempts per code, per-phone lockout). **(b) Joins:** a `POST /fan/queue` that creates an entry or moves an expired one back counts the room's entries with the same `ip_hash` and `joined_at` in the last 5 min; ≥ `rooms.ip-limit-5m` (300) → 429 `QUEUE_RATE_LIMITED` `IP`. `ip_hash` = HMAC-SHA256(rooms secret, "ip\|" + client IP from the trusted-proxy chain), purged with the entry. Same query shape as the device limit, no new table, soft (racing joins can overshoot by those in flight). Idempotent re-joins don't count. 300 per 5 min is 3,600 per IP over a 30-min pre-queue: generous for CGNAT; OTP, accounts and eligibility do the real work. **(c) Polls** stay off the database, so their limit is in memory per instance and keyed by the token's entry id, not the IP (CGNAT-proof; a valid token needs a join): a poll less than `rooms.poll-min-interval` (1 s) after the same entry's last poll on that instance → 429 `QUEUE_RATE_LIMITED` `POLL`. Checked after the signature (bad tokens never touch the map); entries idle > 60 s are swept by the tick. Documented ceiling (`ponytail:`): with N instances one entry can poll N times a second, harmless because a ranked poll is one HMAC and one map read. **No catch-all per-IP limit** on other paths: every costly path is already account-scoped (admission token, `SALE_BUSY`, per-account limit). A CDN/WAF can go in front later without an API change (its addresses join `TRUSTED_PROXIES_REGEX`): Lot 6, if measured. |
| Q148 | OTP per-IP limits vs CGNAT on on-sale day | **Fan per-IP defaults → 60 per 10 min, 600 per 24 h** (config; org unchanged). Per-phone limits (3/10 min, 10/24 h, lockout) stay the real guard. Account age (Q144) makes fresh numbers worthless in verified rooms. Also: the OTP send pool size and queue are measured in BE-45; a dropped code logs a WARN with a counter. |
| Q149 | Split pay in a room event | The admission token is the buyer's only. Members pay their member order with `/pay` (no token). In a room with an eligibility rule, each `pays` member must pass it at place time (409 `NOT_ELIGIBLE` + `details.field`). Holders without `pays` are not checked: they are inside the buyer's limit (P83). |
| Q150 | Holder cap (lot-1 Q18) | **Not in step 1.** Tickets are named, every transfer hop is audited, and eligibility plus one entry per account cover the farm case. Revisit in step 2 with the club's ID-check answer. |
| Q151 | Popular-zone hot row | **No sharding in step 1. Measure it.** Every reserve of the busy zone serialises on one `price_categories` row for the rest of the place transaction (a few ms, plus a replicated commit in production). That gives a ceiling of very roughly 100–300 orders/s. A 500–1,000/min admission makes 6–12 orders/s. BE-45 measures it locally without the room (k6 `hot-row.js`, 100 concurrent buyers on one category). A laptop number is only indicative, so the rule is applied provisionally and re-checked in the deferred §5.4 run. **Rule: if the ceiling is < 5× the order rate at the target admission, build buckets.** Upgrade path (`ponytail:` in `Inventory`): `price_category_buckets(category_id, bucket, quota, sold)` with K = 8, a random bucket with room then the others, the bucket stored on the order item for release, and `sold` = the sum. |
| Q152 | How the shop polls | **The browser calls `GET {API_PUBLIC_URL}/fan/queue` directly**, with `X-Queue-Token`, no credentials, and CORS for `SHOP_ORIGIN` only. Next.js stays off the hottest path (several thousand req/s). The join goes through a route handler (session cookie). The admission token comes back to the page and is passed to `/api/orders`. It is useless without the httpOnly session, because it is bound to the user. `API_PUBLIC_URL` reaches the client as a server-component prop, so no new public env. |
| Q153 | Push "C'est ton tour"? | **No.** The turn waits for the fan (Q143). |
| Q154 | When does a room end? | Manually ("Terminer la file", irreversible), or automatically at `refund_cutoff_at`. Sold out does **not** end it: holds can free places, so fans keep their order with honest copy ("Tout est réservé pour l'instant · des places peuvent se libérer"). An empty room admits a new joiner within a few seconds. |
| Q155 | Retention of queue data | `queue_entries` deleted 30 days after the event's `starts_at` (`rooms.retention`, daily in the tick job). Rooms are kept, for counts and audit. |
| Q156 | Several rooms per event (members presale, then general sale) | **Step 2.** Step 1 has `unique (event_id)`; step 2 drops it and adds phases. |
| Q157 | Derby capacity targets (separate from the Lot 6 launch-scale test) | Table §5.4, kept as the target of the deferred run. **Decided 2026-11-13 by the founder: the capacity test is deferred** (no staging, no cloud runners). Step 1 is proven by §5.5: a two-instance correctness test in CI. **2026-11-15:** the local capacity run is dropped too; the harness (BE-44) waits for the deferred run. |
| Q158 | Load tool and accounts | **Decided 2026-11-13 by the founder: local only, no cloud runners.** **k6** (open source, a local binary, not a provider) with arrival-rate executors (tokens in a `SharedArray`), on the same machine as the system under test, round-robin over `BASE_URLS` (no load balancer). Scale comes from env (`FANS`, `POLL_RPS`, `BASE_URLS`), so the same scripts drive the deferred §5.4 run. Accounts come from a `loadtest`-profile seeder. Phones are in the unassigned `+999…` range, so no message can reach a real person. It is refused under `prod` or with a non-fake card provider. Fake gateway callbacks are signed by the k6 scripts. |
| Q159 | Who builds staging with ≥ 3 instances? | **Decided 2026-11-13 by the founder: no staging for now; deferred until a hosting choice** (Lot 6 at the latest). Step 1 needs none: §5.5 runs on one machine. What the deferred run will need, provider-neutral: ≥ 3 API instances behind a load balancer (health `/v1/actuator/health/readiness`, 30 s drain), Postgres 18 of the production class with a synchronous standby, 2 shop instances, a load generator near the region, secrets from the host's secret store. Built by the founder/orchestrator (lot-1 Q24). |
| Q160 | Mockups before building screen 12? | **No blocking round.** §4.7 + golden screenshots (FR + AR). Founder wow review at day 7 on a local build (app on a phone against the dev backend, shop on localhost; screen 12, W8). |
| Q161 | Kafka? | **None.** No async boundary appears: joins and orders are synchronous, ranking is a 1 s tick. Revisit only if the Lot 6 load test asks for it. |
| Q162 | POS during a room | **Bypass:** club tills sell without a token (they had no `SALE_BUSY` either). The cash cap still applies. |
| Q163 | What a fan in the room sees about stock | `sold_out` and the existing public `remaining` per zone (screen 2 / W2 data). The room shows "Il reste {n} places" (Σ remaining from the event detail, cached ≤ 15 s). |
| Q164 | Admission token in the header or the body? | **Header `X-Admission-Token`.** The `POST /fan/orders` body is unchanged, and the shop handler forwards one header. |
| Q165 | What drives the wait estimate? | The **measured** admission rate over the last full minute (`measured_rate_per_min`). Paused or 0 → no estimate ("en pause"). Rounded honestly: "moins d'une minute", "environ {n} min", above 60 min "plus d'une heure". |
| Q166 | Lot 5 duration | **Decided 2026-11-13 by the founder: one lot, in steps 1–5** (no 5a/5b), step 1 detailed, steps 2 to 5 outlined (§9). No estimates (founder 2026-11-14). `00-product.md` Lot 5 row updated (P115, §4.5). |

## 4. Contract changes

### 4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P101 | 04 §On-sale, `OrderService.place` | The only admission control is `SALE_BUSY`, a DB count on every POST. Under a derby every fan hits the database, and retries amplify it | §Waiting room; admission token checked first, from memory |
| P102 | 04 POST `/fan/orders` | The check order has no admission step | Admission is first, before the event read |
| P103 | — | A ranked queue needs state shared across instances; nothing exists | `waiting_rooms`, `queue_entries`, signed tokens, a 1 s tick |
| P104 | `JdbcEvents.take` | Every reserve of a zone serialises on one row; the ceiling was never measured | Q151, BE-45 measures |
| P105 | `FanMessageJob` | The send-rate cap is per run per instance: N instances = N× the provider rate | One claim per tick under `pg_try_advisory_xact_lock` (class 6) |
| P106 | `GateLoad` `ponytail:` ("shared cache at Lot 6"), lot-4 §7 | Per instance is correct (04 already says "may be 15 s old"); N instances = N computations per 15 s | Keep; rewrite the note |
| P107 | backend | No health endpoint, no graceful shutdown: no load balancer or process manager can check or drain an instance | Actuator health only + `server.shutdown=graceful` |
| P108 | 03 `users.created_at` | It is set when a buyer types a holder's phone, so it can't measure account age | `users.verified_at` (first fan OTP verify), backfilled from `fan_sessions` |
| P109 | DESIGN:117 | "CIN vérifiée" badge, but no CIN is collected (Q145) | "Fan vérifié" chip on screen 12 only |
| P110 | 04 `/fan/auth/otp`, `application.yml` | Per-IP 20/10 min blocks CGNAT crowds on on-sale day | 60/10 min, 600/24 h (Q148) |
| P111 | shop `DETAIL_REVALIDATE_S` = 15 | Room status on W2 can be 15 s stale at `opens_at` | The client flips on `opens_at`; W8 polls the API directly |
| P112 | 02:191 | "Bots on on-sale day … Separate spec" | Threat-model row → 04 §Waiting room |
| P113 | `otpSendExecutor` | The per-instance queue (1,000) drops codes silently when full | WARN + counter; sized in BE-45 |
| P114 | 01 | Silent on instance count and on what coordinates instances | New §Deployment |
| P115 | 00:55 | Lot 5 = 4 wk can't hold step 1 + the rest | ≈ 10 wk in steps 1–5, one lot (Q166, §4.5) |
| P116 | Q147 | The per-IP limits lived in AWS WAF; the founder wants no provider dependency | In the app: OTP sends (Postgres, exists), joins (`queue_entries.ip_hash`, Postgres), polls (per entry, memory); error `QUEUE_RATE_LIMITED` |
| — | Football names / floats / PII in QR or tokens / server on the gate path | No football names (`waiting_rooms`, `queue_entries`; "derby" appears only in plan prose and test data). No new money column; rates and counts are integers. Tokens carry an entry id, the user id (the fan JWT `sub` already), the event id, the rank and the expiry: no phone, no name, no device id. The gate module, the scanner and the manifest are untouched | OK |

### 4.2 `docs/03-schema.sql`

```sql
-- ---------- tenancy: users (add) ----------
  verified_at   timestamptz   -- first successful fan OTP login (POST /fan/auth/verify); null = never logged in (e.g. a holder
                              -- typed by a buyer). Account age for on-sale eligibility (docs/04-api.md §Waiting room)

-- ---------- sales: waiting rooms (new) ----------
create type room_status as enum ('SCHEDULED','PRE_QUEUE','OPEN','PAUSED','ENDED');

create table waiting_rooms (                          -- high-demand on-sale (docs/04-api.md §Waiting room); one per event in Lot 5 step 1
  id                      uuid primary key default gen_random_uuid(),
  organizer_id            uuid not null references organizers(id),
  event_id                integer not null unique references events(id),
  status                  room_status not null default 'SCHEDULED',
  pre_queue_min           smallint not null default 30 check (pre_queue_min between 0 and 1440),
                          -- joins open at events.sale_starts_at - pre_queue_min; joins before sale_starts_at are drawn at random
  admission_rate_per_min  integer not null default 500 check (admission_rate_per_min between 1 and 6000),
  min_account_age_days    smallint check (min_account_age_days between 1 and 365),  -- eligibility, each null = not required:
  member_joined_before    timestamptz,                                              --   verified_at <= sale_starts_at - n days;
  min_events_attended     smallint check (min_events_attended between 1 and 50),    --   member since before; events attended
  joined_count            integer not null default 0,     -- written by the room tick
  ranked_count            integer not null default 0,     -- entries with a rank: ranks are 1..ranked_count
  admitted_through        integer not null default 0,     -- ranks <= this are admitted
  measured_rate_per_min   integer not null default 0,     -- admitted over the last full minute
  rate_mark_at            timestamptz,
  rate_mark_through       integer not null default 0,
  sold_out                boolean not null default false, -- every category remaining = 0 at the last tick
  last_tick_at            timestamptz,
  drawn_at                timestamptz,
  created_at              timestamptz not null default now(),
  constraint waiting_rooms_admitted check (admitted_through <= ranked_count)
);
create index on waiting_rooms (status) where status <> 'ENDED';

create table queue_entries (                          -- one per account per room; sub of the queue token
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  room_id       uuid not null references waiting_rooms(id),
  user_id       uuid not null references users(id),
  device_hash   bytea not null check (length(device_hash) = 32),  -- HMAC-SHA256(rooms secret, device_id); never the raw id
  ip_hash       bytea not null check (length(ip_hash) = 32),      -- HMAC-SHA256(rooms secret, "ip|" + client IP via trusted proxies); never the raw IP
  draw_key      bigint not null,                      -- 8 CSPRNG bytes drawn at join; orders the draw; never returned
  joined_at     timestamptz not null default now(),   -- a rejoin after an expired turn resets it (back of the queue)
  rank          integer check (rank > 0),             -- dense per room, set by the room tick; null until ranked
  admitted_at   timestamptz,                          -- first poll that saw rank <= admitted_through; the turn ends 10 min later
  unique (room_id, user_id),
  unique (room_id, rank)
);
create index on queue_entries (room_id, joined_at) where rank is null;
create index on queue_entries (room_id, device_hash);
create index on queue_entries (room_id, ip_hash, joined_at);   -- per-IP join limit (docs/04-api.md §Waiting room)
-- Rows are deleted 30 days after the event's starts_at (rooms.retention).
```

Flyway, one migration per task:

| File | Task | Content |
| --- | --- | --- |
| `V23__waiting_rooms.sql` | BE-40 | `users.verified_at` + backfill `update users u set verified_at = (select min(created_at) from fan_sessions s where s.user_id = u.id)`; `room_status`; `waiting_rooms` + index |
| `V24__queue_entries.sql` | BE-41 | `queue_entries` + three indexes |

The tenant allowlist stays at 5: both new tables carry `organizer_id`. The `02` §Data model table gains the two tables and `users.verified_at` in the same docs change.

### 4.3 `docs/04-api.md`

**Errors: add or replace** (header: "Lot 0 to Lot 5")

| Code | HTTP | When |
| --- | --- | --- |
| `ADMISSION_REQUIRED` | 409 | `POST /fan/orders` on an event whose room is `PRE_QUEUE`, `OPEN` or `PAUSED`, without a valid `X-Admission-Token`. `details.reason`: `MISSING`, `INVALID` (bad signature, another user or another event), `EXPIRED`. Clients go to the room |
| `QUEUE_NOT_OPEN` | 409 | Join refused. `details.reason`: `NO_ROOM`, `NOT_STARTED` (+ `details.opens_at`), `ENDED` (buy without the room), `CLOSED` (sales closed) |
| `NOT_ELIGIBLE` | 409 | Join, or a split `pays` member, fails the room's rule. `details.reason`: `ACCOUNT_TOO_NEW` (+ `details.verified_before`), `NOT_MEMBER` (+ `details.member_joined_before`), `NOT_ENOUGH_EVENTS` (+ `details.min_events_attended`, `details.attended`); for a member, + `details.field` (the holder path) |
| `QUEUE_DEVICE_LIMIT` | 409 | This device already holds `details.limit` entries of other accounts in the room |
| `QUEUE_RATE_LIMITED` | 429 | `details.reason`: `IP` (a join that would create or move back an entry: too many from this IP in the room in 5 min) or `POLL` (the same entry polled again within 1 s); `details.retry_after_s` (int). Wait that long, then retry; a poller then resumes its `poll_after_s` rhythm |
| `ROOM_STATUS` | 409 | Room PUT changing anything but `admission_rate_per_min` once the room has left `SCHEDULED`, or pause/resume/end in the wrong status; `details.status` |
| `EVENT_NOT_EDITABLE` | 409 | **Lot 5:** + `details.reason` `ROOM_STARTED`: event PATCH moving `sale_starts_at` while its room is past `SCHEDULED` and not `ENDED` |
| `SALE_BUSY` | 429 | Unchanged. With a room it remains a safety net for admitted fans |

**§Fan auth: replace in the `/fan/auth/otp` row:** "Per IP (resolved through trusted proxies only): 60 sends per 10 min, 600 per 24 h." **In the `/fan/auth/verify` row, append:** "The first successful fan verify of a user sets `users.verified_at`."

**§Fan: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/fan/events`, `/fan/events/{id}` | **Lot 5:** each event + `room: {status: SCHEDULED\|PRE_QUEUE\|OPEN\|PAUSED, opens_at, eligibility: {verified_before, member_joined_before, min_events_attended} \| null} \| null`. Null when the event has no room or its room is `ENDED`. `opens_at` = `sale_starts_at − pre_queue_min`. `verified_before` = `sale_starts_at − min_account_age_days` (RFC 3339, or null). `eligibility` is null when no rule is set. |
| POST | `/fan/queue` | `{event_id, device_id}`. `device_id` = 16–64 chars `[A-Za-z0-9_-]` (422). Checks in order: event visible as in `GET /fan/events/{id}` (404); room `PRE_QUEUE`, `OPEN` or `PAUSED`, and sales not closed (409 `QUEUE_NOT_OPEN`); eligibility (409 `NOT_ELIGIBLE`); device limit (409 `QUEUE_DEVICE_LIMIT`); per-IP limit, only when an entry is created or moved back (429 `QUEUE_RATE_LIMITED` `IP`). The caller's entry already exists → `200` with it and a fresh queue token, unless its turn has `EXPIRED`: then it is moved to the back (`rank` and `admitted_at` cleared, `joined_at = now()`) → `201`. New → `201`. → the `GET /fan/queue` shape. |
| GET | `/fan/queue` | Header `X-Queue-Token`; send no `Authorization` header (ignored). Missing, bad or expired token, or a purged entry → 401 `UNAUTHORIZED`. The same entry polled again within `rooms.poll-min-interval` (1 s) on one instance → 429 `QUEUE_RATE_LIMITED` `POLL` (no database read). CORS: allowed from `SHOP_ORIGIN`, no credentials. → `{event_id, status, sale_starts_at, position, in_room, rate_per_min, estimated_wait_s, sold_out, admission: {token, expires_at} \| null, queue_token, poll_after_s, server_time}`. `status`: `PRE_QUEUE` (before the draw; position null), `WAITING` (position null until ranked), `ADMITTED`, `EXPIRED`, `PAUSED`, `ENDED`. `position` = `rank − admitted_through` (≥ 1) while waiting. `in_room` = `ranked_count − admitted_through`, or `joined_count` before the draw. `rate_per_min` = `measured_rate_per_min` (0 when paused). `estimated_wait_s` = ⌈position × 60 ÷ rate_per_min⌉, or null when the rate is 0 or the position is null. `sold_out` per §Waiting room. `admission` only while `ADMITTED`. `queue_token` = refreshed (it carries the rank once known). `poll_after_s` per §Waiting room. `Cache-Control: no-store`. |
| POST | `/fan/orders` | Lot 4 shape. **Lot 5:** "Checks in order: **admission** (only when the event's room is `PRE_QUEUE`, `OPEN` or `PAUSED`: header `X-Admission-Token`, else 409 `ADMISSION_REQUIRED`; decided from the per-instance room memo and the token's signature, before any database read of the request), event (404), sale window (409 `EVENT_NOT_ON_SALE`), items and holders (422), queue (429 `SALE_BUSY`), per-account limit (409), **room eligibility of split `pays` members (409 `NOT_ELIGIBLE`, `details.field`)**, quota (409 `SOLD_OUT`)." The token may be used for several orders until its `expires_at`. |

**§Fan order rules: append.** "Lot 5: with an active room the admission token is the buyer's. Split `pays` members pay their member order without a token but must meet the room's eligibility when the order is placed. POS sales (`/org/pos/orders`) never need a token."

**§On-sale (basic queue): rename to §On-sale and replace the Clients row.** "Clients: without a room, show 'File d'attente' with the retry countdown and retry automatically, with no position. With a room (§Waiting room), the position comes from `GET /fan/queue`. `SALE_BUSY` can still reach an admitted fan as a safety net: retry with the same token."

**New §Waiting room**

| Rule | Value |
| --- | --- |
| Lifecycle | `SCHEDULED` → `PRE_QUEUE` at `sale_starts_at − pre_queue_min` → `OPEN` when the draw commits at `sale_starts_at` (with `pre_queue_min = 0`, straight to `OPEN`) ⇄ `PAUSED` (organizer) → `ENDED` (organizer, or automatically at `refund_cutoff_at`; terminal). Joins are accepted in `PRE_QUEUE`, `OPEN` and `PAUSED`. Admission is required while `PRE_QUEUE`, `OPEN` or `PAUSED`. |
| Tick | Every second (`rooms.tick`), each instance takes the live rooms with `for no key update skip locked`, so one instance handles a room per tick (joins insert with the FK's `for key share`, which that lock doesn't block). In one transaction per room: the status transition; **draw** (once): `rank = row_number() over (order by draw_key, id)` over the unranked entries, `ranked_count`, `drawn_at`, `OPEN`, audit `room.draw` `{count}`; **ranking** of later joins: `rank = ranked_count + row_number() over (order by joined_at, id)`, at most 5,000 per tick; **admission** while `OPEN`, not `sold_out`, and active holds < 90 % of `sales.max-active-holds-per-event`: `admitted_through = least(ranked_count, admitted_through + floor(admission_rate_per_min × elapsed since last_tick_at ÷ 60 s))`; `joined_count`, `sold_out` (every price category `remaining = 0`), `measured_rate_per_min` (re-marked every 60 s), `last_tick_at`. Daily: delete entries 30 days after the event's `starts_at`. |
| Memo | Each instance reads the non-`ENDED` rooms once a second (`rooms.memo-ttl`, 1 s): event id → status, `sale_starts_at`, `ranked_count`, `admitted_through`, `joined_count`, `measured_rate_per_min`, `sold_out`. The poll and the admission check read only this memo. It may be 1 s behind the database. |
| Queue token | JWT HS256 with `ROOMS_TOKEN_SECRET` (≥ 32 bytes; separate from the fan JWT secret). Claims: `iss: jomhor`, `aud: queue`, `sub` = entry id, `evt` = event id, `rnk` = rank (absent until ranked), `exp` = `refund_cutoff_at`. A poll whose token has `rnk` reads nothing from the database. A token without `rnk` reads the entry once per poll until it is ranked, then gets a token with `rnk`. |
| Admission | A poll that sees `rnk ≤ admitted_through` sets `admitted_at = coalesce(admitted_at, now())` (one write per admitted fan). It returns `ADMITTED` with an admission token: `aud: admission`, `sub` = user id, `evt`, `ent` = entry id, `exp` = `least(admitted_at + rooms.admission-window (10 min), refund_cutoff_at)`. Past `exp` → `EXPIRED`. `POST /fan/orders` accepts it iff the signature, `aud`, `evt` = the body's `event_id`, `sub` = the caller and `exp > now` all hold. Fan JWTs and room tokens are never accepted for each other. No phone, name or device id in any token. |
| `poll_after_s` | `PRE_QUEUE`: seconds to `sale_starts_at` + a uniform 0–20 s jitter, capped at 30. `WAITING` unranked: 2. `WAITING` or `PAUSED` with `estimated_wait_s` < 120: 5, else 15, ± 20 % jitter. `ADMITTED`, `EXPIRED`, `ENDED`: 0 (stop). Clients never poll faster, and stop in the background. |
| Eligibility | Checked at join, against the event's organizer: `min_account_age_days` → `users.verified_at` not null and ≤ `sale_starts_at − n days`. `member_joined_before` → a membership with `left_at` null and `joined_at` < the date. `min_events_attended` → `events_attended` (§Fan `/fan/me/membership`) ≥ n. |
| Device | `device_hash` = HMAC-SHA256(`ROOMS_TOKEN_SECRET`, "device\|" + `device_id`). Entries of other accounts in the room with the same hash ≥ `rooms.device-limit` (3) → `QUEUE_DEVICE_LIMIT`. Soft (no lock). |
| Limits | **Per IP, joins only:** entries of the room with the caller's `ip_hash` (HMAC-SHA256(`ROOMS_TOKEN_SECRET`, "ip\|" + client IP, resolved through trusted proxies only)) and `joined_at` in the last 5 min ≥ `rooms.ip-limit-5m` (300; 0 = off) → `QUEUE_RATE_LIMITED` `IP`, `retry_after_s` 60. Soft (no lock). **Per entry, polls:** one per `rooms.poll-min-interval` (1 s) per instance, kept in memory and checked after the signature → `QUEUE_RATE_LIMITED` `POLL`, `retry_after_s` 1. With N instances an entry can poll at most N times a second. No other per-IP limit in the API; a CDN/WAF may add one in front. |
| Fairness | `draw_key` = 8 bytes from a CSPRNG at join, never returned. Ranks are dense and unique per room. `admitted_through` only grows. A rejoin goes to the back. Join is idempotent per account. |

**§Organizer: role line, append.** "Lot 5: `/org/events/{id}/room*` ADMIN, OPS (write), FINANCE (read). CASHIER, GATE, SUPERVISOR get 403."

**§Organizer: add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/org/events/{id}/room` | `{room: null \| {event_id, status, pre_queue_min, opens_at, sale_starts_at, admission_rate_per_min, eligibility: {min_account_age_days, member_joined_before, min_events_attended}, drawn_at, figures: {joined, waiting, admitted, admitted_last_min, active_holds, max_active_holds, paid, quota, remaining, sold_out, estimated_drain_s}}}`. `waiting` = `ranked_count − admitted_through` (+ unranked); `admitted` = `admitted_through`; `paid` / `quota` as `/sales`; `estimated_drain_s` = waiting × 60 ÷ `admitted_last_min`, or null. Unknown or other organizer's event → 404. |
| PUT | `/org/events/{id}/room` | `{pre_queue_min, admission_rate_per_min, eligibility: {min_account_age_days?, member_joined_before?, min_events_attended?}}`. Create → `201`, replace → `200` (GET shape). The event must be `DRAFT` or `ON_SALE` (409 `EVENT_NOT_EDITABLE`), and `sale_starts_at` must be set and in the future at creation (422 on `sale_starts_at`, `details.reason` `SALE_START_REQUIRED`). Ranges per 03 (422). `member_joined_before` ≤ `sale_starts_at`. After `SCHEDULED`, only `admission_rate_per_min` may differ (409 `ROOM_STATUS`); a rate change applies from the next tick. `ENDED` → 409 `ROOM_STATUS`. Audit `room.create` / `room.update` (`{admission_rate_per_min}` and the rule; no fan data). |
| POST | `/org/events/{id}/room/pause` · `/resume` · `/end` | `OPEN` → `PAUSED`; `PAUSED` → `OPEN`; any but `ENDED` → `ENDED` (irreversible: orders need no token afterwards). Else 409 `ROOM_STATUS`. Audit `room.pause` / `room.resume` / `room.end`. → GET shape. |
| PATCH | `/org/events/{id}` | **Lot 5:** moving `sale_starts_at` while the room is `PRE_QUEUE`, `OPEN` or `PAUSED` → 409 `EVENT_NOT_EDITABLE` `ROOM_STARTED`; while `SCHEDULED` the room follows (`opens_at` is derived). |

Gate, manifest, scans, token and vectors: unchanged.

### 4.4 `docs/06-glossary.md`

New terms:

- waiting room · `file d'attente` · طابور الانتظار · "fans join before the sale, then come in turn"
- draw · `tirage au sort` · القرعة · "random order of everyone who joined before the sale opened"
- position · `position` · ترتيبك
- your turn · `ton tour` · دورك · "10 min to reserve"
- admission token · `jeton d'accès` · رمز الدخول (internal)
- verified fan · `fan vérifié` · مشجع موثّق · "meets this event's rule (account age, membership, matches attended)"
- pre-queue · `pré-file` · قبل الفتح

### 4.5 `docs/02`, `docs/01`, `docs/00`

| Where | Change |
| --- | --- |
| 02:191 Threat model | "Bots on on-sale day \| Waiting room (04 §Waiting room): login to join, one entry per account, random draw for early arrivals, admission token checked before the database, per-event eligibility (account age, membership, attendance), per-device cap, in-app per-IP limits (OTP sends, joins) and a per-entry poll limit, per-account limits, payment-first reservation \| Farms of real phone numbers verified before the eligibility date; narrowed by membership/attendance rules, named tickets and the transfer audit" |
| 02 §Data model | `waiting_rooms`, `queue_entries`, `users.verified_at` |
| 01 §Components, API | + "on-sale waiting room (rooms, queue, admission)" |
| 01 §Key flows | New flow 0: "On-sale with a room: join (logged in) → random draw at sale opening for early arrivals, then arrival order → admission token at your turn (10 min) → reserve with the token → pay." |
| 01 new §Deployment | "The API runs as N identical, stateless instances behind any HTTP load balancer; no hosting provider is assumed. Instances coordinate only through Postgres: row locks with `skip locked` (expiry, deliveries, fan messages, campaigns, room tick), advisory locks (orders, scans, manifest build, the fan-message claim). Per-instance memory is a cache or a limiter, never a source of truth: gate load 15 s, room state 1 s, last poll per queue entry 60 s. Secrets (`JWT_SECRET`, `OTP_SECRET`, `ROOMS_TOKEN_SECRET`, the master key) are identical on every instance and come from the environment. Per-IP limits live in the app and count in Postgres (OTP sends, queue joins), so they hold across instances; the queue poll limit is per entry and per instance (at most N polls/s per entry with N instances). A CDN or WAF can sit in front later without an API change; its addresses join `TRUSTED_PROXIES_REGEX`. Lost on an instance's death and accepted: OTP codes still queued (the fan asks again) and the simulated gateway's delayed callbacks (non-prod only). Health: `/v1/actuator/health/{liveness,readiness}`; graceful shutdown drains in-flight requests." |
| 01 Kafka row | append: "Lot 5: the waiting room needs none (synchronous joins and orders, 1 s tick)." |
| 00:55 | Lot 5 row (Q166, founder 2026-11-13): Scope "and a derby capacity test with target numbers. **Then:**" → "and derby capacity targets, proven at reduced scale locally and in CI (the full capacity run waits for a hosting choice). **Then (steps 2–5):**"; Est. "4 wk" → "≈ 10 wk (steps 1–5)" |

### 4.6 Config (backend `application.yml`, plan note)

`rooms.token-secret` (`ROOMS_TOKEN_SECRET`, required outside dev/test, ≥ 32 bytes), `rooms.tick` (1s), `rooms.memo-ttl` (1s), `rooms.admission-window` (10m), `rooms.device-limit` (3), `rooms.retention` (30d), `rooms.ip-limit-5m` (`ROOMS_IP_LIMIT_5M`, 300; 0 = off), `rooms.poll-min-interval` (`ROOMS_POLL_MIN_INTERVAL`, 1s), `jomhor.cors.shop-origin` (`SHOP_ORIGIN`), `spring.datasource.hikari.maximum-pool-size` (`DB_POOL_SIZE`, 20), `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`, `management.endpoints.web.exposure.include: health`, `management.endpoint.health.probes.enabled: true`, `show-details: never`, OTP per-IP fan 60/600.

### 4.7 `design/DESIGN.md`

**Principle 6, append after the Lot 4 sentence:** "Lot 5 adds: on the waiting room, a straight row of twelve dots fills as the queue moves. At the fan's turn its amber twelfth swells once, with a success haptic. It is a progress indicator, never the J mark. Before the draw, a light runs along the row as the indeterminate indicator. The position figure rolls when it changes."

**Screen inventory, append:**

| # | Screen | Mockup | Route | Entry |
| --- | --- | --- | --- | --- |
| 12 | File d'attente | — | `/events/:id/queue` | Screen 1 hero / screen 2 CTA while a room is active |

Web shop table: + `| W8 | File d'attente | — | /[club]/events/[slug]/queue | SSR shell (event header from the cached detail) + client poller calling the API directly |`.
Back-office table: + `| B13 | Salle d'attente | — | /events/:id/room | 5 |`.

**New "Lot 5 adjustments":**

| Where | Change |
| --- | --- |
| Screen 1 hero / calendar | With `room`: `SCHEDULED` → CTA disabled "File d'attente dès {opens_at}" (+ "Connecte-toi maintenant" when signed out); `PRE_QUEUE`/`OPEN`/`PAUSED` → "Rejoindre la file", or "Reprendre ma place" when the phone holds a queue token for the event. Chip "File d'attente" = the room is open. |
| Screen 2 | Rules banner + "Vente à forte demande · file d'attente"; with `eligibility`, one line "Réservée aux fans vérifiés: compte vérifié avant le {date} · membre depuis le {date} · {n} matchs au stade" (only the parts set). Sticky CTA as screen 1. Zone rows read-only until admitted. |
| 12 · File d'attente, pré-file | Header: back ("Ta place est gardée" toast) + event line (crest 20, title, date). Label "TIRAGE AU SORT À {time}", countdown tiles to `sale_starts_at` (server-time corrected). One paragraph: "Tous ceux qui sont là avant {time} ont la même chance: l'ordre est tiré au sort. Après, premier arrivé, premier servi." "{in_room} fans dans la file" (rolls). Twelve dots, the light running along them (indeterminate). |
| 12 · waiting | Display 88 "N° {position}" (Barlow, rolls), "{position − 1} fans devant toi", "Attente estimée · environ {n} min" (Q165; `PAUSED` → "Admission en pause · ta place est gardée"). Twelve dots, determinate: lit = ⌊12 × (1 − position ÷ first position seen)⌋, capped at 11 until the turn (the first position is stored on the phone). Info rows: "Tu peux fermer l'app: ta place est gardée", "Limite {n} billets par compte · billets nominatifs", "Il reste {remaining} places", or with `sold_out` "Tout est réservé pour l'instant · des places peuvent se libérer". "Fan vérifié" chip (check icon) when the event has a rule. Screen kept awake while visible. |
| 12 · ton tour | The amber twelfth swells (≤ 420 ms), success haptic, display "C'EST TON TOUR", "Tu as {mm:ss} pour réserver" counting to `admission.expires_at`, primary CTA "Choisir mes places" → screen 3. |
| 12 · other states | `EXPIRED`: "Ton tour est passé" + "Reprendre une place" (joins again, at the back). `ENDED` → straight to screen 3. `NOT_ELIGIBLE`: outlined panel "Vente réservée aux fans vérifiés" + the reason ("Compte vérifié après le {date}", "Réservée aux membres inscrits avant le {date}", "Il faut {n} matchs au stade · tu en as {attended}"). `QUEUE_DEVICE_LIMIT`: "Trop de comptes sur ce téléphone pour cette vente". `QUEUE_RATE_LIMITED` `IP`: "Beaucoup de fans sur ce réseau · nouvel essai dans {n} s", then one automatic retry at `retry_after_s`; `POLL`: silent, next poll at `retry_after_s`. Offline: last state + "Hors ligne · ta place est gardée". |
| Screen 3 / 4 | While admitted, a slim bar "Ton tour · {mm:ss}" above the content. `ADMISSION_REQUIRED` → back to screen 12. Screen 4: the "CIN vérifiée" badge is removed (Q145). |
| W2 | With `room`: the summary aside becomes the room panel (same states as screen 1, flipping at `opens_at` on the client). Zone cards show prices and remaining but are disabled with "Accès par la file" until admitted. "Rejoindre la file" → `/login?next=…/queue` when signed out. |
| W8 | Two columns: room states as screen 12 (twelve dots, position, wait, turn countdown) + event aside. Polls the API directly at `poll_after_s`, pauses when the tab is hidden. At the turn: "Choisir mes places" → W2 with the zone picker enabled → W3. |
| B2 Règles | "File d'attente · {status}" + link "Gérer" → B13. |
| B13 Salle d'attente | **Not configured:** card "Vente à forte demande" (one paragraph) + form: "Ouverture de la file" (minutes before the sale, 0–1440, default 30, with the computed time in the venue zone), "Cadence d'admission" (fans/min, default 500, helper "≈ {orders}/min · complet en ~{min} min", an estimate from quota ÷ 2.6 ÷ (rate × 0.7)), switch "Fans vérifiés" revealing "Compte vérifié depuis au moins {n} jours" (default 7), "Membre inscrit avant le" (date), "Au moins {n} matchs au stade", at least one when on. **Live:** status badge (Programmée · Pré-file, tirage à {time} · En cours · En pause · Terminée), KPI cards (Dans la file · Admis · Achats en cours {active_holds}/{max} · Places restantes {remaining}/{quota} · Cadence {admitted_last_min}/min · File vidée dans ~{min}), rate input + "Appliquer" (confirm), "Pause"/"Reprendre", "Terminer la file" (confirm: "Les fans achètent sans file. Irréversible."). Refetch every 2 s while not `ENDED`. FINANCE read-only. |

**Wow table, add:**

| Screen | What makes it special |
| --- | --- |
| 12 · File d'attente | The brand's twelve dots become the fan's walk into the stadium: they light one by one as the queue moves, and the amber twelfth (the supporter who completes the team) swells at "C'est ton tour". The position is a real rank, rolling down in display type, with an honest wait from the measured rate. Before the sale, one plain sentence explains the draw, so arriving early isn't a race. Calm, dark, nothing flashing; leave and come back, your place is kept. FR + AR goldens. |

## 5. Tasks

### Wave 0: orchestrator and founder (before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/01`, `02`, `03`, `04`, `06`, `design/DESIGN.md` | §4 applied after the founder agrees |
| W0-2 | orchestrator | git | `feat/lot-5-onsale` from `main`. No new CI job (k6 and `smoke.sh` run locally only; `check.sql` and `OnSaleTwoInstanceTest` run in the existing `./gradlew test`) |
| W0-3 | founder | — | Ask Payzone the Q142 questions. CNDP declaration: on-sale anti-fraud, device-id and IP hashes (Q144, Q146, Q147). Club: verified-only for the derby? Which rule? Wow review slot on day 7 (local build) |
| W0-4 | — | — | Removed (founder 2026-11-13: no hosting provider; staging deferred, Q159). BE-45 local prerequisites: Docker Desktop with ≥ 6 GB for WSL, k6, JDK 25 |

### Tasks

`backend` is serial and on the critical path. ∥ rows run alongside, in disjoint folders. *(contract-first)* = starts on the §4 shapes with fixtures and is done only after one run against the real endpoint. Every client task ships fr/ar/en strings (no hard-coded strings or colours), and the fan app ships FR + AR goldens of the screens it touches.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-40 | backend | `backend/…/sales` (new `rooms` sub-package: domain, `WaitingRoomService` config part, `JdbcWaitingRooms`, `OrgRoomController`, `RoomMemo`), `…/catalog` (`room` on public event reads; event PATCH `ROOM_STARTED`), `…/tenancy` (`verified_at` on first fan verify), V23 | 04 org room rows, `room` on `/fan/events*`, `verified_at` | `WaitingRoomOrgApiTest`: PUT creates (201) with defaults 30/500, GET `{room: null}` before; no or past `sale_starts_at` → 422 `SALE_START_REQUIRED`; ranges → 422; after `PRE_QUEUE` (fixed clock) a `pre_queue_min` change → 409 `ROOM_STATUS` while a rate-only change → 200; pause in `SCHEDULED` → 409; pause/resume/end transitions + audits (payload key-set: no fan data); PUT after `ENDED` → 409; FINANCE GET 200 / PUT 403; CASHIER 403; org B event → 404. `FanEventApiTest`: `room` null without a room or when `ENDED`; `opens_at` derived; `eligibility.verified_before` = sale − n days; `room` on list rows. Event PATCH of `sale_starts_at` in `SCHEDULED` moves `opens_at`; in `PRE_QUEUE` → 409 `EVENT_NOT_EDITABLE` `ROOM_STARTED`. `FanAuthApiTest`: first verify sets `verified_at`, a later one keeps it, a staff verify doesn't set it. `MigrationsTest` V23: backfill = earliest session; allowlist still 5. `RoomMemoTest`: one query per TTL whatever the call count (fixed clock), ENDED rooms absent | W0-1 |
| 1 ∥ | BO-20 | backoffice | `web/backoffice/src/features/room` (new), `src/features/events` (B2 Règles row), `src/app` (route), `src/i18n/fr.ts` | B13 + B2 link | `room.test.tsx`: no room → form with 30 / 500 / switch off; zod ranges; switch on needs ≥ 1 rule; PUT body; live KPIs from fixture; refetch 2 s only while not `ENDED`; once started only the rate is editable (others disabled) and PUT sends the unchanged rest; buttons per status; end confirm copy; `ROOM_STATUS` → toast; FINANCE sees no write buttons; query keys include the organizer | BE-40 *(contract-first)*; figures real after BE-41 |
| 1 ∥ | FL-26 | flutter | `apps/fan/lib/features/queue` (new: screen 12, controller, repository, token store), `features/events` (screens 1, 2 CTA states), `features/checkout` (admission header, admission bar, `ADMISSION_REQUIRED` → room, badge removed), `core/device_id.dart` (new), `app/router.dart`, `app/session.dart` (wipe on logout), `l10n/*.arb`, `test/queue_test.dart`, `test/goldens_test.dart` | Screen 12 and its entry points | Tests (fake clock, fake API): join body `{event_id, device_id}` with the same `device_id` across restarts; `PRE_QUEUE` countdown to `sale_starts_at` server-time corrected, `in_room` rolls; polling waits exactly `poll_after_s`, stops on `ADMITTED`/`ENDED`, stops in the background and polls once on resume; position, "fans devant toi", wait copy for <60 s / n min / >60 min / null; lit dots from the first position, 12th only at the turn; `ADMITTED` → one swell + haptic (none under reduced motion), countdown to `expires_at`, CTA → screen 3, POST `/fan/orders` carries `X-Admission-Token`; 409 `ADMISSION_REQUIRED` → screen 12; `EXPIRED` → "Reprendre une place" POSTs again; each `NOT_ELIGIBLE` reason and `QUEUE_DEVICE_LIMIT` copy; `QUEUE_RATE_LIMITED` `IP` copy + one retry at `retry_after_s`, `POLL` silent with the next poll at `retry_after_s`; `QUEUE_NOT_OPEN` `NOT_STARTED` → disabled CTA with `opens_at`; tokens survive restart and are wiped on logout; wakelock on while visible, off on leave; goldens fr + ar: pre-file, waiting, ton tour, not eligible, screen 2 with room | BE-41, BE-42 *(contract-first)* |
| 1 ∥ | WS-12 | web-shop | `web/shop/app/(public)/[club]/events/[slug]/` (room panel in `event-view`, new `queue/page.tsx` + `QueueRoom.tsx`), `app/api/queue/route.ts` (new), `app/api/orders/route.ts` (forward header), `lib/queue.ts` (new), `lib/i18n/{fr,ar,en}.ts` | W2 room panel, W8, join handler | `queue.test.tsx`: W2 `SCHEDULED` shows `opens_at` and flips to "Rejoindre la file" at `opens_at` (fake timers, no reload); zone cards disabled "Accès par la file" until admitted; signed out → `/login?next=…/queue`; join handler: cross-origin → 403, forwards Bearer + `device_id` from `__Host-jdid` (set HttpOnly, Secure, SameSite=Lax, Path=/, 1 y when absent), returns the queue shape; W8 poller calls `${API_PUBLIC_URL}/fan/queue` with `X-Queue-Token`, `credentials: 'omit'`, honours `poll_after_s`, pauses on `visibilitychange`, stops on `ADMITTED`; admitted → zone picker enabled and `/api/orders` sends `X-Admission-Token`; `ADMISSION_REQUIRED` → W8; `QUEUE_RATE_LIMITED` on join (copy + one retry) and on poll (silent, waits `retry_after_s`); no JWT or K_t in HTML/RSC; W8 renders in ar; `rtl.test` and `reserved-slugs.test` green | BE-41, BE-42 *(contract-first)* |
| 2 | BE-41 | backend | `backend/…/sales/rooms` (join, poll, `RoomTokens` on Nimbus JWT HS256, `RoomTickJob`, draw/rank/admit, purge), `…/crm` + `…/tenancy` application calls for eligibility, V24 | 04 `/fan/queue` rows, §Waiting room | `WaitingRoomFanApiTest`: join before `opens_at` → 409 `NOT_STARTED` + `opens_at`; join in `PRE_QUEUE` → 201, status `PRE_QUEUE`, position null; second join → 200 same entry; claims key-set exactly `{iss, aud, sub, evt, exp}` (+`rnk`), and no phone, name or device id in any token; a fan JWT on `/fan/queue` → 401 and a queue token as Bearer elsewhere → 401; tampered `rnk` → 401; 4th account on one `device_id` → 409 `QUEUE_DEVICE_LIMIT`; per-IP (`rooms.ip-limit-5m` = 3 in the test): the 4th new entry from one IP within 5 min → 429 `QUEUE_RATE_LIMITED` `IP` + `retry_after_s`, a re-join of an existing entry from that IP → 200, another IP → 201, 5 min later (fixed clock) → 201, `X-Forwarded-For` from an untrusted peer ignored, `ip_hash` 32 bytes and never the IP; each eligibility reason with its details (verified one day too late; member who left; 2 of 3 events). `RoomTickTest` (fixed clock): draw at `sale_starts_at` ranks 1..200 dense and once (a second tick changes nothing; audit `room.draw {count: 200}`); rank vs join order not identity, Spearman |ρ| < 0.2 at n = 200 over 5 seeds; a join after the draw → rank 201 by arrival; 10 s at 600/min → `admitted_through` +100, capped at `ranked_count`; no advance when holds ≥ 90 % of the cap, when `sold_out`, when `PAUSED`; `measured_rate_per_min`; `ENDED` at `refund_cutoff_at`. Poll: unranked reads the entry; ranked poll runs **zero** SQL statements (datasource statement counter); `ADMITTED` sets `admitted_at` once and `expires_at` = +10 min; after → `EXPIRED`; rejoin → 201 at the back; purge deletes entries 30 d after `starts_at`. Org B entries never visible to org A. `MigrationsTest` V24 | BE-40 |
| 3 | BE-42 | backend | `backend/…/sales` (`place`: admission first, `pays` eligibility; POS untouched), `…/common/web` (CORS for `GET /fan/queue`: allow the `X-Queue-Token` request header in the preflight for `SHOP_ORIGIN` only, no credentials, `Access-Control-Max-Age` so each poll isn't preceded by a preflight; only), `application.yml` (OTP per-IP 60/600, rooms config; the `TRUSTED_PROXIES_REGEX` comment made provider-neutral: "the shop and any load balancer in front"), `…/sales/rooms` (per-entry poll limiter, swept by the tick, `ponytail:` on the N× ceiling) | Admission enforcement, poll limit | `AdmissionApiTest`: room `OPEN`, no header → 409 `ADMISSION_REQUIRED` `MISSING` with zero SQL statements after the session check; another user's token, another event's, bad signature → `INVALID`; expired → `EXPIRED`; valid → 201 and a second order inside the window → 201 until `PER_ACCOUNT_LIMIT`; `PAUSED` + valid → 201; `ENDED` or no room → no token needed (every existing `OrderApiTest`, `SplitPayApiTest`, `Lot4FanFlowTest` green); `POST /org/pos/orders` during `OPEN` → 201; a split with an ineligible `pays` member → 409 `NOT_ELIGIBLE` + `details.field`. CORS: preflight from `SHOP_ORIGIN` on `GET /fan/queue` OK, another origin refused, other paths send no CORS headers. OTP per-IP: 60th send OK, 61st → 429. Poll limit (fixed clock): a second poll of one entry 0.5 s later → 429 `QUEUE_RATE_LIMITED` `POLL`, `retry_after_s` 1, zero SQL statements; 1 s later → 200; another entry unaffected; a bad token → 401 with the map untouched; entries idle > 60 s swept (map empty) | BE-41 |
| 4 | BE-43 | backend | `backend/…/notifications` (`FanMessageJob` claim under `pg_try_advisory_xact_lock`, class 6 noted in `EventLock` javadoc), `…/gate/application/GateLoad.java` (note only), `…/tenancy/adapters/otp` (dropped-code WARN + counter), `build.gradle.kts` (`spring-boot-starter-actuator`, latest via BOM), security config (health public), `application.yml` (pool, graceful shutdown, probes), `src/test/…/MultiInstanceTest` | Multi-instance proof | `MultiInstanceTest` (two Spring contexts, one Testcontainers Postgres): two expiry passes at once release each order exactly once (`sold` exact); the room tick on both draws once and advances `admitted_through` once per interval (not 2×); fan messages at rate 20/s, period 5 s → ≤ 100 sends per period across both while their runs overlap (a run starting after the other's commit may add one more; see 04 §Campaigns Send); one campaign snapshotted once; a due manifest version built once. `HealthTest`: `/v1/actuator/health` `UP` with no details; readiness `OUT_OF_SERVICE` once shutdown starts; an in-flight `POST /fan/orders` completes during graceful shutdown. A full OTP queue logs WARN and counts | BE-42 |
| 5 | BE-46 | backend | `backend/src/test/…/Lot5OnSaleFlowTest`, `backend/http/lot5-onsale.http` | Exit flow | **`Lot5OnSaleFlowTest`:** a room with "verified ≥ 7 d"; 3 eligible fans + a 2-day account join in `PRE_QUEUE` (the last → `NOT_ELIGIBLE`); draw; admission in rank order at a fixed rate; fan 1 buys with the token and pays (fake) → tickets; an order without a token → 409; pause freezes positions, resume continues; fan 2's turn expires → rejoin at the back; categories exhausted → `sold_out`; end (OPS raises a quota by one) → an order without a token passes; `check.sql` returns no rows | BE-43 |
| 6 | BE-44 | backend | `backend/loadtest/k6/` (`lib.js`, `onsale.js` with scenarios prequeue / poll / buy / cold-visitor, `hot-row.js`), `backend/loadtest/check.sql`, `backend/loadtest/Dockerfile` (the boot jar on a JRE 25 image), `backend/loadtest/smoke.sh`, `backend/compose.yaml` (profile `smoke`: `api-1`, `api-2`, memory caps), `backend/…/LoadSeeder.java` (profile `loadtest`), `docs/TESTING.md` §Load (backend writes it: commands only) | Load harness, local two-instance set-up | `LoadSeederTest`: N users with `+999…` phones, `verified_at` 30 d back, live sessions, a JWT file; refused under `prod`, without the profile, or with a non-fake card provider. `CheckSqlTest`: `check.sql` returns no rows on the `Lot5OnSaleFlowTest` end state and one row per crafted breach (oversell: VALID tickets > quota or `sold` ≠ held + paid places; double charge: two SUCCEEDED payments on one order with no `refund_required`; tickets ≠ places on a PAID order; places over `RESERVED`/`AWAITING_CASH`/`PAID` orders > `per_account_limit`; an entry admitted without a rank or beyond `admitted_through`; `sold` counts held + paid places minus `REFUNDED` tickets on `PAID` orders; scoped by `event_id` because the shared test database holds other classes' deliberate breaches). Smoke: `smoke.sh` uses its own compose project (`-p jomhor-smoke`, own volume and port, removed at the end), so dev data is untouched; readiness `UP` on 8081 and 8082; each k6 scenario runs 30 s with `--vus 5` against both, as `TESTING.md` lists. Plain `docker compose up` still starts Postgres only | BE-46 |
| 7 | BE-45 | backend (CI test only; the local smoke run was dropped 2026-11-15) | `backend/src/test/…/OnSaleTwoInstanceTest`, `backend/loadtest` (tuning), `application.yml` defaults if tuned | Two-instance correctness (CI) + local capacity smoke | **CI** `OnSaleTwoInstanceTest` per §5.5 (BE-43's two-context fixture), all exact checks green, ≤ 2 min. **Local** run dropped by the founder (2026-11-15); it moves with the deferred §5.4 run, and Q151 stays provisional | BE-44 |
| exit | — | orchestrator + founder | — | Demo per §1 locally (2 instances, one killed mid-sale) | All rows above green; manual demo | all |

`reviewer` runs after every builder task. Founder wow review on a local build once FL-26 and WS-12 are real (screen 12 FR + AR, W8).

### 5.1 Estimates

Dropped on 2026-11-14 at the founder's request: the plan keeps order and dependencies only. Backend runs serially BE-40 → BE-41 → BE-42 → BE-43 → BE-46 → BE-44 → BE-45; clients run on fixtures alongside and do their real run once BE-42 lands.

### 5.3 Cut order (if late) and never-cut list

Cut in this order:

1. the per-device limit and the per-IP join limit (account, eligibility and OTP per-IP remain);
2. the measured rate (the estimate uses the configured rate);
3. B13 figures beyond waiting / admitted / remaining (keep every control);
4. the W2 automatic flip at `opens_at` (a reload shows it);
5. the `min_events_attended` rule (keep account age and membership).

Never cut:

- the admission token required while a room is active, checked before any DB read;
- zero DB reads on a ranked poll;
- the random draw for pre-queue joins, and the rank order of admission;
- every job safe with N instances (`MultiInstanceTest`);
- `OnSaleTwoInstanceTest` in CI and the local smoke (§5.5), with `check.sql` clean;
- no PII in tokens;
- tenant scoping on both tables;
- centimes untouched;
- the gate path untouched;
- FR + AR goldens of screen 12.

### 5.4 Derby capacity targets (Q157): **deferred** run, not a step 1 exit

Target for the run on a production-class setup (≥ 3 API instances, production-class Postgres with a synchronous standby) once hosting is chosen (Q159). Step 1's exit is §5.5.

| Metric | Target |
| --- | --- |
| Stadium / stock | 45,000 capacity; 40,000 places on sale in 6 zones; the popular zone has 15,000 places in **one** price category |
| Room at T0 | 100,000 fans joined during a 30-min pre-queue (half in the last 5 min) |
| After T0 | +30,000 joins over 10 min |
| Join | p95 < 500 ms at 1,000 joins/s peak; 0 errors |
| Draw | 100,000 entries ranked ≤ 10 s after `sale_starts_at` |
| First position | 95 % of pre-queue fans see a position ≤ 30 s after T0 |
| Poll | 8,000 req/s for 30 min: p95 < 150 ms, p99 < 400 ms at the load balancer, errors < 0.1 %, 0 SQL per ranked poll |
| Admission | Configured 1,000/min (2× default) held within ± 5 % |
| Orders (admitted) | p95 < 300 ms, p99 < 1 s, 0 5xx; 30 % of holds abandoned, 5 % of cards refused (fake) |
| Hot row (room ended, raw) | ≥ 100 orders/s on the single category with 200 concurrent buyers. Below 5× the order rate → build buckets (Q151) |
| Correctness | `check.sql` clean: 0 oversell, 0 double charge, 0 limit breach, admission in rank order; every expired hold released once; draw rank vs join time Spearman \|ρ\| < 0.05 |
| Resilience | One of 3 instances killed at T0 + 5 min: < 0.5 % errors for 30 s, the tick resumes within 2 s, no lost or duplicated order |
| Resources | Postgres CPU p95 < 60 %, connections < 70 % of max; API CPU < 70 % |
| Sell-out | All 40,000 places sold; time recorded (about 20 min expected at 1,000/min) |

**Lot 6 keeps:** this deferred run if it hasn't happened by then, the full launch-scale test on the production setup (200,000 in the room, real Payzone sandbox, any CDN/WAF chosen live, a 2 h soak, match-day traffic: `/load` polls and manifest downloads at the same time), the pen test, and the runbooks.

### 5.5 Capacity smoke (step 1 exit; BE-44 builds, BE-45 runs)

**Founder, 2026-11-15: no local capacity run.** Step 1 is proven by `OnSaleTwoInstanceTest` in CI. The local column below stays as the harness's spec (BE-44 built it, its 30 s smoke passed); its full run moves with the deferred capacity test (§5.4, when hosting is chosen). The exit demo's (7) is dropped.
Known before that run: `onsale.js` full mode is unexercised; its `ramping-arrival-rate` targets must be whole numbers, so use `timeUnit: '1m'` with rounded stages (found in BE-45, not kept); the `docker kill` step and the gate SQL are still to write.

| | CI: `OnSaleTwoInstanceTest` | Local: `backend/loadtest/smoke.sh` |
| --- | --- | --- |
| When | Every PR, in the existing `./gradlew test` | On demand: in BE-45, before the exit demo, after any tuning or rooms/sales change. Not in CI (needs Docker + k6 + ~25 min) |
| Set-up | BE-43's fixture: two Spring contexts, random ports, one Testcontainers Postgres | Compose project `jomhor-smoke`: Postgres 18 (`PG_MEM_LIMIT`, smoke default 1 g; 1.5 g for the full run), `api-1` + `api-2` (`API_MEM_LIMIT`, smoke default 768 m; 1 g each for the full run, which needs a Docker VM above the local 4 GB; `-XX:MaxRAMPercentage=70`, `DB_POOL_SIZE` 20, profiles `staging,loadtest` since the fake card provider exists only under dev/test/staging); k6 on the host or the `grafana/k6` image round-robin over both. ≈ 4.5 GB in all; set Docker's WSL limit ≥ 6 GB and close other heavy apps on a 16 GB machine |
| Scale | 300 fans, 3 zones, a 120-place hot category, admission 600/min on a stepped fixed clock, 30 % abandon, 5 % refused | 6,000 fans in a 5-min pre-queue (half in the last minute), +1,000 over 2 min after T0; 2,000 places in 3 zones, the hot zone 1,000 in one category, `per_account_limit` 4; admission 1,000/min; polls at `poll_after_s` (≈ 400–600 req/s, k6 cap 600); 30 % holds abandoned, 5 % cards refused; ≈ 25 min (the 8-min hold sets the tail) |
| Kill | `close()` of one context mid-sale (graceful) | `docker kill` of `api-2` (SIGKILL) at T0 + 2 min; k6 then sends to `api-1` only |
| Exact (gating) | `check.sql` no rows (0 oversell, 0 double charge, 0 limit breach, admission in rank order); `sold` = held + paid at the end, each abandoned hold released once; one draw; `admitted_through` monotonic; OTP per-IP and join per-IP counts shared by both instances | Same, plus: tick resumes ≤ 2 s after the kill (`last_tick_at`); no order lost (every 201 seen by k6 exists) or duplicated; 0 5xx from `api-1`; admission within ± 5 % of 1,000/min over 5 min (`measured_rate_per_min`); draw rank vs join time Spearman \|ρ\| < 0.05 at n = 6,000 |
| Indicative (recorded, not gating) | — | p95 join / poll / order; draw time; hot-row orders/s (room ended, 100 buyers, 60 s) with Q151 applied provisionally; `docker stats` CPU/memory per container. One shared machine: latency says nothing about production; §5.4 does |

Zero SQL on a ranked poll is proven by BE-41's statement counter, not here.

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Admission before the DB | `place` decides admission from `RoomMemo` + the signature before any repository call (statement-counter test). A ranked `GET /fan/queue` runs no SQL. The memo is one query per TTL per instance. Nothing in the poll path takes a lock |
| Tokens | Nimbus HS256 with a separate `ROOMS_TOKEN_SECRET`. `aud` `queue` / `admission` never accepted for each other or as a fan JWT, and the reverse. Admission is bound to `sub` = caller, `evt` = body event, `exp`. Claim key-sets exact: no phone, name or device id. Secret ≥ 32 bytes, startup fails without it outside dev/test |
| Fairness | CSPRNG `draw_key`, never returned. Draw and status flip in one transaction, once. Ranks dense and unique (`unique (room_id, rank)`). `admitted_through` monotonic and ≤ `ranked_count`. A rejoin goes to the back. Join idempotent per account (unique + on-conflict). No path lets a client choose or improve its rank |
| Multi-instance | The tick takes rooms with `for no key update skip locked` (joins' FK `key share` not blocked). The fan-message claim is under a try-lock. No new in-memory state beyond TTL memos and the swept poll map. Jobs proven in `MultiInstanceTest`. Graceful shutdown + readiness wired |
| Inventory | `Inventory` untouched except a `ponytail:` note. The lock order in `OrderService` javadoc still holds with the admission step first. Holds cap and `sold_out` read without locks. `check.sql` definitions match 04 (`sold` = held + paid) |
| Eligibility and privacy | Rules computed through crm/tenancy application services, not their repositories. `NOT_ELIGIBLE` details carry only the rule and the caller's own count. `device_hash` is an HMAC, never the raw id. Purge after 30 d. The `verified_at` backfill is correct. The load seeder can't run in prod and uses `+999…` phones |
| Rate limits | The join IP limit counts in Postgres with the device-limit query shape, applies only to created or moved-back entries, takes the IP from the trusted-proxy chain only and stores an HMAC, never the IP. The poll limiter is keyed by entry id, runs after the signature, does no SQL, is swept, and carries the `ponytail:` N× note. No catch-all in-memory per-IP limiter. No doc, code or config makes a hosting provider a dependency |
| Tenancy | Both tables filter `organizer_id`. Org B room → 404. A queue token of club A's event never admits on club B's event |
| Contract | Every new or changed 04 row has a contract test. Error codes and `details.reason` exactly as 04. V23/V24 names = 03. OTP per-IP defaults = 04 |
| Clients | Position and wait only from the server (no client-made numbers). `poll_after_s` honoured, background polling off. Direct browser poll with `credentials: 'omit'`. Admission header forwarded, tokens wiped on logout. Twelve dots never drawn as the J mark. Reduced motion off. RTL mirrored with numbers LTR. Strings in fr/ar/en |
| Gate | Zero diff under `…/gate` except the `GateLoad` comment. No scanner or `packages/core` change |

## 7. Carried forward

| Item | Lot | Note |
| --- | --- | --- |
| Full launch-scale load test, pen test, runbooks | 6 | §5.4 last row |
| Hosting choice, staging with ≥ 3 instances, the §5.4 capacity run | when the founder picks hosting (Lot 6 at the latest) | Q158, Q159 |
| CDN in front of the shop, WAF rate rules / Bot Control / CAPTCHA | 6, if measured; no API change | Q147 |
| Redis/Valkey room store | only if the BE-45 smoke or the deferred run shows joins under target | Q139, `ponytail:` note |
| WS-12 follow-ups: the "Ton tour" bar on W3, "Reprendre ma place" on W2, keep-awake on W8 (Wake Lock API), "matchs" vs "événements" in the eligibility copy | step 2 (WS-13) | wave 1 review, 2026-11-13 |
| BE-41 review follow-ups (2026-11-13, carry into BE-42/BE-43): (1) `JdbcQueueEntries.admit()` add `and admitted_at is null` so an admitted fan's polls write nothing; (2) `RoomTicks` read `now` per room inside its transaction (or `last = max(last, now)`) so `last_tick_at` never moves back across instances (BE-43's once-per-interval test); (3) 04 §Waiting room Tick: say the admission fraction carries and `last_tick_at` advances by n × 60 s ÷ rate (code is right, doc is literal); (4) `RoomTickTest` zero-SQL check: warm the memo right before counting or lengthen `rooms.memo-ttl` in tests; (5) `QueueService` join: one `forSale` + not-DRAFT query instead of the listing query + `orElseThrow()` (500 → 404); (6) `FanInboxApiTest` import `ChronoUnit` | step 1 (BE-42/BE-43) | BE-41 review |
| WS-12 real run (2026-11-15): first minute after the draw has no wait estimate (`measured_rate_per_min` 0 until re-marked at 60 s) and `poll_after_s` ≈ 15 even at position 1, so a fan admitted on the next tick learns it ≈ 17 s late (the 10-min window starts at that poll, so no time is lost). Fix: estimate from the configured rate until the first measurement, and shorten `poll_after_s` near the front | step 2 | WS-12 real run |
| FL-26 reviewed and fixed (2026-11-14); left: the docker golden run, then the real run after BE-42 | step 1 | — |
| FL-26 review leftovers for the founder wow review: the box shadow on the lit twelfth dot (DESIGN: no shadows on the brand mark), "matchs" in `roomMinEvents`/`queueNotEnoughEvents` (event-agnostic wording, same item as the WS-12 one), display font sizes 88/56 as type tokens | wow review | FL-26 review |
| Category buckets (sharded `sold`) | only if the hot-row ceiling fails the rule | Q151 |
| Rooms per phase (members presale), members prices, holder cap, waitlist ("Liste d'attente") | 5 step 2 | Q150, Q156, lot-4 Q57 |
| Refunded place still counting in `per_account_limit` | 5 step 2 | `TicketAdminService` `ponytail:` (lot-3 Q52) |
| CIN-based verification | post-V1 | Q145 |
| Push "C'est ton tour" | when asked | Q153 |
| Kafka | 6, only if the load test needs it | Q161 |
| `GateLoad` memo | stays per instance | P106 |
| Deploy env added in step 1 | whoever deploys (no host chosen) | `ROOMS_TOKEN_SECRET` (required), `ROOMS_TICK` (1s), `ROOMS_MEMO_TTL` (1s), `ROOMS_ADMISSION_WINDOW` (10m), `ROOMS_DEVICE_LIMIT` (3), `ROOMS_RETENTION` (30d), `SHOP_ORIGIN` (backend CORS), `DB_POOL_SIZE` (20), `OTP_PER_IP_FAN_10M` (60), `OTP_PER_IP_FAN_24H` (600), `ROOMS_IP_LIMIT_5M` (300), `ROOMS_POLL_MIN_INTERVAL` (1s); load-balancer health on `/v1/actuator/health/readiness`; `TRUSTED_PROXIES_REGEX` = the load balancer's and the shop's addresses |

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| Payzone or the CMI 3DS can't take the payment rate | The admission rate is a live knob (rate ≤ 50 × P, Q142); prod ≤ 300/min until confirmed; Lot 6 tests on the Payzone sandbox |
| OTP surge at the pre-queue (provider throughput, Q2) | App sessions last 30 days; "Connecte-toi avant" copy from `opens_at`; the draw removes the incentive to arrive at the last second; per-IP raised (Q148); the OTP pool measured in BE-45 |
| CGNAT puts thousands of fans behind one IP | Per-IP limits only where they matter and generous (OTP sends 60/10 min, new room entries 300/5 min per room); polls are limited per entry, not per IP; per-phone and per-account limits do the real work; both IP limits are live config (0 = off); watch the `QUEUE_RATE_LIMITED` and `OTP_RATE_LIMITED` counts on the day |
| Postgres as the queue store hits a limit | Polls are DB-free by design; join and draw measured in BE-45; Redis path documented (Q139) |
| Hot row | Admission keeps the order rate an order of magnitude under the expected ceiling; measured; buckets path (Q151) |
| Farms of verified phone numbers | Account age before the sale date, membership and attendance rules, one entry per account, device cap, named tickets, transfer audit; resale with a price cap post-V1 |
| Fans see the draw as opaque or unfair | One plain sentence on screen 12 and W8, real positions, an honest wait, audit `room.draw` with the count |
| Admitted fans who don't buy slow the sale | Measured rate shown in B13; OPS raises the rate live; the holds cap stops overload |
| Cash codes sit on derby stock for 24 h | The existing cash cap (20 %, config); lower it for derbies |
| Cold visitors flood the W2 SSR | Detail fetch cached 15 s per shop instance; polls bypass Next (Q152); a CDN in Lot 6 |
| The local smoke doesn't predict production capacity (one machine, shared CPU, no replicated commit) | It gates on exact correctness, not latency; the §5.4 run is deferred, not dropped, and gates Live (§1) |
| No edge protection against an L7 flood | Bad tokens fail on one HMAC; every costly path is account-scoped; a CDN/WAF goes in front when hosting is chosen, with no API change |
| Lot 5 size | Q166: one lot in steps 1–5 |

## 9. Rest of Lot 5 (outline only; planned in detail when each starts)

| Step | Scope | Main contract moves |
| --- | --- | --- |
| 2 · Fair on-sale | Rooms per phase (members presale, then general sale: drop `unique (event_id)`, add a phase label and order); members-only price categories (`price_categories.audience` `ALL\|MEMBERS`, checked at place); holder cap (lot-1 Q18); sold-out waitlist ("Liste d'attente", screen 1); refunded places no longer counting toward the limit (lot-3 Q52) | `waiting_rooms` phases, `price_categories.audience`, a waitlist table + notify on release |
| 3 · Passes, paid membership, installments | Orders that sell something other than an event place (lot-4 Q57): `orders.event_id` nullable + a product on items; season passes materialise one ticket per event of the series (03 `passes`); paid membership (`memberships.paid_until`); installments for passes (a schedule of N payments, cash code or card per instalment, pass `SUSPENDED` → future tickets `BLOCKED` through the manifest) | order/product rework, `passes`, `pass_installments`, B-pages for passes |
| 4 · Accreditations, invitations, promo codes | B7; `POST /org/events/{id}/invitations` (method `FREE`, zone counts, named); promo codes (amount in centimes or percent with integer rounding, per-code and per-account caps, valid per event or category) | `promo_codes`, order `discount_mad`, invitation batches |
| 5 · Finance and CRM export | Daily settlement statements (03 `settlements`), VAT invoices (numbering, PDF), 48 h payouts, bank-deposit matching for till cash (02 threat model), `/org/crm/export.csv` (opt-ins from `memberships`, CNDP) | settlements, invoices, payouts; the CRM export row |

## 10. Step 2 · Fair on-sale

As of 2026-11-15 · owner: tech-lead · status: **agreed 2026-11-15** by the founder: members price only for members who joined before `sale_starts_at` (Q170/Q171), waitlist notifies first come with no hold (Q176/Q177), holder cap online and on transfer (Q173), waitlist notices by push + WhatsApp (Q178); the other defaults stand (Q168, Q169, Q183 and the rest). Branch `feat/lot-5-fair-sale` from `main` (step 1 merged in #19). V24 is the latest migration, so step 2 starts at V25. Ids: Q167–Q183, P117–P128, BE-47–BE-50, FL-27–FL-28, WS-13–WS-14, BO-21.

### 10.1 Scope and exit

Scope (§9 row 2, §7):

- **Rooms per phase:** a members presale, then the general sale. `unique (event_id)` is dropped, and each room gets a phase number, an optional label and its own sale start.
- **Members-only price categories:** `price_categories.audience` `ALL|MEMBERS`, checked when an order is placed.
- **The holder cap** (lot-1 Q18, Q150).
- **The sold-out waitlist** ("Liste d'attente", screen 1), with a notification when places come back.
- **Refunded places** no longer count toward the per-account limit (lot-3 Q52).
- **Step-1 follow-ups:**
  - the WS-12 follow-ups: the turn bar on W3, "Reprendre ma place" on W2, keep-awake on W8, and "matchs" vs "événements" in the copy;
  - a wait estimate in the first minute after the draw;
  - a shorter `poll_after_s` near the front.

**What step 2 reuses:**

- The waiting-room tick, memo, signed tokens and eligibility rules. A presale is simply a phase-1 room whose rule is `member_joined_before`.
- The per-account advisory locks in `OrderService` (the holder cap adds accounts to the same lock set).
- `MembershipService.liveMemberSince`.
- The `fan_messages` outbox. The waitlist notice is one new kind, sent exactly like `SPLIT_INVITE`.
- The `pg_try_advisory_xact_lock` job pattern (`ManifestBuildJob`, the fan-message claim).

No new runtime component and no Kafka. Payzone stays simulated. No hosting-provider dependency.

**Exit, demo-complete.** Locally, provider-free, against the dev backend:

1. In B2, OPS adds a category "Tarif membres" (Accès: Membres) next to "Plein tarif".
2. In B13, OPS sets up two phases:
   - phase 1, "Prévente membres": rule "membre inscrit avant le" a date;
   - phase 2, "Vente générale": its own sale start the next day.
3. Screen 2 and W2 show "Prévente membres · tirage à {time}" and "Ensuite : Vente générale · file dès {time}".
4. A non-member gets "Vente réservée aux fans vérifiés" plus the next-phase line. A member joins, is drawn, buys a member-price place, and pays (simulated).
5. When phase 2's pre-queue opens, phase 1 ends by itself. The member's leftover presale token is refused (`ADMISSION_REQUIRED` `INVALID`). Every fan joins phase 2.
6. In phase 2, a non-member picking "Tarif membres" gets "Tarif réservé aux membres inscrits avant le {date}" on that holder row.
7. A fourth friend's order naming a phone that already holds 4 places gets "Ce numéro a atteint la limite de 4 billets".
8. The event sells out and OPS ends the room. Screen 1 shows "Liste d'attente". Two fans join.
9. An ADMIN refunds one ticket. The refunded ticket no longer counts toward the buyer's limit (Q52: two more places → `PER_ACCOUNT_LIMIT` `held` 3). Within about 30 s, the oldest waitlisted fan gets "Des places se sont libérées" (push, plus WhatsApp in the simulated provider's log) and buys the place. OPS then releases one more place: no second notice inside the 10 min cooldown, and the refunded buyer buys it.
10. `check.sql` is clean.

CI green on: `Lot5FairSaleFlowTest`, `FairLimitsApiTest`, `WaitlistApiTest`, `WaitlistJobTest`, the extended `MultiInstanceTest`, every step-1 test moved to `/rooms/1`, `apps/fan/test/queue_test.dart` and `waitlist_test.dart`, shop `queue.test.tsx` and `waitlist.test.tsx`, back-office `room.test.tsx`.

### 10.2 What it builds on, spec refs

| Area | State on `main` (58730e8) | Step 2 use |
| --- | --- | --- |
| Rooms | `waiting_rooms.event_id unique`. `WaitingRoom.saleStartsAt` comes from `events.sale_starts_at` (`JdbcWaitingRooms.SELECT`). `RoomMemo` maps event id → room. `QueueService.join` uses `rooms.find(org, event)`. `requireAdmission` filters the memo by `statusAt(now).live()`. Tokens carry `evt`, not the room | Phase-aware: effective start = `coalesce(r.starts_at, e.sale_starts_at)`; memo = event → rooms by phase; one domain function picks the **current phase**; tokens gain `rm` |
| Tick | `RoomTicks.tick`: SCHEDULED → PRE_QUEUE → draw → OPEN; `measured_rate_per_min` stays 0 until the first re-mark at 60 s | + ends itself when the next phase's `opens_at` passes; seeds the measured rate at the draw |
| Limits | `JdbcOrders.OWN_HELD` sums RESERVED/AWAITING_CASH/PAID places with refunded tickets still counted (`ponytail:` in `TicketAdminService`). `accounts` (buyer + `pays` members) are locked in UUID order before the limits are read | Subtract REFUNDED tickets; add every holder account to the lock set |
| Membership | `MembershipService.liveMemberSince(org, user)`, used by room eligibility | Members-only check |
| Notifications | `FanMessageQueue`, `fan_messages` with a subject constraint, the STALE rule, a rate-capped drain under advisory class 6, `/events/{id}` already a valid push path | New kind `WAITLIST_OPEN` with `event_id` |
| Shop | Bare `/events/{id}` already 308s (`parseEventId`) | WhatsApp waitlist link |

Spec refs:

- `CLAUDE.md`: non-negotiables and conventions.
- `docs/00-product.md`: §Why it can win ("Fairness"), §V1 in lots (Lot 5).
- `docs/02`: §Data model, threat model "Bots on on-sale day" (line 193).
- `docs/03`: `price_categories`, `order_items.holders`, `tickets`, `waiting_rooms`, `memberships`, `fan_messages`, `message_kind`.
- `docs/04`:
  - Errors: `EVENT_NOT_ON_SALE`, `PER_ACCOUNT_LIMIT`, `ADMISSION_REQUIRED`, `ROOM_STATUS`;
  - §Fan: `/fan/events*`, `/fan/queue`, `POST /fan/orders`, transfer;
  - §Fan order rules, §Split pay Limits, §Notifications, §Waiting room;
  - §Organizer: prices, room rows, event PATCH, refund.
- `docs/06`.
- `design/DESIGN.md`: screens 1, 2, 3, 4, 8, 12; W2, W3, W8; B2, B13; Lot 1 adjustments (screen 1 "Liste d'attente → Lot 5"); Lot 5 adjustments.
- Plans: lot-1 Q18; lot-3 Q52; lot-4 Q57, Q62; lot-5 Q143, Q149, Q150, Q154, Q156, Q165, §7.

### 10.3 Open questions (each has a default, none blocks)

| # | Question | Default for step 2 |
| --- | --- | --- |
| Q167 | Phase model | **Up to 5 rooms per event, numbered 1–5.** Phase 1 starts at `events.sale_starts_at`, as in step 1, and follows event PATCHes while `SCHEDULED`. Phase ≥ 2 has its own `starts_at`. Each phase is a full step-1 room: its own pre-queue, draw, rate and eligibility rule. **Current phase** = the latest phase whose `opens_at` has passed and that isn't `ENDED`; otherwise the earliest phase not `ENDED`. A phase ends by itself when the next phase's `opens_at` passes, so at most one phase sells at a time. Order rule: `opens_at(n) > sale_starts_at(n−1)` for consecutive phases that aren't `ENDED`. Phases are numbered contiguously. A phase entered by mistake is ended (step 1's "any but ENDED → ENDED"); there is no DELETE. |
| Q168 | Phase label | **Optional free text, 1–40 characters**, shown to fans as typed ("Prévente membres"), like event titles (organizer content, one language). Null shows no label, and the next-phase line falls back to "Prochaine vente". Deriving names from the rule was rejected: every client would need the same fuzzy mapping (a general sale can carry an account-age rule too). |
| Q169 | Between phases (presale ended early by hand, next phase not yet open), and presale tokens | **Orders are refused** with 409 `EVENT_NOT_ON_SALE` `NOT_STARTED` and `sale_starts_at` set to the next phase's start, decided from the memo with no SQL. Once an event has phases, nobody buys outside a room until the last phase ends. A presale admission token is **refused as soon as the next phase is current** (`rm` claim). An order already reserved keeps its 8-min hold. The presale's buying stops at the general sale's pre-queue opening, which B13 shows. |
| Q170 | Members price: who must be a member? | **Each place of a `MEMBERS` category, through its holder.** A place without a phone is the buyer's. Otherwise a member could buy 4 member-price places for non-members. Split `pays` holders are holders like the others. Refusal: 409 `MEMBERS_ONLY` with the holder path, and no reason about another phone's account. That is the same yes/no signal Q149 accepted. |
| Q171 | Members price with free membership (lot-4 Q57) | **A live membership whose `joined_at` < the event's `sale_starts_at`.** Joining the club during the sale doesn't unlock the price. No `sale_starts_at` (on sale from publish) means any live member counts. No per-category date column. Paid membership (step 3) can tighten this later. |
| Q172 | Members price elsewhere | **POS:** not checked; the cashier checks the card. **`from_price_mad`:** the lowest remaining `ALL` price, so the public "dès" is a price anyone can pay; null if none is left. **`sold_out`:** unchanged (every category). **Waitlist `R`:** every category (`ponytail:` member-only leftovers notify non-members too). **Org prices:** `audience` editable at any time, applies to new orders (like the price). |
| Q173 | Holder cap (lot-1 Q18, Q150) | **Yes, at `per_account_limit`, with no new column.** For each holder account in a request (the buyer's own places included), count: its places in `RESERVED`/`AWAITING_CASH` orders of the event (any buyer), plus its `VALID`/`BLOCKED` tickets of the event, plus the new places. The total must be ≤ the limit. Checked online at place and at transfer (recipient), so a farm can't pool tickets on one phone afterwards. Not checked at POS or on re-issue (count unchanged). **Exact:** holder accounts join the existing per-account lock set (UUID order); a transfer takes the recipient's lock after the ticket row. `ponytail:` a late success's re-reserve re-checks the buyer limit only, not holders (rare; it falls back to a manual refund otherwise). |
| Q174 | Holder-cap refusal privacy | 409 `PER_ACCOUNT_LIMIT` with `details.reason` `HOLDER`, `field` and `limit`; **never `held`**. Pass/fail still says "this phone holds the limit" (accepted, as in Q149). |
| Q175 | Refunded places (lot-3 Q52) | **A `REFUNDED` ticket of the caller's own `PAID` order no longer counts**, for the buyer limit and for a split member's own limit. The order stays `PAID`. Transfers change nothing (tickets move, orders don't). `check.sql`'s limit-breach check subtracts them too. The `TicketAdminService` `ponytail:` goes. |
| Q176 | Waitlist: notify, or hold places for waitlisters? | **Notify only, no hold, no priority.** Notified fans buy like anyone, first come, first served. An offer/hold system (reserved place, accept window, cascade) is a different build. Copy is honest: "Premier arrivé, premier servi." |
| Q177 | Waitlist: how many and how often | **One fan per released place** (`R` = Σ `remaining`), oldest `joined_at` first, at most 1,000 per run, then **10 min cooldown per event** (`waitlist.cooldown`). Each entry is notified once; joining again after a notice puts the fan at the back. `ponytail:` with about 2.6 places per order, some notified fans will find it gone. Lower the ratio if the club hears complaints. |
| Q178 | Waitlist channels and clients | **PUSH + WHATSAPP** (template `waitlist_open`: event title + `{SHOP_ORIGIN}/events/{id}`), same pattern as `SPLIT_INVITE`. **App** (screens 1, 2) and **shop** (W2), because shop fans have no push. WhatsApp needs Meta template approval and is simulated until BE-36. |
| Q179 | Waitlist vs rooms; retention | Join refused while any phase isn't `ENDED` (409 `WAITLIST_CLOSED` `ROOM`): the room is the queue (Q154). The notify job skips such events too. Rows are deleted on leave and 30 days after the event's `starts_at` (`waitlist.retention`). Data: user + event, already purchase-relationship data. Founder: mention "availability notices" in the CNDP declaration. |
| Q180 | First-minute wait estimate (WS-12 real run) | **The tick seeds `measured_rate_per_min` with `admission_rate_per_min` at the room's first ranking tick** (the draw, or the opening when there's no pre-queue). Real measurement takes over at the 60 s re-mark. One line in `RoomTicks`, no new column. B13 "Cadence" shows the seed during that minute. |
| Q181 | `poll_after_s` near the front | **`estimated_wait_s` < 20 → 2 s** (± 20 %); < 120 → 5; else 15. Extra load ≈ rate ÷ 3 fans polling every 2 s (≈ 85 req/s at 500/min), no SQL. The poll limiter (1 s) still holds. A fan admitted on the next tick learns it within about 2 s instead of about 17 s. |
| Q182 | "matchs" vs "événements" in the eligibility copy | **By the event's `event_type`:** `FOOTBALL_MATCH` → "matchs au stade", else "événements". UI only. Same strings in app, shop and B13. |
| Q183 | Per-phase quotas (e.g. presale capped at N places)? | **No.** Quotas stay per category. The organizer uses a `MEMBERS` category or edits quotas between phases. |

### 10.4 Contract changes

#### 10.4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P117 | 03 `waiting_rooms` | `event_id unique`: one room per event, no presale | `phase`, `label`, `starts_at`, `unique (event_id, phase)` |
| P118 | 04 `/org/events/{id}/room*` | Singular resource | `/org/events/{id}/rooms` and `/rooms/{phase}` (old paths removed) |
| P119 | 04 `/fan/events*` `room` | One room, no phase info, no "what's next" | Current phase + `phase`, `label`, `sale_starts_at`, `next` |
| P120 | 04 §Waiting room | Memo, tokens and admission assume one room | Current phase; `rm` claim on both tokens; auto-end; gap → `NOT_STARTED` |
| P121 | 04 §Waiting room `poll_after_s`, Tick | No estimate in the first minute; 15 s polls at the front | Seed the measured rate; 2 s under 20 s of wait |
| P122 | 03 `price_categories`, 04 prices, fan detail | No members-only price; `from_price_mad` could show a member price | `audience`; `from_price_mad` over `ALL` |
| P123 | 04 POST `/fan/orders` | No members check | `MEMBERS_ONLY` |
| P124 | 04 POST `/fan/orders`, transfer; 03 `order_items` | No holder cap (lot-1 Q18) | Holder limit; GIN index on `holders` |
| P125 | 04 order rules, `JdbcOrders.OWN_HELD`, `check.sql` | Refunded places still count (lot-3 Q52) | Subtract REFUNDED tickets of PAID orders |
| P126 | 03, 04 | No waitlist | `waitlist_entries`, `fan_messages.event_id`, `WAITLIST_OPEN`, `/fan/waitlist/{event_id}`, §Waitlist, `WAITLIST_CLOSED` |
| P127 | `design/DESIGN.md` | Screen 1's "Liste d'attente" is unspecified; nothing for phases, members prices, holder errors; WS-12 follow-ups | §10.4.6 |
| P128 | 02, 06, `EventLock` javadoc, config | New tables/columns, terms, advisory class 7 | §10.4.5 |
| — | Football names / floats / PII in QR or tokens / gate path | Names: `phase`, `label`, `audience`, `waitlist_entries` (no football names; "matchs" only in UI copy by `event_type`). No new money column (a member price is a category's existing `price_mad` in centimes). `rm` is a room UUID: no phone, name or device id. WAITLIST_OPEN payload: title, body, path. Gate, scanner, manifest, `packages/core` untouched | OK |

#### 10.4.2 `docs/03-schema.sql`

```sql
-- ---------- catalog: price_categories (add) ----------
create type price_audience as enum ('ALL','MEMBERS');
  audience      price_audience not null default 'ALL',  -- MEMBERS: each place held by a live member of the organizer who joined
                                                         -- before events.sale_starts_at (docs/04-api.md POST /fan/orders)

-- ---------- sales: order_items (add index) ----------
create index on order_items using gin (holders jsonb_path_ops);  -- holder limit: places one account holds (docs/04-api.md POST /fan/orders)

-- ---------- sales: waiting rooms (change) ----------
create table waiting_rooms (                          -- high-demand on-sale (docs/04-api.md §Waiting room); one per phase of an event
  ...
  event_id                integer not null references events(id),   -- no longer unique (Lot 5 step 2)
  phase                   smallint not null default 1 check (phase between 1 and 5),
                          -- sale phases in order; a phase ends when the next one's opens_at passes
  label                   text check (char_length(label) between 1 and 40),  -- shown to fans as typed ('Prévente membres'); null = none
  starts_at               timestamptz,   -- this phase's sale start (its draw); null for phase 1 = events.sale_starts_at
  ...
  unique (event_id, phase),
  constraint waiting_rooms_starts check ((phase = 1) = (starts_at is null))
);

-- ---------- sales: waitlist (new) ----------
create table waitlist_entries (                       -- sold-out waitlist (docs/04-api.md §Waitlist); one per account per event
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  user_id       uuid not null references users(id),
  joined_at     timestamptz not null default now(),   -- notice order; a join after a notice resets it (back of the list)
  notified_at   timestamptz,                          -- set with the WAITLIST_OPEN messages, in one transaction
  unique (event_id, user_id)
);
create index on waitlist_entries (event_id, notified_at, joined_at);
-- Rows are deleted on leave and 30 days after the event's starts_at (waitlist.retention).

-- ---------- notifications (change) ----------
create type message_kind as enum ('TICKET_RECEIVED','SPLIT_INVITE','PAY_CODE','ORDER_PAID','CAMPAIGN','WAITLIST_OPEN');
-- fan_messages, add:
  event_id         integer references events(id),        -- WAITLIST_OPEN
-- fan_messages_subject becomes:
  constraint fan_messages_subject check (
    (ticket_id is not null) = (kind = 'TICKET_RECEIVED')
    and (order_id is not null) = (kind in ('SPLIT_INVITE','PAY_CODE','ORDER_PAID'))
    and (event_id is not null) = (kind = 'WAITLIST_OPEN'))
-- No unique index for WAITLIST_OPEN: waitlist_entries.notified_at, set in the same transaction, makes it once per wait.
```

Flyway, one task per migration group:

| File | Task | Content |
| --- | --- | --- |
| `V25__fair_limits.sql` | BE-47 | `price_audience`; `price_categories.audience` (existing rows `ALL`); GIN index on `order_items.holders` |
| `V26__room_phases.sql` | BE-48 | `alter table waiting_rooms drop constraint waiting_rooms_event_id_key`; add `phase` (existing rooms = 1), `label`, `starts_at`; `unique (event_id, phase)`; `waiting_rooms_starts` |
| `V27__message_kind_waitlist.sql` | BE-49 | `alter type message_kind add value 'WAITLIST_OPEN'`, **alone**: Postgres can't use a new enum value in the transaction that adds it |
| `V28__waitlist.sql` | BE-49 | `waitlist_entries` + index; `fan_messages.event_id`; drop and re-add `fan_messages_subject` |

The tenant allowlist stays at 5 (`waitlist_entries` carries `organizer_id`).

#### 10.4.3 `docs/04-api.md`

**Errors: add or replace**

| Code | HTTP | When |
| --- | --- | --- |
| `EVENT_NOT_ON_SALE` | 409 | Append: "**Lot 5 step 2:** `NOT_STARTED` also between two phases (the current phase is `SCHEDULED`; §Waiting room Phases): `details.sale_starts_at` = that phase's sale start." |
| `PER_ACCOUNT_LIMIT` | 409 | Append: "**Lot 5:** `details.reason` `HOLDER`: a holder account (the buyer's own places included), or a transfer's recipient, would hold more than `per_account_limit` places of the event. Then `details.field` (the holder path, or `to_phone`) and `details.limit` only, never `held`." |
| `ADMISSION_REQUIRED` | 409 | `INVALID` becomes: "bad signature, another user, another event, or a room that isn't the event's current phase". |
| `MEMBERS_ONLY` | 409 | A place of a `MEMBERS` price category whose holder isn't a live member of the organizer who joined before the event's `sale_starts_at`. `details.field` (the holder path), `details.member_joined_before` (the event's `sale_starts_at`, or null when it has none). |
| `WAITLIST_CLOSED` | 409 | Waitlist join refused. `details.reason`: `NOT_SOLD_OUT` (places are left: buy), `ROOM` (a waiting-room phase isn't `ENDED`: use the room), `CLOSED` (the event isn't inside its sale window). |
| `ROOM_STATUS` | 409 | "Room PUT …" → "Room phase PUT …"; otherwise unchanged, per phase. |

**§Fan: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/fan/events`, `/fan/events/{id}` | **Lot 5** `room` replaced by: `room: {phase, label, status, opens_at, sale_starts_at, eligibility, next: {phase, label, opens_at, sale_starts_at} \| null} \| null`. It describes the event's **current phase** (§Waiting room Phases), and is null when the event has no phase or every phase is `ENDED`. `sale_starts_at` and `opens_at` are that phase's. `next` is the following phase not `ENDED`. `eligibility` as before, from the phase's rule. **Lot 5 step 2:** the detail's `prices[]` + `audience: ALL\|MEMBERS`. `from_price_mad` = the lowest `price_mad` among categories with `remaining > 0` **and `audience = ALL`**, null if none. `sold_out` unchanged. |
| POST | `/fan/queue` | Append: "**Lot 5 step 2:** joins the event's current phase. `QUEUE_NOT_OPEN`: `NO_ROOM` (no phase), `ENDED` (every phase `ENDED`), `NOT_STARTED` + `opens_at` (the current phase is `SCHEDULED`). An entry in an earlier phase doesn't carry over: the new phase creates a new entry." |
| GET | `/fan/queue` | Append: "**Lot 5 step 2:** `ENDED` also when the token's room is no longer the event's current phase. Clients then re-read `GET /fan/events/{id}`: a `room` means the next phase (join it), null means buy without a room." |
| POST | `/fan/orders` | **Lot 5** check list replaced: "Checks in order: the body's shape (422, no SQL); **admission** (from the per-instance memo and the token's signature, before any database read of the request): when the current phase is `PRE_QUEUE`, `OPEN` or `PAUSED`, header `X-Admission-Token` for that phase's room, else 409 `ADMISSION_REQUIRED`; when the current phase is `SCHEDULED`, 409 `EVENT_NOT_ON_SALE` `NOT_STARTED` with that phase's `sale_starts_at`; event (404); sale window (409 `EVENT_NOT_ON_SALE`); items and holders (422); queue (429 `SALE_BUSY`); per-account limit (409); **holder limit** (409 `PER_ACCOUNT_LIMIT` `HOLDER`); room eligibility of split `pays` members (409 `NOT_ELIGIBLE`, `details.field`); **members-only places** (409 `MEMBERS_ONLY`); quota (409 `SOLD_OUT`)." **Holder limit:** for each distinct holder account of the request (a place without `phone` is the buyer's), its places in `RESERVED`/`AWAITING_CASH` orders of the event, plus its `VALID`/`BLOCKED` tickets of the event, plus its places in this request, must be ≤ `per_account_limit`. `details.field` = that account's first place. **Members-only:** each place of a `MEMBERS` category is held by an account with a membership of the organizer, `left_at` null and `joined_at` < the event's `sale_starts_at` (any live member when it is null). `details.field` = the first such place that fails. Holder accounts are locked with the buyer's (per-account lock, UUID order), so both limits are exact. |
| GET | `/fan/waitlist/{event_id}` | `{entry: {joined_at, notified_at} \| null}` for the caller. Event not visible as in `GET /fan/events/{id}` → 404. `Cache-Control: no-store`. |
| PUT | `/fan/waitlist/{event_id}` | No body. Event not visible → 404. The event must be inside its sale window (`on_sale`), else 409 `WAITLIST_CLOSED` `CLOSED`; no waiting-room phase may be not `ENDED`, else `ROOM`; `sold_out` must be true, else `NOT_SOLD_OUT`. New → `201`. Already waiting (`notified_at` null) → `200`, unchanged. Already notified → moved to the back (`joined_at = now()`, `notified_at` null) → `201`. → the GET shape. Not audited. |
| DELETE | `/fan/waitlist/{event_id}` | Leave: the row is deleted → `204` (idempotent; unknown event → 404). |
| POST | `/fan/tickets/{id}/transfer` | Append: "**Lot 5:** the recipient's `VALID`/`BLOCKED` tickets of the event, plus its places in `RESERVED`/`AWAITING_CASH` orders, plus 1, must be ≤ `per_account_limit`, else 409 `PER_ACCOUNT_LIMIT` `HOLDER`, `details.field` `to_phone`. Read under the recipient's per-account lock, taken after the ticket row. Re-issue: unchanged." |

**§Fan order rules: replace "Σ qty over the buyer's …" and append.** "The limit counts places in the buyer's `RESERVED`, `AWAITING_CASH` and `PAID` orders for the event, **minus the `REFUNDED` tickets of those `PAID` orders** (Lot 5 step 2; also for a split member's own limit). … Lot 5 step 2: no account **holds** more than `per_account_limit` places of an event, whoever bought them (holder limit, checked on fan orders and transfers; not at POS). POS sales check neither the holder limit nor `MEMBERS` categories."

**§Notifications: add a row and extend the stale rule**

| Kind | Trigger | Channels and content |
| --- | --- | --- |
| `WAITLIST_OPEN` | The waitlist job picks the entry (§Waitlist) | PUSH: "Des places se sont libérées · {event title}" → `/events/{event_id}`. WHATSAPP (template `waitlist_open`): event title + `{SHOP_ORIGIN}/events/{event_id}` (the shop 308s it). Once per wait (the entry's `notified_at`) |

Stale rule, append: "WAITLIST_OPEN when the event is `sold_out` again or no longer `on_sale`."

**New §Waitlist**

| Rule | Value |
| --- | --- |
| Join | Logged-in fan, event on sale and `sold_out`, no waiting-room phase left that isn't `ENDED` (`PUT /fan/waitlist/{event_id}`). One entry per account per event. |
| Notify | Every 30 s (`waitlist.tick`), for each event with waiting entries, in one transaction under `pg_try_advisory_xact_lock` (class 7, event id), so one instance handles an event per run. Skipped when: the event isn't `on_sale`; any phase isn't `ENDED`; or an entry of the event got its notice in the last `waitlist.cooldown` (10 min). R = Σ `remaining` over the event's categories, read without a lock. The `least(R, 1000)` oldest waiting entries (by `joined_at`, `id`) get `notified_at = now()` and one `WAITLIST_OPEN` per channel. No hold, no priority: notified fans buy like anyone. |
| Retention | Deleted on leave and 30 days after the event's `starts_at` (`waitlist.retention`, daily). |
| Privacy | The organizer sees no list (no back-office view in Lot 5). Payloads carry the event title and a link only. |

**§Waiting room: add or replace**

| Rule | Value |
| --- | --- |
| Phases (new) | An event has 0–5 rooms, one per **phase** (1, 2, …), contiguous. Phase 1's sale start is `events.sale_starts_at`; later phases have their own `starts_at`. `opens_at` = sale start − `pre_queue_min`. For consecutive phases not `ENDED`: `opens_at(n) > sale start(n−1)`. **Current phase** = the latest phase not `ENDED` whose `opens_at` has passed, else the earliest phase not `ENDED`. Joins, polls, admission and `/fan/events` `room` use the current phase. `label` is shown as typed. |
| Lifecycle | Append: "→ `ENDED` also when the next phase's `opens_at` passes (the tick of that phase's room, audit `room.end` `{reason: NEXT_PHASE}`)." |
| Tick | Append: "Each room ticks on its own row (it reads the next phase's `opens_at` without a lock). At the room's first ranking tick, `measured_rate_per_min` = `admission_rate_per_min` (a seed until the first 60 s re-mark)." |
| Memo | Replace "event id → …" with "event id → its rooms not `ENDED`, by phase (each with `id`, `phase`, `label`, status, sale start, `pre_queue_min`, counts, `admission_rate_per_min`, rule)". The current phase is computed from the memo and the clock. |
| Queue token | Claims + `rm` = room id. |
| Admission | Admission token claims + `rm`. Acceptance: "iff the signature, `aud`, `evt` = the body's `event_id`, **`rm` = the current phase's room**, `sub` = the caller and `exp > now` all hold". |
| `poll_after_s` | Replace the `WAITING` clause: "`WAITING` or `PAUSED`: `estimated_wait_s` < 20 → 2; < 120 → 5; else 15; ± 20 % jitter." |
| Eligibility | "Checked at join" → "Checked at join, against the current phase's rule". |

**§Organizer: role line.** `/org/events/{id}/room*` → `/org/events/{id}/rooms*`.

**§Organizer: replace the three room rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/org/events/{id}/rooms` | `{rooms: [room]}` by `phase` (empty when none). `room` = step 1's shape + `phase`, `label`, `starts_at` (null for phase 1). `sale_starts_at` is the phase's. `figures` per room (`paid`, `quota`, `remaining`, `active_holds` stay event-wide). Unknown or other organizer's event → 404. |
| PUT | `/org/events/{id}/rooms/{phase}` | Step 1's body + `label?` (trimmed, 1–40, null = none) + `starts_at` (required for `phase` ≥ 2; must be absent or null for phase 1, else 422 on `starts_at`). `phase` outside 1–5 → 404. Phase n ≥ 2 needs phase n−1 (422, `details.reason` `PHASE_ORDER`). `starts_at` in the future and before the event's `starts_at − 60 min` (422). The phase order of §Waiting room Phases (422 on `starts_at`, or `pre_queue_min`, `PHASE_ORDER`). Otherwise step 1's rules, per phase (after `SCHEDULED` only the rate may change; `label` and `starts_at` are frozen too). Audit `room.create` / `room.update` + `phase`, `label`, `starts_at`. |
| POST | `/org/events/{id}/rooms/{phase}/pause` · `/resume` · `/end` | Step 1's rules, per phase. No such phase → 404. B13 warns before ending a phase while a later one is `SCHEDULED`: orders then wait for it (Q169). |
| PATCH | `/org/events/{id}` | **Lot 5** sentence, per phase 1: "moving `sale_starts_at` while phase 1 is `PRE_QUEUE`, `OPEN` or `PAUSED` → 409 `EVENT_NOT_EDITABLE` `ROOM_STARTED`; while `SCHEDULED` it follows, and a move that breaks the phase order → 422 on `sale_starts_at`, `details.reason` `PHASE_ORDER`." |
| GET, POST, PATCH | `/org/events/{id}/prices*` | + `audience: ALL\|MEMBERS` (POST optional, default `ALL`; PATCH any time, applies to new orders; another value → 422). |

Gate, manifest, scans, token and vectors: unchanged.

#### 10.4.4 Config (backend `application.yml`)

`waitlist.tick` (30s), `waitlist.cooldown` (10m), `waitlist.retention` (30d). The WhatsApp link uses the existing `jomhor.cors.shop-origin` (`SHOP_ORIGIN`). No new secret.

#### 10.4.5 `docs/02`, `docs/06`, `EventLock`

| Where | Change |
| --- | --- |
| 02 §Data model | `price_categories` + `audience (ALL, MEMBERS)`; `waiting_rooms` "one per event" → "one per sale phase of an event", `event_id` (no longer unique) + `phase`, `label`, `starts_at`; new row `waitlist_entries` (id, organizer_id, event_id, user_id, joined_at, notified_at; one per account per event); `fan_messages` kind + `WAITLIST_OPEN`, + `event_id` |
| 02:193 Bots | Defence + "a holder cap (no account holds more than `per_account_limit` places of an event, transfers included), members presale phases" |
| 06 | waitlist · `liste d'attente` · قائمة الانتظار · "sold out: we tell you when places come back". presale · `prévente` · البيع المسبق. sale phase · `phase de vente` · مرحلة البيع. general sale · `vente générale` · البيع العام. members price · `tarif membres` · سعر الأعضاء · "for members who joined before the sale opened" |
| `EventLock` javadoc (code, BE-49) | Class 7: the waitlist notify try-lock per event |

#### 10.4.6 `design/DESIGN.md`

**"Lot 5 adjustments", add rows:**

| Where | Change |
| --- | --- |
| Screen 1 hero / calendar | `sold_out`, on sale, `room` null: CTA "Liste d'attente" (signed out → login, then join). On the list: an outlined CTA "Sur la liste d'attente" with a check icon; tap → sheet "On te prévient dès que des places se libèrent. Premier arrivé, premier servi." + "Quitter la liste". Calendar chip "Complet". With `room` and a `label`: the room CTA states as before, prefixed by the label ("Prévente membres · file dès {time}"). |
| Screen 2 | Room banner line: "{label} · {status copy}". With `next`: one muted line "Ensuite : {next.label or 'Prochaine vente'} · file dès {next.opens_at}". Members categories: chip "Membres" on the row + caption "Tarif membres · inscrits avant le {sale_starts_at date}". Sold out: sticky CTA as screen 1's waitlist states + the info line "On te prévient par notification et WhatsApp". |
| Screen 3 | A `MEMBERS` zone price shows the "Membres" chip; nothing is pre-blocked (the server decides). |
| Screen 4 / W3 holder rows | `MEMBERS_ONLY` on a holder → under that row "Tarif réservé aux membres inscrits avant le {date}". `PER_ACCOUNT_LIMIT` `HOLDER` → "Ce numéro a atteint la limite de {limit} billets pour cet événement". |
| Screen 8 Transférer | `PER_ACCOUNT_LIMIT` `HOLDER` on `to_phone` → the same line under the phone field. |
| 12 · other states | `NOT_ELIGIBLE` with `room.next`: + "Ensuite : {next.label or 'Prochaine vente'} · file dès {date}". `ENDED` → re-read the event: `room` present → screen 2 (its CTA joins the next phase; no auto-join); null → screen 3. Eligibility copy by `event_type` (Q182). |
| W2 | As screen 2, plus "Reprendre ma place" when the browser holds a queue token for the event. Waitlist states in the aside (as screen 1) when sold out. |
| W3 | While admitted, the slim bar "Ton tour · {mm:ss}" above the content (as screen 4). |
| W8 | Screen kept awake while visible (Wake Lock API; silently none where unsupported). `ENDED` as screen 12. |
| B2 Tarifs & quotas | Column "Accès": select Tous · Membres (helper: "Membres inscrits avant l'ouverture de la vente"). B2 Règles: "File d'attente · {n} phase(s) · {current status}" + "Gérer". |
| B13 Salle d'attente | **Phases:** one card per phase (N°, label, status badge, sale start and pre-queue time in the venue zone), the live KPIs of step 1 inside the card of the live phase, controls per phase. "Ajouter une phase" (until 5): the step-1 form + "Libellé" (optional, 40) + "Début de la vente" (phase ≥ 2, date-time in the venue zone). `PHASE_ORDER` → field error "Doit ouvrir après le début de la phase précédente". Ending a phase while a later one is scheduled: confirm "Les ventes restent fermées jusqu'à la phase suivante." Eligibility label by `event_type` (Q182). |

### 10.5 Tasks

#### Wave 0: orchestrator and founder (before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/02`, `03`, `04`, `06`, `design/DESIGN.md` | §10.4 applied after the founder agrees · **done 2026-11-15** |
| W0-2 | orchestrator | git | `feat/lot-5-fair-sale` from `main`. No new CI job |
| W0-3 | founder | — | The product calls (Q168–Q171, Q173, Q176–Q178, Q183). Club: member price per place or per buyer? Presale dates? Meta template `waitlist_open` once the WhatsApp provider exists (BE-36). CNDP: "availability notices" |

#### Tasks

`backend` is serial and on the critical path. ∥ rows run alongside, in disjoint folders. *(contract-first)* = the task starts on the §10.4 shapes with fixtures, and is done only after one run against the real endpoint. Every client task ships fr/ar/en strings (no hard-coded strings or colours). The fan app ships FR + AR goldens of the screens it touches.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-47 | backend | V25; `backend/…/catalog` (`audience` on org prices and the fan detail, `from_price_mad`, `Inventory.SalePrice` + audience); `…/sales/application/OrderService` (holder accounts in the lock set, holder limit, `MEMBERS_ONLY` through `MembershipService`, javadoc lock order); `…/sales/adapters/persistence/JdbcOrders` (`OWN_HELD` minus REFUNDED tickets; holder count via `holders @>` + tickets); `…/tickets/application/TicketTransferService` (recipient lock + holder limit); `…/tickets/application/TicketAdminService` (drop the `ponytail:`); `backend/loadtest/check.sql` | Members prices, holder cap, Q52 | **`FairLimitsApiTest`.** (a) **Q52:** a buyer at 4/4 PAID, one ticket refunded → an order of 1 → 201, of 2 → 409 `PER_ACCOUNT_LIMIT` held 3. A split member's refunded ticket frees their own limit. A late success's re-reserve counts the same. (b) **Holder:** H holds 3 VALID tickets (bought by A) + 1 place in B's RESERVED order; C's order naming H → 409 `{reason: HOLDER, field: "items[0].holders[1]", limit: 4}`, key-set exactly that (no `held`). B's order expires → 201. CANCELLED (transferred away) and REFUNDED tickets don't count. The buyer's own places count. Two parallel orders naming the same new phone for 3 places each → exactly one 201. POS naming H at the limit → 201. (c) **Transfer** to H at the limit → 409 `HOLDER` `to_phone`; below → 201; re-issue unaffected. (d) **Members:** a non-member, a member who joined after `sale_starts_at`, a member who left → 409 `MEMBERS_ONLY` + `member_joined_before`; a member from before → 201. A member buyer naming a non-member on a `MEMBERS` place → 409 on that holder. An `ALL` place for the non-member in the same order is fine. No `sale_starts_at` → any live member. POS → 201. (e) **Prices:** POST defaults `ALL`, PATCH → `MEMBERS` visible on GET; bad value → 422; fan detail `audience`; `from_price_mad` ignores `MEMBERS` (null when no `ALL` place is left). `CheckSqlTest`: a refund-then-rebuy is no limit-breach row; a real breach still is. `MigrationsTest` V25: existing categories `ALL`, GIN index present. Every existing order, split, POS and transfer test green | W0-1 |
| 1 ∥ | BO-21 | backoffice | `web/backoffice/src/features/room` (phases), `src/features/events` (Tarifs "Accès" column, Règles phases row), `src/i18n/fr.ts` | B13 phases, B2 audience | `room.test.tsx`: `GET /rooms` list → one card per phase; "Ajouter une phase" for phase 2 needs "Début de la vente" (zod) and sends `PUT /rooms/2` with `label`, `starts_at`; phase 1 sends no `starts_at`; `PHASE_ORDER` → field error; controls hit `/rooms/{phase}/…`; end-with-later-phase confirm copy; refetch 2 s only while a phase is live; FINANCE no writes; query keys include organizer and phase. Prices test: "Accès" select, PATCH sends `audience`. Règles: "{n} phase(s)". Eligibility label by `event_type`. No call to the old `/room` path left | BE-47, BE-48 *(contract-first)* |
| 1 ∥ | FL-27 | flutter | `apps/fan/lib/features/queue`, `features/events` (screen 2 room line, next phase, members chip), `features/checkout` (holder-row errors), `features/tickets` (transfer sheet error), `l10n/*.arb`, `test/queue_test.dart`, `test/goldens_test.dart` | Phases, members prices, holder errors, copy by `event_type` | Fake API: screen 2 shows "{label} · …" and the `next` line (label null → "Prochaine vente"); `NOT_ELIGIBLE` panel + next-phase line; poll `ENDED` → re-reads the event: `room` present → screen 2 with "Rejoindre la file" (no POST made), null → screen 3; "Membres" chip + caption date; `MEMBERS_ONLY` and `PER_ACCOUNT_LIMIT` `HOLDER` map to the holder row by `field`; `HOLDER` on the transfer sheet's phone; "matchs" vs "événements" by `event_type` in `roomMinEvents`/`queueNotEnoughEvents`. Goldens fr + ar: screen 2 with presale + next, screen 3 with a members category, screen 4 holder-row errors | BE-47, BE-48 *(contract-first)* |
| 1 ∥ | WS-13 | web-shop | `web/shop/app/(public)/[club]/events/[slug]/` (`event-view`, `ZonePicker`, `queue/QueueRoom.tsx`), `app/(public)/orders/new/` (turn bar, holder errors), `lib/queue.ts`, `lib/i18n/{fr,ar,en}.ts` | WS-12 follow-ups, phases, members prices, holder errors | `queue.test.tsx`: the W3 (`orders/new`) turn bar while admitted, counting to `expires_at`, absent otherwise; W2 "Reprendre ma place" when a queue token for the event is stored; W8 calls `navigator.wakeLock.request('screen')` while visible, releases on `visibilitychange` hidden and on unmount, no error when unsupported; copy by `event_type`; W2 label + `next` line; W8 `ENDED` → re-reads the event → W2 room panel or zone picker; "Membres" chip; `MEMBERS_ONLY` / `HOLDER` copy on the holder field by `field`. No JWT or K_t in HTML/RSC; `rtl.test` green | BE-47, BE-48 *(contract-first)* |
| 2 | BE-48 | backend | V26; `backend/…/sales/rooms` (`WaitingRoom` + phase, label, startsAt, a static `current(rooms, now)`; `RoomMemo` event → rooms; `QueueService` join/poll/admission by the current phase; `RoomTokens` `rm`; `RoomTicks` self-end + rate seed + `poll_after_s` < 20 → 2; `JdbcWaitingRooms` effective start + next `opens_at`; `OrgRoomController` → `/rooms`, `/rooms/{phase}`); `…/catalog` (`EventRooms.PublicRoom` + phase, label, saleStartsAt, next; PATCH `PHASE_ORDER`); every step-1 test, `backend/http/lot5-onsale.http`, `backend/loadtest/k6/onsale.js` moved to `/rooms/1` | Rooms per phase + the WS-12 real-run follow-ups | `WaitingRoomOrgApiTest` (rewritten): `{rooms: []}`; PUT `/rooms/1` → 201 with `starts_at` null; `/rooms/1` with `starts_at` → 422; `/rooms/3` without 2 → 422 `PHASE_ORDER`; `/rooms/2` without `starts_at` → 422; `opens_at(2)` ≤ phase 1's start → 422 `PHASE_ORDER`; label 41 chars → 422; `/rooms/6` → 404; old `/room` → 404; controls per phase; event PATCH breaking the order → 422 `PHASE_ORDER`; FINANCE read, CASHIER 403, org B 404. `RoomTickTest` (fixed clock): at phase 2's `opens_at`, phase 1 ENDS once (one audit `room.end {reason: NEXT_PHASE}`), phase 2 goes `PRE_QUEUE` and draws at its own `starts_at` (ranks 1..n in its room); measured rate = configured at the draw, real after 60 s. `WaitingRoomFanApiTest`: phase 1 with a member rule → non-member `NOT_ELIGIBLE`; `room` = phase 1 with `next`; once phase 2 is current a join creates a new entry (201); a phase-1 token polls `ENDED`; token key-sets + `rm`; `poll_after_s` 2 / 5 / 15 at waits 10 / 60 / 300 s; `estimated_wait_s` non-null in the first minute. `AdmissionApiTest`: a phase-1 admission token once phase 2 is current → `INVALID`; gap (phase 1 ended, phase 2 `SCHEDULED`) → 409 `EVENT_NOT_ON_SALE` `NOT_STARTED` with phase 2's start and **zero SQL** (statement counter); all phases `ENDED` → no token needed; ranked poll still zero SQL. `FanEventApiTest`: `room` phase/label/next; null when every phase is `ENDED`. `MultiInstanceTest`: two instances at phase 2's `opens_at` → phase 1 ended once, one audit. `MigrationsTest` V26: step-1 rooms → phase 1, `starts_at` null. `Lot5OnSaleFlowTest`, `OnSaleTwoInstanceTest`, `CheckSqlTest` green on `/rooms/1`; k6 `onsale.js` 30 s smoke still passes | BE-47 |
| 3 | BE-49 | backend | V27, V28; `backend/…/sales/waitlist` (new: `WaitlistService`, `FanWaitlistController`, `JdbcWaitlist`, `WaitlistJob` under try-lock class 7, daily purge); `…/notifications` (`WAITLIST_OPEN` kind, `FanMessageQueue.waitlistOpen`, push path, WhatsApp template params, STALE rule); `src/main/resources/messages*` (fr, ar, en); `common/EventLock` javadoc (class 7); `application.yml` (`waitlist.*`) | Waitlist + notice | `WaitlistApiTest`: GET `{entry: null}`; PUT with places left → 409 `WAITLIST_CLOSED` `NOT_SOLD_OUT`; sold out, on sale, no room → 201, again → 200 (same `joined_at`); a phase not `ENDED` → `ROOM`; after `refund_cutoff_at` → `CLOSED`; DRAFT or unknown → 404; no token → 401; DELETE → 204 twice; another user's entry never visible. `WaitlistJobTest` (fixed clock): 10 waiting; one 3-place order expires → the 3 oldest get `notified_at` and 2 `fan_messages` each (`WAITLIST_OPEN`, PUSH + WHATSAPP, `event_id` set); a run inside 10 min → nothing; after 10 min with 1 place → the 4th; a notified fan re-joining goes to the back (201); a live phase or a closed sale → nothing; purge at `starts_at` + 30 d. `FanMessageServiceTest`: push `/events/{id}`, fr/ar/en copy, payload key-set `{title, body, path}`; WhatsApp params `{title, link}` with `SHOP_ORIGIN`; `STALE` when sold out again or sales closed. `MultiInstanceTest`: both instances run the job at once → the R oldest notified exactly once. `MigrationsTest` V27/V28: allowlist still 5; `fan_messages_subject` accepts `WAITLIST_OPEN` only with `event_id` | BE-48 |
| 3 ∥ | FL-28 | flutter | `apps/fan/lib/features/waitlist` (new: repository, controller), `features/events` (screen 1 hero + calendar chip, screen 2 sticky CTA), `l10n/*.arb`, `test/waitlist_test.dart`, `test/goldens_test.dart` | Screen 1 "Liste d'attente" | Fake API: sold out + on sale + `room` null → hero CTA "Liste d'attente"; signed out → login then PUT; on the list → outlined state, sheet, "Quitter la liste" → DELETE; each `WAITLIST_CLOSED` reason refreshes the event and shows its state; `sold_out` false → the normal CTA; a WAITLIST_OPEN push `/events/{id}` opens screen 2 (existing router); no polling of the waitlist (read on screen open). Goldens fr + ar: hero waitlist, on the list, screen 2 sold out | BE-49 *(contract-first)*; FL-27 (same `.arb` files) |
| 3 ∥ | WS-14 | web-shop | `web/shop/app/(public)/[club]/events/[slug]/` (aside waitlist panel), `app/api/waitlist/[eventId]/route.ts` (new), `lib/waitlist.ts` (new), `lib/i18n/{fr,ar,en}.ts` | W2 waitlist | `waitlist.test.tsx`: W2 sold out → "Liste d'attente"; signed out → `/login?next=…`; route handler: cross-origin → 403, forwards the Bearer from the cookie, GET/PUT/DELETE pass-through; states and `WAITLIST_CLOSED` copy; `/events/{id}` (bare id, the WhatsApp link) → 308 to the canonical W2; renders in ar; `rtl.test` green | BE-49 *(contract-first)*; WS-13 (same i18n files) |
| 4 | BE-50 | backend | `backend/src/test/…/Lot5FairSaleFlowTest`, `backend/http/lot5-fair-sale.http` | Exit flow | **`Lot5FairSaleFlowTest`:** an event with "Plein tarif" (`ALL`) and "Tarif membres" (`MEMBERS`); phase 1 "Prévente membres" (member before a date), phase 2 "Vente générale". In phase 1 a non-member is `NOT_ELIGIBLE` and sees `next`; a member is admitted and buys at the member price → tickets. Phase 2 opens → phase 1 `ENDED`, the presale token → `INVALID`. In phase 2 a non-member's member-price place → `MEMBERS_ONLY`; an order naming a phone at its limit → `HOLDER`; the event sells out; the room ends; two fans join the waitlist; an ADMIN refund frees the buyer's limit (Q52) and one place → the job notifies the oldest fan only; that fan buys; `check.sql` returns no rows | BE-49 |
| exit | — | orchestrator + founder | — | Demo per §10.1 locally | All rows above green; manual demo | all |

`reviewer` runs after every builder task. Backend runs serially: BE-47 → BE-48 → BE-49 → BE-50. Clients run on fixtures alongside and do their real run once BE-49 lands.

**Cut order (if needed):**

1. WS-14 (W2 waitlist; the app and WhatsApp still serve);
2. the WhatsApp channel of `WAITLIST_OPEN` (push only);
3. phase labels (null everywhere);
4. the W8 Wake Lock.

**Never cut:**

- admission and the gap decided from the memo with zero SQL;
- `rm` binding;
- exact holder and buyer limits under the per-account locks;
- members cutoff at `sale_starts_at`;
- the waitlist notice once per wait across instances;
- tenant scoping of `waitlist_entries`;
- no PII in tokens or payloads;
- the gate untouched.

### 10.6 Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Current phase | One domain function used by the memo, join, poll, admission and `/fan/events` (no second copy in catalog). Admission and the gap rule from `RoomMemo` + the signature only (statement counter, including the `NOT_STARTED` path). `rm` checked on admission and poll. Each room's tick writes only its own row (the next phase's `opens_at` read without a lock). Auto-end audited once. Phase 1 still derives its start from the event |
| Limits and locks | Holder accounts added to the existing `accounts` lock set before any limit is read (UUID order). Transfer: ticket row → recipient lock, never the reverse. The `OrderService` lock-order javadoc updated, with no new cycle (place, settle, expiry, refund, transfer). `OWN_HELD` subtracts only REFUNDED tickets of the user's own PAID orders. The holder count uses the GIN index (`holders @>`), not a scan of the event's orders. `PER_ACCOUNT_LIMIT` `HOLDER` details carry no `held`. Re-reserve's holder `ponytail:` present |
| Members | Membership read through `MembershipService`, never crm's repository. Cutoff = the event's `sale_starts_at` (null → any live member). Checked per place via its holder. POS untouched. `from_price_mad` over `ALL` only. Error details about another phone carry only `field` + the public date |
| Waitlist | Try-lock class 7 + the cooldown read inside it: two instances never notify the same event twice in a period (`MultiInstanceTest`). `notified_at` and the messages in one transaction. R read without locks; capped at 1,000. Join conditions in contract order. STALE at send. Push payload `{title, body, path}`; WhatsApp `{title, link}`; no phone or name. Purge. `organizer_id` on every statement |
| Migrations | `ADD VALUE` alone in V27. Constraint name dropped correctly in V26 (`waiting_rooms_event_id_key`). Step-1 rooms → phase 1. 03 and 02 match the migrations |
| Contract | Every new or changed 04 row has a contract test; the old `/room` path is gone from backend, BO, `http/`, k6; error codes and reasons exactly as 04 |
| Clients | Rules decided by the server (no client-side membership or holder maths); errors mapped by `details.field`; `ENDED` re-reads the event (no auto-join); waitlist read on open, not polled; Wake Lock released; strings fr/ar/en; RTL mirrored with numbers LTR; goldens fr + ar |
| Names / money / gate | No football names in tables or columns (copy by `event_type` is UI). No float. Zero diff under `…/gate`, scanner, `packages/core` |

### 10.7 Risks

| Risk | Mitigation |
| --- | --- |
| Free membership makes member prices easy to farm | Cutoff at the event's `sale_starts_at` (Q171); the presale phase can add account age; paid membership in step 3 |
| An organizer ends the presale early and sales stop | By design (Q169); B13 confirm copy; OPS can resume nothing, so the copy says so; the next phase opens on time |
| Presale fans mid-checkout when the general phase opens | Their 8-min hold stands; only new orders need the new phase's token; B13 shows when the presale stops (the next pre-queue opening) |
| The holder cap blocks real groups (one parent holding the family's tickets) | Same number as `per_account_limit` (raise it per event); a clear message on the holder row; POS unaffected |
| GIN index write cost on `order_items` at derby order rates | One GIN insert per order item, at about 10 orders/s; measured in the deferred §5.4 run; drop to a jsonb scan behind the `ponytail:` if it shows |
| Waitlist notices disappoint (places gone) | One fan per place, cooldown, honest copy "Premier arrivé, premier servi"; ratio adjustable (Q177) |
| WhatsApp template not approved / provider not live (BE-36) | Push still works; the WhatsApp row fails `NOT_CONFIGURED`, as today; shop-only fans wait for BE-36 |
| Old room paths still called somewhere | BE-48 greps backend/http/k6; BO-21 removes the client; the reviewer checks |
| More 409 shapes for clients to map | One mapping by `details.field` in each client; fixture tests per reason |

### 10.8 Deliberately left out

- **Per-phase quotas (Q183).** Waitlist offers, holds or priority (Q176). Waitlist per zone or category (the list is per event).
- **Back-office waitlist view or counts.** Add a `waitlist` count to `/sales` when the club asks.
- **Holder-limit column per event or category** (it reuses `per_account_limit`). Holder cap at POS and on a late success's re-reserve.
- **Members price at POS.** Derived phase names (Q168). DELETE of a phase (end it instead).
- **Push "C'est ton tour"** (Q153). CIN (Q145). Paid membership and passes (step 3). Promo codes (step 4).
- **The FL-26 wow-review leftovers** (box shadow on the twelfth dot, display sizes as tokens): they stay with the founder wow review. Only the "matchs" wording is taken here (Q182).

### 10.9 Carried forward (update §7)

| Item | Lot | Note |
| --- | --- | --- |
| WS-12 follow-ups, WS-12 real-run follow-ups, rooms per phase, members prices, holder cap, waitlist, Q52 residual | done in step 2 | strike from §7 when merged |
| Waitlist count in the back-office | when asked | §10.8 |
| Holder limit re-checked on a late success | if it ever matters | `ponytail:` in `OrderService.reReserve` |
| Member-only leftovers notifying non-members | if the club complains | `ponytail:` in `WaitlistJob` (R over every category) |
| Meta template `waitlist_open` | with BE-36 | Q178 |
| W2 after `WAITLIST_CLOSED`: `router.refresh()` reads the 15 s event cache, so the page can stay stale until it expires; a fresh read when rendered `sold_out` with no room | when polishing W2 | WS-14 review n2 |
| Event PATCH moving `starts_at` earlier doesn't re-check that phases ≥ 2 start before `starts_at − 60 min` | if it ever matters | BE-48 review m3 |
