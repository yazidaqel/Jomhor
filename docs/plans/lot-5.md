# Lot 5 · Organizer platform: implementation plan

As of 2026-11-13 · owner: tech-lead · status: **draft**. The founder still has to agree. §3 takes defaults under the founder's standing instruction, and §4 is applied to `docs/` and `design/` only after that agreement. V22 is the latest migration (`V22__campaigns.sql`), so Lot 5 starts at V23. Next ids: BE-40, FL-26, WS-12, BO-20, Q139, P101. Only step 1 is detailed here; steps 2 to 5 are an outline (§9).

## 1. Lot

**Lot 5 · Organizer platform, step 1: the high-demand on-sale.** Branch `feat/lot-5-onsale` from `main` (Lot 4 merged in #16).

Scope (`00-product.md:55`, `02:253`):

- a ranked virtual waiting room. Early arrivals are drawn in random order, then it is first come, first served. Fans see their position and wait. An admission token is required to reserve;
- admission control before the database;
- the popular-zone hot spot;
- bot and per-device limits;
- verified-fan access per event;
- a multi-instance backend;
- a derby capacity test with target numbers.

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
- CloudFront, and WAF Bot Control or CAPTCHA (Lot 6, Q147).

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
| Demo-complete (step 1 done) | On staging with **3 API instances** behind the ALB. **(1) Set-up:** In B13, OPS configures a room for a test derby: pre-queue 30 min, 500 fans/min, "Fans vérifiés" with account verified ≥ 7 days before the sale. W2 and screen 2 show "File d'attente dès 15:30". **(2) Pre-queue:** At 15:30, Yazid joins from the app. Karim is a cold visitor from a WhatsApp link on the shop: W2 → "Rejoindre la file" → OTP login → W8. Both see "Tirage au sort à 16:00" and a rolling "{n} fans dans la file". A 2-day-old account gets "Vente réservée aux fans vérifiés · compte vérifié avant le 6 nov." A 4th account on one phone gets the device limit. **(3) Draw:** At 16:00, every pre-queue entry is ranked at random, and positions appear within seconds. Joins after 16:00 queue behind them, in arrival order. **(4) Admission:** Positions fall at about 500/min. Yazid's 12-dot row fills. At his turn, the amber twelfth swells, a haptic fires, and "C'est ton tour · 10:00" appears. He buys with the token. **(5) Guard:** A `POST /fan/orders` without a token gets 409 `ADMISSION_REQUIRED`, and the test shows no SQL statement ran beyond the session check. **(6) Controls:** OPS pauses (positions freeze, "en pause"), resumes, and moves the rate to 800. One API task is killed mid-sale: the room keeps ticking, and no order is lost. A turn left unused for 10 min → "Ton tour est passé" → "Reprendre une place" puts the fan at the back. OPS ends the room: orders go through without a token. **(7) Capacity:** The BE-45 run meets every target in Q157. `check.sql` finds no oversell, no double charge and no limit breach. CI is green on `Lot5OnSaleFlowTest`, `MultiInstanceTest`, `apps/fan/test/queue_test.dart`, shop `queue.test.tsx` and back-office `room.test.tsx`. |
| Live | The same in prod, gated by four things. **WAF** rate rules on the prod ALB (Q147). The **CNDP** declaration covering on-sale anti-fraud processing: device-id hash, account age, attendance (Q144, Q146). The **Payzone** rate limit, which sets the admission ceiling (Q142). The **runbook**: raise or lower the rate, pause, end. Until Payzone is confirmed, prod rooms run at ≤ 300/min. |

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
| Load harness (seeder, k6 scripts, `check.sql`) | BE-44 |
| Staging with ≥ 3 instances, WAF, k6 runner | W0-4 (founder/orchestrator; no agent owns infra, lot-1 Q24) |
| Capacity run + tuning | BE-45 |

### 2.3 Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (no PII in QR or tokens, `organizer_id` on every table, centimes, gate never calls the server, strings never hard-coded), Stack (Kafka only for a real async boundary; AWS eu-west-3), Conventions |
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
| Q139 | Where does the waiting room live: Postgres, or Redis/ElastiCache? | **Postgres + signed tokens.** Polls don't read any store: rank is in the token, room state is in a 1 s memo per instance. So the store only sees joins, one rank write per room per second, and one primary-key read per fan after ranking. That is well within an RDS r7g.xlarge. ElastiCache (Valkey, primary + replica) would cost roughly 50–100 USD/month. It would also add a second source of truth (a lost node loses the order), a VPC component to patch and watch, a Testcontainers dependency, and a failover mode to test. That is a lot for a three-developer team. **Revisit only if BE-45 shows joins under target.** The upgrade path (`ponytail:` note in the room service): sorted set per room, same token contract. |
| Q140 | Does joining need a login? | **Yes: phone OTP session, one entry per account per room.** Without login, bots could take thousands of positions for free. App fans hold 30-day sessions already. The shop tells cold visitors "Connecte-toi avant {heure}" from `opens_at`. Risk: an OTP surge at the pre-queue, covered by Q148 and §8. |
| Q141 | Draw window | **Pre-queue opens `pre_queue_min` (default 30) before `sale_starts_at`.** Every entry that joins before `sale_starts_at` is ranked by a CSPRNG draw at `sale_starts_at`; later joins rank by arrival. So arriving at 15:30 or at 15:59:59 gives the same chance, and there is no stampede at T0. `0` = no draw (pure first come, first served). A join that commits while the draw runs ranks right after the drawn ones (documented). |
| Q142 | Admission rate and Payzone | **Default 500 fans/min**, live-editable from 1 to 6,000. The tick admits only while active holds stay under 90 % of `sales.max-active-holds-per-event` (2,000) and the event is not sold out. **Payzone:** about 70 % of admitted fans start a payment and about 1.2 hosted-page attempts are made per order, so payment sessions per second ≈ rate × 0.84 / 60. With P = Payzone's allowed new sessions per second and a 70 % safety margin, **rate ≤ 50 × P**. The default assumes P ≥ 10. Founder: ask Payzone for the max new hosted sessions/s per merchant, the 3DS/CMI ceiling, the callback retry policy, and whether the sandbox allows a load test. Until answered, prod rooms run at ≤ 300/min. At 500/min a 36,000-place derby sells in about 40 min (36,000 ÷ 2.6 places per order ÷ 350 orders/min). |
| Q143 | When does a turn start? | **At the first poll that sees `rank ≤ admitted_through`**, which sets `admitted_at`. The admission token is valid for `rooms.admission-window` (10 min, capped at sales close) and can be used for several orders inside it (`per_account_limit` caps them). A fan who closed the app keeps their turn until they come back. After the window: `EXPIRED`, and a new join goes to the back. |
| Q144 | What does "verified fan" mean in Morocco? | **Per-event eligibility, built from data we already hold. No new personal data.** A room may require any of these (ANDed; null = not required): (a) **account verified before `sale_starts_at − min_account_age_days`**, where `users.verified_at` = first fan OTP login (SIMs are ID-registered in Morocco, so an old verified number ≈ an identified person; farming phones right before a derby is useless); (b) **member of the club since before `member_joined_before`** (free membership, Lot 4); (c) **≥ `min_events_attended` events attended** at this organizer (the loyalty definition, all time). Fans see "Fan vérifié" when they pass the rule. Back-office preset "Fans vérifiés" = (a) with 7 days. Checked at join (fans who fail never take a place) and for split `pays` members at place time (Q149). **CNDP:** account age and attendance are existing purchase-relationship data used for fraud prevention. Founder: add "on-sale anti-fraud" to the declaration. |
| Q145 | CIN as a verification factor? | **No CIN is collected in V1.** National ID numbers likely need a CNDP **authorisation**, not just a declaration. No public registry API exists (the DGSN digital identity service is for licensed partners), and a typed CIN proves nothing. If the league requires ID checks (open club question), stewards compare the physical CIN with the ticket's holder name at the gate. Revisit post-V1 with a licensed verification provider. DESIGN's "CIN vérifiée" badge is dropped (P109). |
| Q146 | Per-device limit | **3 accounts per device per room** (`rooms.device-limit`), soft (no lock). `device_id` = a random install id: app → generated once in `SharedPreferencesAsync`; shop → httpOnly cookie `jdid`, 1 year. Stored only as HMAC-SHA256(rooms secret, id). This is friction against one-phone account farms, not a guarantee: a reinstall resets it. Purged with the entries (Q155). |
| Q147 | Per-IP limits | **At the edge: AWS WAF rate-based rules on the ALB** (managed, about 5 USD/ACL + 1 USD/rule per month + 0.60 USD per million requests). Generous because Moroccan mobile CGNAT puts many fans behind one IP: `POST /v1/fan/queue` 300 per 5 min per IP, `/v1/fan/auth/*` 300 per 5 min, `GET /v1/fan/queue` 3,000 per 5 min, everything else 6,000 per 5 min. Action: block (429). Per-instance in-app limiters are not built: with N instances they would be N times too loose. Bot Control and CAPTCHA (the native app would need the WAF mobile SDK) → Lot 6, only if needed. Founder/orchestrator configures it (W0-4). |
| Q148 | OTP per-IP limits vs CGNAT on on-sale day | **Fan per-IP defaults → 60 per 10 min, 600 per 24 h** (config; org unchanged). Per-phone limits (3/10 min, 10/24 h, lockout) stay the real guard. Account age (Q144) makes fresh numbers worthless in verified rooms. Also: the OTP send pool size and queue are measured in BE-45; a dropped code logs a WARN with a counter. |
| Q149 | Split pay in a room event | The admission token is the buyer's only. Members pay their member order with `/pay` (no token). In a room with an eligibility rule, each `pays` member must pass it at place time (409 `NOT_ELIGIBLE` + `details.field`). Holders without `pays` are not checked: they are inside the buyer's limit (P83). |
| Q150 | Holder cap (lot-1 Q18) | **Not in step 1.** Tickets are named, every transfer hop is audited, and eligibility plus one entry per account cover the farm case. Revisit in step 2 with the club's ID-check answer. |
| Q151 | Popular-zone hot row | **No sharding in step 1. Measure it.** Every reserve of the busy zone serialises on one `price_categories` row for the rest of the place transaction (a few ms, plus a Multi-AZ commit). That gives a ceiling of very roughly 100–300 orders/s. A 500–1,000/min admission makes 6–12 orders/s. BE-45 measures the raw ceiling without the room (k6 `hot-row.js`, 200 concurrent buyers on one category). **Rule: if the ceiling is < 5× the order rate at the target admission, build buckets.** Upgrade path (`ponytail:` in `Inventory`): `price_category_buckets(category_id, bucket, quota, sold)` with K = 8, a random bucket with room then the others, the bucket stored on the order item for release, and `sold` = the sum. |
| Q152 | How the shop polls | **The browser calls `GET {API_PUBLIC_URL}/fan/queue` directly**, with `X-Queue-Token`, no credentials, and CORS for `SHOP_ORIGIN` only. Next.js stays off the hottest path (several thousand req/s). The join goes through a route handler (session cookie). The admission token comes back to the page and is passed to `/api/orders`. It is useless without the httpOnly session, because it is bound to the user. `API_PUBLIC_URL` reaches the client as a server-component prop, so no new public env. |
| Q153 | Push "C'est ton tour"? | **No.** The turn waits for the fan (Q143). |
| Q154 | When does a room end? | Manually ("Terminer la file", irreversible), or automatically at `refund_cutoff_at`. Sold out does **not** end it: holds can free places, so fans keep their order with honest copy ("Tout est réservé pour l'instant · des places peuvent se libérer"). An empty room admits a new joiner within a few seconds. |
| Q155 | Retention of queue data | `queue_entries` deleted 30 days after the event's `starts_at` (`rooms.retention`, daily in the tick job). Rooms are kept, for counts and audit. |
| Q156 | Several rooms per event (members presale, then general sale) | **Step 2.** Step 1 has `unique (event_id)`; step 2 drops it and adds phases. |
| Q157 | Derby capacity targets (separate from the Lot 6 launch-scale test) | Table §5.4. |
| Q158 | Load tool and accounts | **k6 (open source)** with arrival-rate executors (tokens in a `SharedArray`, so 100k fans don't need 100k VUs), from one or two EC2 c7i.2xlarge runners in eu-west-3. Accounts come from a `loadtest`-profile seeder. Phones are in the unassigned `+999…` range, so no message can reach a real person. It is refused under `prod` or with a non-fake card provider. Fake gateway callbacks are signed by the k6 scripts. |
| Q159 | Who builds staging with ≥ 3 instances? | **Founder/orchestrator** (lot-1 Q24: no agent owns infra). Needed by day 9: ECS Fargate API service (3 × 2 vCPU / 4 GB), ALB (health `/v1/actuator/health/readiness`, deregistration 30 s), RDS PostgreSQL 18 Multi-AZ of the prod class (r7g.xlarge), shop 2 tasks, WAF ACL (Q147), the k6 runner, env from Secrets Manager (`ROOMS_TOKEN_SECRET`). About 2–3 d. A test run costs a few tens of USD. |
| Q160 | Mockups before building screen 12? | **No blocking round.** §4.7 + golden screenshots (FR + AR). Founder wow review on a staging build at day 7 (screen 12, W8). |
| Q161 | Kafka? | **None.** No async boundary appears: joins and orders are synchronous, ranking is a 1 s tick. Revisit only if the Lot 6 load test asks for it. |
| Q162 | POS during a room | **Bypass:** club tills sell without a token (they had no `SALE_BUSY` either). The cash cap still applies. |
| Q163 | What a fan in the room sees about stock | `sold_out` and the existing public `remaining` per zone (screen 2 / W2 data). The room shows "Il reste {n} places" (Σ remaining from the event detail, cached ≤ 15 s). |
| Q164 | Admission token in the header or the body? | **Header `X-Admission-Token`.** The `POST /fan/orders` body is unchanged, and the shop handler forwards one header. |
| Q165 | What drives the wait estimate? | The **measured** admission rate over the last full minute (`measured_rate_per_min`). Paused or 0 → no estimate ("en pause"). Rounded honestly: "moins d'une minute", "environ {n} min", above 60 min "plus d'une heure". |
| Q166 | Lot 5 duration | Step 1 ≈ 3 weeks. Steps 2 to 5 ≈ 6–7 more weeks (§9). The 4 wk in `00-product.md:55` doesn't hold. **Founder: re-cut Lot 5** (P115). Suggested split: 5a = step 1 + step 2 (derby-ready); 5b = passes, finance, accreditations. |

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
| P107 | backend | No health endpoint, no graceful shutdown: the ALB can't check or drain an instance | Actuator health only + `server.shutdown=graceful` |
| P108 | 03 `users.created_at` | It is set when a buyer types a holder's phone, so it can't measure account age | `users.verified_at` (first fan OTP verify), backfilled from `fan_sessions` |
| P109 | DESIGN:117 | "CIN vérifiée" badge, but no CIN is collected (Q145) | "Fan vérifié" chip on screen 12 only |
| P110 | 04 `/fan/auth/otp`, `application.yml` | Per-IP 20/10 min blocks CGNAT crowds on on-sale day | 60/10 min, 600/24 h (Q148) |
| P111 | shop `DETAIL_REVALIDATE_S` = 15 | Room status on W2 can be 15 s stale at `opens_at` | The client flips on `opens_at`; W8 polls the API directly |
| P112 | 02:191 | "Bots on on-sale day … Separate spec" | Threat-model row → 04 §Waiting room |
| P113 | `otpSendExecutor` | The per-instance queue (1,000) drops codes silently when full | WARN + counter; sized in BE-45 |
| P114 | 01 | Silent on instance count and on what coordinates instances | New §Deployment |
| P115 | 00:55 | Lot 5 = 4 wk can't hold step 1 + the rest | Q166 |
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
  draw_key      bigint not null,                      -- 8 CSPRNG bytes drawn at join; orders the draw; never returned
  joined_at     timestamptz not null default now(),   -- a rejoin after an expired turn resets it (back of the queue)
  rank          integer check (rank > 0),             -- dense per room, set by the room tick; null until ranked
  admitted_at   timestamptz,                          -- first poll that saw rank <= admitted_through; the turn ends 10 min later
  unique (room_id, user_id),
  unique (room_id, rank)
);
create index on queue_entries (room_id, joined_at) where rank is null;
create index on queue_entries (room_id, device_hash);
-- Rows are deleted 30 days after the event's starts_at (rooms.retention).
```

Flyway, one migration per task:

| File | Task | Content |
| --- | --- | --- |
| `V23__waiting_rooms.sql` | BE-40 | `users.verified_at` + backfill `update users u set verified_at = (select min(created_at) from fan_sessions s where s.user_id = u.id)`; `room_status`; `waiting_rooms` + index |
| `V24__queue_entries.sql` | BE-41 | `queue_entries` + two indexes |

The tenant allowlist stays at 5: both new tables carry `organizer_id`. The `02` §Data model table gains the two tables and `users.verified_at` in the same docs change.

### 4.3 `docs/04-api.md`

**Errors: add or replace** (header: "Lot 0 to Lot 5")

| Code | HTTP | When |
| --- | --- | --- |
| `ADMISSION_REQUIRED` | 409 | `POST /fan/orders` on an event whose room is `PRE_QUEUE`, `OPEN` or `PAUSED`, without a valid `X-Admission-Token`. `details.reason`: `MISSING`, `INVALID` (bad signature, another user or another event), `EXPIRED`. Clients go to the room |
| `QUEUE_NOT_OPEN` | 409 | Join refused. `details.reason`: `NO_ROOM`, `NOT_STARTED` (+ `details.opens_at`), `ENDED` (buy without the room), `CLOSED` (sales closed) |
| `NOT_ELIGIBLE` | 409 | Join, or a split `pays` member, fails the room's rule. `details.reason`: `ACCOUNT_TOO_NEW` (+ `details.verified_before`), `NOT_MEMBER` (+ `details.member_joined_before`), `NOT_ENOUGH_EVENTS` (+ `details.min_events_attended`, `details.attended`); for a member, + `details.field` (the holder path) |
| `QUEUE_DEVICE_LIMIT` | 409 | This device already holds `details.limit` entries of other accounts in the room |
| `ROOM_STATUS` | 409 | Room PUT changing anything but `admission_rate_per_min` once the room has left `SCHEDULED`, or pause/resume/end in the wrong status; `details.status` |
| `EVENT_NOT_EDITABLE` | 409 | **Lot 5:** + `details.reason` `ROOM_STARTED`: event PATCH moving `sale_starts_at` while its room is past `SCHEDULED` and not `ENDED` |
| `SALE_BUSY` | 429 | Unchanged. With a room it remains a safety net for admitted fans |

**§Fan auth: replace in the `/fan/auth/otp` row:** "Per IP (resolved through trusted proxies only): 60 sends per 10 min, 600 per 24 h." **In the `/fan/auth/verify` row, append:** "The first successful fan verify of a user sets `users.verified_at`."

**§Fan: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/fan/events`, `/fan/events/{id}` | **Lot 5:** each event + `room: {status: SCHEDULED\|PRE_QUEUE\|OPEN\|PAUSED, opens_at, eligibility: {verified_before, member_joined_before, min_events_attended} \| null} \| null`. Null when the event has no room or its room is `ENDED`. `opens_at` = `sale_starts_at − pre_queue_min`. `verified_before` = `sale_starts_at − min_account_age_days` (RFC 3339, or null). `eligibility` is null when no rule is set. |
| POST | `/fan/queue` | `{event_id, device_id}`. `device_id` = 16–64 chars `[A-Za-z0-9_-]` (422). Checks in order: event visible as in `GET /fan/events/{id}` (404); room `PRE_QUEUE`, `OPEN` or `PAUSED`, and sales not closed (409 `QUEUE_NOT_OPEN`); eligibility (409 `NOT_ELIGIBLE`); device limit (409 `QUEUE_DEVICE_LIMIT`). The caller's entry already exists → `200` with it and a fresh queue token, unless its turn has `EXPIRED`: then it is moved to the back (`rank` and `admitted_at` cleared, `joined_at = now()`) → `201`. New → `201`. → the `GET /fan/queue` shape. |
| GET | `/fan/queue` | Header `X-Queue-Token`; send no `Authorization` header (ignored). Missing, bad or expired token, or a purged entry → 401 `UNAUTHORIZED`. CORS: allowed from `SHOP_ORIGIN`, no credentials. → `{event_id, status, sale_starts_at, position, in_room, rate_per_min, estimated_wait_s, sold_out, admission: {token, expires_at} \| null, queue_token, poll_after_s, server_time}`. `status`: `PRE_QUEUE` (before the draw; position null), `WAITING` (position null until ranked), `ADMITTED`, `EXPIRED`, `PAUSED`, `ENDED`. `position` = `rank − admitted_through` (≥ 1) while waiting. `in_room` = `ranked_count − admitted_through`, or `joined_count` before the draw. `rate_per_min` = `measured_rate_per_min` (0 when paused). `estimated_wait_s` = ⌈position × 60 ÷ rate_per_min⌉, or null when the rate is 0 or the position is null. `sold_out` per §Waiting room. `admission` only while `ADMITTED`. `queue_token` = refreshed (it carries the rank once known). `poll_after_s` per §Waiting room. `Cache-Control: no-store`. |
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
| 02:191 Threat model | "Bots on on-sale day \| Waiting room (04 §Waiting room): login to join, one entry per account, random draw for early arrivals, admission token checked before the database, per-event eligibility (account age, membership, attendance), per-device cap, edge rate limits (WAF), per-account limits, payment-first reservation \| Farms of real phone numbers verified before the eligibility date; narrowed by membership/attendance rules, named tickets and the transfer audit" |
| 02 §Data model | `waiting_rooms`, `queue_entries`, `users.verified_at` |
| 01 §Components, API | + "on-sale waiting room (rooms, queue, admission)" |
| 01 §Key flows | New flow 0: "On-sale with a room: join (logged in) → random draw at sale opening for early arrivals, then arrival order → admission token at your turn (10 min) → reserve with the token → pay." |
| 01 new §Deployment | "The API runs as N identical, stateless instances behind the ALB. Instances coordinate only through Postgres: row locks with `skip locked` (expiry, deliveries, fan messages, campaigns, room tick), advisory locks (orders, scans, manifest build, the fan-message claim). Per-instance memos are caches, never sources of truth: gate load 15 s, room state 1 s. Secrets (`JWT_SECRET`, `OTP_SECRET`, `ROOMS_TOKEN_SECRET`, the master key) are identical on every instance. Per-IP limits sit at the edge (AWS WAF). Lost on an instance's death and accepted: OTP codes still queued (the fan asks again) and the simulated gateway's delayed callbacks (staging only). Health: `/v1/actuator/health/{liveness,readiness}`; graceful shutdown drains in-flight requests." |
| 01 Kafka row | append: "Lot 5: the waiting room needs none (synchronous joins and orders, 1 s tick)." |
| 00:55 | Unchanged text. Founder: re-cut the estimate (Q166) |

### 4.6 Config (backend `application.yml`, plan note)

`rooms.token-secret` (`ROOMS_TOKEN_SECRET`, required outside dev/test, ≥ 32 bytes), `rooms.tick` (1s), `rooms.memo-ttl` (1s), `rooms.admission-window` (10m), `rooms.device-limit` (3), `rooms.retention` (30d), `jomhor.cors.shop-origin` (`SHOP_ORIGIN`), `spring.datasource.hikari.maximum-pool-size` (`DB_POOL_SIZE`, 20), `server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase: 20s`, `management.endpoints.web.exposure.include: health`, `management.endpoint.health.probes.enabled: true`, `show-details: never`, OTP per-IP fan 60/600.

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
| 12 · other states | `EXPIRED`: "Ton tour est passé" + "Reprendre une place" (joins again, at the back). `ENDED` → straight to screen 3. `NOT_ELIGIBLE`: outlined panel "Vente réservée aux fans vérifiés" + the reason ("Compte vérifié après le {date}", "Réservée aux membres inscrits avant le {date}", "Il faut {n} matchs au stade · tu en as {attended}"). `QUEUE_DEVICE_LIMIT`: "Trop de comptes sur ce téléphone pour cette vente". Offline: last state + "Hors ligne · ta place est gardée". |
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
| W0-2 | orchestrator | git | `feat/lot-5-onsale` from `main`. No new CI job (k6 scripts are not run in CI; `check.sql` runs inside a JUnit test) |
| W0-3 | founder | — | Ask Payzone the Q142 questions. CNDP declaration: on-sale anti-fraud (Q144, Q146). Club: verified-only for the derby? Which rule? Re-cut Lot 5 (Q166). Wow review slot on day 7 |
| W0-4 | founder / orchestrator | AWS staging | Q159 set-up **by day 9**: 3 API tasks, ALB health on readiness, RDS prod class Multi-AZ, shop 2 tasks, WAF ACL (Q147), k6 runner, `ROOMS_TOKEN_SECRET` + `SHOP_ORIGIN` + `DB_POOL_SIZE` in Secrets Manager |

### Tasks

`backend` is serial and on the critical path. ∥ rows run alongside, in disjoint folders. *(contract-first)* = starts on the §4 shapes with fixtures and is done only after one run against the real endpoint. Every client task ships fr/ar/en strings (no hard-coded strings or colours), and the fan app ships FR + AR goldens of the screens it touches.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-40 | backend | `backend/…/sales` (new `rooms` sub-package: domain, `WaitingRoomService` config part, `JdbcWaitingRooms`, `OrgRoomController`, `RoomMemo`), `…/catalog` (`room` on public event reads; event PATCH `ROOM_STARTED`), `…/tenancy` (`verified_at` on first fan verify), V23 | 04 org room rows, `room` on `/fan/events*`, `verified_at` | `WaitingRoomOrgApiTest`: PUT creates (201) with defaults 30/500, GET `{room: null}` before; no or past `sale_starts_at` → 422 `SALE_START_REQUIRED`; ranges → 422; after `PRE_QUEUE` (fixed clock) a `pre_queue_min` change → 409 `ROOM_STATUS` while a rate-only change → 200; pause in `SCHEDULED` → 409; pause/resume/end transitions + audits (payload key-set: no fan data); PUT after `ENDED` → 409; FINANCE GET 200 / PUT 403; CASHIER 403; org B event → 404. `FanEventApiTest`: `room` null without a room or when `ENDED`; `opens_at` derived; `eligibility.verified_before` = sale − n days; `room` on list rows. Event PATCH of `sale_starts_at` in `SCHEDULED` moves `opens_at`; in `PRE_QUEUE` → 409 `EVENT_NOT_EDITABLE` `ROOM_STARTED`. `FanAuthApiTest`: first verify sets `verified_at`, a later one keeps it, a staff verify doesn't set it. `MigrationsTest` V23: backfill = earliest session; allowlist still 5. `RoomMemoTest`: one query per TTL whatever the call count (fixed clock), ENDED rooms absent | W0-1 | 2 |
| 1 ∥ | BO-20 | backoffice | `web/backoffice/src/features/room` (new), `src/features/events` (B2 Règles row), `src/app` (route), `src/i18n/fr.ts` | B13 + B2 link | `room.test.tsx`: no room → form with 30 / 500 / switch off; zod ranges; switch on needs ≥ 1 rule; PUT body; live KPIs from fixture; refetch 2 s only while not `ENDED`; once started only the rate is editable (others disabled) and PUT sends the unchanged rest; buttons per status; end confirm copy; `ROOM_STATUS` → toast; FINANCE sees no write buttons; query keys include the organizer | BE-40 *(contract-first)*; figures real after BE-41 | 2.5 |
| 1 ∥ | FL-26 | flutter | `apps/fan/lib/features/queue` (new: screen 12, controller, repository, token store), `features/events` (screens 1, 2 CTA states), `features/checkout` (admission header, admission bar, `ADMISSION_REQUIRED` → room, badge removed), `core/device_id.dart` (new), `app/router.dart`, `app/session.dart` (wipe on logout), `l10n/*.arb`, `test/queue_test.dart`, `test/goldens_test.dart` | Screen 12 and its entry points | Tests (fake clock, fake API): join body `{event_id, device_id}` with the same `device_id` across restarts; `PRE_QUEUE` countdown to `sale_starts_at` server-time corrected, `in_room` rolls; polling waits exactly `poll_after_s`, stops on `ADMITTED`/`ENDED`, stops in the background and polls once on resume; position, "fans devant toi", wait copy for <60 s / n min / >60 min / null; lit dots from the first position, 12th only at the turn; `ADMITTED` → one swell + haptic (none under reduced motion), countdown to `expires_at`, CTA → screen 3, POST `/fan/orders` carries `X-Admission-Token`; 409 `ADMISSION_REQUIRED` → screen 12; `EXPIRED` → "Reprendre une place" POSTs again; each `NOT_ELIGIBLE` reason and `QUEUE_DEVICE_LIMIT` copy; `QUEUE_NOT_OPEN` `NOT_STARTED` → disabled CTA with `opens_at`; tokens survive restart and are wiped on logout; wakelock on while visible, off on leave; goldens fr + ar: pre-file, waiting, ton tour, not eligible, screen 2 with room | BE-41, BE-42 *(contract-first)* | 4.5 |
| 1 ∥ | WS-12 | web-shop | `web/shop/app/(public)/[club]/events/[slug]/` (room panel in `event-view`, new `queue/page.tsx` + `QueueRoom.tsx`), `app/api/queue/route.ts` (new), `app/api/orders/route.ts` (forward header), `lib/queue.ts` (new), `lib/i18n/{fr,ar,en}.ts` | W2 room panel, W8, join handler | `queue.test.tsx`: W2 `SCHEDULED` shows `opens_at` and flips to "Rejoindre la file" at `opens_at` (fake timers, no reload); zone cards disabled "Accès par la file" until admitted; signed out → `/login?next=…/queue`; join handler: cross-origin → 403, forwards Bearer + `device_id` from `jdid` (set httpOnly, SameSite=Lax, 1 y, Secure in prod when absent), returns the queue shape; W8 poller calls `${API_PUBLIC_URL}/fan/queue` with `X-Queue-Token`, `credentials: 'omit'`, honours `poll_after_s`, pauses on `visibilitychange`, stops on `ADMITTED`; admitted → zone picker enabled and `/api/orders` sends `X-Admission-Token`; `ADMISSION_REQUIRED` → W8; no JWT or K_t in HTML/RSC; W8 renders in ar; `rtl.test` and `reserved-slugs.test` green | BE-41, BE-42 *(contract-first)* | 3.5 |
| 2 | BE-41 | backend | `backend/…/sales/rooms` (join, poll, `RoomTokens` on Nimbus JWT HS256, `RoomTickJob`, draw/rank/admit, purge), `…/crm` + `…/tenancy` application calls for eligibility, V24 | 04 `/fan/queue` rows, §Waiting room | `WaitingRoomFanApiTest`: join before `opens_at` → 409 `NOT_STARTED` + `opens_at`; join in `PRE_QUEUE` → 201, status `PRE_QUEUE`, position null; second join → 200 same entry; claims key-set exactly `{iss, aud, sub, evt, exp}` (+`rnk`), and no phone, name or device id in any token; a fan JWT on `/fan/queue` → 401 and a queue token as Bearer elsewhere → 401; tampered `rnk` → 401; 4th account on one `device_id` → 409 `QUEUE_DEVICE_LIMIT`; each eligibility reason with its details (verified one day too late; member who left; 2 of 3 events). `RoomTickTest` (fixed clock): draw at `sale_starts_at` ranks 1..200 dense and once (a second tick changes nothing; audit `room.draw {count: 200}`); rank vs join order not identity, Spearman |ρ| < 0.2 at n = 200 over 5 seeds; a join after the draw → rank 201 by arrival; 10 s at 600/min → `admitted_through` +100, capped at `ranked_count`; no advance when holds ≥ 90 % of the cap, when `sold_out`, when `PAUSED`; `measured_rate_per_min`; `ENDED` at `refund_cutoff_at`. Poll: unranked reads the entry; ranked poll runs **zero** SQL statements (datasource statement counter); `ADMITTED` sets `admitted_at` once and `expires_at` = +10 min; after → `EXPIRED`; rejoin → 201 at the back; purge deletes entries 30 d after `starts_at`. Org B entries never visible to org A. `MigrationsTest` V24 | BE-40 | 3 |
| 3 | BE-42 | backend | `backend/…/sales` (`place`: admission first, `pays` eligibility; POS untouched), `…/common/web` (CORS for `GET /fan/queue` only), `application.yml` (OTP per-IP 60/600, rooms config) | Admission enforcement | `AdmissionApiTest`: room `OPEN`, no header → 409 `ADMISSION_REQUIRED` `MISSING` with zero SQL statements after the session check; another user's token, another event's, bad signature → `INVALID`; expired → `EXPIRED`; valid → 201 and a second order inside the window → 201 until `PER_ACCOUNT_LIMIT`; `PAUSED` + valid → 201; `ENDED` or no room → no token needed (every existing `OrderApiTest`, `SplitPayApiTest`, `Lot4FanFlowTest` green); `POST /org/pos/orders` during `OPEN` → 201; a split with an ineligible `pays` member → 409 `NOT_ELIGIBLE` + `details.field`. CORS: preflight from `SHOP_ORIGIN` on `GET /fan/queue` OK, another origin refused, other paths send no CORS headers. OTP per-IP: 60th send OK, 61st → 429 | BE-41 | 1.5 |
| 4 | BE-43 | backend | `backend/…/notifications` (`FanMessageJob` claim under `pg_try_advisory_xact_lock`, class 6 noted in `EventLock` javadoc), `…/gate/application/GateLoad.java` (note only), `…/tenancy/adapters/otp` (dropped-code WARN + counter), `build.gradle.kts` (`spring-boot-starter-actuator`, latest via BOM), security config (health public), `application.yml` (pool, graceful shutdown, probes), `src/test/…/MultiInstanceTest` | Multi-instance proof | `MultiInstanceTest` (two Spring contexts, one Testcontainers Postgres): two expiry passes at once release each order exactly once (`sold` exact); the room tick on both draws once and advances `admitted_through` once per interval (not 2×); fan messages at rate 20/s, period 5 s → ≤ 100 sends per period across both; one campaign snapshotted once; a due manifest version built once. `HealthTest`: `/v1/actuator/health` `UP` with no details; readiness `OUT_OF_SERVICE` once shutdown starts; an in-flight `POST /fan/orders` completes during graceful shutdown. A full OTP queue logs WARN and counts | BE-42 | 1.5 |
| 5 | BE-46 | backend | `backend/src/test/…/Lot5OnSaleFlowTest`, `backend/http/lot5-onsale.http` | Exit flow | **`Lot5OnSaleFlowTest`:** a room with "verified ≥ 7 d"; 3 eligible fans + a 2-day account join in `PRE_QUEUE` (the last → `NOT_ELIGIBLE`); draw; admission in rank order at a fixed rate; fan 1 buys with the token and pays (fake) → tickets; an order without a token → 409; pause freezes positions, resume continues; fan 2's turn expires → rejoin at the back; categories exhausted → `sold_out`; end → an order without a token passes; `check.sql` returns no rows | BE-43 | 1 |
| 6 | BE-44 | backend | `backend/loadtest/k6/` (`lib.js`, `prequeue.js`, `poll.js`, `buy.js`, `cold-visitor.js`, `hot-row.js`), `backend/loadtest/check.sql`, `backend/…/LoadSeeder.java` (profile `loadtest`), `docs/TESTING.md` §Load (backend writes it: commands only) | Load harness | `LoadSeederTest`: N users with `+999…` phones, `verified_at` 30 d back, live sessions, a JWT file; refused under `prod`, without the profile, or with a non-fake card provider. `CheckSqlTest`: `check.sql` returns no rows on the `Lot5OnSaleFlowTest` end state and one row per crafted breach (oversell: VALID tickets > quota or `sold` ≠ held + paid places; double charge: two SUCCEEDED payments on one order with no `refund_required`; tickets ≠ places on a PAID order; held > `per_account_limit`; admission out of rank order). Smoke: each k6 script runs 30 s against local dev (`k6 run --vus 5`), as `TESTING.md` lists | BE-46 | 2.5 |
| 7 | BE-45 | backend (+ orchestrator for the run) | `backend/loadtest` (tuning), `application.yml` defaults if tuned | Capacity run on staging | The §5.4 targets met, in a results table (numbers + Grafana/CloudWatch exports) that the orchestrator pastes into this plan. The hot-row ceiling is recorded and the Q151 rule applied. Tuning changes (pool size, rate default, OTP pool) committed with tests green. Re-run after any fix | BE-44, **W0-4** | 1.5 |
| exit | — | orchestrator + founder | — | Demo per §1 on staging (3 instances, one killed mid-sale) | All rows above green; manual demo | all | — |

`reviewer` runs after every builder task. Day 7: founder wow review on staging (screen 12 FR + AR, W8).

### 5.1 Lanes and critical path (working days, before review loops)

| Agent | Sequence | Ends |
| --- | --- | --- |
| backend (critical) | BE-40 0–2 → BE-41 2–5 → BE-42 5–6.5 → BE-43 6.5–8 → BE-46 8–9 → BE-44 9–11.5 → BE-45 11.5–13 (needs W0-4 by d9) | d13 |
| flutter | FL-26 0.5–5 on fixtures (wow review d7), real run after d6.5 → 7.5 | d7.5 |
| web-shop | WS-12 0.5–4 on fixtures, real run after d6.5 → 7 | d7 |
| backoffice | BO-20 0.5–3 (real config at d2, real figures at d5) → 5.5 | d5.5 |

### 5.2 Budget (step 1 = 3 weeks = 15 working days)

| Agent | Sum | ×1.1 review | Fits? |
| --- | --- | --- | --- |
| backend | 13 | 14.3 | Yes, 0.7 d slack: tight. BE-45 depends on W0-4 |
| flutter | 4.5 | 5 | Yes. Free capacity can start step 2 client work |
| web-shop | 3.5 | 3.9 | Yes |
| backoffice | 2.5 | 2.8 | Yes |

### 5.3 Cut order (if late) and never-cut list

Cut in this order:

1. the per-device limit (account + eligibility remain);
2. the measured rate (the estimate uses the configured rate);
3. B13 figures beyond waiting / admitted / remaining (keep every control);
4. the W2 automatic flip at `opens_at` (a reload shows it);
5. the `min_events_attended` rule (keep account age and membership).

Never cut:

- the admission token required while a room is active, checked before any DB read;
- zero DB reads on a ranked poll;
- the random draw for pre-queue joins, and the rank order of admission;
- every job safe with N instances (`MultiInstanceTest`);
- the capacity run against §5.4, with `check.sql` clean;
- no PII in tokens;
- tenant scoping on both tables;
- centimes untouched;
- the gate path untouched;
- FR + AR goldens of screen 12.

### 5.4 Derby capacity targets (Q157; staging, prod-class RDS, 3 API instances)

| Metric | Target |
| --- | --- |
| Stadium / stock | 45,000 capacity; 40,000 places on sale in 6 zones; the popular zone has 15,000 places in **one** price category |
| Room at T0 | 100,000 fans joined during a 30-min pre-queue (half in the last 5 min) |
| After T0 | +30,000 joins over 10 min |
| Join | p95 < 500 ms at 1,000 joins/s peak; 0 errors |
| Draw | 100,000 entries ranked ≤ 10 s after `sale_starts_at` |
| First position | 95 % of pre-queue fans see a position ≤ 30 s after T0 |
| Poll | 8,000 req/s for 30 min: p95 < 150 ms, p99 < 400 ms at the ALB, errors < 0.1 %, 0 SQL per ranked poll |
| Admission | Configured 1,000/min (2× default) held within ± 5 % |
| Orders (admitted) | p95 < 300 ms, p99 < 1 s, 0 5xx; 30 % of holds abandoned, 5 % of cards refused (fake) |
| Hot row (room ended, raw) | ≥ 100 orders/s on the single category with 200 concurrent buyers. Below 5× the order rate → build buckets (Q151) |
| Correctness | `check.sql` clean: 0 oversell, 0 double charge, 0 limit breach, admission in rank order; every expired hold released once; draw rank vs join time Spearman \|ρ\| < 0.05 |
| Resilience | One of 3 instances killed at T0 + 5 min: < 0.5 % errors for 30 s, the tick resumes within 2 s, no lost or duplicated order |
| Resources | RDS CPU p95 < 60 %, connections < 70 % of max; API CPU < 70 % |
| Sell-out | All 40,000 places sold; time recorded (about 20 min expected at 1,000/min) |

**Lot 6 keeps:** the full launch-scale test on the production setup (200,000 in the room, real Payzone sandbox, WAF + CloudFront live, a 2 h soak, match-day traffic: `/load` polls and manifest downloads at the same time), the pen test, and the runbooks.

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Admission before the DB | `place` decides admission from `RoomMemo` + the signature before any repository call (statement-counter test). A ranked `GET /fan/queue` runs no SQL. The memo is one query per TTL per instance. Nothing in the poll path takes a lock |
| Tokens | Nimbus HS256 with a separate `ROOMS_TOKEN_SECRET`. `aud` `queue` / `admission` never accepted for each other or as a fan JWT, and the reverse. Admission is bound to `sub` = caller, `evt` = body event, `exp`. Claim key-sets exact: no phone, name or device id. Secret ≥ 32 bytes, startup fails without it outside dev/test |
| Fairness | CSPRNG `draw_key`, never returned. Draw and status flip in one transaction, once. Ranks dense and unique (`unique (room_id, rank)`). `admitted_through` monotonic and ≤ `ranked_count`. A rejoin goes to the back. Join idempotent per account (unique + on-conflict). No path lets a client choose or improve its rank |
| Multi-instance | The tick takes rooms with `for no key update skip locked` (joins' FK `key share` not blocked). The fan-message claim is under a try-lock. No new in-memory state beyond TTL memos. Jobs proven in `MultiInstanceTest`. Graceful shutdown + readiness wired |
| Inventory | `Inventory` untouched except a `ponytail:` note. The lock order in `OrderService` javadoc still holds with the admission step first. Holds cap and `sold_out` read without locks. `check.sql` definitions match 04 (`sold` = held + paid) |
| Eligibility and privacy | Rules computed through crm/tenancy application services, not their repositories. `NOT_ELIGIBLE` details carry only the rule and the caller's own count. `device_hash` is an HMAC, never the raw id. Purge after 30 d. The `verified_at` backfill is correct. The load seeder can't run in prod and uses `+999…` phones |
| Tenancy | Both tables filter `organizer_id`. Org B room → 404. A queue token of club A's event never admits on club B's event |
| Contract | Every new or changed 04 row has a contract test. Error codes and `details.reason` exactly as 04. V23/V24 names = 03. OTP per-IP defaults = 04 |
| Clients | Position and wait only from the server (no client-made numbers). `poll_after_s` honoured, background polling off. Direct browser poll with `credentials: 'omit'`. Admission header forwarded, tokens wiped on logout. Twelve dots never drawn as the J mark. Reduced motion off. RTL mirrored with numbers LTR. Strings in fr/ar/en |
| Gate | Zero diff under `…/gate` except the `GateLoad` comment. No scanner or `packages/core` change |

## 7. Carried forward

| Item | Lot | Note |
| --- | --- | --- |
| Full launch-scale load test, pen test, runbooks | 6 | §5.4 last row |
| CloudFront in front of the shop, WAF Bot Control / CAPTCHA | 6, if measured | Q147 |
| Redis/Valkey room store | only if BE-45 shows joins under target | Q139, `ponytail:` note |
| Category buckets (sharded `sold`) | only if the hot-row ceiling fails the rule | Q151 |
| Rooms per phase (members presale), members prices, holder cap, waitlist ("Liste d'attente") | 5 step 2 | Q150, Q156, lot-4 Q57 |
| Refunded place still counting in `per_account_limit` | 5 step 2 | `TicketAdminService` `ponytail:` (lot-3 Q52) |
| CIN-based verification | post-V1 | Q145 |
| Push "C'est ton tour" | when asked | Q153 |
| Kafka | 6, only if the load test needs it | Q161 |
| `GateLoad` memo | stays per instance | P106 |
| Deploy env added in step 1 | W0-4 | `ROOMS_TOKEN_SECRET` (required), `ROOMS_TICK` (1s), `ROOMS_MEMO_TTL` (1s), `ROOMS_ADMISSION_WINDOW` (10m), `ROOMS_DEVICE_LIMIT` (3), `ROOMS_RETENTION` (30d), `SHOP_ORIGIN` (backend CORS), `DB_POOL_SIZE` (20), `OTP_PER_IP_FAN_10M` (60), `OTP_PER_IP_FAN_24H` (600); ALB health `/v1/actuator/health/readiness`; WAF ACL rules per Q147 |

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| Payzone or the CMI 3DS can't take the payment rate | The admission rate is a live knob (rate ≤ 50 × P, Q142); prod ≤ 300/min until confirmed; Lot 6 tests on the Payzone sandbox |
| OTP surge at the pre-queue (provider throughput, Q2) | App sessions last 30 days; "Connecte-toi avant" copy from `opens_at`; the draw removes the incentive to arrive at the last second; per-IP raised (Q148); the OTP pool measured in BE-45 |
| CGNAT puts thousands of fans behind one IP | Generous WAF limits, nothing per-IP in the app except OTP, per-phone limits do the real work; watch the WAF metrics on the day |
| Postgres as the queue store hits a limit | Polls are DB-free by design; join and draw measured in BE-45; Redis path documented (Q139) |
| Hot row | Admission keeps the order rate an order of magnitude under the expected ceiling; measured; buckets path (Q151) |
| Farms of verified phone numbers | Account age before the sale date, membership and attendance rules, one entry per account, device cap, named tickets, transfer audit; resale with a price cap post-V1 |
| Fans see the draw as opaque or unfair | One plain sentence on screen 12 and W8, real positions, an honest wait, audit `room.draw` with the count |
| Admitted fans who don't buy slow the sale | Measured rate shown in B13; OPS raises the rate live; the holds cap stops overload |
| Cash codes sit on derby stock for 24 h | The existing cash cap (20 %, config); lower it for derbies |
| Cold visitors flood the W2 SSR | Detail fetch cached 15 s per shop instance; polls bypass Next (Q152); CloudFront in Lot 6 |
| Staging infra not ready on day 9 | BE-45 is the only blocked task; demo-complete waits for it, because the capacity test is in scope (founder: W0-4) |
| Lot 5 estimate | Q166: re-cut into 5a / 5b |

## 9. Rest of Lot 5 (outline only; planned in detail when each starts)

| Step | Scope | Main contract moves | Est. |
| --- | --- | --- | --- |
| 2 · Fair on-sale | Rooms per phase (members presale, then general sale: drop `unique (event_id)`, add a phase label and order); members-only price categories (`price_categories.audience` `ALL\|MEMBERS`, checked at place); holder cap (lot-1 Q18); sold-out waitlist ("Liste d'attente", screen 1); refunded places no longer counting toward the limit (lot-3 Q52) | `waiting_rooms` phases, `price_categories.audience`, a waitlist table + notify on release | 1.5 wk |
| 3 · Passes, paid membership, installments | Orders that sell something other than an event place (lot-4 Q57): `orders.event_id` nullable + a product on items; season passes materialise one ticket per event of the series (03 `passes`); paid membership (`memberships.paid_until`); installments for passes (a schedule of N payments, cash code or card per instalment, pass `SUSPENDED` → future tickets `BLOCKED` through the manifest) | order/product rework, `passes`, `pass_installments`, B-pages for passes | 2.5 wk |
| 4 · Accreditations, invitations, promo codes | B7; `POST /org/events/{id}/invitations` (method `FREE`, zone counts, named); promo codes (amount in centimes or percent with integer rounding, per-code and per-account caps, valid per event or category) | `promo_codes`, order `discount_mad`, invitation batches | 1 wk |
| 5 · Finance and CRM export | Daily settlement statements (03 `settlements`), VAT invoices (numbering, PDF), 48 h payouts, bank-deposit matching for till cash (02 threat model), `/org/crm/export.csv` (opt-ins from `memberships`, CNDP) | settlements, invoices, payouts; the CRM export row | 2 wk |

Lot 5 total ≈ 3 + 1.5 + 2.5 + 1 + 2 = **10 weeks** against the 4 in `00-product.md`. The founder decides the split (Q166).
