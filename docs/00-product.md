# Jomhor — product scope

As of 2026-11-06.

## One line

Jomhor is the ticketing and fan platform built for Morocco: cash, WhatsApp, Darija,
match-day support on the ground, and the organizer keeps its data.

## Why it can win

The Moroccan market already has webook.com (exclusive partner of the FRMF, Raja CA,
Jazzablanca, Casablanca Music Week), Guichet.ma (incumbent), Ticket.ma, Tidar.ma and
iTicket. Polish alone does not beat them. What they do worse in Morocco:

| Axis | Incumbents | Jomhor |
| --- | --- | --- |
| Payment | Card-first | Card + cash at agencies (Wafacash / Cash Plus code) + mobile wallet + installments for passes |
| Delivery | Email / app | App + WhatsApp (ticket, gate, reminders, live gate load) |
| Language | FR/EN | Darija-first copy, AR with proper RTL, FR, EN |
| Fairness | Scalping complaints | Visible virtual queue, per-account limits, verified fans |
| Organizer data | Platform keeps it | Organizer owns and exports its fan base |
| Finance | Slow settlement | Daily settlement, VAT invoices, 48 h payouts, cash reconciliation |
| Match day | Remote | Local team at the gates for the first matches |

## Demand anchor

First customer: one football club, home matches, one stadium, up to 45,000 attendance,
no existing gate hardware. Expansion: the Botola clubs not locked into webook, then
promoters and venues (same tables, no schema change).

## Product surfaces

- Fan app (Flutter, iOS + Android): discover, buy, hold the ticket (rotating QR, offline),
  transfer, membership, matchday companion.
- Web shop (Next.js, SSR, responsive): SEO-indexed event pages, buy, cash-code order page, PDF ticket with static QR.
- Scanner app (Flutter, Android phones / PDAs): offline validation, sync, supervisor.
- Back-office (React + shadcn/ui SPA): events, pricing, zones/gates, devices, live dashboard,
  reconciliation, finance, CRM export, campaigns, accreditations.
- Cash POS print (thermal) at kiosks and agencies.

## V1 in lots

One V1 covers the whole platform; it ships in seven lots, each usable at a real match.
Team assumption: 3 developers (backend, Flutter, React) + founder. Estimates to re-cut
after Lot 0.

| Lot | Scope | Usable at the end | Est. |
| --- | --- | --- | --- |
| 0 · Foundations | Tenancy (`organizers`), `events` model with `metadata jsonb`, venues/zones/gates, pricing, auth (phone OTP), card payment (Payzone; simulated gateway until the kit arrives), back-office skeleton, event creation | Admin creates an event and sells a test ticket by card | 3 wk |
| 1 · Sell & deliver | Fan app + web shop purchase flow, named tickets and per-account limits, static and rotating QR, PDF + WhatsApp delivery, ticket wallet, basic on-sale queue | Fans buy and hold a ticket for a real fixture | 4 wk |
| 2 · Gate | Scanner app, offline validation, manifest + K_e distribution, sync and duplicate resolution, supervisor override, live gate dashboard, reconciliation report | First pilot match end to end | 3 wk |
| 3 · Moroccan payments | Cash at agencies (reserve → pay code → QR unlocks), mobile wallet, POS thermal print, cash reconciliation (installments for passes moved to Lot 5 with passes) | Every Moroccan fan can pay, online or offline | 3 wk |
| 4 · Fan platform | Group buying with split pay, ticket transfer, membership + loyalty, matchday companion, push and WhatsApp campaigns, Darija/AR/FR with RTL | The app people open between matches | 4 wk |
| 5 · Organizer platform | **Step 1, high-demand on-sale (first, before the rest):** ranked virtual waiting room (random order for early arrivals, then first come first served; position and wait shown; admission token required to reserve), admission control before the database, the popular-zone hot spot, bot and per-device limits, verified-fan access per event, multi-instance backend, and derby capacity targets, proven at reduced scale locally and in CI (the full capacity run waits for a hosting choice). **Then (steps 2–5):** accreditations and invitations, promo codes, season passes with installments, paid membership, finance module (settlement, VAT invoices, payouts), CRM export | The club runs the whole season alone, derby on-sales included | ≈ 10 wk (steps 1–5) |
| 6 · Hardening & launch | Full derby load test at launch scale on the production setup (the on-sale mechanism itself is Lot 5 step 1), pen test on QR and sync, device fleet and runbooks, monitoring, second organizer on config only | Public launch, second customer | 2 wk |

Lots 0–2 are the pilot match (≈10 weeks). Lots 3–5 can partly run in parallel once
Lot 2 is in production. Lot 6 gates the public launch. First post-V1 item: official
resale with a price cap.

## Out of scope for V1

Resale marketplace, hotels/flights, streaming, NFC season cards, turnstile integration,
away-match ticketing.

## Open questions with the club

- Named tickets with ID check at the gate required by the league/FRMF?
- Re-entry policy: NONE (default) or ONE_EXIT?
- Stadium plan: which gates serve which tribunes; gates staffed on a normal match vs derby.
- Cash on match day: stadium kiosks print, or agencies only in advance?
- Who buys the scanner devices?
- Season tickets: app-only, or physical card required?
