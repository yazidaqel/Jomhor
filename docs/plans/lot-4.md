# Lot 4 · Fan platform: implementation plan

As of 2026-11-07 · owner: tech-lead · status: **approved**. The §3 defaults are taken under the founder's standing instruction and §4 is applied to `docs/` and `design/`. Migrations up to V15 at the start. V16 (BE-28) and V17 (multi-club BE-37) exist now, so Lot 4 continues at V18 (multi-club P63).

## 1. Lot

**Lot 4 · Fan platform.** Branch `feat/lot-4-fan-platform` from `main` (Lot 3 merged in #5).

Scope (`00-product.md:54`, `02:245`):

- group buying with split pay;
- ticket transfer (new K_t, old QR dies);
- membership + loyalty;
- matchday companion (gate directions, live gate load, "Tu es dedans");
- push and WhatsApp campaigns;
- Darija/AR/FR with RTL.

Carried in from earlier plans:

- Arabic-script holder names and the AR PDF (lot-1 Q16);
- lost-phone re-issue (lot-1 Q13 residual);
- "Tu es dedans" (lot-2 Q42);
- the automatic pay-code message (lot-3 Q53), behind the port.

Not in Lot 4:

- paid membership (Q57 → Lot 5 with passes);
- Apple/Google Wallet (Q67);
- CRM export, members-only prices and presale (Lot 5, fair on-sale);
- resale (post-V1);
- the stadium map (lot-1 Q22).

**Kafka: none in Lot 4 (Q69).** Every send goes through a DB outbox (`fan_messages`, the same pattern as `ticket_deliveries`). Campaign volume per club is small, a few tens of thousands of rows per send, drained at a configured rate. "Tu es dedans" is a **poll**, not a push: the fan app already polls `/fan/gates/{id}/load` for the gate load, and that response carries the caller's entries. So no scan-ingestion → push pipeline exists. This changes the lot-2 Q32 / `01` note that named "Lot 4 fan push" as Kafka's first consumer.

**Messaging and push providers stay behind ports with fakes.** WhatsApp sends go through the existing `TicketMessenger` port (log adapter; prod still refuses to start without a real one, Q2). Push goes through a new `PushSender` port with a log adapter. The FCM adapter (BE-36) is built once a Firebase project exists (Q61). Payzone stays simulated.

Exit ("the app people open between matches"):

| Level | Proof |
| --- | --- |
| Demo-complete (Lot 4 done) | On staging, with the Lot 1–3 flows and a Lot 2 scanner. **(1) Arabic:** a fan switches the app to العربية. Every screen mirrors, the copy is Darija, Arabic headings use Cairo, and numbers and codes stay LTR. They buy a place for "يزيد عقل". The PDF shows the shaped name; the scanner shows `ي. ع.`. The shop is the same with `?lang=ar`. **(2) Split pay:** Yazid reserves 3 places, two of them for friends with "Paie sa place". He pays his own place by card. Each friend gets a `SPLIT_INVITE` (WhatsApp log row + push fake), and the app's "Relancer sur WhatsApp" opens wa.me with the `pay_url`. Karim pays on the web shop by card; Sara gets a cash code and pays it at a Lot 3 guichet. The group screen goes 1/3 → 3/3, with one check settling per member. **(3) Transfer:** at T0 − 2 h, Karim transfers his ticket to Omar (phone + name). Karim's card slides out of his wallet. Omar gets the push and a `SENT` WhatsApp delivery. After the next manifest delta, the scanner shows Karim's old QR as ANNULÉ and Omar's as VALIDE. At T0 − 85 min the action is disabled (`TRANSFER_CLOSED`). **(4) Re-issue:** Sara uses "Téléphone perdu ?" and gets a new ticket; her old QR scans ANNULÉ. **(5) Matchday:** screen 5 flips to its gate side (Porte 4, directions, "Ouvrir dans Plans") and shows load chips from live scanner traffic. Yazid is scanned; within 60 s of signal his card flips to "Tu es dedans". **(6) Membership:** Yazid joins (push opt-in) and his card reads N° 000001. After the event closes, his 12-dot row lights one dot and his tier reads Membre. **(7) Campaigns:** OPS writes a push to MEMBERS in B9 with an event link, sees the estimate and sends it. Yazid's device (or the fake) receives it, and it lists in his inbox. A WhatsApp EVENT_HOLDERS campaign "Ouverture des portes à 18 h" writes one log row per holder. **(8) Back-office:** B10 shows gate directions; B12 shows the transfer chain; B9 shows the member counts. CI is green on `Lot4FanFlowTest` (backend), `apps/fan/test/fan_platform_test.dart`, the shop `split-pay.test.tsx` and the back-office `crm.test.tsx`. |
| Live | The same in prod, gated by: **push** (Firebase project + APNs key, Q61, BE-36); **WhatsApp** (the Q2 provider + approved templates `split_invite`, `pay_code`, `club_news`, `event_info`, Q63); a native speaker's **Darija review** (Q64); the **CNDP** declaration covering marketing opt-ins (Q62). Until then, prod has no push (PUSH campaigns get 422 `NOT_CONFIGURED`), and invites rely on the share link. |

## 2. What exists, gaps, spec refs

### 2.1 What Lot 4 builds on

| Area | State on `feat/lot-3-payments` | Lot 4 use |
| --- | --- | --- |
| Schema | `ticket_transfers`, `passes` and `settlements` exist only in `03`; no migration creates them. `tickets.status` has `CANCELLED`; the manifest maps CANCELLED → `ANNULÉ` | V16 creates `ticket_transfers` (new shape). Transfer = CANCELLED + new row: no new status |
| Tickets | `TicketService.issue(org, orderId, eventId, seats)`; K_t = HMAC(K_e, ticket_id); `heldBy(holder, withKeys)`; `TicketAdminService` (block/refund) locks the ticket row | Transfer and re-issue reuse `issue` for one seat in the same order, inside one transaction with the cancel |
| Sales | `OrderService.place` resolves holder phones to users (created if new); `per_account_limit`; `expireDue`; late success | Split pay = more orders in the same `place` transaction, plus `group_order_id` |
| Notifications | `TicketMessenger.send(Message(phone, ticketNo, pdf))`, `LogTicketMessenger` (dev/staging), prod refuses to start without a messenger; `ticket_deliveries` outbox + `TicketDeliveryJob` | Extend the port with template messages; a new `fan_messages` outbox copies the job |
| Gate | `/live` aggregates (rate_per_min, devices online, canonical scans); B4 charge = `rate_per_min ÷ (devices_total × 24)` | `/fan/gates/{id}/load` reuses the `/live` queries per gate, memoised for 15 s |
| Holder names | `common/HolderNames` (Latin only, Q16); copies in `apps/fan/.../holder_name.dart`, `web/shop/lib/holder-name.ts` and the back-office POS form | Arabic script (BE-34, FL-17, WS-7, BO-19) |
| PDF | PDFBox with Standard-14 fonts (WinAnsi); `ponytail:` note "Arabic … Lot 4" (`TicketPdf.java:193`) | Embedded font + shaping (BE-34) |
| Fan app | Riverpod, go_router `StatefulShellRoute` with 3 tabs, `ui/motion.dart` (`Entrance`, `TapCard`, reduced motion), `TicketCache` (secure storage), matchday auto-open, `ORGANIZER_ID` dart-define, ARB `fr` complete and `ar`/`en` stubs (4 keys), fonts Barlow + Inter only | Club tab, Arabic fonts, full `ar`/`en` ARB, new screens |
| Web shop | `lib/i18n` with an FR dict, `ar`/`en` stubs, `merge` fallback and `dirOf`; `layout.tsx` is FR only (`ponytail:` note); event pages are already `force-dynamic` | Locale from a cookie, with no route restructuring (Q65) |
| Back-office | B9 shows "Bientôt"; `features/venues` (B10), `features/tickets` (B12), i18next | B9 Membres · Campagnes; gate fields; transfer columns |

### 2.2 What does not exist

| Need | Plan |
| --- | --- |
| Transfer / re-issue endpoints and their freeze | BE-28, V16 |
| Gate location and directions; the fan load endpoint; entries for "Tu es dedans" | BE-32, V18 |
| Membership, opt-ins, loyalty | BE-31, V19 |
| Split pay (order grouping, holds, invites) | BE-29, V20 (+ invites in BE-30) |
| Push tokens, push port, transactional message outbox, message copy per locale | BE-30, V21 |
| Campaigns, members summary, inbox | BE-33, V22 |
| Arabic holder names, Arabic PDF | BE-34 |
| FCM adapter | BE-36 (blocked on Q61) |
| Real WhatsApp provider and templates | BE-15 (blocked on Q2, Q63) |

### 2.3 Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (no PII in QR, `event_id`, `organizer_id`, tickets immutable except `status`, centimes, strings never hard-coded, FR/AR/EN), Stack (Kafka only where async is real; Riverpod; `flutter_secure_storage`), Conventions (Flutter feature-first, shop server components, back-office shadcn + TanStack Query) |
| `docs/00-product.md` | §Why it can win (WhatsApp, Darija-first, organizer data), §V1 in lots (Lot 4 row; Lot 5 row for passes / CRM export / verified fans) |
| `docs/01-architecture.md` | §Components (fan app holds K_t; Kafka row), §Key flows 1 |
| `docs/02-gate-validation-and-data-model.md` | §Ticket token (K_t = HMAC(K_e, ticket_id): a new K_t needs a new id), §Match manifest (deltas, min 5 min between versions, accepted risk line 72), §Threat model (refund freeze), §Data model, §Fan app design direction (lines 222–231: transfer, card flip, "You're in", AR/RTL) |
| `docs/03-schema.sql` | tenant rule header, `organizers.settings`, `users.locale`, `gates`, `orders`, `order_items.holders`, `tickets`, `ticket_transfers`, `ticket_deliveries`, `fan_sessions`, `scans` |
| `docs/04-api.md` | Errors, §Fan (`/fan/me`, `/fan/orders*`, `/fan/tickets*`, transfer row line 79, load row line 80), order rules, §Holder names, §Late success, §Organizer (roles line, venues/gates, tickets search, `/org/crm/export.csv`), §Gate reports (`/live` figures), §Manifest payload |
| `docs/06-glossary.md` | Terms, order statuses |
| `design/DESIGN.md` | Principles 3, 6, 7; Components (ticket card, bottom nav 4 items); Screen inventory; screens 1, 4, 5; W3–W5; B9, B10, B12; §Theming; §Accessibility |
| `design/BRAND.md` | 12-dot idea (the amber 12th = the supporter who completes the team), colours, Arabic type (Cairo, IBM Plex Sans Arabic), voice (Darija-first) |
| `design/tokens.json` | `motion.*`, `type.label.letterSpacing`, `font.arabic*` |
| Mockups | `1-accueil.html` (bell, Club tab), `5-billet.html` (Transférer, gate guidance row) |
| Plans | lot-1 Q13, Q16, Q21, Q22, Q23, Q26, P10; lot-2 Q32, Q40, Q42, Q43; lot-3 Q53, §7 |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default for Lot 4 |
| --- | --- | --- |
| Q57 | Membership: free or paid (cotisation)? | **Free in Lot 4.** Membership = joining the club's fan base: a member number, a card, loyalty and per-channel marketing opt-ins. **Paid membership moves to Lot 5 with passes.** Both need orders that sell something other than an event place (`orders.event_id` is `not null` and items point at `price_categories`), so that rework should happen once. Lot 4 perks are display only. Members-only prices and presale belong to Lot 5 fair on-sale. |
| Q58 | Loyalty unit and tiers | **Events attended**, i.e. a held ticket with a canonical admitted scan (`OK`/`OVERRIDE`). Computed on read, with no points ledger and no redemption in V1. Tiers come from `organizers.settings.loyalty.tiers` (localised names). Defaults: Membre 0 · Fidèle 3 · Pilier 10. The scan counts for the ticket's holder, so a transferred ticket counts for the recipient. |
| Q59 | Transfer rules | **Holder only, free, `VALID` only, any number of times in a chain, phone + full name required** (tickets are named, lot-1 Q15). **Transfers close at `refund_cutoff_at − 30 min` (T0 − 90)**, config `tickets.transfer.close-before-cutoff`. A later transfer would reach no scanner: versions are ≥ 5 min apart and signal is assumed gone at T0 − 60, so the recipient would get INCONNU (no override without a manifest row, lot-2 Q40) while the old QR still passes. Received tickets don't count toward `per_account_limit`, which limits buying. There is no per-event toggle until the club asks for one for derbies. **Re-issue** (lost phone) follows the same rules, keeps the same holder and sends no message. |
| Q60 | Split pay rules | One place per invited friend's phone (a friend may get several if the same phone is on several places). The initiator pays their own places first; friends are held **2 h after the initiator pays** (`sales.split.hold`), capped at sales close. Friends pay any method, cash code included. Unpaid friend places are released at their deadline; the initiator is never charged for them. Split pay is refused when sales close in less than 2 h. The initiator must keep at least one place. |
| Q61 | Push provider | **FCM** (Android, and iOS through APNs). Founder: create a Firebase project and an APNs auth key (Apple developer account, Q25). Until then, the `PushSender` port has a log adapter in dev/staging, prod refuses PUSH campaigns (422 `NOT_CONFIGURED`), and the app builds Firebase only with `--dart-define=PUSH_ENABLED=true`. **Risk:** Huawei phones without Google services have no push token, so PUSH campaigns skip them (and so does the inbox, §Campaigns Audience); they still get WhatsApp, transactional and campaign. |
| Q62 | Campaign consent (CNDP 09-08, Meta policy) | **MEMBERS campaigns are marketing:** only members who opted in on that channel (both switches default off). **EVENT_HOLDERS campaigns are service information about that event** (doors, access, schedule change), sent to every `VALID` holder under the purchase relationship (lot-1 Q21). WhatsApp uses a *utility* template for them, and the composer says "infos pratiques uniquement". There is no frequency cap in Lot 4. Founder: extend the CNDP declaration to marketing. |
| Q63 | WhatsApp templates (needs Q2) | Five templates to register through the provider, each FR/AR/EN: `ticket` (exists), `split_invite`, `pay_code` (utility), `event_info` (utility), `club_news` (marketing). Template ids live in config. Until Q2, the log adapter writes the kind and the recipient user id, never the phone. Marketing conversations are paid per message: the founder sets the budget. |
| Q64 | What `ar` means, and who writes it | **`ar` = Moroccan Darija in Arabic script** on fan surfaces (app, shop, WhatsApp, push), Modern Standard Arabic for legal pages. EN is complete too. The agents draft both, and **a native speaker named by the founder reviews before live**. The back-office stays FR (organizers). |
| Q65 | Shop locale URLs (SEO) | **`?lang=`, else the `locale` cookie, else `Accept-Language`, else FR.** `?lang=ar\|fr\|en` wins and sets the cookie, so a shared Arabic link opens in Arabic. URLs stay unprefixed and the canonical stays the bare URL (event pages are already `force-dynamic`, so nothing is lost). `/ar/...` paths and `hreflang` come later if Arabic SEO matters. |
| Q66 | Arabic-script holder names | **Allowed**: one script per name (Latin or Arabic, never mixed), harakat and tatweel stripped. Initials and labels follow the same rules. **Club question:** if the league requires the name as on the CIN's Latin line, restrict ticket holders to Latin again and keep Arabic for `display_name` only (one flag in `HolderNames`). |
| Q67 | Apple / Google Wallet (lot-1 adjustments listed it for Lot 4) | **Not in Lot 4 → post-V1.** It would carry only the static v1 QR (Google's rotating barcode uses its own TOTP, not our token). It needs an Apple pass certificate and a Google issuer account, about 3 d of backend work. The button stays hidden. |
| Q68 | Mockup lines "6 min à pied" and "2 min d'attente" | **Dropped.** There is no fan location (no location permission) and no queue length: scanners count throughput, not people waiting. Lot 4 shows gate directions, an "Ouvrir dans Plans" link, a load level per gate (Fluide · Modérée · Chargée, hidden when unknown) and "Porte 3 plus fluide en ce moment". |
| Q69 | Kafka (lot-2 Q32 said "Lot 4 fan push") | **None.** DB outbox for sends, a poll for entries (§1). Revisit with the Lot 6 load test. `01` §Components Kafka row edited (§4.5). |
| Q70 | Mockups for the new screens before building? | **No blocking mockup round.** Builders follow §4.6 and the "wow" table. Golden screenshots (FR + AR) of screens 5 (both sides), 7, 8 and 9 are part of each task. **Founder "wow review" at day 11** on a staging build (screens 5 and 7 done). Changes from it go into the FL-21/FL-22 slack. If the founder wants Claude Design canvases first, add 2 d before FL-19. |
| Q81 | Which gate is "your gate" on screen 5 when several serve the zone? | The first of `gates[]` by code. "Porte Y plus fluide" names a LOW gate of the same zone; the gate side lists all of them. (Wave 1 re-plan, 2026-11-09.) |
| Q82 | Does "entered" survive an app restart? | Yes: ticket ids in `SharedPreferencesAsync` (no personal data), wiped on logout. Only a newly entered id animates. |
| Q83 | Poll `/load` from the "Mon billet" strip too? | Not in FL-19; screen 5 only. |
| Q84 | Gate PATCH both-or-neither: on the body or the resulting row? | On the body. The back-office always sends the pair. |
| Q85 | Demo gate directions and coordinates in the dev seed? | Yes, in BE-32. Made-up text; the founder can correct the coordinates. |
| Q86 | `/load` entries for a ticket transferred away? | As 04: any held status. No visible effect (screen 5 shows transferred tickets as rows). |
| Q87 | Accueil chip row while `/fan/organizers` is down? | Hidden; the event list still works from the follows on the phone. |
| Q88 | Load chips offline? | Keep the last level while its `server_time` is under 2 min old, then hide the chips. Directions never hide. |
| Q89 | Does a rejoin reset `joined_at`? | No: a rejoin clears `left_at`, keeps `number` and `joined_at`; 201 with audit `membership.join`. BE-33 `joined_7d` doesn't count rejoins. (Wave 2 plan, 2026-11-09.) |
| Q90 | Unknown or bad organizer on the membership calls | Missing or malformed `?organizer=` → 400; unknown → 404 (PUT too). Listed or not, any existing organizer works. |
| Q91 | Audit a PUT that changes nothing? | No: `membership.consent` only when a boolean changes; still 200. |
| Q92 | "matchs" vs "événements" | "matchs" when every `last_events` row is `FOOTBALL_MATCH` (at least one); with none, when the club is a `SPORTS_CLUB`; else "événements". Same on the Accueil strip. |
| Q93 | Fewer than 11 closed events, or no next event | Always 12 dots: missing past slots are faint placeholders on the oldest side; the 12th is always amber, captioned only when there is a next event. |
| Q94 | Club tab signed out | `/club` needs a session (like Billets) → login → back. |
| Q95 | "Rolls once" / "fills once" | Per phone: `club.seen` in `SharedPreferencesAsync` (organizer ids, `orgId:eventId`), wiped on sign-out. |
| Q96 | Source of the 12th-dot event | One `GET /fan/events` with every followed `organizer`; the first row per organizer. |
| Q97 | Campaign wall-time zone in B9 | The selected event's venue timezone; MEMBERS without event → `Africa/Casablanca`. |
| Q98 | Dev data for the wow review | `DevSeeder` (dev only): 10 more past CLOSED events for the first demo club, the demo fan admitted on 7. Timebox 0.5 d, else the review runs on goldens. |
| Q99 | Malformed `settings.loyalty` | Defaults + one WARN log, never a 500. |
| Q100 | `events_attended` before CLOSED? | Yes (04 has no status filter): the count moves at the entry scan; the dot fills once the event is CLOSED. |
| Q101 | PATCH can't clear a campaign's `event_id` (null = unchanged) | Keep the contract. The B9 composer doesn't offer removing the event of a saved draft; delete the draft and start again. Revisit with BE-33 if organizers ask. |
| Q102 | Member `AWAITING_CASH` or in card grace when the buyer expires | Keeps its own hold (P79). (Wave 3 plan, 2026-11-12.) |
| Q103 | Member hold while the buyer has a cash code | Mirrors the buyer's `reserved_until` (P78). |
| Q104 | Split as a holding vector | Unpaid member places also count toward the initiator's limit (P83). |
| Q105 | `initiator_label` source | P82; null → "Un ami t'a gardé une place". |
| Q106 | Same phone with `pays` on one place and not on another | Allowed: the non-`pays` place stays in the buyer's order. |
| Q107 | "{paid}/{total}" counts places or people | Places (P84). |
| Q108 | "Check settles once" | Per phone: member order ids in `group.seen`, wiped on sign-out. |
| Q109 | "2 h" caption vs `sales.split.hold` | Literal "2 h" in copy; clients learn TOO_LATE only from the 422. Changing the hold means changing the copy. |
| Q110 | Relancer per member or per group | Per unpaid member (P84). |
| Q111 | W4 group block live | SSR + `router.refresh()` every 10 s while visible and a member is payable. |
| Q112 | Mirrored member places in the cash cap | Not counted (soft cap, `ponytail:` note). |
| Q113 | Buyer pays cash close to the cutoff | Accepted: members get `least(paid_at + 2 h, cutoff)`, no minimum. |
| Q114 | List member orders to pay in W5 / Billets | No: app Accueil strip only; the shop relies on `pay_url`. |
| Q115 | Initiator opens a member's `pay_url` | 404 (not the owner). |
| Q116 | Which settles send ORDER_PAID | `AWAITING_CASH` → `PAID` by any method, and a late success reviving an `EXPIRED` order. Not `RESERVED` → `PAID` (P89). (Wave 4 plan, 2026-11-12.) |
| Q117 | Subject changed before send | `FAILED STALE`, no retry (P88). |
| Q118 | One token per session | Yes: a PUT deletes the session's other tokens (P90). |
| Q119 | Firebase client config | Dart-defines `FIREBASE_API_KEY`, `FIREBASE_APP_ID`, `FIREBASE_MESSAGING_SENDER_ID`, `FIREBASE_PROJECT_ID` (+ `FIREBASE_IOS_BUNDLE_ID`) into `Firebase.initializeApp(options:)`. No google-services.json, plist or gms Gradle plugin in the repo. |
| Q120 | Foreground banner | DESIGN "Push in foreground" row (P93); never over `/tickets/:id`. |
| Q121 | Bell signed out | Hidden. |
| Q122 | Unread basis | Newest `sent_at` seen (server time) under one key `inbox.seen`, wiped on sign-out. |
| Q123 | Profil "Notifications" row | Shows the permission state; "Activer" asks only while the OS allows a prompt; denied → "Active-les dans les réglages du téléphone". |
| Q124 | Offline logout leaves a live server token | `signOut()` also calls `deleteToken()` on the device; FCM then reports UNREGISTERED. |
| Q125 | Arabic push text with amounts and times | FSI…PDI isolates in the `ar` push bundle, not in WhatsApp params. |
| Q126 | "First purchase" for the permission ask | The first PAID order screen (split members included) or a join with push on; once per install (`push.asked`). |
| Q127 | Who builds the send-rate cap | BE-30: at most rate × job period per run, transactional kinds first. |
| Q128 | PAY_CODE recipient and later codes | The order's `user_id`; a later code sends nothing (unique index). |
| Q129 | Inbox before BE-33 | Dot hidden on any error; screen 10 shows the error panel with retry. FL-22's inbox closes after one run against BE-33. |
| Q130 | Phones with no push token, and the inbox | Keep the token rule (the estimate = who is reachable); the inbox lists `SENT` rows only (P94). (Wave 5 plan, 2026-11-13.) |
| Q131 | Re-check EVENT_HOLDERS at send | No: the snapshot decides; service info to an ex-holder is harmless. |
| Q132 | Campaign audit payloads | Create `{channel, audience}`; schedule `{scheduled_at}`; cancel none; PATCH not audited. Never the title or body. |
| Q133 | Tier names in the members summary | `fr`, zeros included. |
| Q134 | Title/body length unit | Code points (as §Holder names); the back-office zod counts UTF-16 units, never looser. |
| Q135 | "Send now" latency | Up to 30 s through the job; the back-office polls while due (P100). |
| Q136 | Campaign linked to a `CLOSED` event | Allowed (the rule is "not DRAFT"). |
| Q137 | Dev data for the real-API runs | No seeder change; `backend/http/lot4-fan.http` logs the dev fan in and PUTs a fake push token. |
| Q138 | PUSH row whose tokens all died after the snapshot | `NO_DEVICE` (counted in `failed`); no WhatsApp fallback. |

## 4. Contract changes

### 4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P47 | 04:79 | Transfer `{to_phone}` has no holder name (tickets are named; the manifest needs initials), no freeze, no response, no message rule | §4.3 transfer + re-issue rows |
| P48 | 03:217 | `ticket_transfers` doesn't point to the new ticket, so the chain can't be followed | `new_ticket_id`; `ticket_id` unique |
| P49 | 02:72, 02:65 | A transfer up to `refund_cutoff_at` reaches no scanner: versions are ≥ 5 min apart and signal is gone at T0 − 60. The new ticket is INCONNU (no override, lot-2 Q40) and the old QR still passes | `transfer_until` = cutoff − 30 min (Q59), `TRANSFER_CLOSED` |
| P50 | 04:80 | `/fan/gates/{event_id}/load` says only "Lot 4" | §4.3 row; it also carries the caller's entries ("Tu es dedans", lot-2 Q42) |
| P51 | 03:99, 02:228, `5-billet` mockup | Gates have no location or directions; "6 min à pied" and "2 min d'attente" can't be computed | `gates.directions/latitude/longitude` (numeric, never float); Q68 |
| P52 | 04 §Fan `/fan/tickets` | Gate details aren't in the cached ticket, so offline directions are impossible | `gates[]` per ticket, plus `transfer_until` and `transferred` |
| P53 | 04:99, lot-1 Q16 | Holder names are Latin only | §Holder names, Arabic script (Q66) |
| P54 | `TicketPdf` (WinAnsi) | Arabic text prints as "?" | Embedded font + shaping (BE-34); 04 PDF row note |
| P55 | 01:42, lot-2 Q32 | Kafka was announced for "Lot 4 fan push" | DB outbox; `01` edit (Q69) |
| P56 | `TicketMessenger` | Sends only ticket PDFs; Lot 4 needs template messages (invite, pay code, campaigns) | Port gains template messages; `fan_messages` outbox; §Notifications |
| P57 | — | No consent record anywhere; marketing needs per-channel opt-in | `memberships.marketing_push/_whatsapp` + audit |
| P58 | 03 orders | One buyer per order; no grouping for split pay | `orders.group_order_id` + §Split pay |
| P59 | DESIGN:16–24 | Principle 6 lists no motion for transfer, member card or group progress | Principle 6 append (§4.6) |
| P60 | DESIGN:117 | Screen 5 lists "Wallet (Apple/Google)" | Hidden; post-V1 (Q67) |
| P61 | DESIGN:187 | B9 is "4–5" without a scope | Lot 4: Membres (aggregates) · Campagnes; export Lot 5 |
| P62 | tokens `type.label` (letterSpacing 1.5, uppercase) | Tracking breaks Arabic joining, and uppercase means nothing in Arabic | AR rule in §4.6 |
| — | Football names / floats / PII in QR / server on the gate path | No football names in tables or columns ("match" only in UI copy keyed on `event_type`; tier names in settings). Coordinates are `numeric`, money stays `bigint` centimes. Push and WhatsApp payloads carry no phone, no full name, no K_t, no QR. The scanner gets no code change (one Arabic-initials golden only) | OK |

### 4.2 `docs/03-schema.sql`

```sql
-- organizers.settings shape (append):
--   "loyalty": {"tiers": [{"code": "T1", "name": {"fr": "Membre", "ar": "منخرط", "en": "Member"}, "min_events": 0},
--                         {"code": "T2", "name": {"fr": "Fidèle", "ar": "وفي", "en": "Regular"}, "min_events": 3},
--                         {"code": "T3", "name": {"fr": "Pilier", "ar": "ركيزة", "en": "Stalwart"}, "min_events": 10}]}
--   tiers by min_events ascending, the first at 0; absent = these defaults; a missing locale falls back to "fr".

-- ---------- catalog: gates (replace) ----------
create table gates (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  venue_id      uuid not null references venues(id),
  code          text not null,                        -- 'P4'
  name          text not null,
  directions    text,                                 -- 0-200 chars, how to reach the gate; fan app only, never in the manifest
  latitude      numeric(8,6) check (latitude between -90 and 90),     -- WGS 84, for the "Ouvrir dans Plans" link
  longitude     numeric(9,6) check (longitude between -180 and 180),
  constraint gates_location check ((latitude is null) = (longitude is null)),
  unique (venue_id, code)
);

-- ---------- sales: orders (add) ----------
  group_order_id  uuid references orders(id),   -- split pay: set on a member order, = the initiator's order (docs/04-api.md §Split pay)
create index on orders (group_order_id) where group_order_id is not null;

-- ---------- sales: ticket_transfers (replace) ----------
create table ticket_transfers (                       -- transfer or re-issue: the old ticket goes CANCELLED, a new one is issued
  id              uuid primary key default gen_random_uuid(),   -- (new ticket_id, so a new K_t; the old QR dies with the next manifest)
  organizer_id    uuid not null references organizers(id),
  ticket_id       uuid not null unique references tickets(id),  -- the ticket cancelled
  new_ticket_id   uuid not null unique references tickets(id),  -- same order, event, zone and price category
  from_user_id    uuid not null references users(id),
  to_user_id      uuid not null references users(id),           -- = from_user_id for a re-issue (lost phone)
  transferred_at  timestamptz not null default now()
);
create index on ticket_transfers (from_user_id, transferred_at desc);

-- ---------- fans (new) ----------
create table memberships (                            -- a fan who joined an organizer's club (free, Lot 4); loyalty is computed on read
  id                  uuid primary key default gen_random_uuid(),
  organizer_id        uuid not null references organizers(id),
  user_id             uuid not null references users(id),
  number              integer not null check (number > 0),   -- member number per organizer, from 1, never reused
  joined_at           timestamptz not null default now(),
  left_at             timestamptz,                           -- left the club; a rejoin clears it and keeps the number
  marketing_push      boolean not null default false,        -- opt-ins for MEMBERS campaigns (changes audited: membership.consent)
  marketing_whatsapp  boolean not null default false,
  unique (organizer_id, user_id),
  unique (organizer_id, number)
);

-- ---------- notifications (new) ----------
create type message_channel as enum ('PUSH','WHATSAPP');
create type message_kind    as enum ('TICKET_RECEIVED','SPLIT_INVITE','PAY_CODE','ORDER_PAID','CAMPAIGN');

create table push_tokens (                            -- FCM registration tokens of the Jomhor fan app (one app, every club)
  id            uuid primary key default gen_random_uuid(),   -- platform identity like fan_sessions: no organizer_id;
  user_id       uuid not null references users(id),          -- per-club marketing consent is memberships.marketing_push
  session_id    uuid not null references fan_sessions(id), -- used only while this session is live; deleted when it is revoked
  token         text not null unique,
  platform      text not null check (platform in ('android','ios')),
  updated_at    timestamptz not null default now()
);
create index on push_tokens (user_id);

create type campaign_status   as enum ('DRAFT','SCHEDULED','SENDING','SENT','CANCELLED');
create type campaign_audience as enum ('MEMBERS','EVENT_HOLDERS');

create table campaigns (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  channel       message_channel not null,
  audience      campaign_audience not null,     -- MEMBERS: opted-in members (marketing); EVENT_HOLDERS: VALID holders (service info)
  event_id      integer references events(id),  -- required for EVENT_HOLDERS; the push deep link
  title         text not null,                  -- 1-60 chars, any script
  body          text not null,                  -- 1-500 chars, any script
  status        campaign_status not null default 'DRAFT',
  scheduled_at  timestamptz,
  created_by    uuid not null references staff(id),
  created_at    timestamptz not null default now(),
  started_at    timestamptz,                    -- audience snapshot taken
  finished_at   timestamptz,
  constraint campaigns_event check (audience <> 'EVENT_HOLDERS' or event_id is not null)
);
create index on campaigns (organizer_id, created_at desc);
create index on campaigns (scheduled_at) where status = 'SCHEDULED';

create table fan_messages (                           -- outbox: push and WhatsApp messages other than ticket PDFs (ticket_deliveries)
  id               uuid primary key default gen_random_uuid(),
  organizer_id     uuid not null references organizers(id),
  user_id          uuid not null references users(id),     -- recipient; phone, locale and push tokens read at send time
  channel          message_channel not null,
  kind             message_kind not null,
  campaign_id      uuid references campaigns(id),
  order_id         uuid references orders(id),             -- SPLIT_INVITE, PAY_CODE, ORDER_PAID
  ticket_id        uuid references tickets(id),            -- TICKET_RECEIVED
  status           delivery_status not null default 'PENDING',
  attempts         smallint not null default 0,
  next_attempt_at  timestamptz not null default now(),
  last_error       text,                                   -- provider code or NO_DEVICE / OPTED_OUT / NOT_CONFIGURED; never a phone or body
  created_at       timestamptz not null default now(),
  sent_at          timestamptz,
  constraint fan_messages_campaign check ((kind = 'CAMPAIGN') = (campaign_id is not null))
);
create index on fan_messages (next_attempt_at) where status = 'PENDING';
create unique index on fan_messages (campaign_id, user_id) where campaign_id is not null;          -- one per recipient
create unique index on fan_messages (order_id, kind, channel) where kind in ('SPLIT_INVITE','PAY_CODE','ORDER_PAID');  -- once
create index on fan_messages (user_id, created_at desc) where kind = 'CAMPAIGN' and channel = 'PUSH';  -- inbox
```

Flyway, one migration per task:

| File | Task | Content |
| --- | --- | --- |
| `V16__ticket_transfers.sql` | BE-28 | `ticket_transfers` + index |
| `V18__gate_directions.sql` | BE-32 | `gates.directions`, `latitude`, `longitude`, `gates_location` |
| `V19__memberships.sql` | BE-31 | `memberships` |
| `V20__split_pay.sql` | BE-29 | `orders.group_order_id` + partial index |
| `V21__fan_messages.sql` | BE-30 | `message_channel`, `message_kind`, `push_tokens`, `fan_messages` without `campaign_id` (+ its index and the per-order unique index) |
| `V22__campaigns.sql` | BE-33 | `campaign_status`, `campaign_audience`, `campaigns`, `fan_messages.campaign_id` + `fan_messages_campaign` check + the two campaign indexes |

The tenant-exempt allowlist goes to 5 when BE-30 lands: `push_tokens` is platform identity like `fan_sessions` (multi-club Q73, founder-confirmed 2026-11-08). The `02` §Data model table gains the five tables and the gate columns in the same docs change.

### 4.3 `docs/04-api.md`

> Applied 2026-11-07; multi-club B.2 changed the push-token and inbox rows, §Notifications rules and §Campaigns Audience. `docs/04-api.md` is normative.

**Errors: add or replace** (header: "Lot 0 to Lot 4")

| Code | HTTP | When |
| --- | --- | --- |
| `TICKET_NOT_VALID` | 409 | PDF, WhatsApp re-send, transfer or re-issue of a ticket that isn't `VALID` |
| `TRANSFER_CLOSED` | 409 | Transfer or re-issue at or after the ticket's `transfer_until` (`refund_cutoff_at − tickets.transfer.close-before-cutoff`, default 30 min; no cutoff set = closed); `details.transfer_until` |
| `CAMPAIGN_STATUS` | 409 | Campaign PATCH or send outside `DRAFT`, cancel outside `SCHEDULED`; `details.status` |
| `PER_ACCOUNT_LIMIT` | 409 | `details.limit`, `details.held`; **Lot 4:** + `details.field` (the holder path) when a split-pay member is over their own limit |

**§Fan: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/fan/tickets?with_keys=` | Lot 1 shape. **Lot 4:** each row + `gates:[{code, name, directions, latitude, longitude}]` (the gates serving the ticket's zone, by code; coordinates as JSON numbers with at most 6 decimals, or null; `gate_codes` stays). Each row also gets `transfer_until` (RFC 3339, or null when the event has no cutoff) and `transferred: {to_label, at} \| null`, set on a ticket this holder transferred away (its status is `CANCELLED`). Tickets cancelled by a re-issue are not listed. |
| POST | `/fan/tickets/{id}/transfer` | `{to_phone, name}`. Holder only (404). `VALID` (else 409 `TICKET_NOT_VALID`) and `now < transfer_until` (else 409 `TRANSFER_CLOSED`). `to_phone` E.164, not the caller's (422 on `to_phone`, `details.reason` `SELF`: use re-issue). `name` per §Holder names (422 on `name`). In one transaction, with the ticket row locked first: the ticket becomes `CANCELLED`, and a **new ticket** is issued in the same order, event, zone and price category. Its `holder_user_id` = the `to_phone` account (created if new), with `holder_name` / `holder_initials` from `name`. A `ticket_transfers` row is written, plus one WhatsApp delivery to the recipient (as on payment) and one `TICKET_RECEIVED` push (§Notifications). Audit `ticket.transfer` (ids only). → `201 {transfer_id, to_label, transferred_at}`. The next manifest version carries both rows: the old QR scans `ANNULÉ`. `price_categories.sold`, `/sales` and the gate `sold` are unchanged. The order's buyer never sees the new ticket. |
| POST | `/fan/tickets/{id}/reissue` | No body. Lost or replaced phone: the same checks and transaction as a transfer, to the caller with the same name. No delivery, no push. Audit `ticket.reissue`. → `201 {ticket_id}`. The app then refetches `/fan/tickets?with_keys=true`. The old K_t is dead from the next manifest version. |
| GET | `/fan/gates/{event_id}/load` | The caller holds a ticket of the event, any status (else 404). → `{event_id, server_time, gates:[{code, level}], entries:[{ticket_id, entered_at, gate_code}]}`. `gates` = the venue's gates with an assigned device, by code. `level`: `UNKNOWN` when none of the gate's assigned `ACTIVE` devices was seen in the last 2 min. Otherwise charge = `rate_per_min ÷ (devices_online × 24)` (§Gate reports `/live` figures for that gate): `LOW` < 0.5 ≤ `MEDIUM` < 0.85 ≤ `HIGH`. The gate part is computed once per event per 15 s (it may be 15 s old). `entries` = the caller's held tickets of the event that have a canonical admitted scan (`entered_at` = its `device_time`, `gate_code` = its gate). `Cache-Control: no-store`. Clients poll every 60 s ± 10 s, only while screen 5 or the matchday strip is visible, from T0 − 3 h to T0 + 1 h. They stop polling once the shown ticket is entered. |
| POST | `/fan/orders` | Lot 3 shape. **Lot 4:** `holders:[{name, phone?, pays?}]`; `pays: true` = split pay (§Split pay). The response + `group: {members:[{order_id, holder_label, places, total_mad, status, reserved_until, pay_url}]} \| null`. |
| GET | `/fan/orders/{id}` | Lot 3 shape + `group`, one of: null; `{role: INITIATOR, members:[{order_id, holder_label, places, total_mad, status, reserved_until, pay_url}]}` (members by `order_id`); or `{role: MEMBER, initiator_label, initiator_status}`. `pay_url` = `{payments.return-url.web}` filled with the member order id (the shop's `/orders/{id}`). Labels only: never a member's phone or full name. |
| GET | `/fan/orders` | Lot 3 shape + per row `group_role` (`INITIATOR`, `MEMBER` or null). Order: `AWAITING_CASH` orders and **member** orders still `RESERVED` and held come first, then by `created_at` desc. |
| GET | `/fan/me/membership?organizer=` | → `{member: {number, joined_at, marketing_push, marketing_whatsapp} \| null, loyalty: {events_attended, tier:{code, name}, next_tier:{code, name, min_events} \| null, last_events:[{event_id, title, event_type, starts_at, timezone, attended}]}}`. `events_attended` = distinct events of this organizer where the caller holds a ticket with a canonical admitted scan. `tier` per `organizers.settings.loyalty` (`name` in the caller's `locale`, falling back to `fr`). `last_events` = the organizer's 11 latest `CLOSED` events, oldest first. Loyalty is returned for non-members too. A membership with `left_at` set → `member: null`. |
| PUT | `/fan/me/membership` | `{organizer_id, marketing_push, marketing_whatsapp}` (booleans, required). Not a member → join, `201`: the next `number` for the organizer (unique index; a collision draws the next), or the old number if they left before. Already a member → the opt-ins are updated, `200`. Audit `membership.join` / `membership.consent` (the two booleans, nothing else). → GET shape. |
| DELETE | `/fan/me/membership?organizer=` | Leave: `left_at = now()`, both opt-ins false → `204` (idempotent). Campaign rows not yet sent to this user are skipped (`OPTED_OUT`). Audit `membership.leave`. |
| PUT | `/fan/me/push-token` | `{token, platform: android\|ios}` → `204`. Upsert by `token`: the row moves to the caller and the current session. Tokens of revoked or expired sessions are never used. `POST /fan/me/logout` deletes the tokens of the sessions it revokes. |
| GET | `/fan/me/inbox` | PUSH campaigns sent to the caller, newest first, at most 30 → `{messages:[{id, title, body, event_id, sent_at}]}` (`id` = the campaign). `Cache-Control: no-store`. |

**§Fan order rules: append.** "Split pay (Lot 4) adds member orders (§Split pay). Their places count toward the member's limit, not the initiator's, once the order exists."

**New §Split pay**

| Rule | Value |
| --- | --- |
| Request | In `POST /fan/orders`, a holder with `pays: true` pays for their own place. They need a `phone` that isn't the buyer's (422 on `items[i].holders[j].pays`, `details.reason` `PHONE_REQUIRED`). At least one place has no `pays` (422 on `items`, `NO_BUYER_PLACE`). Refused when `now + sales.split.hold` (config, default 2 h) is at or after `refund_cutoff_at` (422 on the first `pays`, `TOO_LATE`). |
| Orders | One transaction and one quota reservation for every place. The buyer's order holds the places without `pays`, and its `total_mad` counts only those. There is one **member order** per distinct `pays` phone. Its `user_id` is that phone's account (created if new). It has the same `channel` and event, `group_order_id` = the buyer's order, status `RESERVED` and `reserved_until` = the buyer's. Its items carry that member's places, held by the member (`name` as typed). |
| Limits | At request time the buyer's check counts every place of the request (as today). Each member's `RESERVED`/`AWAITING_CASH`/`PAID` places for the event, plus their new ones, must be ≤ `per_account_limit`, else 409 `PER_ACCOUNT_LIMIT` with `details.field` = that member's first holder path. |
| Hold | The expiry job skips member orders while the buyer's order is `RESERVED` or `AWAITING_CASH`. When the buyer's order expires, its member orders expire in the same pass. When the buyer's order becomes `PAID`, every member order still `RESERVED` gets `reserved_until = least(paid_at + sales.split.hold, refund_cutoff_at)` and one `SPLIT_INVITE` (§Notifications), in the settle transaction. From then on each member order expires on its own. |
| Paying | A member pays their order with `/fan/orders/{id}/pay` like any order (card, wallet or cash code), before or after the buyer. Tickets go to the member only. The buyer never pays for a member's place and never sees a member's ticket. |
| Late | A buyer's success after expiry (§Late success) doesn't revive member orders that have expired. |

**§Holder names: replace the Input row, append two notes**

| Rule | Value |
| --- | --- |
| Input | Input containing a letter-number (`\p{Nl}`) is refused first. Then: NFKC normalisation (fullwidth → ASCII, Arabic presentation forms → letters); typographic apostrophe `’` → `'`; Arabic tatweel U+0640 and Arabic combining marks (U+0610–U+061A, U+064B–U+065F, U+0670, U+06D6–U+06ED) removed; inner whitespace (incl. no-break spaces) collapsed to one space; trimmed. Then 2–60 chars in **one script**. **Latin:** `^[\p{IsLatin}&&\p{L}][[\p{IsLatin}&&\p{L}] '.\-]{1,59}$`. **Arabic:** `^[\p{IsArabic}&&\p{L}][[\p{IsArabic}&&\p{L}] \-]{1,59}$` (Java class syntax). Mixed scripts → refused. Applies to holders, transfer `name` and `display_name`. |
| Arabic labels | `holder_label` = first word + space + first letter of the last word + "." → `يزيد ع.`. `holder_initials` → `ي. ع.`. No case mapping. Clients render names and labels in a bidi isolate. |
| PDF | Arabic text (title, venue, holder label) is shaped and drawn with an embedded font; the labels stay FR. |

**New §Notifications** (transactional; `fan_messages` outbox, sent after commit with the `ticket_deliveries` retry policy)

| Kind | Trigger | Channels and content |
| --- | --- | --- |
| `TICKET_RECEIVED` | Transfer | PUSH to the recipient: "Tu as reçu un billet · {event title}" → `/tickets/{new_ticket_id}`. The PDF goes on WhatsApp through `ticket_deliveries` |
| `SPLIT_INVITE` | The buyer's order becomes `PAID` | WHATSAPP (template `split_invite`) and PUSH to each member: "{initiator_label} t'a gardé une place · {event title} · {amount} · avant {deadline in venue tz}" + `pay_url` (push → `/orders/{member_order_id}`) |
| `PAY_CODE` | First code of a `CASH_AGENCY` `/pay` | WHATSAPP (template `pay_code`) to the buyer: code `JMH 4F7K 29`, exact amount, deadline (lot-3 Q53; the W4 button stays hidden) |
| `ORDER_PAID` | An `AWAITING_CASH` order becomes `PAID` (guichet or network) | PUSH to the buyer: "Paiement reçu · tes billets sont prêts" → `/orders/{id}` |

Rules: copy comes from backend message bundles in the recipient's `users.locale` (fr, ar, en; fr fallback). Push payloads carry a title, a body and an app path only: no phone, no full name (labels only), no K_t, no QR, no amount beyond the invite's. PUSH goes to the user's tokens for the message's organizer with a live session. No token → `FAILED` `NO_DEVICE`. A token the provider reports as unregistered is deleted. No push provider configured → `FAILED` `NOT_CONFIGURED`. Each (order, kind, channel) is queued once.

**§Organizer: role line, append.** "Lot 4: `/org/campaigns/**` ADMIN, OPS (write), FINANCE (read). `/org/members/summary` ADMIN, OPS, FINANCE. CASHIER, GATE, SUPERVISOR get 403."

**§Organizer: replace or add rows**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/org/venues/{id}/gates` | `{code, name, directions?, latitude?, longitude?}` → `{id, code, name, directions, latitude, longitude, zone_ids:[]}`. `directions` 0–200 chars (`""` clears it on PATCH). Coordinates as JSON numbers, both or neither (else 422 on `latitude`), ranges −90..90 / −180..180, at most 6 decimals (422). These fields are not in the manifest payload, so they never build a new version. PATCH takes the same fields; `GET /org/venues/{id}` gates carry them. |
| POST | `/org/events/{id}/tickets/search` | Lot 3 shape + per row `transferred_to` (`ticket_no` of the new ticket, or null) and `transferred_from` (`ticket_no` of the ticket it replaced, or null). |
| GET | `/org/members/summary` | `{members, joined_7d, marketing_push, marketing_whatsapp, reachable_push, tiers:[{code, name, members}]}`: active members (`left_at` null). `reachable_push` = members with push opt-in and a live token. `tiers` by `min_events`. Counts only: no name or phone (the CRM export is Lot 5). |
| GET | `/org/campaigns` | `[{id, channel, audience, event_id, title, status, scheduled_at, started_at, finished_at, created_by:{id, name}, recipients, sent, failed}]`, newest first, at most 100. `recipients` / `sent` / `failed` count `fan_messages` rows (0 before the snapshot). |
| POST | `/org/campaigns` | `{channel: PUSH\|WHATSAPP, audience: MEMBERS\|EVENT_HOLDERS, event_id?, title, body}` → `201` (GET shape), status `DRAFT`. `title` 1–60 chars, `body` 1–500 chars (trimmed, any script). `event_id` is required for `EVENT_HOLDERS` and optional for `MEMBERS` (the push deep link); it must be an event of this organizer that isn't `DRAFT` (422 on `event_id`). A channel with no provider configured → 422 on `channel`, `details.reason` `NOT_CONFIGURED`. Audit `campaign.create`. |
| GET, PATCH | `/org/campaigns/{id}` | GET: the list row + `body` + `audience_estimate` (recipients if sent now, computed on read). PATCH: the POST fields, `DRAFT` only (409 `CAMPAIGN_STATUS`). |
| POST | `/org/campaigns/{id}/send` | `{scheduled_at?}` (RFC 3339; absent or past = now; at most 30 days ahead, else 422). `DRAFT` → `SCHEDULED`. Audit `campaign.schedule`. → GET shape. |
| POST | `/org/campaigns/{id}/cancel` | `SCHEDULED` → `CANCELLED`, else 409 `CAMPAIGN_STATUS`. Audit `campaign.cancel`. → GET shape. |

**New §Campaigns**

| Rule | Value |
| --- | --- |
| Audience | `MEMBERS`: members with `left_at` null and the channel's opt-in (`marketing_push` / `marketing_whatsapp`); marketing. `EVENT_HOLDERS`: users holding a `VALID` ticket of `event_id`, member or not; service information about that event only (lot-4 Q62). PUSH also needs ≥ 1 live push token of the user. |
| Send | Every 30 s the job takes due `SCHEDULED` campaigns (row lock, skip locked). It sets `SENDING` and `started_at` and snapshots the audience into `fan_messages`, one row per user (`on conflict do nothing`), in one transaction. The outbox drains at most `notifications.send-rate-per-second` (default 20) across kinds, transactional first. When no row of the campaign is `PENDING`, the campaign becomes `SENT` and `finished_at` is set. A user who has opted out or left by send time is skipped: `FAILED` `OPTED_OUT`. |
| Content | PUSH: `title` and `body` as typed; tapping opens `/events/{event_id}`, or the home screen. WHATSAPP: template `club_news` (MEMBERS) or `event_info` (EVENT_HOLDERS) with `title` and `body` as parameters, in the recipient's locale (Q63). Nothing about the recipient goes in the payload. |
| Inbox | PUSH campaign rows sent to a user are listed by `GET /fan/me/inbox`. |

**`PushSender` port and messenger extension (plan note, in `notifications`)**

```java
interface PushSender {                                   // adapters: log (dev/staging), fcm (BE-36); none under prod until Q61
  void send(Push push);                                  // throws Unregistered (delete the token) or Failed(code)
  record Push(String token, String title, String body, String path) {}   // path = app route, e.g. /orders/{id}
}
// TicketMessenger gains: void send(Template t);  record Template(String phoneE164, String locale, String name, List<String> params)
// LogTicketMessenger logs the template name and the recipient user id only.
```

### 4.4 `docs/06-glossary.md`

New terms:

- transfer · `transfert` · تحويل · "the ticket is cancelled and a new one is issued to the recipient (new QR)"
- re-issue · `réémission` · إعادة الإصدار · "a new ticket for the same holder after a lost phone"
- member · `membre` · منخرط · "a fan who joined the organizer's club (free)"
- member number · `n° de membre` · رقم المنخرط
- tier · `niveau` · المستوى · "from events attended"
- split pay · `paiement partagé` · الدفع المشترك · "each friend pays their own place"
- campaign · `campagne` · حملة
- gate load · `affluence` · الازدحام · "Fluide / Modérée / Chargée"
- opt-in · `consentement` · الموافقة

Scan result (no new code): the fan app's entry state is "Tu es dedans" / "راك داخل" / "You're in".

### 4.5 `docs/02`, `docs/01`, `docs/00`

| Where | Change |
| --- | --- |
| 02 §Threat model | Add a row: "Ticket transferred after a scanner's last delta \| Transfers and re-issues close at `refund_cutoff_at` − 30 min (T0 − 90), leaving deltas 30 min of signal \| A scanner offline before T0 − 90 still admits the old QR and refuses the new one (INCONNU)". |
| 02 §Data model | `memberships`, `push_tokens`, `campaigns`, `fan_messages`, `ticket_transfers` (new shape); gates `directions`, `latitude`, `longitude`; orders `group_order_id` |
| 02:228 (Fan app design direction) | "Sharing built in: transfer a ticket to a friend by phone number **and name**; the recipient gets a new ticket (new K_t), and the sender's QR dies with the next manifest version (transfers close at T0 − 90)." |
| 01:42 Kafka row | "Lot 4: fan messages and campaigns use the `fan_messages` DB outbox; the fan entry state is polled through `/fan/gates/{id}/load`. Kafka arrives only if the Lot 6 load test shows an async boundary." |
| 01 §Components, fan app | "Discover, buy, hold tickets, rotating QR offline, transfer, split pay, membership, matchday companion, push" |
| 00:54 | Unchanged (Lot 4 scope stands). Founder, if Q57 holds: the Lot 5 row gains "paid membership". |

### 4.6 `design/DESIGN.md`

**Principle 6, append after "a haptic tick marks each QR rotation":**
"Lot 4 adds, each tied to a meaning: a transferred ticket slides out of the wallet (the reverse of the purchase slide-in); the ticket card flips to its gate side and back (`motion.ticketFlip`); figures roll when they change (events attended, a group's paid count, the member number on its first reveal); a member's paid check settles in once in the group view; a newly attended event's dot fills once; the hold-to-confirm fill on a transfer counts as a progress indicator."

**Screen inventory, append:**

| # | Screen | Mockup | Route | Entry |
| --- | --- | --- | --- | --- |
| 7 | Club | — | `/club` (tab) | Bottom nav |
| 8 | Transférer | — | sheet on `/tickets/:id` | Screen 5 action |
| 9 | Groupe | — | `/orders/:id` (split variant) | After a split order; push / link for members |
| 10 | Notifications | — | `/inbox` | Bell on screen 1 |

**New "Lot 4 adjustments"** (these override the screen specs above for Lot 4):

| Where | Change |
| --- | --- |
| Bottom nav | Accueil · Billets · Club · Profil |
| Screen 1 | Bell → screen 10, with an 8 px accent dot when a campaign is newer than the last visit (stored on the device). The amber strip also covers member orders to pay ("Ta place t'attend · avant {time}"). Members get a slim strip under "Mon billet": "N° 000042 · Fidèle · 7 matchs" → screen 7. |
| Screen 4 / W3 | Each friend row with a phone gets a switch "Paie sa place" (default off). The summary splits "Tu paies {n} place(s) · {total}" and "{m} place(s) payée(s) par tes amis". Caption: "Tes amis ont 2 h après ton paiement". 422 `TOO_LATE` → the switch is disabled with "Trop tard pour partager". |
| Screen 5 front | Actions: Transférer · WhatsApp (Wallet hidden, Q67). Gate row: "Porte 4" + a load chip (Fluide · Modérée · Chargée; hidden when `UNKNOWN`) + an "Itinéraire" chip. When this gate is `HIGH` and another gate of the zone is `LOW`: "Porte 3 plus fluide en ce moment". No walking time and no wait minutes (Q68). |
| Screen 5 gate side | Tapping the PORTE fact or "Itinéraire" flips the card (`motion.ticketFlip`, 420 ms). This side shows: the gate code in display 72, the gate name, `directions`, and "Ouvrir dans Plans" when coordinates exist (Android `geo:{lat},{lng}?q={lat},{lng}({gate name})`, iOS `https://maps.apple.com/?ll={lat},{lng}&q={gate name}`). It also lists the zone's other gates with their chips and "Retourner le billet". The QR is not on this side; flipping back restores it. Works offline from the cached ticket (chips hidden offline). |
| Screen 5 entry | When `entries` lists this ticket, the card flips once to "Tu es dedans" / "راك داخل": band `success`, gate and time in the venue zone, heavy haptic. Re-opening shows the entered state without the animation. |
| 8 Transférer | Bottom sheet: WhatsApp number + full name (§Holder names). Info: "Ton QR ne marchera plus. {name} reçoit un nouveau billet." Hold-to-confirm CTA (54 px, 800 ms fill, haptic at the end), then the ticket slides out of the wallet. Billets keeps a muted row "Transféré à Karim B. · {date}". After `transfer_until`: disabled, with "Transferts fermés 1 h 30 avant le début". Profil → "Téléphone perdu ?" → confirm listing the upcoming tickets → re-issue each, after "Se déconnecter partout". |
| 9 Groupe, initiator | "Ta bande · {event}". Big figure "{paid}/{total} payées" (rolls). Member row cards: initials avatar (40 px circle, surface2, display 16), holder label, amount, chip (À payer · avant {time} / Payé / Expiré). The paid check settles in once per member. "Relancer sur WhatsApp" opens `https://wa.me/?text=` with the copy and `pay_url`; there is also "Copier le lien". Polls the order every 10 s while visible. |
| 9 Groupe, member (app, and W4 on the shop) | Event header (as screen 2) + "{initiator_label} t'a gardé une place" + amount + countdown to `reserved_until` + the screen 4 method rows + CTA. Expired: "La place a été libérée". |
| 7 Club | Member card (wow table below). Not a member: an outlined card + "Rejoindre le club · gratuit" → a sheet with two switches ("Notifications du club", "Messages WhatsApp du club"; default off, one line each on what they send) → join, then the card reveals. Below: "Ta saison" 12-dot row, "{n} matchs au stade" (or "événements" when `event_type` ≠ FOOTBALL_MATCH), tier progress "Encore 2 pour Pilier", "Derniers" row cards (attended marked). Settings: the two switches and "Quitter le club" (confirm). |
| 10 Notifications | Campaign rows (title, body, relative time). Tapping a row with `event_id` → screen 2. Empty state: "Rien pour l'instant". |
| Profil | Langue (Français · العربية · English → PATCH `/fan/me`), Notifications (opt-ins + system permission state), "Téléphone perdu ?". Push permission is asked after the first purchase or a join with push on, never at launch. |
| AR / RTL (fan app and shop) | `ar` = Darija in Arabic script (Q64). Cairo 700/800 for display text in AR, IBM Plex Sans Arabic for body text. Barlow Condensed stays for digits, codes and ticket numbers. Western digits 0–9 in every locale (format with `ar-MA`). Layout mirrored: leading icons and back chevrons flip, and progress and rotation bars fill from the right. QR, `JMH 4F7K 29`, phone numbers, ticket numbers and prices stay LTR inside bidi isolates. **Arabic text gets letter-spacing 0 and no uppercase** (tracking breaks joining), so `label`-style AR text gets its emphasis from weight 700. |
| W2 / all shop pages | Header language switch "FR · ع · EN" (sets the cookie; `?lang=` also works). `<html lang dir>` per locale. Logical CSS only (`ms-`/`me-`/`ps-`/`pe-`/`start`/`end`). |
| W4 | Member variant as screen 9 member. Initiator `PAID` view: a group progress block under the tickets. |
| W5 | "Transférer" dialog (same fields; a confirm dialog instead of hold-to-confirm) and "Transféré à …" rows. No re-issue on the web (it holds no K_t). |
| B9 Fans · CRM `/crm` (Lot 4: tabs Membres · Campagnes) | **Membres:** KPI cards (members, +7 days, push opt-ins with "joignables", WhatsApp opt-ins) and a tier table; counts only. **Campagnes:** table (title, channel, audience, status badge, planned/sent at in the venue zone, recipients, sent, failed) + "Nouvelle campagne" sheet: channel, audience (Membres opt-in · Détenteurs d'un billet → event select; for the latter the note "Infos pratiques uniquement"), title (60) and body (500) with counters and `dir="auto"`, a phone push preview, the audience estimate after save, "Envoyer maintenant" / "Programmer" (date and time in the venue zone), confirm "Envoyer à {n} fans ?", refetch every 10 s while `SENDING`, "Annuler" while `SCHEDULED`. FINANCE is read-only. |
| B10 Lieux | Gate rows + "Accès" (directions), latitude, longitude, and a "Voir sur la carte" link (new tab). |
| B12 Billets | Columns "Transféré vers" / "Transféré depuis" (ticket numbers, clickable search). |
| Sidebar | Fans & finance: "Fans / CRM" enabled. |

**Screens that carry the founder's "wow" bar** (inside principles 3, 6 and 7 and `BRAND.md`):

| Screen | What makes it special |
| --- | --- |
| 5 · Mon billet, matchday | The ticket behaves like a physical object with two faces. A flip shows the gate side: the gate code in display 72, the directions, one tap to Maps, live load chips. A second flip, "Tu es dedans" / "راك داخل", turns the band `success` with a heavy haptic the moment the scan syncs: the stadium tells the fan they made it. Brightness max, nothing moves over the QR, everything works offline except the chips. |
| 7 · Club, Carte membre | A dark `surface2` card, radius 28, with a Jomhor accent band carrying the club's crest and name (the club is content, never a colour; like the ticket band, so the ticket stays the only light surface). "MEMBRE N° 000042" in Barlow 44 rolls once on first reveal; "depuis nov. 2026" and the tier name sit in accent. Under it, **"Ta saison" in twelve dots**: the club's last 11 closed events as white dots (attended) or outlined dots (missed), and the 12th dot in amber for the next event. This is the brand's idea (the amber twelfth is the supporter who completes the team) applied to the fan's own season. It is a straight row, never the J mark, so the mark rules stay intact. A new attendance fills its dot once. Joining: the card rises from the CTA and settles (≤ 420 ms) with a success haptic. |
| 8 · Transférer | Hold-to-confirm with a fill (an irreversible action deserves a deliberate gesture). Then the ticket slides out of the wallet, the reverse of the purchase slide-in. The recipient gets a push that opens screen 5 with the existing fly-in. Giving a ticket feels like handing it over. |
| 9 · Groupe | "2/4 payées" in display type rolls up as friends pay. Each friend's check settles in once, and initials avatars make the group feel like people, not rows. "Relancer sur WhatsApp" is one tap with the copy prefilled. The member side opens on the event hero with one line: "Yazid A. t'a gardé une place". |
| 1 · Accueil between matches | The countdown digits roll, the member strip shows tier and matchs at a glance, and the bell has a live inbox: reasons to open the app on a Tuesday. |
| Arabic everywhere | True RTL done right is the differentiator against incumbents: Cairo display headings, Darija voice ("راك داخل"), mirrored layouts, codes and prices kept LTR in isolates, no broken letter-spacing. Every wow screen ships FR + AR goldens. |

Under reduced motion, every Lot 4 motion is off (instant states). There are no decorative loops, no parallax, and no gradients or shadows on the brand mark.

## 5. Tasks

### Wave 0: orchestrator and founder (0.5 d, before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/02`, `03`, `04`, `06`, `01`, `design/DESIGN.md` | §4 applied after the founder agrees |
| W0-2 | orchestrator | git | `feat/lot-4-fan-platform` from `main` after Lot 3 merges. No new CI job (no new package; new dependencies only) |
| W0-3 | founder | — | Firebase project + APNs key (Q61). Q2 provider + the four new templates (Q63). Name the Darija reviewer (Q64). Club question on Arabic holder names (Q66). CNDP marketing declaration (Q62). Wow review slot on day 11 (Q70) |

### Tasks

`backend` is serial and on the critical path. Rows marked ∥ run alongside the backend task of their wave, in disjoint folders. A client task marked *(contract-first)* starts on the §4 shapes with fixtures and is done only after one run against the real endpoint. Each client task ships its FR/AR/EN strings (no hard-coded strings or colours) and, for the fan app, FR + AR goldens of the screens it touches.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-34 | backend | `backend/…/common/HolderNames.java`, `…/tickets/adapters/pdf` (+ an OFL Arabic font in resources), `build.gradle` (`com.ibm.icu:icu4j`, latest) | §Holder names Arabic rule; PDF shaping (ICU4J `ArabicShaping` + `Bidi`) with an embedded font (IBM Plex Sans Arabic or Noto Sans Arabic, OFL) | `HolderNamesTest`: `يزيد عقل` → label `يزيد ع.`, initials `ي. ع.`; harakat and tatweel stripped; presentation forms NFKC'd; `Yazid عقل` refused; Arabic-Indic digits refused; all Latin cases still green. `FanOrderApiTest`: Arabic holder accepted; manifest `T` row carries `ي. ع.`. `TicketPdfTest`: an Arabic title + label render with no `?`, the Arabic font is embedded, and the extracted text NFKC-normalised contains the label's letters | W0 | 1.5 |
| 1 ∥ | FL-17 | flutter | `apps/fan/lib/l10n`, `lib/theme`, `lib/ui`, `assets/fonts` (Cairo, IBM Plex Sans Arabic, OFL), `features/profile` (language), `features/checkout/holder_name.dart`, `apps/scanner/test` (one golden) | AR/RTL foundation: full `app_ar.arb` (Darija) and `app_en.arb`; Arabic font fallback per locale; `ar-MA` formatting; mirrored layouts across Lot 1–3 screens; AR text style rule (spacing 0, no uppercase); language switch → PATCH `/fan/me`; Arabic holder names | Tests: every `app_fr.arb` key exists in `ar` and `en`; AR goldens of screens 1, 4, 5 and the cash order (RTL, chevrons flipped, `JMH 4F7K 29`, prices and ticket numbers LTR, Western digits); AR `label` style has letterSpacing 0; the language switch persists and PATCHes; `holder_name_test` Arabic cases mirror BE-34; scanner golden of a result with `ي. ع.` initials | BE-34 *(contract-first)* | 3 |
| 1 ∥ | WS-7 | web-shop | `web/shop/lib/i18n`, `app/layout.tsx`, `app/(public)/**` (switcher, logical classes), `lib/holder-name.ts`, fonts via `next/font` | AR/EN dictionaries, locale from cookie / `Accept-Language` / `?lang=`, `<html lang dir>`, Cairo + IBM Plex Sans Arabic, logical CSS, Arabic holder names | Tests: every FR key in `ar` and `en`; locale resolution order (`?lang=`, which also sets the cookie, > cookie > Accept-Language > fr); `dir="rtl"` for ar; a grep test finds no `ml-`/`mr-`/`pl-`/`pr-`/`left-`/`right-` utilities in `app/`; `Intl` `ar-MA` gives Western digits; holder-name Arabic cases; W2 and W4 render in ar | BE-34 *(contract-first)* | 3.5 |
| 1 ∥ | BO-17 | backoffice | `web/backoffice/src/features/venues` | B10 gate fields (directions, latitude, longitude, map link) | Tests: zod both-or-neither and 6-decimal rule; PATCH body; `""` clears directions | BE-32 *(contract-first)* | 1 |
| 2 | BE-28 | backend | `backend/…/tickets` (transfer, re-issue, lookup chain, `/fan/tickets` fields), `…/notifications` (delivery for the new ticket), V16 | 04 transfer + re-issue rows, `transfer_until`, `transferred`, search `transferred_to/from` | `TicketTransferApiTest`: transfer → old `CANCELLED`, new `VALID` with the recipient as holder and initials, same order/zone/category; `ticket_transfers` row; one delivery for the new ticket; the next manifest build has the old row `CANCELLED` and the new `VALID`; new `k_t` ≠ old; sender list shows `transferred.to_label`; recipient lists it; buyer's order GET doesn't list it; own phone → 422 `SELF`; at `transfer_until` → 409 `TRANSFER_CLOSED`; `BLOCKED` → 409 `TICKET_NOT_VALID`; another holder's ticket → 404; two parallel transfers of one ticket → one 201, one 409; unknown phone → user created; re-issue → new id, same holder, no delivery, old row not listed; `sold`, `/sales` and `/live` `sold` unchanged; search shows both ticket numbers; `MigrationsTest` V16, allowlist 4 | BE-34 | 2 |
| 2 ∥ | FL-18 | flutter | `apps/fan/lib/features/tickets` (transfer sheet, transferred rows, re-issue), `features/profile` ("Téléphone perdu ?") | Screen 8 + re-issue | Tests: body `{to_phone, name}`; own phone → field error; after `transfer_until` the action is disabled with the copy; on 201 the slide-out runs (none under reduced motion), the cache refetches with keys, the old K_t is gone from `TicketCache`, and the list shows "Transféré à Karim B."; re-issue refetches; hold-to-confirm needs the full hold; goldens fr + ar | BE-28 *(contract-first)* | 2 |
| 2 ∥ | WS-9 | web-shop | `web/shop/app/(public)/me/tickets`, `app/api/tickets/[id]/transfer/route.ts` | W5 transfer dialog + transferred rows | Tests: the handler forwards `{to_phone, name}` with the cookie and returns no token; `TRANSFER_CLOSED` / `SELF` copy; the row shows "Transféré à …" | BE-28 *(contract-first)* | 1.5 |
| 2 ∥ | BO-19 | backoffice | `web/backoffice/src/features/tickets`, `…/features/pos` (holder-name rule) | B12 transfer columns; Arabic holder names in the POS form | Tests: columns render and a click searches that number; POS zod accepts `يزيد عقل` and refuses mixed scripts | BE-28 *(contract-first)* | 0.5 |
| 3 | BE-32 | backend | `backend/…/catalog` (gate fields), `…/gate` (load + entries, 15 s memo), `…/tickets` (`gates[]` on `/fan/tickets`), V18 | 04 gate rows, `/fan/gates/{id}/load` | `GateDirectionsApiTest`: PATCH directions + coordinates → GET; one coordinate → 422; 7 decimals → 422; the manifest builds no new version after a directions change; `/fan/tickets` has `gates[]` for the zone. `GateLoadApiTest`: non-holder → 404; levels from fixtures (2 devices online, 30 admitted scans in 5 min → LOW; 230 → HIGH); silent devices → UNKNOWN; `entries` = the caller's canonical scans only (another holder's absent; a DUPLICATE_LATE doesn't move `entered_at`); two calls within 15 s share the gate figures (fixed clock) while entries are fresh; `no-store`; `MigrationsTest`: `v1ToV18Apply`; `gates_location` refuses one coordinate, `gates_latitude_check` refuses 91; platform allowlist unchanged (4). PATCH with both `latitude: null` and `longitude: null` clears the location, one null alone → 422 on `latitude`. Multi-club: a fan holding tickets of clubs A and B gets, for A's event, A's gates and A's entries only; asking for A's event while holding only a B ticket → 404; on `/fan/tickets`, two clubs that both have a `P1` gate each list only their own venue's gate | — (BE-28 done) | 2 |
| 3 ∥ | FL-19 | flutter | `apps/fan/lib/features/tickets` (gate side, load row, entry flip, poller), `pubspec` (`url_launcher`, latest) | Screen 5 gate side, load chips, "Tu es dedans" | Tests: flip 420 ms and back (instant under reduced motion); directions shown offline from the cache; the Maps link uses the platform URL and is absent without coordinates; chips hidden for UNKNOWN; the "plus fluide" line only for HIGH vs LOW; with a fake clock, polling only inside [T0 − 3 h, T0 + 1 h], only while visible, 60 s ± 10 s, stopping after entry; entry → one flip + haptic, re-open → static entered state; offline → no error and last state kept; goldens fr + ar of both sides | BE-32 *(contract-first)* | 3 |
| 4 | BE-31 | backend | `backend/…/crm` (new module per `01`: memberships, loyalty read), V19 | 04 membership rows; tiers from settings | `MembershipApiTest`: join → 1, second user → 2; parallel joins → distinct numbers; leave + rejoin → same number; opt-in change audited with booleans only; `events_attended` counts OK and OVERRIDE canonical scans; a second admitted scan of the same ticket and two tickets of one event count once; refused results count zero; a transferred-away ticket's later scan counts for the recipient; tiers from settings, defaults when absent, `name` in the caller's locale with fr fallback; `last_events` = 11 latest CLOSED, oldest first; non-member gets loyalty; DELETE idempotent; organizers isolated; `MigrationsTest` V19; a fan who is a member of two clubs has an independent number in each (1 in A, 1 in B) | BE-32 | 2 |
| 4 ∥ | FL-20 | flutter | `apps/fan/lib/features/club` (new), `app/shell.dart`, `app/router.dart`, `features/events/home_screen.dart` (member strip), `app/session.dart` (logout wipe), `test/club_test.dart`, `test/goldens_test.dart`, `test/shell_test.dart`, `l10n/*.arb` (`navClub`) | Screen 7, the Club tab: one member card per followed club, swiped in follow order; no follows → "Choisis tes clubs" → `/clubs`; join sheet; opt-in settings | Tests: 4 tabs (Accueil · Billets · Club · Profil; a 4th `StatefulShellBranch` at index 2); follows A, B → two cards in follow order, one `GET /fan/me/membership?organizer=` per club; no follows → "Choisis tes clubs" → `/clubs`; each card shows its club's crest + name on a Jomhor accent band (never a club colour); non-member → join CTA, switches default off, PUT body; card number padded to 6 and rolls once; 12 dots = 11 `last_events` + the next event in accent; tier progress copy; "matchs" vs "événements" by `event_type`; leave → DELETE; the Accueil member strip shows for the first followed club (follow order) where the fan is a member; none when not a member anywhere; goldens fr + ar (card, non-member, join) | BE-31 *(contract-first)* | 3 |
| 4 ∥ | BO-18 | backoffice | `web/backoffice/src/features/crm` (new), `src/app` (route, sidebar), `src/i18n/fr.ts` | B9 Membres + Campagnes on fixtures | Tests: composer zod (title 60, body 500, event required for holders); `dir="auto"`; schedule converts the venue-zone wall time to RFC 3339; the confirm shows the estimate; refetch 10 s only while `SENDING`; cancel only while `SCHEDULED`; FINANCE sees no write buttons; `NOT_CONFIGURED` → field error; query keys include the organizer; `crm.test.tsx` | BE-33 *(contract-first)* | 4 |
| 5 | BE-29 | backend | `backend/…/sales` (split in `place`, expiry rule, settle extension, group reads), V20 | §Split pay; order and list `group` fields | `SplitPayApiTest`: 1 own + 2 `pays` → buyer total = own place, two member orders `RESERVED` with `group_order_id` and the friends as users, quota −3; buyer expires unpaid → members expire in the same pass, `sold` back by 3 exactly once; buyer pays → members' `reserved_until` = paid_at + 2 h (capped at the cutoff); member pays CARD → `PAID`, ticket to the member, none to the buyer; member deadline → `EXPIRED`, one place back; `pays` without phone or with the buyer's → 422 `PHONE_REQUIRED`; all `pays` → 422 `NO_BUYER_PLACE`; member at the limit → 409 with `details.field`; 1 h 59 before the cutoff → 422 `TOO_LATE`; buyer `AWAITING_CASH` → members kept past 8 min; buyer card success after 10 min (redirected) keeps the members; GET shapes have labels and `pay_url` only (fixture phone and full name searched in the JSON); `/fan/orders` puts the member order first; a member pays before the buyer → `PAID`; `MigrationsTest` V20 | BE-31 | 3 |
| 5 ∥ | FL-21 | flutter | `apps/fan/lib/features/checkout` (switch, group screen, member screen) | Screens 4 and 9 for split pay | Tests: the switch needs a phone; POST body `pays: true`; summary split amounts in integer centimes; initiator screen figures and chips from fixtures; the wa.me URL contains `pay_url` and no phone; member screen pays through the existing method rows; poll every 10 s stops on terminal states; each paid check animates once; goldens fr + ar | BE-29 *(contract-first)* | 3 |
| 5 ∥ | WS-8 | web-shop | `web/shop/app/(public)/orders` (W3 switch, W4 member/initiator variants), `lib/order-client.ts`, `lib/checkout.ts` | Split pay on the shop | Tests: `split-pay.test.tsx`: the W3 switch posts `pays`; the member W4 shows the initiator label, amount and countdown and pays through the existing handler; the initiator PAID view lists members with chips; wa.me link; no phone in the HTML | BE-29 *(contract-first)* | 2.5 |
| 6 | BE-30 | backend | `backend/…/notifications` (`fan_messages` outbox + job, `PushSender` port + log adapter, `TicketMessenger.send(Template)`, message bundles fr/ar/en, `PUT /fan/me/push-token`), `…/tenancy` (logout deletes tokens), hooks in `…/tickets` (TICKET_RECEIVED), `…/sales` (SPLIT_INVITE, ORDER_PAID), `…/payments` (PAY_CODE), V21 | §Notifications; push-token row | `FanMessagesTest`: token upsert moves between users and sessions; logout → the token is deleted and never used; a transfer queues TICKET_RECEIVED once; buyer PAID queues SPLIT_INVITE WhatsApp + push per member once (a replayed callback adds none); the first cash code queues PAY_CODE once (a second `/pay` adds none); AWAITING_CASH → PAID queues ORDER_PAID; backoff and FAILED after the max attempts; no token → `NO_DEVICE`; unregistered → token deleted; no push provider → `NOT_CONFIGURED`; captured payloads contain no phone, no `holder_name`, no `k_t` (fixture values searched); `locale=ar` → Arabic copy; prod profile: the log push adapter is refused; `MigrationsTest`: `v1ToV21Apply`; platform allowlist 4 → 5 (`push_tokens`); a token registered once receives a TICKET_RECEIVED of club A and an ORDER_PAID of club B (no per-club filter); a fan who follows nothing still gets transactional pushes | BE-29 | 2.5 |
| 6 ∥ | FL-22 | flutter | `apps/fan/lib/core/push` (new), `features/inbox` (new), `features/events/home_screen.dart` (bell in `_TopBar`: `BrandBar.trailing` becomes bell + avatar, at the end side), `pubspec` (`firebase_core`, `firebase_messaging`, latest), platform config behind `PUSH_ENABLED` | Push registration, permission timing, deep links, foreground banner, inbox | Tests (fake messaging): no Firebase init without `PUSH_ENABLED`; token PUT after login and on refresh; the permission is not asked at launch, only after the first purchase or a join with push on; tap routes `/tickets/:id`, `/orders/:id`, `/events/:id`; a foreground message shows an in-app banner; inbox list + unread dot from the last-seen time; logout stops re-sending; the push-token PUT body is exactly `{token, platform}`, sent once per token whatever the follows; inbox rows from every club, each starting with the club line from `organizer` (crest 20 + name); the unread dot uses one last-visit time on the phone, not one per club | BE-30 *(contract-first)*; inbox BE-33 | 2 |
| 7 | BE-33 | backend | `backend/…/crm` (campaigns, job, members summary, inbox), V22 | §Campaigns, campaign rows, `/org/members/summary`, `/fan/me/inbox` | `CampaignApiTest`: create DRAFT, PATCH, EVENT_HOLDERS without event → 422; send now → the job snapshots → recipients = members with the push opt-in and a live token of the user (fixtures: opted in with a token, opted in without, not opted in → 1); WhatsApp MEMBERS uses the opt-in only; EVENT_HOLDERS = VALID holders (a transferred-away sender excluded, the recipient included), members or not; the job running twice adds no rows; leaving after the snapshot → `OPTED_OUT`; `SENT` when drained; cancel SCHEDULED → CANCELLED, cancel SENT → 409; PUSH without a provider → 422 `NOT_CONFIGURED`; FINANCE GET 200 / POST 403; CASHIER 403; org B campaign 404; summary counts; inbox: the caller's PUSH campaigns of every organizer, newest first, at most 30, each with `organizer:{id, slug, name, crest_url}` (key-set assertion; unlisted club → slug null); another user's rows and WhatsApp campaigns absent; body of 501 → 422; Arabic body round trip; `MigrationsTest` V22; update the BE-30 tests that insert CAMPAIGN rows without `campaign_id` (`PushSenderProfileTest`, `FanMessagesTest` rate-cap case) and wire CAMPAIGN rendering in `FanMessageService` (BE-30 backs them off until then) | BE-30 | 2.5 |
| 8 | BE-35 | backend | `backend/src/test/…/Lot4FanFlowTest`, `backend/http/lot4-fan.http` | Exit test | **`Lot4FanFlowTest`:** Arabic holder bought → manifest initials. Split of 3: buyer card → invites queued; member A card, member B cash code collected at a till → group 3/3. Transfer A → C: manifest delta has A `CANCELLED` and C `VALID`; C's delivery `SENT` (log). B re-issues. Scans for the buyer and C → `/load` entries for each caller only. Event closed → buyer's membership loyalty 1 event, `last_events` attended. A push MEMBERS campaign reaches the opted-in buyer (captured) and lists in the inbox; WhatsApp EVENT_HOLDERS → one row per VALID holder. `/sales` and `/live` `sold` unchanged by the transfer; the inbox row carries the organizer block | BE-33 | 1.5 |
| blocked | BE-36 | backend | `backend/…/notifications/adapters/fcm` | FCM HTTP v1 adapter (service-account auth); `UNREGISTERED` → token deleted | Against a stub HTTP server: request shape, auth header, unregistered and quota errors mapped; prod starts with it configured | **Q61 Firebase project** | 1.5 |
| blocked | BE-15 | backend | messaging adapters | As in Lot 1, plus the four templates | As in Lot 1 | **Q2, Q63** | 2 |
| exit | — | orchestrator + founder | — | Demo per §1 on staging with a Lot 2 scanner; Darija review sign-off | `Lot4FanFlowTest` + client checks green; manual demo | all non-blocked | — |

With BE-36 (FL-22 follow-ups, wave 4 review): an Android default notification channel (`default_notification_channel_id`, channel created at startup with a localized name, so pushes don't land in FCM's English "Miscellaneous") and the iOS `Runner.entitlements` `aps-environment` (needs the Apple account, Q25); then a manual check on a real device (background, killed-app tap, APNs).

`reviewer` runs after every builder task. Day 11: founder wow review on a staging build (screens 5 and 7, FR and AR).

### Lanes and critical path (working days, before review loops)

| Agent | Sequence | Ends |
| --- | --- | --- |
| backend (critical) | BE-34 0–1.5 → BE-28 1.5–3.5 → BE-32 3.5–5.5 → BE-31 5.5–7.5 → BE-29 7.5–10.5 → BE-30 10.5–13 → BE-33 13–15.5 → BE-35 15.5–17 | d17 |
| flutter | FL-17 0–3 → FL-18 3.5–5.5 → FL-19 5.5–8.5 → FL-20 8.5–11.5 (wow review d11) → FL-21 11.5–14.5 → FL-22 14.5–16.5 | d16.5 |
| web-shop | WS-7 0–3.5 → WS-9 3.5–5 → WS-8 10.5–13 | d13 |
| backoffice | BO-17 0–1 (real at d5.5) → BO-19 3.5–4 → BO-18 6–10 (real at d15.5) → smoke 15.5–16 | d16 |

### Budget check (4 weeks = 20 working days)

| Agent | Sum | ×1.1 review | Fits? |
| --- | --- | --- | --- |
| backend | 17 | 18.7 | Yes, about 1.3 d slack |
| flutter | 16 | 17.6 | Yes, about 2.4 d slack (absorbs the wow-review changes) |
| web-shop | 7 | 7.7 | Yes |
| backoffice | 5.5 | 6.05 | Yes |

Verdict: 4 weeks holds because paid membership moves to Lot 5 (Q57), Wallet passes leave V1 (Q67), and the real providers (BE-36, BE-15) are not counted.

If late, cut in this order:

1. the inbox (the bell stays hidden; campaigns still push);
2. re-issue (lost phone = "Se déconnecter partout" + support);
3. the gate load chips (keep the gate side, directions and "Tu es dedans");
4. WhatsApp campaigns (keep push);
5. split pay on the web shop (app only; members can still pay their order on W4 as a normal order);
6. the B9 Membres tab.

Never cut:

- the transfer freeze and the new ticket id on every transfer or re-issue;
- old and new tickets both in the next manifest;
- split holds released exactly once;
- opt-in checks at snapshot and at send;
- no PII in push, WhatsApp or QR payloads;
- tenant scoping on every new table;
- centimes end to end;
- nothing on the scanner path;
- FR + AR goldens on the wow screens.

## 6. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Transfer integrity | One transaction with the ticket row locked first: old → `CANCELLED` (status only, no other column touched), new row via `TicketService.issue` in the same order, zone and category, so a new id and a new K_t. `ticket_transfers` unique on both ids; two parallel transfers → exactly one. Freeze at `transfer_until` on the server with the DB clock. The quota and `sold` are untouched. The buyer never gets the new ticket's K_t or PDF. |
| Gate safety | The manifest gets both rows in the next version. Gate directions and coordinates are not in the payload, so no new version is built. No scanner code change except the Arabic-initials golden. `/load` is off the scanner path, computed from `/live` queries, memoised 15 s, and never blocks ingestion (no lock on the scans advisory lock). |
| Split holds | Member orders are created in the same transaction and quota reservation as the buyer's. The expiry skip rule is keyed on the buyer's status, and places are released exactly once (parallel expiry passes). The extension happens only in the settle transaction. Each member's limit is checked. Labels only in group reads; `pay_url` comes from server config, never from the client. |
| Consent and spam | Marketing goes only to members with the channel's opt-in, checked at snapshot and again at send. EVENT_HOLDERS is limited to VALID holders of that event. Snapshots are idempotent (unique index). Opt-in changes are audited with booleans only. Permission is never asked at launch. |
| PII | Push payloads: title, body, path. WhatsApp templates: labels, event, amount, code, link; no phone or full name in params, logs, `last_error` or audit. `/org/members/summary` returns counts only. Tokens are tied to sessions and deleted on logout. |
| Tenancy | `memberships`, `campaigns`, `fan_messages`, `ticket_transfers` filter `organizer_id`; `push_tokens` is a platform table (multi-club Q73). Org B campaign → 404. An org B campaign reaches only org B's audience (org B members with the opt-in, or holders of an org B event). |
| i18n / RTL | Every new string in fr/ar/en (app ARB, shop dicts, backend bundles); no literal strings (existing lint tests). Arabic text has letter-spacing 0 and no uppercase. Codes, prices, phone numbers and ticket numbers are in LTR isolates. Western digits everywhere. The holder-name rule is identical in Java, Dart and the two TS copies (shared cases). |
| Motion bar | Only the motions listed in principle 6 (with the Lot 4 append); everything off under reduced motion; nothing animates over the QR; entrances ≤ 420 ms; no decorative loops or parallax; the 12-dot row is never drawn as the J mark. |
| Money | `total_mad` split between the buyer's and member orders adds up to the request in integer centimes; no float in Java, Dart or TS. Coordinates are `numeric`/`BigDecimal`, never a money type and never floats in SQL. |
| Contract | Each new or changed 04 row has a contract test; error codes and `details.reason` exactly as 04; V16–V22 names = 03; the tenant allowlist is 5 once `push_tokens` exists (multi-club Q73). |

## 7. Carried forward

| Item | Lot | Note |
| --- | --- | --- |
| Arabic-script holder names + AR PDF (lot-1 Q16) | 4 | BE-34, Q66 |
| Lost-phone re-issue (lot-1 Q13) | 4 | BE-28 re-issue |
| "Tu es dedans" (lot-2 Q42) | 4 | `/load` entries, no Kafka (Q69) |
| Automatic pay-code message (lot-3 Q53) | 4 (queued) / BE-15 (sent) | PAY_CODE kind |
| Kafka | 6, only if the load test needs it | Q69 |
| Paid membership | 5 | Q57, with passes (non-event orders) |
| Members-only prices, presale by tier | 5 | Fair on-sale |
| CRM export (`/org/crm/export.csv`) | 5 | Opt-ins from `memberships` |
| Apple / Google Wallet | post-V1 | Q67 |
| App Links / Universal Links for `pay_url` | 6 | The shop link works for everyone meanwhile |
| Per-event transfer toggle (derbies) | when the club asks | Q59 |
| Campaign frequency cap, per-locale bodies, test send | when asked | Q62 |
| Huawei push (HMS) | when measured | Q61 risk |
| Stadium map on screen 3 | when the club gives an SVG | lot-1 Q22 |
| `/load` under the derby load test | 6 | 45,000 fans; the 15 s memo is per instance (`ponytail:` note), shared cache if needed |
| Deploy env added in Lot 4 | Q24 | `TICKETS_TRANSFER_CLOSE_BEFORE_CUTOFF` (`30m`), `SALES_SPLIT_HOLD` (`2h`), `NOTIFICATIONS_PUSH_PROVIDER` (staging `log`; prod unset until BE-36 → PUSH off), `NOTIFICATIONS_SEND_RATE_PER_SECOND` (`20`), `FCM_SERVICE_ACCOUNT` (BE-36); WhatsApp template ids with BE-15; fan app dart-define `PUSH_ENABLED` + Firebase config files (not in the repo) |

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| Matchday polling of `/load` by tens of thousands of fans | Window T0 − 3 h…T0 + 1 h, visible screen only, 60 s with jitter, stop after entry; 15 s memo of the gate part; entries = one indexed query per caller. Included in the Lot 6 derby load test |
| A transfer late on match day reaches a scanner too late | Close at T0 − 90 (Q59). The threat-model row states the residual |
| Transfers become the scalpers' delivery channel | No money flows in-app; every hop is audited (`ticket_transfers`, B12 chain); limits stay on buying. Official resale with a price cap is post-V1; a per-event toggle when the club asks |
| Split holds sit on derby inventory | Only after the buyer pays; 2 h, capped at sales close; refused within 2 h of close; per-member limits; `SALE_BUSY` still applies. Knob `sales.split.hold` |
| Push depends on Firebase/APNs; Huawei phones without GMS | Port + log adapter; the inbox and WhatsApp cover non-push phones; HMS later if measured |
| WhatsApp templates rejected or costly (marketing) | Utility templates for service messages; the founder budgets marketing; push first |
| Darija copy quality | Native review gate before live (Q64); goldens make every string visible in review |
| RTL regressions in Lot 1–3 screens | FL-17 AR goldens of screens 1, 4, 5 and the cash order; shop grep test for physical CSS utilities |
| ICU4J weight (~14 MB) and shaping edge cases in the PDF | Used only in the PDF adapter; test with joined, lam-alef and mixed Latin/Arabic titles; Latin path unchanged |
| Arabic initials on old PDA fonts | Scanner golden + a check on one real PDA at the dry run |
| Consent law (CNDP) | Opt-ins off by default, audited, revocable in-app; founder files the declaration (Q62) |
