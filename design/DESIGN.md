# Jomhor — design system and screen specs

Tokens: `tokens.json` (normative). Mockups: `mockups/html/*.html` open in any browser (phone screens at
390×844, web pages at 1280 wide); sources `mockups/src/*.dc.html` are the editable canvas files (Claude Design).
Live canvas: https://claude.ai/artifact/RZdBe8HCqJUDBhB61S55mt

## Principles

1. The ticket is the hero. Every screen exists to get the fan to the ticket in one tap.
2. Club identity, not generic ticketing: the organizer's `accent` colour and crest skin
   the app; the layout never changes per club.
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
   rotation (nothing moves over the QR itself). No decorative loops (progress indicators
   excepted), no parallax. All of it off under reduced motion.
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

Not mocked yet (Lot 1/4): OTP login, wallet list, transfer flow, order status (cash
code), membership, profile, gate map. Follow the same components.

### 1 · Accueil

- Top bar: crest + club name (left), bell icon button (right).
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

Same identity as the fan app (dark ground, club accent, condensed display face). Public,
indexable, every event page shareable on WhatsApp with an OG image (crest, title, date).
Container 1180 px, 24 px gutters, stacks at phone width (zones grid → 1 column, summary
card moves under the zones, CTA becomes sticky bottom bar).

| # | Page | Mockup | Route | Rendering |
| --- | --- | --- | --- | --- |
| W1 | Événements (liste) | — | `/` | SSR, revalidate 60 s |
| W2 | Page événement | `ShopEvent` | `/events/[slug]` | SSR; zone grid + summary card are client components |
| W3 | Paiement | — (same as app screen 4, two-column) | `/orders/[id]/pay` | Client; Payzone redirect |
| W4 | Commande · code cash | `ShopOrderStatus` | `/orders/[id]` | SSR + polling; status updates without reload |
| W5 | Mes billets | — | `/me/tickets` | Client, OTP login; PDF download; "Ouvrir dans l'app" deep link |
| W6 | Billet PDF | — | `/api/tickets/[id]/pdf` (proxy, `no-store`) | Server-generated A4 + wallet-size, static QR |

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
| B6 | Tabs Appareils (list with status badge, fingerprint, last seen; "Ajouter des appareils" dialog with the QR + countdown; Approuver; Révoquer with confirm) and Équipe de porte (staff list, add → "PIN : 4821, affiché une seule fois" dialog, reset PIN, deactivate). |
| B2 | Appareils tab (device → gate select, manifest version, last seen, wipe ack); Règles "Ré-entrée · aucune" read-only. |

## Theming per organizer

`accent`, `onAccent`, crest asset, display font (optional) come from
`organizers.settings.theme`. Everything else is fixed. Zone colours are assigned per
venue (`zoneA/B/C` palette, extendable).

## Accessibility

Contrast ≥ 4.5:1 for all text (muted `#9AA3B8` on `#0B0F1A` passes). Touch targets ≥ 44 px.
Scanner result never relies on colour alone: the word and the icon carry the meaning.
Dynamic type supported up to 130% without clipping the ticket card (QR scales down to 160 px).
