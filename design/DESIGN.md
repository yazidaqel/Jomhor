# Jomhor — design system and screen specs

Tokens: `tokens.json` (normative). Mockups: `mockups/html/*.html` open in any browser (phone screens at
390×844, web pages at 1280 wide); sources `mockups/src/*.dc.html` are the editable canvas files (Claude Design).
Live canvas: https://claude.ai/artifact/RZdBe8HCqJUDBhB61S55mt

## Principles

1. The ticket is the hero. Every screen exists to get the fan to the ticket in one tap.
2. Jomhor identity, clubs as content: the app and the shop are always Jomhor (ink + amber, `BRAND.md`).
   A club appears as its crest and name on its events, tickets, chips and club page; colours and layout
   never change per club.
3. Dark by default (stadiums at night, phone held up at a gate). Light surfaces are
   reserved for the ticket itself, so it reads as a physical object.
4. One-thumb: primary CTA pinned to the bottom, 54 px tall, full width.
5. Numbers and names in the condensed display face; everything else in the body face.
6. Motion means something: ticket slides into the wallet after purchase; card flips to
   gate directions; green "You're in" when the scan syncs. Beyond those, only quiet
   affordances: a one-time entrance on first paint (fade/rise, at most 420 ms, short
   stagger, capped after 5 items), a hover lift on clickable cards, a cross-fade between pages
   (web and Android; iOS keeps its native slide and swipe-back), a quick selection mark
   (`motion.base`). Fan app specifics: the pass flies from its card into screen 5, countdown
   digits roll when they change, the paid check settles in once, a haptic tick marks each QR
   rotation (nothing moves over the QR itself). The Jomhor mark moves in two places only. At
   launch, the splash plays one pass: the eleven dim, a light runs along them, the twelfth
   swells as it lands and the splash fades to the app. That is the one entrance allowed past
   the 420 ms cap (about 0.9 s in all, a tap skips it). On login, the dots assemble once on
   first paint (420 ms, the twelfth last) and the same pass loops as the progress indicator
   while a code is sent or checked.
   Lot 4 adds, each tied to a meaning: a
   transferred ticket slides out of the wallet (the reverse of the purchase slide-in); the
   ticket card flips to its gate side and back (`motion.ticketFlip`); figures roll when they
   change (events attended, a group's paid count, the member number on its first reveal); a
   member's paid check settles in once in the group view; a newly attended event's dot fills
   once; the hold-to-confirm fill on a transfer counts as a progress indicator. No decorative
   loops (progress indicators excepted), no parallax. All of it off under reduced motion.
7. FR first, AR with true RTL mirroring (leading icons flip, numbers stay LTR), EN.

## Components

| Component | Spec |
| --- | --- |
| Screen root | `color.ground`, safe-area top 52 px, horizontal padding 20 |
| Card | `color.surface`, 1 px `color.border`, radius 24, padding 20 |
| Row card (list item) | surface, border, radius 16, padding 12–14, 10 px gap between rows |
| Primary CTA | height 54, radius 16, `accent` bg, `onAccent` text, body 16/600 |
| Secondary button | height 56, surface bg, border, white text, icon above 12 px label |
| Icon button | 44×44 circle, surface bg, border, 20 px stroke icon |
| Chip | surface2/ground bg, radius 12, 8×12 padding, 13 px |
| Countdown tile | ground bg, radius 12, display 26/800 + 11 px label |
| Zone colour bar | 10×40 rounded bar, `zoneA/B/C` or `zoneDisabled` |
| Status banner | success: `successBg` + `success` border + `successText` |
| Bottom nav | 84 px, 4 items, 22 px icons, 11 px labels, active = accent |
| Ticket card | `ticketPaper`, radius 28, accent header band, perforation row (two ground-coloured 28 px circles + dashed rule), QR 200 px, rotation progress bar |
| Scanner result | full-bleed `scanOk` / `scanReject` / `scanWarn` / `scanOverride`, 120 px white circle icon, display 72/800 word, 2×2 fact grid at 14% white |

Icons: 1.8 px stroke, round caps, 20–22 px. No emoji anywhere.

## Screen inventory (fan app)

| # | Screen | Mockup | Route | Entry |
| --- | --- | --- | --- | --- |
| 1 | Accueil | `Main` | `/` | App open outside matchday mode |
| 2 | Match / event detail | `Match` | `/events/:id` | Tap hero or fixture |
| 3 | Choix de zone | `Zones` | `/events/:id/zones` | CTA on event |
| 4 | Paiement | `Checkout` | `/orders/:id/pay` | CTA on zones (order reserved) |
| 5 | Mon billet | `Ticket` | `/tickets/:id` | Wallet, "Mon billet" strip, or matchday mode auto-open |
| 6 | Scanner · résultat | `Scanner` | scanner app `/scan` | After each scan |
| 7 | Club | — | `/club` (tab) | Bottom nav |
| 8 | Transférer | — | sheet on `/tickets/:id` | Screen 5 action |
| 9 | Groupe | — | `/orders/:id` (split variant) | After a split order; push / link for members |
| 10 | Notifications | — | `/inbox` | Bell on screen 1 |
| 11 | Clubs | — | `/clubs` | First launch (skippable); Profil → Mes clubs |

Not mocked yet (Lot 1/4): OTP login, wallet list, transfer flow, order status (cash
code), membership, profile, gate map. Follow the same components.

### 1 · Accueil

- Top bar: Jomhor lockup (start); bell (Lot 4), then the avatar (end).
- Hero card: label "PROCHAIN MATCH · DOMICILE", two crests + "VS", date · time · venue,
  three countdown tiles (jours / heures / min), CTA "Prendre mes places · dès {from_price}".
  If sold out: CTA becomes "Liste d'attente". If on-sale not open: "Vente le {date}".
- "Mon billet" strip (only when the user holds a ticket for an upcoming event): success
  colours, event, zone · gate · relative time; tap → screen 5.
- Calendrier: next 3 events as row cards; price chip or "Bientôt" / "File d'attente".
- Bottom nav: Accueil · Billets · Club · Profil.
- Data: `GET /fan/events`, `GET /fan/tickets`.

### 2 · Match

- Header block (surface) with back + share, competition label, hero title (two lines,
  uppercase), chips for date/time and venue.
- Rules banner: "Vente ouverte · limite {n} billets par compte · billets nominatifs".
- "Tarifs par zone": row per zone with colour bar, name, description, price (display 22)
  and remaining ("2 300 restants" / "Dernières places" in danger colour / "Complet" at 55% opacity).
- Payment hint line with info icon.
- Sticky footer: "À partir de" + price, CTA "Choisir ma zone".
- Data: `GET /fan/events/{id}`.

### 3 · Choix de zone (interactive)

- Stadium map card 300 px: pitch in the centre, zone blocks as buttons (Nord top, Sud
  bottom, Est/Honneur sides); selected = accent fill + border; disabled = surface2 + muted
  text ("Visiteurs · complet"). Gate hints in the corners.
- Selection card: zone name + unit price; quantity stepper (44 px round −/+, display 28
  count), max from `per_account_limit`.
- Group hint line: seats stay together, each friend gets their own QR.
- Footer: "Total" + amount, CTA "Continuer" → `POST /fan/orders`.
- States: no zone selectable → map shows all disabled + "Complet" banner.

### 4 · Paiement (interactive)

- Header shows the reservation countdown "Places réservées 8 min · 07:42" (from
  `reserved_until`); at 0 → modal "Réservation expirée", back to screen 3.
- Order summary card: lines, service fee (0 DH shown explicitly), total.
- "BILLETS NOMINATIFS": holder rows; first = me (CIN vérifiée badge if verified); others =
  dashed row with a WhatsApp-number input per ticket.
- "MOYEN DE PAIEMENT": three selectable rows (Carte bancaire · Cash en agence · Wallet
  mobile), 2 px accent border on the selected one; right-side hint "Instantané" / "24 h".
- Footer CTA text changes with the method: "Payer {total}" / "Obtenir mon code cash" /
  "Payer {total} par wallet"; caption "Billets envoyés dans l'app et sur WhatsApp".
- Card → Payzone 3DS webview → return → screen 5 with the slide-in animation.
  Cash → order status screen with the code, agencies list, 24 h countdown.

### 5 · Mon billet

- Header: close, "MODE JOUR DE MATCH" label (only inside T0 − 4 h), brightness icon
  (brightness forced to max while this screen is visible).
- Ticket card (paper): accent band with competition label, title, crest; 3-column facts
  (ZONE / PORTE / COUP D'ENVOI); holder + ticket number (4-4 grouping); perforation; QR
  200 px; rotation progress bar + "Nouveau code dans {s} s"; "Fonctionne sans réseau".
- Gate guidance row: "Porte 4 · 6 min à pied", live hint from `GET /fan/gates/{event}/load`.
- Actions: Transférer · Wallet (Apple/Google) · WhatsApp (re-send PDF).
- After scan sync: card flips, band turns `success`, text "Tu es dedans" / "راك داخل".
- Multiple tickets in one order: horizontal pager, dots under the card.

### 6 · Scanner · résultat

- Top line: gate · zone · mode (IN/OUT) with accent dot; right: online/offline + unsynced count.
- Result: white circle icon, display 72 word, 2×2 facts (zone, holder initials, ticket
  no, token type), "Validé hors ligne en {ms} ms · {time}".
- Red screens add the reason and, for WRONG_ZONE, the correct gates; they persist until
  dismissed. Green auto-clears after 1.5 s.
- Counters card: entered here · rejected · rate/min.
- Bottom: "Scanner le suivant" (primary, white), manual-entry button, supervisor (lock) button.
- Supervisor sheet: PIN pad → reason list → confirm → OVERRIDE result (blue).

## Web shop (Next.js, SSR)

Same identity as the fan app (dark ground, Jomhor amber accent, condensed display face). Public,
indexable, every event page shareable on WhatsApp with an OG image (club crest + name, title, date).
Container 1180 px, 24 px gutters, stacks at phone width (zones grid → 1 column, summary
card moves under the zones, CTA becomes sticky bottom bar).

| # | Page | Mockup | Route | Rendering |
| --- | --- | --- | --- | --- |
| W1 | Événements (liste) | — | `/` (`?club=mine\|all\|<slug>`) | SSR per request; backend reads cached 60 s (one per club + one for Tout) |
| W2 | Page événement | `ShopEvent` | `/[club]/events/[slug]` (`/events/[slug]` → 308) | SSR; zone grid + summary card are client components |
| W3 | Paiement | — (same as app screen 4, two-column) | `/orders/[id]/pay` | Client; Payzone redirect |
| W4 | Commande · code cash | `ShopOrderStatus` | `/orders/[id]` | SSR + polling; status updates without reload |
| W5 | Mes billets | — | `/me/tickets` | Client, OTP login; PDF download; "Ouvrir dans l'app" deep link |
| W6 | Billet PDF | — | `/api/tickets/[id]/pdf` (proxy, `no-store`) | Server-generated A4 + wallet-size, static QR |
| W7 | Club | — | `/[club]` | SSR per request; backend reads cached 60 s |

W2 details: header (crest + club name, nav Matchs / Abonnements / Mes billets / Aide,
language switch, Se connecter); hero with competition label (fallback "À l'affiche"), 64–80 px uppercase title,
chips (date · time, venue · doors, sale status + limit), countdown tiles (26–36 px figures); "Choisissez
votre zone" 2-column grid of zone cards (colour bar, name, description, price 28 px,
remaining / "Dernières places" / "Complet" disabled; price may go to 32 px); info strip (payment, tickets,
access); sticky summary aside (zone, quantity stepper, lines, 0 DH fee shown, total 30–34 px,
CTA "Continuer", 8-minute note); footer "Propulsé par Jomhor".

W4 details: amber reservation banner with deadline and countdown; 44 px title; white
code card (label, code `JMH 4F7K 29` at 56 px with 3 px letter-spacing, exact amount,
"Copier le code" / "Recevoir sur WhatsApp"); three numbered steps (agence → code +
montant → billets arrivent); "Payer maintenant ?" strip with card / wallet alternatives;
summary aside with holders and a status chip. Page polls `GET /fan/orders/{id}` every 10 s
and swaps to the ticket view on `PAID`.

## Back-office (React + shadcn/ui)

Internal tool: light neutral theme, shadcn defaults (background `#F8FAFC`, card `#FFFFFF`,
border `#E2E8F0`, foreground `#0F172A`, muted `#64748B`, radius 8–10, primary = black
button). No club accent here except in previews. 240 px sidebar (shadcn Sidebar): icons
only with tooltips from 768 to 1023 px, a sheet below 768 px that closes on navigation;
content in a centred main up to 1320 px wide.

Sidebar: Jomhor mark + "Espace organisateur", grouped nav — Pilotage (Aperçu · Événements ·
Ventes), Jour J (Portes en direct · Appareils), Fans & finance (Accréditations · Finance ·
Fans/CRM), Paramètres (Lieux); items not built yet show "Bientôt". Sticky top bar: sidebar
toggle, organizer switcher (organizer name), user menu (name, role, Se déconnecter).

| # | Page | Mockup | Route | Lot |
| --- | --- | --- | --- | --- |
| B0 | Aperçu | — | `/` (landing after login: next event hero with countdown, paid/quota ring, KPIs, zone fill, channels, upcoming list, quick actions; existing endpoints only) | 1 |
| B1 | Événements (liste) | — | `/events` | 0 |
| B2 | Configuration événement | `BoEvent` | `/events/:id` (tabs Général · Tarifs & quotas · Portes & zones · Appareils · Règles) | 0 |
| B3 | Ventes | — | `/events/:id/sales` | 1 |
| B4 | Portes en direct | `BoLive` | `/events/:id/live` | 2 |
| B5 | Réconciliation | — | `/events/:id/reconciliation` | 2 |
| B6 | Appareils | — | `/devices` (enrol, approve, revoke, assign) | 2 |
| B7 | Accréditations | — | `/events/:id/invitations` | 5 |
| B8 | Finance | — | `/finance` (settlements, invoices, payouts) | 5 |
| B9 | Fans · CRM | — | `/crm` (segments, export, campaigns) | 4–5 |
| B10 | Lieux | — | `/settings/venues/:id` (zones table, gates table, gate → zone checkbox matrix) | 0 |

Lot 0 scope of B2: tabs Général, Tarifs & quotas, Portes & zones (venue matrix, read-only;
per-event override comes in Lot 2). Règles shows only ré-entrée, billets max par compte and gel
des remboursements (read-only "T0 − 60 min"). Hidden until their lot: nominatifs (1), file
d'attente (1/5), Appareils tab (2), cash en agence (3), Série (5).

B2 details: breadcrumb + title + status badge (Brouillon / En vente / Portes ouvertes /
Clôturé); actions "Enregistrer le brouillon" (outline) and "Publier · ouvrir la vente"
(primary); tabs; Général card (type, série, titre, T0, ouverture des portes, lieu, billets
max par compte) with a muted "métadonnées" sub-card whose fields depend on `event_type`
(football: adversaire, compétition, journée); Tarifs & quotas table (zone, catégorie, prix,
quota, capacité zone) with inline inputs and a total line; right column: Règles card
(nominatifs, ré-entrée, gel des remboursements, file d'attente, cash en agence) and a
Portes → zones checkbox matrix prefilled from the venue.

B4 details: title with event + time, green status pill with T0 offset, "Exporter CSV" and
"Clôturer l'événement"; four KPI cards (Entrés with progress bar, Cadence with delta,
Refusés with breakdown, Dérogations with per-device cap warning); amber alert row for
devices unsynced > 5 min (reassuring copy: offline validation still active); per-gate table
(porte, zones, appareils online/total, entrés, refusés, cadence/min, charge bar
green/amber/red, dernière sync) with the problem gate row tinted; footnote explaining
"charge". Data: `GET /org/events/{id}/live`, refetched every 5 s.

## Lot 1 adjustments

These override the screen specs above for Lot 1 (see `docs/plans/lot-1.md`).

| Where | Change |
| --- | --- |
| Screen 1 | "Vente le {date}" comes from `sale_starts_at` (venue time zone). "File d'attente" chip/CTA state = the client is retrying after `SALE_BUSY`. "Liste d'attente" (sold out) → Lot 5. Bottom nav in Lot 1: Accueil · Billets · Profil (Club in Lot 4). |
| Screen 2 | "Dernières places" when `remaining ≤ 50`. |
| Screen 3 | Lot 1: zone row cards (same as screen 2) + stepper; the stadium map comes later (Q22). |
| Screen 4 | "BILLETS NOMINATIFS": each row has a **name** input (required, Latin) and an optional WhatsApp-number input. Row 1 is prefilled from `display_name` with no phone. Lot 1 shows only Carte bancaire; Cash and Wallet in Lot 3. Busy state: "Beaucoup de monde en ce moment · nouvel essai dans {s} s". |
| Screen 5 | "TITULAIRE" = `holder_label`. Lot 1 actions: WhatsApp (re-send PDF) only; Transférer (Lot 4) and Apple/Google Wallet (Lot 4) are hidden. |
| W3 / W4 | W3 route is `/orders/new?event=&price=&qty=` (the W2 Continuer target), not `/orders/[id]/pay`. W3 Lot 1: card only; when a friend's phone is given, a required consent line and the caption "Billets envoyés dans l'app et sur WhatsApp" (Q21). `/orders/[id]` in Lot 1 shows "paiement en cours" + polling → `PAID` → link to W5; the cash code layout is Lot 3. |
| W5 / W6 | W6 is a route handler proxying `/fan/tickets/{id}/pdf`. |
| B2 | Général: "Ouverture de la vente" (venue time zone). Règles: "Billets nominatifs · toujours" (read-only). |
| B3 | Lot 1: Ventes (KPIs, per zone, per channel, "Remboursements à faire"). |

## Lot 2 adjustments

These override the screen specs above for Lot 2 (see `docs/plans/lot-2.md`).

| Where | Change |
| --- | --- |
| Screen 6 | Mode label ENTRÉE only (Q33). WRONG_ZONE lists the gate codes from the manifest. Supervisor sheet: pick supervisor → PIN → action (Dérogation · Changer de porte · Recherche billet), override count "n / 20". Dérogation: reason → ticket (scan, or type 8 characters; several matches → rows with zone + initials) → OVERRIDE blue. Text on the amber (`scanWarn`) result is dark ink (`ground`), not white, for ≥ 4.5:1; the dismiss button stays pinned at the bottom with the content scrolling above it at any text size. |
| Scanner screens (not mocked) | Same dark components: Enrôlement (scan QR, label, fingerprint at 28 px, "En attente d'approbation"), Événement (assignment, download progress, drift check result), Déverrouillage (name list + 44 px PIN pad), Recherche (ticket no → zone, status, initials only), Clôture (drain progress, "Données effacées"). |
| B4 | Data from `/live` refetched every 5 s (no SSE). Amber row = `devices_stale` (no "scans en attente" figure: the server can't know it). "Clôturer l'événement" opens a confirm dialog with the stale-device count. "Exporter CSV" = reconciliation CSV. Charge = `rate_per_min` ÷ (`devices_total` × 24). |
| B5 | "Provisoire" / "Définitive" badge (`final`), totals cards, zone table, gate table, unsynced devices, CSV. |
| B6 | Tabs Appareils (list with status badge, fingerprint, last seen; "Ajouter des appareils" dialog with the QR + countdown; Approuver; Révoquer with confirm) and Équipe (gate staff and cashiers; staff list, add → "PIN : 4821, affiché une seule fois" dialog, reset PIN, deactivate). |
| B2 | Appareils tab (device → gate select, manifest version, last seen, wipe ack); Règles "Ré-entrée · aucune" read-only. |

## Lot 3 adjustments

These override the screen specs above for Lot 3 (see `docs/plans/lot-3.md`).

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

## Lot 4 adjustments

These override the screen specs above for Lot 4 (see `docs/plans/lot-4.md`).

| Where | Change |
| --- | --- |
| Bottom nav | Accueil · Billets · Club · Profil |
| Screen 1 | Bell → screen 10, with an 8 px accent dot when a campaign is newer than the last visit (stored on the device). The amber strip also covers member orders to pay ("Ta place t'attend · avant {time}"). Members get a slim strip under "Mon billet": "N° 000042 · Fidèle · 7 matchs" → screen 7. |
| Screen 4 / W3 | Each friend row with a phone gets a switch "Paie sa place" (default off). The summary splits "Tu paies {n} place(s) · {total}" and "{m} place(s) payée(s) par tes amis". Caption: "Tes amis ont 2 h après ton paiement". 422 `TOO_LATE` → the switch is disabled with "Trop tard pour partager". |
| Screen 5 front | Actions: Transférer · WhatsApp (Wallet hidden, Q67). Gate row: "Porte 4" + a load chip (Fluide · Modérée · Chargée; hidden when `UNKNOWN`) + an "Itinéraire" chip. When this gate is `HIGH` and another gate of the zone is `LOW`: "Porte 3 plus fluide en ce moment". No walking time and no wait minutes (Q68). |
| Screen 5 gate side | Tapping the PORTE fact or "Itinéraire" flips the card (`motion.ticketFlip`, 420 ms). This side shows: the gate code in display 72, the gate name, `directions`, and "Ouvrir dans Plans" when coordinates exist (Android `geo:{lat},{lng}?q={lat},{lng}({gate name})`, iOS `https://maps.apple.com/?ll={lat},{lng}&q={gate name}`). It also lists the zone's other gates with their chips and "Retourner le billet". The QR is not on this side; flipping back restores it. Works offline from the cached ticket (chips hidden offline). |
| Screen 5 entry | When `entries` lists this ticket, the card flips once to "Tu es dedans" / "راك داخل": band `success`, gate and time in the venue zone, heavy haptic. Re-opening shows the entered state without the animation. |
| 8 Transférer | Bottom sheet: WhatsApp number + full name (§Holder names). Info: "Ton QR ne marchera plus. {name} reçoit un nouveau billet." Hold-to-confirm CTA (54 px, 800 ms fill, haptic at the end), then the ticket slides out of the wallet. Billets keeps a muted row "Transféré à Karim B. · {date}". After `transfer_until`: disabled, with "Transferts fermés 1 h 30 avant le début". Profil → "Téléphone perdu ?" → confirm listing the upcoming tickets → re-issue each, after "Se déconnecter partout". |
| 9 Groupe, initiator | "Ta bande · {event}". Big figure "{paid}/{total} payées" (rolls). Member row cards: initials avatar (40 px circle, surface2, display 16), holder label, amount, chip (À payer · avant {time} / Payé / Expiré). The paid check settles in once per member. "Relancer sur WhatsApp" opens `https://wa.me/?text=` with the copy and `pay_url`; there is also "Copier le lien". Polls the order every 10 s while visible. "{paid}/{total}" counts **places**: `total` = the buyer's places + every member's, expired included; `paid` = places in `PAID` orders. "Relancer sur WhatsApp" and "Copier le lien" are per unpaid member row (each member has its own `pay_url`). Initials: first letter of the first word + first letter of the last word (`KB`); Arabic letters separated by a space so they don't join. |
| 9 Groupe, member (app, and W4 on the shop) | Event header (as screen 2) + "{initiator_label} t'a gardé une place" + amount + countdown to `reserved_until` + the screen 4 method rows + CTA. Expired: "La place a été libérée". The member variant shows while the member order is `RESERVED` with no attempt (or a `FAILED` one); after that come the existing cash layout, "paiement en cours", then the paid view. |
| 7 Club | Member card (wow table below). Not a member: an outlined card + "Rejoindre le club · gratuit" → a sheet with two switches ("Notifications du club", "Messages WhatsApp du club"; default off, one line each on what they send) → join, then the card reveals. Below: "Ta saison" 12-dot row, "{n} matchs au stade" (or "événements" when `event_type` ≠ FOOTBALL_MATCH), tier progress "Encore 2 pour Pilier", "Derniers" row cards (attended marked). Settings: the two switches and "Quitter le club" (confirm). "matchs" when every `last_events` row is `FOOTBALL_MATCH` (no closed event: when the club is a `SPORTS_CLUB`), else "événements". "Ta saison" always has 12 dots: missing past slots are faint placeholders on the oldest side; the 12th is always amber. Several clubs: cards in a horizontal pager with the next card peeking (viewport ≈ 0.9), no page dots (they would compete with the twelve). |
| 10 Notifications | Campaign rows (title, body, relative time). Tapping a row with `event_id` → screen 2. Empty state: "Rien pour l'instant". |
| Push in foreground | Top banner: surface2, radius 16, title then body (2 lines). Tap → the message's path; swipe or 4 s → dismiss; no slide under reduced motion. Never shown on `/tickets/:id` (nothing over the QR). The inbox refreshes either way. |
| Profil | Langue (Français · العربية · English → PATCH `/fan/me`), Notifications (opt-ins + system permission state), "Téléphone perdu ?". Push permission is asked after the first purchase or a join with push on, never at launch. |
| AR / RTL (fan app and shop) | `ar` = Darija in Arabic script (Q64). Cairo 700/800 for display text in AR, IBM Plex Sans Arabic for body text. Barlow Condensed stays for digits, codes and ticket numbers. Western digits 0–9 in every locale (format with `ar-MA`). Layout mirrored: leading icons and back chevrons flip, and progress and rotation bars fill from the right. QR, `JMH 4F7K 29`, phone numbers, ticket numbers and prices stay LTR inside bidi isolates. **Arabic text gets letter-spacing 0 and no uppercase** (tracking breaks joining), so `label`-style AR text gets its emphasis from weight 700. |
| W2 / all shop pages | Header language switch: a globe button opening a small menu with each language in its own name and script (Français · العربية · English), the current one checked; it sets the cookie (and `PATCH /fan/me` when signed in); `?lang=` also works. `<html lang dir>` per locale. Logical CSS only (`ms-`/`me-`/`ps-`/`pe-`/`start`/`end`). |
| W4 | Member variant as screen 9 member. Initiator `PAID` view: a group progress block under the tickets, server-rendered, refreshed every 10 s while the tab is visible and a member is still payable. |
| W5 | "Transférer" dialog (same fields; a confirm dialog instead of hold-to-confirm) and "Transféré à …" rows. No re-issue on the web (it holds no K_t). |
| B9 Fans · CRM `/crm` (Lot 4: tabs Membres · Campagnes) | **Membres:** KPI cards (members, +7 days, push opt-ins with "joignables", WhatsApp opt-ins) and a tier table; counts only. **Campagnes:** table (title, channel, audience, status badge, planned/sent at in the venue zone, recipients, sent, failed) + "Nouvelle campagne" sheet: channel, audience (Membres opt-in · Détenteurs d'un billet → event select; for the latter the note "Infos pratiques uniquement"), title (60) and body (500) with counters and `dir="auto"`, a phone push preview, the audience estimate after save, "Envoyer maintenant" / "Programmer" (date and time in the venue zone), confirm "Envoyer à {n} fans ?", refetch every 10 s while `SENDING`, "Annuler" while `SCHEDULED`. FINANCE is read-only. |
| B10 Lieux | Gate rows + "Accès" (directions), latitude, longitude, and a "Voir sur la carte" link (new tab). |
| B12 Billets | Columns "Transféré vers" / "Transféré depuis" (ticket numbers, clickable search). |
| Sidebar | Fans & finance: "Fans / CRM" enabled. |

Screens that carry the founder's "wow" bar (inside principles 3, 6 and 7 and `BRAND.md`):

| Screen | What makes it special |
| --- | --- |
| 5 · Mon billet, matchday | The ticket behaves like a physical object with two faces. A flip shows the gate side: the gate code in display 72, the directions, one tap to Maps, live load chips. A second flip, "Tu es dedans" / "راك داخل", turns the band `success` with a heavy haptic the moment the scan syncs: the stadium tells the fan they made it. Brightness max, nothing moves over the QR, everything works offline except the chips. |
| 7 · Club, Carte membre | A dark `surface2` card, radius 28, with a Jomhor accent band carrying the club's crest and name (the club is content, never a colour; like the ticket band, so the ticket stays the only light surface). "MEMBRE N° 000042" in Barlow 44 rolls once on first reveal; "depuis nov. 2026" and the tier name sit in accent. Under it, **"Ta saison" in twelve dots**: the club's last 11 closed events as white dots (attended) or outlined dots (missed), and the 12th dot in amber for the next event. This is the brand's idea (the amber twelfth is the supporter who completes the team) applied to the fan's own season. It is a straight row, never the J mark, so the mark rules stay intact. A new attendance fills its dot once. Joining: the card rises from the CTA and settles (≤ 420 ms) with a success haptic. |
| 8 · Transférer | Hold-to-confirm with a fill (an irreversible action deserves a deliberate gesture). Then the ticket slides out of the wallet, the reverse of the purchase slide-in. The recipient gets a push that opens screen 5 with the existing fly-in. Giving a ticket feels like handing it over. |
| 9 · Groupe | "2/4 payées" in display type rolls up as friends pay. Each friend's check settles in once, and initials avatars make the group feel like people, not rows. "Relancer sur WhatsApp" is one tap with the copy prefilled. The member side opens on the event hero with one line: "Yazid A. t'a gardé une place". |
| 1 · Accueil between matches | The countdown digits roll, the member strip shows tier and matchs at a glance, and the bell has a live inbox: reasons to open the app on a Tuesday. |
| Arabic everywhere | True RTL done right is the differentiator against incumbents: Cairo display headings, Darija voice ("راك داخل"), mirrored layouts, codes and prices kept LTR in isolates, no broken letter-spacing. Every wow screen ships FR + AR goldens. |

Under reduced motion, every Lot 4 motion is off (instant states). There are no decorative loops, no parallax, and no gradients or shadows on the brand mark.

## Multi-club adjustments

These override the screen specs above (see `docs/plans/multi-club.md`).

| Where | Change |
| --- | --- |
| Crest stand-in | When a club has no `crest_url` (or it fails to load): the club name's first letter on a disc, as the Lot 1 stand-in. Real crests are drawn as supplied, never recoloured, never on an accent disc. |
| 11 Clubs (picker) | Lockup, title "Tes clubs", one line "Suis tes clubs pour voir leurs matchs en premier. Tu changes quand tu veux." 2-column grid of club cards (surface, radius 24, crest 56, name display 18, kind as caption). Each card has "Suivre" (outlined chip), which becomes "Suivi" (accent chip with a check, `motion.base`). Search field above the grid when there are more than 8 clubs (by name, accent-insensitive). First launch: primary CTA "Continuer" (enabled from 1 follow) and text button "Tout voir". From Profil: back chevron and "Terminé". |
| Screen 1 chip row | Under the greeting, above the hero: horizontally scrollable, RTL-aware, 8 px gap, pill chips (36 px, 44 px touch target). Order: `Mes clubs` (only when following), one chip per followed club (crest 20 + short name), `Tout`. Selected chip = accent bg + onAccent text; others = surface + border + textSecondary. Default: `Mes clubs` when following, else `Tout`. The chosen chip is remembered on the device. |
| Screen 1 cards | The hero and calendar rows carry a club line above the title: crest 20 + club name (caption, textSecondary). In `Tout`, an unfollowed club's line ends with a small "Suivre" text button (accent, 44 px target), which becomes "Suivi". Empty chip: state panel "Pas de match à venir" ("Pas d'événement à venir" when the club isn't a sports club) + text button "Voir tout" → `Tout`. |
| Billets, "Mon billet" strip, screen 5 | Each ticket row and the strip show the club line (crest 20 + name). The screen 5 band crest is the ticket's club. Every ticket the fan holds is listed, whatever they follow. |
| Profil | Row "Mes clubs" ({n}) → screen 11. |
| 7 Club (Lot 4) | One member card per followed club, swiped in follow order; no follows → "Choisis tes clubs" → screen 11. |
| 10 Notifications (Lot 4) | One inbox across clubs; each row starts with the club line. |
| Shop header (all pages) | Jomhor lockup + "Billetterie" (no club name). |
| W1 `/` | The screen 1 chip row as links (`/?club=mine`, `/?club=<slug>`, `/?club=all`; the selected one has `aria-current="page"`); event cards with the club line; "Suivre" is a small form button in `Tout`. Under the list, a "Clubs" strip: crest 56 + name per listed club, scrolling sideways on phones, wrapping on desktop, each → `/<slug>`. |
| W7 `/<slug>` | Header block: crest 64, club name (h1, uppercase display), "Suivre" / "Suivi". Then the club's events as W1 cards, or the screen 1 empty state with "Voir tout" → `/?club=all`. OG image: crest stand-in + club name + lockup. |
| W2 | The club line above the title links to `/<slug>`. |

## Theming per organizer

None: Jomhor is not white-label (`BRAND.md`). `organizers.settings.theme` is not read by the fan app, the shop or
the scanner. A club supplies its name and a crest (`settings.crest_url`, served from Jomhor's asset host, see
`docs/04-api.md` §Club directory); without a crest, clients draw the stand-in. Zone colours are assigned per venue
(`zoneA/B/C` palette, extendable).

## Accessibility

Contrast ≥ 4.5:1 for all text (muted `#9AA3B8` on `#0B0F1A` passes). Touch targets ≥ 44 px.
Scanner result never relies on colour alone: the word and the icon carry the meaning.
Dynamic type supported up to 130% without clipping the ticket card (QR scales down to 160 px).
