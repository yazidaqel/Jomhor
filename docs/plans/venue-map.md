# Venue map ("the stage") · implementation plan

As of 2026-11-17 · owner: tech-lead · status: **agreed 2026-11-17** by the founder.

- **Placement:** post-Lot-5 item, not part of Lot 6. Lot 7 builds on it.
- **Branch:** `feat/venue-map` from `main` (Lot 5 merged in #28).
- **Migration:** V38.
- **Ids used:** BE-73–BE-75, FL-34, WS-18, BO-29, Q240–Q243, P167–P174.
- **Next free:** BE-76, FL-35, WS-19, BO-30, Q244, P175, migration V39.

## 1. Scope and exit

Founder decisions (2026-11-17):

- **Templates per venue:** `STADIUM`, `END_STAGE`, `CENTER_STAGE`, `THEATRE`, defined in `docs/venue-layouts.json`.
  - The canvas is 400×300, with a decorative centre (`FIELD` or `STAGE`).
  - Slots are named rectangles `[x, y, w, h]`.
- **One zone per slot, unique per venue.** A stand with several prices stays one zone with several price categories. A slot with no zone on sale is drawn inert, so the outline stays whole.
- **Zones only.** Every zone stays "no seats". The map is display and sales only: the QR, manifest, scanner, tickets and orders never read it.
- **Honest group copy:** a group gets N places in the same zone with free placement, and no side-by-side promise. The copy replaces the app's `groupHint` and DESIGN L111, and goes under the shop's quantity stepper.
- **Lot 7 · Seating** is in `docs/00-product.md`: three modes per zone, per event (pick your seat / automatic with groups kept together / no seats, the default). It is not built here.
- **No new dependency:** slots are rectangles, so each client lays out native positioned buttons. No SVG.

**Exit (demo-complete, local on the dev seed):**
1. In B10, ADMIN opens the ATLAS FC venue: "Plan" shows Stade with NORD→Nord, SUD→Sud, EST→Est, HONNEUR→Ouest, and the corners free.
2. App screen 3 shows the map above the list.
   - Tapping Est fills it amber and opens the selection card.
   - Tapping the "Nord" row moves the highlight to Nord.
   - In `ar` the map isn't mirrored and the labels are Arabic.
3. Shop W2 shows the same map above the grid, with `dir="ltr"` on `/ar`, and the group line under the stepper.
4. In B10, OPS moves EST to Sud-Est and saves.
   - The shop shows it after a refresh (≤ 15 s), and so does the app.
   - "Aucun plan (liste)" restores today's screens.
5. The Olympique Tensift event (unmapped venue) shows the list only.
6. `git diff main --stat -- apps/scanner packages/core backend/src/main/java/ma/jomhor/gate backend/src/main/java/ma/jomhor/tickets` is empty.

CI is green on `VenueLayoutsTest`, `VenueMapApiTest`, `FanEventApiTest`, `DevSeederTest`, `stage_map_test.dart`, `layouts_test.dart`, the goldens `fr/ar-3-plan` (Linux docker), `StageMap.test.tsx` and `map.test.tsx`.

**Live** means the same in prod, once the AR copy has passed a native speaker's check (Q240).

## 2. Builds on

**Docs:**
- `docs/03-schema.sql`: `venues`, `zones`.
- `docs/04-api.md`: §Organizer venue rows, with `PUT /org/gates/{id}/zones` as the model; fan `GET /fan/events/{id}`.
- `design/DESIGN.md`: screen 3 (L104–113), W2, B10, the zone palette.
- `docs/plans/lot-1.md`: Q22 and FL-9 (FL-9 is dropped).

**Code:**
- **Backend:** `catalog/application/VenueService.java` (`replaceGateZones`, `lockVenue`), `adapters/persistence/JdbcVenues.java`, `JdbcEvents.java` (fan zones), `adapters/web/FanEventController.java` `ZoneDto`, `DevSeeder.java`.
- **App:** `checkout/zones_screen.dart`, `zone_pick.dart`, `events/zone_row.dart`, `events/event.dart` (`Zone.firstOpen`), `tool/gen_tokens.dart`.
- **Shop:** `[club]/events/[slug]/ZonePicker.tsx`, `lib/events.ts`.
- **Back-office:** `features/venues/` (`VenuePage.tsx`, `GateZoneMatrix.tsx`, `api.ts`).

The fan detail lists only zones that have prices in the event; a slot whose zone isn't listed is inert.

## 3. Open questions (each has a default)

| # | Question | Default |
| --- | --- | --- |
| Q240 | AR (Darija) group copy and map labels | Ship the DESIGN proposals; a native speaker checks them before "Live". |
| Q241 | Map change while an event is on sale | Allowed: display only, read live, no `VENUE_IN_USE`. |
| Q242 | Dev seed | The ATLAS FC venue gets STADIUM (NORD→NORTH, SUD→SOUTH, EST→EAST, HONNEUR→WEST), set in `DevSeeder` only while `layout` is null. The Tensift venue stays unmapped (shows the list fallback). |
| Q243 | Audit log for map writes | None, as for the other venue, zone and gate writes. |

## 4. Contract changes (applied on `feat/venue-map`)

| # | Where | Change |
| --- | --- | --- |
| P167 | `docs/venue-layouts.json` (new) | The four layouts. Checked: integer rects inside 400×300, unique ids per layout, no overlaps (centre included). Smallest slot side and gap per layout: STADIUM 62/6, END_STAGE 72/6, CENTER_STAGE 58/6, THEATRE 56/8. |
| P168 | `docs/03-schema.sql` | `venues.layout text` (null = no map); `zones.map_slot text`; `unique (venue_id, map_slot)` |
| P169 | `docs/04-api.md` | `PUT /org/venues/{id}/map` with every 422 case; `layout`/`map_slot` on the org venue reads and the fan detail; the role line; new §Venue map |
| P170 | `design/DESIGN.md` | §Venue map adjustments: screen 3 map and states, W2, B10 Plan card, slot labels, group copy, map labels |
| P171 | `docs/06-glossary.md` | Venue map, layout, slot, seating mode |
| P172 | `docs/00-product.md` | Eight lots (0–7); the Lot 7 · Seating row; the venue map and seating-mode paragraphs |
| P173 | `docs/plans/lot-5.md` line 3 | Ids used here and the next free ids |
| P174 | This file | — |

## 5. Tasks

Order: contracts → backend → clients. FL-34, WS-18 and BO-29 run in parallel, contract-first, in disjoint folders.

| Wave | Id | Owner | Files | Done = test | Blocked on |
| --- | --- | --- | --- | --- | --- |
| 1 | BE-73 | backend | `V38__venue_map.sql` (two columns + the unique constraint); `catalog/domain/VenueLayouts.java`: ordered map layout → slot ids, `isLayout`, `hasSlot` | **`VenueLayoutsTest`** (reads the JSON, like `TokenVectorsTest`):<br>• `VenueLayouts` equals the JSON's ids, in order;<br>• canvas 400×300, `version` ≥ 1;<br>• ids match `^[A-Z][A-Z0-9_]{0,31}$`, unique per layout; centre is `FIELD`\|`STAGE`;<br>• rects are integer and in bounds, sides ≥ 55, no overlaps.<br>**`MigrationsTest` V38:**<br>• columns are nullable;<br>• same slot twice in one venue → unique violation;<br>• same slot in two venues is OK;<br>• many nulls are OK. | P167, P168 |
| 1 ∥ | FL-34 | flutter | `tool/gen_layouts.dart` → `checkout/layouts.g.dart`; pure `mapView(...)` in `zone_pick.dart`; `checkout/stage_map.dart` (`AspectRatio(4/3)` → `LayoutBuilder` → `Stack` of `Positioned(left, top)`, never `PositionedDirectional`, with `Semantics`); `zones_screen.dart` (map above the rows); `events/event.dart` (`EventDetail.layout`, `Zone.mapSlot`); `l10n` (`groupHint`, `venueMap`, `mapField`, `mapStage`) | **`layouts_test`:** committed file == generator output.<br>**`stage_map_test`:**<br>• view-model states (selectable, selected, sold out, members, all disabled when not on sale, inert); null when the layout is null, unknown or unmapped; unknown slot → list only;<br>• a block tap gives the same `zonePickProvider` state as a row tap;<br>• semantics labels and selected state; inert blocks absent from semantics;<br>• every layout at 320 px in `ar`: no overflow, not mirrored, blocks ≥ 44×44.<br>**Goldens:** new `fr/ar-3-plan`; `fr/ar-3-tarif-membres` re-recorded for the copy.<br>`pubspec.yaml` unchanged. | P167, P169, P170 *(contract-first)* |
| 1 ∥ | WS-18 | web-shop | `lib/venue-map.ts` (imports the JSON; pure `mapView`); `StageMap.tsx` (client); `ZonePicker.tsx` (map between title and grid; tap → first open price, cheapest `ALL` else cheapest `MEMBERS`; disabled when closed or gated; group line under the stepper); `lib/events.ts`; `lib/i18n/*` | **`StageMap.test.tsx`:**<br>• each mapped zone sits at its % position;<br>• null, unknown or unmapped layout → grid only; unknown slot → grid only;<br>• sold out is disabled with "Complet";<br>• a click selects the first open price (members-only zone → its members price);<br>• closed or gated → all blocks disabled;<br>• `/ar` keeps `dir="ltr"`;<br>• every JSON layout renders;<br>• the group copy shows in fr, ar and en.<br>`package.json` unchanged. | P167, P169, P170 *(contract-first)* |
| 1 ∥ | BO-29 | backoffice | `features/venues/MapCard.tsx`; `api.ts` (types, zod, `mapBody()`, `useSaveMap`); `VenuePage.tsx`; `i18n/fr.ts` (template and slot labels); `vite.config.ts` `server.fs.allow`, only if the dev server refuses the `docs/` import | **`map.test.tsx`:**<br>• a preview per layout;<br>• the exact PUT body;<br>• a taken slot isn't offered;<br>• switching layout keeps shared ids and lists the dropped zones;<br>• each 422 lands on its Select;<br>• FINANCE sees the preview only;<br>• every slot id has a label. | P167, P169, P170 *(contract-first)* |
| 2 | BE-74 | backend | `VenueService.replaceMap` (`lockVenue`, checks in the 04 order, clear then set); `Venues`/`JdbcVenues` (`layout`, `map_slot` reads; scoped writes); `Venue.java`; `VenueController` `PUT /org/venues/{id}/map` | **`VenueMapApiTest`:**<br>• round trip; an omitted zone becomes null; an A↔B swap in one PUT; `{null, []}` clears;<br>• every 422 field key, with nothing written;<br>• concurrent conflicting PUTs → both 200, no 500;<br>• deleting a mapped zone frees its slot;<br>• roles: ADMIN/OPS 200, FINANCE 403 on PUT and 200 on GET, CASHIER 403, org B → 404;<br>• `manifest_version` unchanged.<br>`VenueApiTest`, `TokenVectorsTest`, `GateVectorsTest` green. | BE-73 |
| 3 | BE-75 | backend | `JdbcEvents` (`z.map_slot`, venue `layout`); `Event.ZoneListing`; `FanEventController` (detail only); `DevSeeder` (Q242); `backend/http/venue-map.http` | **`FanEventApiTest`:**<br>• the detail carries `layout`/`map_slot`;<br>• a PUT shows on the next read;<br>• the list shape is unchanged.<br>**`DevSeederTest`:**<br>• STADIUM mapping per Q242; Tensift null;<br>• a re-run is a no-op;<br>• an edited map is left alone. | BE-74 |
| run | FL-34, WS-18, BO-29 | each | — | §1 demo steps against the seeded backend. BO-29 can run after BE-74; FL-34 and WS-18 after BE-75. | BE-75 |
| exit | — | reviewer + orchestrator | — | Reviewer APPROVE on each diff, goldens recorded, CI green, PR merged on green | all |

## 6. Review focus

- **Gate path untouched:** no read of `layout`/`map_slot` in `gate/`, `tickets/`, `sales/`, the manifest, `packages/core` or `apps/scanner`. The vectors are unchanged.
- **PUT:**
  - venue row lock, then clear-then-set, so a swap never hits the unique key;
  - atomic on any 422;
  - every write scoped by organizer and venue;
  - the 04 check order and exact field paths;
  - no 500 under concurrency.
- **Event-agnostic names:** columns `layout`, `map_slot`. Template ids are data, not schema.
- **Unknown means unmapped** in all three clients. The JSON is built in, never fetched.
- **Tap parity:** a block tap equals a row tap. The shop's first-open rule is the app's `Zone.firstOpen`.
- **No RTL mirroring:** only the canvas is `ltr`; text keeps its locale.
- **Accessibility:**
  - blocks ≥ 44 px at 320 px;
  - "Complet" shown as text, not only colour;
  - inert slots hidden from assistive tech;
  - focus order follows the JSON.
- **Honest copy:** no "côte à côte" / "side by side" promise left outside the new hint.
- **Dependencies and colours:** no new dependency, no hard-coded colours.

## 7. Risks

| Risk | Mitigation |
| --- | --- |
| Four templates fit few real venues exactly | Price categories cover split stands; new layouts and slots are append-only JSON (old clients fall back to the list) |
| Long names in small blocks | One line with ellipsis; the full name in semantics and in the row below |
| Shop detail cache | A change shows within 15 s, as stated in 04 |
| Unique key on a slot swap | Clear-then-set inside the lock; covered by a swap test |
| Vite dev server refuses `../../docs` | `server.fs.allow` += repo root (dev only) |
| Darija copy quality | Q240: native speaker's check before "Live" |

## 8. Left out

- **Lot 7:** seats, seating modes, seat maps, `seat_label`.
- **Map authoring:** free-form editor, uploaded SVGs, per-club drawings, zoom and pan.
- **Per-event map override.**
- **The map elsewhere:** gate hints on the map; the map in B2/B4, the scanner, the PDF or the ticket.
- **A colour column.** The `zoneA/B/C` palette by position stays.
