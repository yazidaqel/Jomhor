# Multi-club · implementation plan

As of 2026-11-08 · owner: tech-lead · status: **agreed** (founder, 2026-11-08: Q73 per account, B.7 wording). Source of truth: the founder's approved design
(multi-club picker). The §3 defaults are taken unless the founder says otherwise. Q73 changes the tenant rule's
exempt list, so the founder confirms it explicitly. Migrations: V1–V16 exist (V16 = Lot 4 BE-28); this work
takes **V17**.

## 1. Lot

**Multi-club**: a cross-cutting piece outside Lots 4–6. It sits on Lot 0 (tenancy, `organizers`), Lot 1
(fan events, shop, wallet) and the merged part of Lot 4 (transfer, AR/RTL). Branch `feat/multi-club-picker`
from `main` (8bade83).

Outcome: one Jomhor fan app and one shop at `jomhor.ma` for every club. The club is content (crest + name),
never a skin (`design/BRAND.md` §Where the logo appears). Fans pick the clubs they follow; every event stays
one tap away (`Tout`).

Scope:

- remove `ORGANIZER_ID` from the fan app and the shop;
- remove the build-time club identity (`design/tokens.json` `club`, `JomhorClub`, `web/shop/lib/club.ts`);
- `organizers.slug` (V17): the club directory, its listing rule and slug rules;
- `organizer` block on fan events and tickets; `organizer` query param can repeat; absent = listed clubs;
- fan app: skippable club picker on first launch, the chip row on Accueil, crest + name on every event card
  and ticket, "Mes clubs" in Profil;
- shop: chip row on `/`, clubs strip, club page `/<slug>`, events at `/<slug>/events/<id-slug>`,
  permanent redirect from `/events/<id-slug>`, follows cookie, per-club OG image;
- dev seed: ATLAS FC gets `atlas-fc`; a second demo club **Olympique Tensift** (`olympique-tensift`);
- Lot 4 contract fix-ups (contract only): `push_tokens` and `/fan/me/inbox` for one app across clubs.

Not in scope:

- server-side follows (no `follows` table);
- a slug editor in the back-office (ops sets slugs by SQL, like `cash_points`);
- real crest assets (Q72);
- paging beyond 50 events in `Tout` (`ponytail:` note);
- any scanner behaviour change;
- back-office changes.

Exit:

| Level | Proof |
| --- | --- |
| Done | On the local stack (`scripts/dev.ps1 reset`, then `start`). **(1) Shop:** `localhost:3000/` shows `Tout` with ATLAS FC and Olympique Tensift events, each card with crest + name. Follow ATLAS FC from a card with JS off: `Tout` stays selected, the card shows "Suivi", and the `Mes clubs` and `ATLAS FC` chips appear; `Mes clubs` shows only ATLAS FC events. `/atlas-fc` lists only its events. `/events/<old-slug>` → 308 to `/atlas-fc/events/<id-slug>`. Buy with OTP `123456`; `/me/tickets` shows tickets from both clubs, each with crest + name. **(2) Fan APK:** first launch opens the picker. "Tout voir" lands on `Tout`. Follow Olympique Tensift from a card (`Tout` stays selected, the card shows "Suivi"): the chips read `Mes clubs · Olympique Tensift · Tout` (full name, cut at 140 px: the contract has no short name) and `Mes clubs` is the default on the next launch. Switching chips switches the list; the choice survives an app restart. Billets shows tickets of both clubs. Profil → "Mes clubs" reopens the picker. **(3) CI** green: backend `FanOrganizerApiTest`, `FanEventApiTest`, `FanTicketApiTest`, `MigrationsTest`, `DevSeedTest`, `DevSeederTest`; fan `clubs_test.dart`, `events_screens_test.dart`, goldens; shop `page.test.tsx` (home), `[club]` tests, `follows.test.ts`, `reserved-slugs.test.ts`. |

## 2. What exists, gaps, spec refs

### 2.1 What it builds on

| Area | State on `main` 8bade83 | Use |
| --- | --- | --- |
| Public events | `GET /fan/events?organizer=` (one UUID or none), `/fan/events/{id}`. `JdbcEvents.LISTING` + `listings(org, from, 50)`. Each row has `organizer_id`. Own public security chain (`SecurityConfig.publicFanChain`) | Join `organizers` in `LISTING`; `organizer` becomes a list; absent = listed only |
| `EventService.publicEvent` | Also used by `TicketService` (lines 134, 167, 202) for issuance and holder reads | **No listing filter here** (P68) |
| Fan tickets | `FanTicketController.TicketDto` builds its event from `Event.Listing` | The `organizer` block comes for free from `Listing` |
| Organizers | `id, name, kind, settings`; `settings.theme.crest_url` is documented but read nowhere; `OrganizerService` / `JdbcAccounts` | V17 `slug`; directory reads; `crest_url` moves to `settings.crest_url` (P70) |
| Dev seed | `R__dev_seed.sql` (ATLAS FC, Grand Stade, staff, demo fans) + `DevSeeder` (3 ATLAS events that follow the clock, demo fan tickets via a till sale) | Second club + loop over clubs |
| Fan app | `AppConfig.organizerId` + release check; `organizerIdProvider`; `missingOrganizer` error; `JomhorClub` used in `home_screen.dart:220` and `ticket_screen.dart:413`; `Crest` stand-in in `ui/brand.dart`; `SharedPreferencesAsync` read in `main` for session and locale; go_router with one global `redirect` | Same pattern for follows and the "seen" flag; one extra redirect rule |
| Shop | `organizerId()` in `lib/api.ts`; `lib/club.ts` = `tokens.club` (header "Billetterie ATLAS FC", `Crest`, `Watermark`, OG, W5 rows); `/events/[slug]` with canonical 308; pages `force-dynamic`, backend reads cached 60 s / 15 s via `next.revalidate`; locale cookie read server-side; `proxy.ts` (`?lang=`); `isSameOrigin`, `safeNext` | Follows cookie read the same way; follow form → route handler that reuses `isSameOrigin` + `safeNext` |
| Back-office | Organizer switcher, every query keyed on `X-Organizer-Id` | No change |
| Scanner | Multi-club through enrolment; `tokens.g.dart` has an unused `JomhorClub` | Regenerated only |

### 2.2 Spec refs

| Doc | Sections |
| --- | --- |
| `CLAUDE.md` | Non-negotiables (`organizer_id` everywhere; no football names in tables; strings never hard-coded; FR/AR/EN), Stack, Conventions (shop server components; no K_t in HTML), Placeholder identity |
| `design/BRAND.md` | §Where the logo appears (not white-label, line 32 onwards; the club only as content) |
| `docs/03-schema.sql` | Tenant rule header; `organizers` (+ settings shape); `push_tokens` (Lot 4) |
| `docs/04-api.md` | Audiences table; §Fan rows `/fan/events*`, `/fan/tickets`, `/fan/me/push-token`, `/fan/me/inbox`; public-reads paragraph; §Notifications rules; §Campaigns Audience |
| `docs/02` | §Data model rows `organizers`, `push_tokens` |
| `design/DESIGN.md` | Principle 2; Screen inventory; screen 1; §Web shop W1/W2/W5; Lot 4 adjustments (Club tab, bell); §Theming per organizer |
| Plans | lot-1 Q23 (superseded: no theme over the API, ever; crest + name only); lot-4 §4.2 migration table, BE-29..33, FL-20, FL-22, §6 Tenancy |

## 3. Open questions (each has a default, none blocks)

| # | Question | Default |
| --- | --- | --- |
| Q71 | Event URL whose club slug doesn't match the event's club | **404** (founder plan). An event-slug spelling mismatch keeps its 308 to the canonical URL. |
| Q72 | Crest assets: host and who uploads | **None yet.** `crest_url` is null for both demo clubs, so clients draw the stand-in (the name's first letter on a disc, as today). Production crests go on Jomhor's asset host (`https://assets.jomhor.ma/crests/…`, S3 + CloudFront eu-west-3), uploaded by ops. The backend returns a `crest_url` only under `jomhor.crests.base-url`, so clients never load a crest from anywhere else. Neither the shop's image optimizer nor its OG renderer fetches crests. |
| Q73 | `push_tokens` per (token, club) or per token? | **Per token: a platform table, no `organizer_id`** (exempt list 4 → 5, beside `fan_sessions`). Keying by followed clubs would cut transactional pushes (TICKET_RECEIVED, SPLIT_INVITE, ORDER_PAID) and EVENT_HOLDERS pushes for fans who bought without following. Marketing consent already lives per club in `memberships.marketing_push`. `PUT /fan/me/push-token` = `{token, platform}`. **Founder confirms** (tenant-rule exemption). Fallback: the founder's `(token, organizer_id)` + `organizer_ids[]`, with transactional pushes sent to all of the user's tokens, de-duplicated. |
| Q74 | Lot 4 Club tab (screen 7) and bell inbox in a multi-club app | **Club tab:** one member card per followed club, swiped in follow order; no follows → "Choisis tes clubs" → `/clubs`. No contract change: membership endpoints are already per club. **Inbox:** every organizer's campaigns, each with its `organizer` block (04 row replaced, contract only; built with BE-33 / FL-22). |
| Q75 | What "unlisted" means | Out of `/fan/organizers` and out of `Tout` only. Its events still answer `GET /fan/events/{id}`, `?organizer=<id>` and orders. Clients drop followed ids missing from the directory. The shop 404s events whose club has no slug (no URL). |
| Q76 | Second demo club | **Olympique Tensift** (`olympique-tensift`, SPORTS_CLUB), venue "Stade Tensift", Marrakech. One home event, "Olympique Tensift vs Union Rif", at now + 6 days, on sale; the demo fan holds a ticket for it. A distinct name, so TESTING.md's "Sporting Anfa" matchday stays unambiguous. |
| Q77 | Remove `club` from `design/tokens.json`? | **Yes.** It is the build-time single-club identity (lot-1 Q23). Both apps' `tokens.g.dart` are regenerated (the scanner loses the unused `JomhorClub`: no behaviour change, theme test stays green). |
| Q78 | Existing app installs after the update | They see the picker once (no "seen" flag yet). Accepted. |
| Q79 | Shop `?club=` values | `mine`, `all`, `<slug>`; absent = `mine` when the cookie holds a listed club, else `all`; unknown = `all` (no 404). `mine` and `all` are reserved slugs. |
| Q80 | Shop header after the club leaves the build | Lockup + "Billetterie" (no club). The club page and event pages carry crest + name as content. Home `<title>` "Billetterie · Jomhor". |

## 4. Contract changes

### 4.1 Problems found

| # | Where | Problem | Fix |
| --- | --- | --- | --- |
| P63 | lot-4 §4.2 | V17–V21 are reserved for unbuilt Lot 4 tasks; multi-club needs V17 now | Renumber Lot 4: gate_directions V18, memberships V19, split_pay V20, fan_messages V21, campaigns V22 |
| P64 | `design/tokens.json` `club`, `gen_tokens.dart`, `JomhorClub`, `web/shop/lib/club.ts` | Build-time single club: header "Billetterie ATLAS FC", crest discs, OG, W5 rows | Remove; crest + name from the API |
| P65 | DESIGN principle 2, §Theming per organizer, §Web shop intro; `CLAUDE.md` Stack line; `.claude/agents/flutter.md:30`, `web-shop.md:9,22` | Say the club's accent skins the app; contradicts BRAND (not white-label) | Text in §B.5 / §B.7 |
| P66 | 03 `push_tokens`, 04 push-token / §Notifications / §Campaigns | Tokens scoped to "the club app"; one app now | Q73 |
| P67 | 04 `/fan/me/inbox?organizer=` | One bell, many clubs | Q74 |
| P68 | `EventService.publicEvent` | Reused by `TicketService`; a listing filter there breaks tickets of unlisted clubs | Filter only in `listings` with `organizer` absent |
| P69 | Fan `TicketCache` (secure storage) | Cached responses have no `organizer` | `organizer` optional in the Dart model |
| P70 | 03 settings shape | `crest_url` sits under `theme`, which fans never read | `settings.crest_url` (nothing reads the old key; no data migration) |
| P71 | 04 public-reads paragraph | "the shop and the app always send it" | Replaced |
| — | Football names / floats / PII in QR / server on the gate path | `slug` is data; "match" only in UI copy keyed on `kind`; no money touched; scanner untouched | OK |

### 4.2 `docs/03-schema.sql`, Flyway

The diffs are in Part B §B.1. Migration:

| File | Task | Content |
| --- | --- | --- |
| `V17__organizer_slug.sql` | BE-37 | `alter table organizers add column slug text unique constraint organizers_slug check (…)`, exactly as in 03 |

`R__dev_seed.sql` (BE-39): `update organizers set slug = 'atlas-fc' where id = 'a7a5fc00-…0001' and slug is null;` plus the second organizer, its venue, zones, gates, gate_zones, and an ADMIN staff row for the dev admin user. All idempotent (`on conflict do nothing` / `where slug is null`).

### 4.3 `docs/04-api.md`

Part B §B.2: audiences row; two new `/fan/organizers` rows; `/fan/events` row; `/fan/tickets` row; push-token and inbox rows (Lot 4, contract only); public-reads paragraph; new §Club directory; §Notifications and §Campaigns sentences.

### 4.4 Other docs

`docs/02` §Data model (B.3), `docs/06-glossary.md` (B.4), `design/DESIGN.md` (B.5), `design/tokens.json` (B.6), `docs/plans/lot-4.md` (B.8).

## 5. Decisions on the asked points

| Point | Decision |
| --- | --- |
| Shop follows cookie | **Read** in server components with `cookies()`. Pages are already per-request (`force-dynamic`, and `getI18n` already reads the locale cookie), so there's no client island and no hydration flash. **Write** with a plain form `POST /api/follows` (`organizer_id`, `follow=1\|0`, `next`) → `isSameOrigin` (403 otherwise), UUID check (422), `Set-Cookie`, then 303 to `safeNext(next)`. Works with JS off. Cookie `follows` = organizer ids, comma-joined, at most 50 (oldest dropped), `httpOnly`, `SameSite=Lax`, 1 year, `Secure` in production (same as `locale`). On read, ids that aren't valid UUIDs or aren't in the directory are dropped. |
| `/?club=` and `Mes clubs` vs caching | The backend reads are cached, not the pages. `Tout` = `GET /fan/events` (one cache entry, 60 s). One club = `GET /fan/events?organizer=<id>` (one entry per club, 60 s). `Mes clubs` = those per-club reads in parallel, merged by `(starts_at, id)`, sliced to 50: no cache entry per follow combination, so the cache is N clubs + 1. The `windowContradicts` uncached re-read applies per read. The directory `GET /fan/organizers` and `GET /fan/organizers/{slug}` are cached 60 s. The repeated `organizer` param exists for the app (one request, no shared cache). |
| Crest source and image rule | Dev: `crest_url` null, so the stand-in. Rule in 04 §Club directory: the backend returns `settings.crest_url` only when it starts with `jomhor.crests.base-url` (default `https://assets.jomhor.ma/crests/`), else null. Shop: `next/image` with `unoptimized` and **no `remotePatterns` added** (no optimizer proxy, so the shop never fetches crests; the browser loads them from the one asset host). Verify in WS-10; fallback is a plain `<img>` with the same per-line lint skip the OG files use. OG images draw the stand-in only (`ponytail:` Satori fetch of `crest_url` once real assets exist). App: `Image.network` with `errorBuilder` → stand-in (offline wallet included). |
| go_router picker | Fits the existing global `redirect`. Add `GoRoute('/clubs')` at top level (no shell, no session). Add one rule after the `jomhor-fan://` deep-link block and before the login logic: `if (path == '/' && !ref.read(clubsSeenProvider)) return '/clubs';`. Deep links (`/orders/:id`, `/tickets`, `/events/:id`) are never intercepted. The flag is read at startup in `main` from `SharedPreferencesAsync`, like `savedLocale` → `initialClubsProvider` override. No `refreshListenable` change: only the picker flips the flag, and it navigates itself (`go('/')`). Profil → "Mes clubs" = `push('/clubs')` (back chevron + "Terminé", no "Tout voir"). |
| Football names | `kind` drives "Pas de match à venir" (SPORTS_CLUB) vs "Pas d'événement à venir" (others): UI copy only. |

## 6. Tasks

### Wave 0: orchestrator and founder (0.5 d, before any builder)

| # | Owner | Area | Done |
| --- | --- | --- | --- |
| W0-1 | orchestrator | `docs/03`, `04`, `02`, `06`, `docs/plans/lot-4.md`, `docs/plans/multi-club.md`, `design/DESIGN.md`, `design/tokens.json` | Part B applied after the founder agrees |
| W0-2 | founder | — | Confirm Q73 (push_tokens exempt from the tenant rule) and the `CLAUDE.md` / agent-brief wording (B.7). Defaults hold meanwhile: nothing in waves 1–2 builds push tokens |

### Tasks

`backend` is serial. Rows marked ∥ run alongside the backend task of their wave, in disjoint folders. A client task marked *(contract-first)* starts on the Part B shapes with fixtures and is done only after one run against the real endpoints. Every client task ships FR/AR/EN strings (no hard-coded strings or colours) and, in the fan app, FR + AR goldens of the screens it touches.

| Wave | # | Owner | Files / area | Task | Done = test | Blocked on | Days |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | BE-37 | backend | `db/migration/V17__organizer_slug.sql`; `tenancy/application/OrganizerService.java`, `Accounts.java` (port), `adapters/persistence/JdbcAccounts.java`; new `tenancy/adapters/web/FanOrganizerController.java`; `adapters/web/SecurityConfig.java` (`publicFanChain` + `/fan/organizers`, `/fan/organizers/{slug}`); `application.yml` (`jomhor.crests.base-url`); one crest-URL rule shared by tenancy and catalog (`common/`) | V17; `GET /fan/organizers`, `GET /fan/organizers/{slug}`; crest rule | `FanOrganizerApiTest`: list = listed only, by name then id, shape exactly `{id, slug, name, kind, crest_url}` (key-set assertion); a `settings` with `theme`, `cash_points` and a stray `SECRET` field leaks nothing (JSON searched); crest under the base → returned, `http://`, other host or prefix trick (`https://assets.jomhor.ma.evil/…`) → null; `{slug}` unknown, unlisted or malformed (`ATLAS`) → 404 (`../x` is refused earlier by the request firewall: 400); any `Authorization` header ignored (no 401); no `Cache-Control: no-store`. `MigrationsTest`: `v1ToV17Apply`; slug check refuses `Atlas`, `atlas--fc`, `-atlas`, `a`, 41 chars, `orders`, `mine`, `ar`; two null slugs coexist; duplicate slug → unique violation; exempt allowlist still 4 | W0 | 1 |
| 1 | BE-38 | backend | `catalog/domain/Event.java` (`Listing` + `Organizer(id, slug, name, crestUrl)`); `catalog/adapters/persistence/JdbcEvents.java` (`LISTING` joins `organizers`; `listings(List<UUID>, from, limit)`: empty list → `o.slug is not null`, else `e.organizer_id = any(:ids)`); `catalog/application/EventService.java` (`publicList`); `catalog/adapters/web/FanEventController.java` (`@RequestParam(required=false) List<UUID> organizer`, `organizer` DTO without id); `tickets/adapters/web/FanTicketController.java` (event `organizer` DTO with id) | 04 `/fan/events*` + `/fan/tickets` organizer block; repeated filter; `Tout` = listed | `FanEventApiTest`: `EVENT_KEYS` + `organizer`; `organizer` = `{slug, name, crest_url}` exactly; no param → events of listed organizers only (an unlisted organizer's event absent); `?organizer=A&organizer=B` → both clubs merged by `starts_at` then `id`, a third club absent; `?organizer=<unlisted>` → its events, `organizer.slug` null; malformed UUID → 400 `BAD_REQUEST`; cap 50 holds across clubs; `/fan/events/{id}` of an unlisted organizer → 200, slug null. `FanTicketApiTest`: `event.organizer` = `{id, slug, name, crest_url}`; a ticket of an unlisted organizer is still listed. Existing `OrderApiTest` / `Lot1SaleFlowTest` / transfer tests green (proves P68) | BE-37 | 1 |
| 1 | BE-39 | backend | `db/dev/R__dev_seed.sql`; `DevSeeder.java` (a `Club(org, venue, demos)` list looped over, one till per club); `DevSeedTest`, `DevSeederTest` | Q76 seed | `DevSeedTest`: two organizers with slugs `atlas-fc`, `olympique-tensift`; second venue, zones and gates; dev admin is ADMIN in both (memberships by name: ATLAS FC first); idempotent on re-run. `DevSeederTest`: the Tensift event exists, `ON_SALE`, moves with the clock; the demo fan holds tickets of both clubs; `GET /fan/events` without params lists both clubs | BE-38 | 0.5 |
| 1–2 ∥ | FL-23 | flutter | `apps/fan/lib/app/config.dart`, `app/router.dart`, `app/error_text.dart`, `main.dart`, `core/api/api_client.dart` (query values may be lists), `features/events/events_repository.dart` (guard and `organizerIdProvider` removed), new `features/clubs/` (`club.dart`, `clubs_repository.dart`, `follows.dart`: followed ids + seen flag + chosen chip on `SharedPreferencesAsync`, loaded in `main` like the locale; `clubs_screen.dart`), `features/profile/profile_screen.dart` ("Mes clubs" row), `ui/brand.dart` (`Crest(name, url)`: network image or stand-in), `l10n/*.arb` (remove `errorMissingOrganizer`, add keys), `tool/gen_tokens.dart` (drop `JomhorClub`), `lib/theme/tokens.g.dart` + `apps/scanner/lib/theme/tokens.g.dart` (regenerated), `README.md`; tests `config_test`, `theme_test`, `helpers.dart`, new `clubs_test.dart` | No `ORGANIZER_ID`; picker on first launch; follows storage; Profil entry | `config_test`: release needs https (organizer cases removed). `theme_test`: the `JomhorClub` test removed; generated file matches. Scanner `theme_test` green. `clubs_test`: first launch with no flag → `/clubs`; "Tout voir" → flag set, `/`, no follows; "Suivre" ×1 + "Continuer" → follows persisted (re-pump reads them); a deep link `/orders/<id>` on first launch is not intercepted; Profil → "Mes clubs" opens the picker with back, no "Tout voir"; search field only above 8 clubs (fixture of 9) and accent-insensitive (`Etoile` finds `Étoile`); ids missing from the directory are dropped; a directory failure shows the error panel with "Réessayer" and "Tout voir" still works. `l10n_test`: new keys in fr/ar/en. `no_hardcoded_colors_test` green. Goldens: new `fr/ar-11-clubs` | BE-37 *(contract-first)* | 1.5 |
| 2 | FL-24 | flutter | `apps/fan/lib/features/events/home_screen.dart` (chip row, card club line, "Suivre", empty state), `events_repository.dart` (`upcoming(ids)`: `Tout` = no param, else repeated `organizer`), `event.dart` (`organizer`), `event_screen.dart` (header crest + name); tests `events_screens_test.dart`, `events_test.dart`, `home_cash_strip_test.dart`, `shell_test.dart` | Accueil chips per DESIGN multi-club section | Tests: no follows → only `Tout`, selected, request has no `organizer`; follows A, B → chips `Mes clubs · A · B · Tout`, `Mes clubs` default, request `organizer=A&organizer=B`; chip A → `organizer=A`; the chosen chip persists across re-pump, and a remembered chip of an unfollowed club falls back to the default; "Suivre" only on unfollowed cards in `Tout`, and a tap adds the follow and shows `Mes clubs`; empty club → "Pas de match à venir" (SPORTS_CLUB) / "Pas d'événement à venir" (other kind) + "Voir tout" → `Tout`; hero and calendar rows show crest + organizer name; the old "another organizer's event reads as not found" test inverted (renders); the "without ORGANIZER_ID" test deleted; RTL: chip row starts at the right. Goldens re-recorded `fr/ar-1-accueil` | FL-23, BE-38 *(contract-first)* | 1.5 |
| 2 | FL-25 | flutter | `apps/fan/lib/features/tickets/ticket.dart` (`organizer` optional), `my_ticket_strip.dart`, `pass_hero.dart`, `tickets_screen.dart`, `ticket_screen.dart` (band crest from the ticket); `test/tickets_test.dart`, `goldens_test.dart` + every PNG re-recorded in the Linux docker run (`test/goldens.dart`) | Wallet: crest + name per ticket | Tests: rows and strip show crest + club name; a cached response without `organizer` (fixture from today's cache) parses and shows no club line; screen 5 band shows the ticket's club initial. Goldens re-recorded: `fr/ar-billets-transfere`, `fr/ar-5-billet`, `fr/ar-8-transfert`, `fr/ar-8-transfert-hold` (unchanged expected: login, topbar, 4-paiement, cash-order); the run must be green on Linux | FL-24, BE-38 *(contract-first)* | 1 |
| 1–2 ∥ | WS-10 | web-shop | `web/shop/lib/api.ts` (`organizerId` and its env name removed), delete `lib/club.ts`, new `lib/clubs.ts` (`getClubs`, `getClub(slug)`, `readFollows()`, `FOLLOWS_COOKIE`, `eventPath(e)` = `/${slug}/events/${eventSlug(e)}`, `initialOf(name)`), `lib/events.ts` (`getEvents('all' \| ids)` per §5, `organizer` mapped field by field, `getEvent` without the org check), `lib/tickets.ts` (`organizer`), `lib/events.fixture.ts`, `app/brand.tsx` (`Crest`/`Watermark` take `{name, crest_url}`), new `app/api/follows/route.ts`, `lib/i18n/{fr,ar,en}.ts` (new keys; `header.shop` loses `{club}`), `.env.example` | Data layer, cookie, follow handler | `events.test.ts`: `Tout` → `/fan/events` with no `organizer`, cached 60 s, no Authorization; one club → `?organizer=<id>`; `Mes clubs` of 2 → two cached per-club reads merged by `(starts_at, id)`, ≤ 50; a per-club read drops rows of another organizer; `organizer` keeps only `slug`, `name`, `crest_url` (fixture `settings`/`SECRET` absent from output); clock-skew re-read per read. `clubs.test.ts`: directory mapping; `readFollows` drops bad UUIDs and ids missing from the directory; `eventPath`; `initialOf('Olympique Tensift') = 'O'`, Arabic name → first letter. `follows.test.ts`: cross-origin → 403; bad UUID → 422; follow/unfollow sets the cookie (`HttpOnly`, `SameSite=Lax`, `Max-Age` 1 y, `Secure` in production), keeps ≤ 50, 303 to `safeNext(next)` (`//evil` → `/`). `i18n.test`: keys in ar/en. Typecheck, lint, build green with no `ORGANIZER_ID` set | BE-37 *(contract-first)* | 1 |
| 2 | WS-11 | web-shop | `web/shop/app/(public)/page.tsx` + `event-list.tsx` (chip links, clubs strip, card club line, "Suivre" form), new `app/(public)/[club]/page.tsx` + `opengraph-image.tsx`, `git mv app/(public)/events/[slug]/{page,event-view,ZonePicker,opengraph-image}.tsx app/(public)/[club]/events/[slug]/` (+ tests), new redirect-only `app/(public)/events/[slug]/page.tsx`, `app/(public)/layout.tsx` (header "Billetterie"), `orders/new/page.tsx`, `orders/[id]/page.tsx` (links via `eventPath`, crest from the event), `me/tickets/TicketList.tsx` (crest + name per row), `proxy.test.ts`, new `app/reserved-slugs.test.ts` | Routes and pages | New `app/(public)/page.test.tsx`: no cookie → `Tout` selected, both clubs' events, every card with crest + name; cookie (A) → `Mes clubs` selected and only A; `?club=all\|mine\|atlas-fc\|unknown` (unknown = `Tout`); chips are `<a>` with `aria-current="page"`; clubs strip links `/atlas-fc`, `/olympique-tensift`; "Suivre" is a `<form method="post" action="/api/follows">` on unfollowed cards in `Tout` only; canonical `/`. `[club]/page.test.tsx`: unknown slug → 404 (no backend call for a slug failing the regex); title "Billetterie ATLAS FC"; only that club's events; empty → "Pas de match à venir" + `/?club=all`. `[club]/events/[slug]/page.test.tsx` (moved): canonical `/atlas-fc/events/12-…`; wrong event slug → 308 canonical; club mismatch → 404 (Q71); null-slug club → 404; no key in HTML/RSC. Old `events/[slug]/page.test.tsx`: → 308 `/atlas-fc/events/12-…`; unknown, or club without slug → 404. OG tests: event OG draws the organizer's initial + name; club OG renders; mismatch → 404. `orders-pages.test.tsx`: no stub env; back links `/atlas-fc/events/12-…`. `layout.test.tsx`: header "Billetterie" / AR "تذاكر" after the lockup, no club name. `TicketList.test` / `me/tickets/page.test`: club line per row. `proxy.test`: `/atlas-fc/events/12-x?lang=ar` matches; `/atlas-fc/opengraph-image-x` does not. `reserved-slugs.test`: every top-level segment under `app/` and `app/(public)/` (folders not starting with `(`, `[` or `_`, plus file routes such as `manifest`, `opengraph-image`) is in the `organizers_slug` list parsed from `docs/03-schema.sql`. `rtl.test` green | WS-10, BE-38 *(contract-first)* | 2 |
| — | — | backoffice | — | No change | `npm run check` stays green | — | 0 |
| 3 | W3-1 | orchestrator | `scripts/dev.ps1` (drop `$OrganizerId` and `$env:ORGANIZER_ID`; backend wait URL `/fan/events` expecting `Rapid Oued`), `docs/TESTING.md` (Données de démo: Tensift event; Yassine holds 4 tickets over 2 clubs; new scenario "Clubs suivis" shop + app), `CLAUDE.md` + `.claude/agents/*.md` (B.7, founder's own approval), `dist/` debug APKs rebuilt (`flutter build apk --debug --dart-define=API_BASE_URL=http://<lan>:8090/v1`, no `ORGANIZER_ID`) | Cleanup | `rg ORGANIZER_ID` returns only `docs/plans/lot-*.md` history; char check prints `ok`; manual exit demo §1 | all above | 0.5 |
| exit | — | orchestrator + founder | — | Manual demo per §1, PR, merge | CI green | all | — |

`.github/workflows/ci.yml` has no `ORGANIZER_ID`: nothing to change. `reviewer` runs after every builder task.

### Lanes (working days, before review loops)

| Agent | Sequence | Ends |
| --- | --- | --- |
| backend (critical) | BE-37 0–1 → BE-38 1–2 → BE-39 2–2.5 | d2.5 |
| flutter | FL-23 0.5–2 (contract-first) → FL-24 2–3.5 → FL-25 3.5–4.5 (+ docker goldens) | d4.5 |
| web-shop | WS-10 0.5–1.5 (contract-first) → WS-11 2–4 | d4 |
| orchestrator | W0 0–0.5 → W3-1 4.5–5 | d5 |

Total ≈ 5 working days, about 5.5 with review loops. Without contract-first starts it's about 7.

If late, cut in this order:

1. the per-club OG image (the root OG covers the club page);
2. search in the picker (there are 2 clubs today);
3. the remembered chip (default every launch).

Never cut:

- `Tout` = listed only;
- no `settings` leak;
- the crest-host rule;
- the old-URL 308;
- reserved slugs;
- FR + AR goldens of Accueil and the picker.

## 7. Review focus

| Area | What the reviewer checks hardest |
| --- | --- |
| Branding | No club colour anywhere: `settings.theme` is read nowhere by the fan app, shop or scanner. The Jomhor lockup stays first in every header. `JomhorClub`, `tokens.club` and `lib/club.ts` are gone. The crest stand-in uses Jomhor tokens only. |
| No `settings` leak | Fan responses carry only `slug`, `name`, `kind` (directory), `crest_url`. Key-set assertions in the backend tests; fixture secrets searched in the shop HTML and RSC payload. |
| Crest host | `crest_url` passes the base-URL prefix rule in one place, with a dot or user-info trick refused. The shop adds no `remotePatterns` and no image proxy; no server-side crest fetch (OG). The app falls back offline. |
| Listing semantics | `Tout` filters `slug is not null` inside the 50-row SQL limit, not after. **No filter in `publicEvent` / `listing(id)`** (P68). An explicit `organizer` returns unlisted clubs' events. |
| Tenancy | Every new query still scopes tenant tables by `organizer_id` (zones and prices of a listing joined on `organizer_id`). The repeated `organizer` binds as `uuid[]`, never string-built SQL. |
| Routes | Club mismatch → 404, old URL → 308 with the club slug. Reserved list in 03 = top-level shop segments (test). No K_t in any HTML. The follow handler checks Origin, `next` goes through `safeNext`, cookie flags are as specified. |
| App routing | The picker redirect only from `/`, never on `jomhor-fan://` links or the card return; the "seen" flag is written before navigating; `pumpJomhor` defaults to seen. |
| i18n / RTL | New strings in fr/ar/en (ARB, shop dicts). Chip row and clubs strip mirror in AR; club names in bidi isolates. "match"/"événement" chosen by `kind` (UI only). |
| Contract | Each new or changed 04 row has a contract test; `V17__organizer_slug.sql` = 03; lot-4 renumbering applied before BE-32 starts. |

## 8. Carried forward

| Item | When | Note |
| --- | --- | --- |
| Server-side follows (sync across devices) | when asked | `ponytail:` device-only now |
| `Tout` beyond 50 events | when the directory grows | `cursor` paging on `/fan/events` |
| Slug + crest editor in B-settings | Lot 5 | Ops SQL until then |
| Crest in OG images | when Q72 assets exist | Satori fetch, crest host only |
| Shop crest load-failure stand-in | when Q72 assets exist | Client `onError` swap in `web/shop/app/brand.tsx` `Crest` (DESIGN §Multi-club "or it fails to load") |
| Push tokens, inbox and Club tab per Q73/Q74 | Lot 4 BE-30, BE-33, FL-20, FL-22 | Contract applied now |
