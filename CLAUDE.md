# Jomhor — project brief for Claude Code

Jomhor (جمهور, "the crowd" — the fans, the 12th man) is a
ticketing and fan platform built for the Moroccan market. First customer: a football
club (home matches, one stadium). The persistence layer is event-agnostic: a match, a
concert and a conference day are all rows in `events`.

Read, in this order, before writing code:

1. `docs/00-product.md` — positioning, what we must win on, V1 scope in lots.
2. `docs/01-architecture.md` — components and trust boundaries.
3. `docs/02-gate-validation-and-data-model.md` — the core protocol (offline gate
   validation, QR token, manifest, sync, duplicate resolution). Normative.
4. `docs/03-schema.sql` — PostgreSQL DDL draft, derived from the spec. Normative for names.
5. `docs/04-api.md` — HTTP contracts for fan, organizer and gate-sync APIs.
6. `docs/05-scanner-sqlite.sql` — scanner-side schema.
7. `docs/06-glossary.md` — FR/EN/AR vocabulary and result codes.
8. `design/BRAND.md` + `design/logo/` — identity (12-dot J mark, wordmark as paths).
9. `design/DESIGN.md` + `design/tokens.json` — visual system and per-screen specs.
   Mockups: `design/mockups/html/*.html` (open in a browser), sources in
   `design/mockups/src/*.dc.html`.

## Stack (fixed)

- Backend: Java 25 (LTS), Spring Boot 4.x, PostgreSQL 18, Flyway migrations, Gradle 9.
  Kafka only where an async boundary is real (scan ingestion → dashboard, notifications).
- Fan app and scanner app: Flutter (one repo, two targets or two packages sharing
  `packages/core`). Riverpod for state, Drift (SQLite) on the scanner, `flutter_secure_storage`
  + Android Keystore for keys.
- Web shop: Next.js 16 (App Router, TypeScript, server-rendered event pages for SEO and
  shareable links), Tailwind, same design tokens as the fan app (dark, club accent).
- Back-office: React 19, TypeScript, Vite, shadcn/ui (light neutral theme), TanStack Query.
- Infra: AWS eu-west-3. Secrets never in the repo.

Versions: always the latest stable release of every runtime, framework and library when
adding or upgrading (Java: latest LTS). A version held back needs a one-line reason next to
its pin (e.g. TypeScript 6.0 until typescript-eslint supports 7).

## Repository layout (target)

```
jomhor/
  backend/            Spring Boot modular monolith (modules: tenancy, catalog, sales,
                      payments, tickets, gate, finance, notifications)
  apps/fan/           Flutter fan app
  apps/scanner/       Flutter scanner app
  packages/core/      Dart: token codec, HMAC, models shared by both apps
  web/shop/           Next.js public web shop (SSR)
  web/backoffice/     React + shadcn organizer back-office (SPA)
  docs/               specs (this folder)
  design/             design system + mockups
```

## Non-negotiables

- Gate validation never calls the server on the critical path. The scanner validates
  with the manifest + event key it already holds (spec §Offline validation algorithm).
- No personal data inside a QR payload. Ever.
- `event_id` everywhere; never `match_id`. Football-specific data goes in
  `events.metadata` (jsonb) or in UI/theming, never in table or column names.
- Every table carries `organizer_id`. Multi-tenant from day one.
- Tickets are immutable after issue; state changes go through `status` and `scans`.
- Scans are append-only, idempotent on `scan_id` (UUIDv7 generated on the device).
- Money is in integer centimes (`*_mad` columns are `bigint` centimes), never floats.
- Copy is FR by default, with AR (RTL) and EN. Strings never hard-coded in widgets.

## Conventions

- Backend: package-by-module, hexagonal inside a module (domain / application /
  adapters). JPA entities are not API DTOs. Use records for DTOs. Tests: JUnit 5 +
  Testcontainers (Postgres). Every endpoint in `docs/04-api.md` gets a contract test.
- Flutter: feature-first folders, `freezed` models, no business logic in widgets.
  The token codec in `packages/core` must have golden tests against the vectors in
  `docs/04-api.md` §Test vectors.
- Back-office (React): feature folders, server state only via TanStack Query, forms via
  react-hook-form + zod, shadcn components only (no custom button/input primitives).
- Web shop (Next.js): server components for event and order pages, client components only
  for the zone picker, quantity and payment method; reservation + payment via route handlers
  calling the backend; never expose K_t or any ticket key in server-rendered HTML.
- Commits: conventional commits (`feat(gate): …`). No Co-Authored-By trailers.
- Branches: `main` protected; `feat/<lot>-<topic>`.

## Working with lots

Work is organised in lots (docs/00-product.md §V1 in lots). When asked to implement
something, say which lot it belongs to, check the spec section it depends on, and
propose the DB migration first, then the API, then the UI.

## Agent team

Subagents live in `.claude/agents/`. The main session is the orchestrator: it dispatches,
merges results and talks to the founder; it does not hand off decisions.

| Agent | Owns | Writes code |
| --- | --- | --- |
| `tech-lead` | plan per lot, proposed changes to `docs/03-schema.sql` / `docs/04-api.md` | no |
| `backend` | `backend/`, `docs/token-vectors.json` | yes |
| `flutter` | `apps/fan/`, `apps/scanner/`, `packages/core/` | yes |
| `web-shop` | `web/shop/` | yes |
| `backoffice` | `web/backoffice/` | yes |
| `reviewer` | review verdicts against non-negotiables, specs, security | no |

Loop for every piece of work:

1. `tech-lead` plans it (lot, spec refs, contract changes, ordered tasks with owners).
2. The orchestrator applies contract changes to `docs/` after the founder agrees. Contracts
   move before code.
3. Builders run in waves: migration + API (`backend`) first, then clients. Tasks marked
   parallel by the plan run at the same time; they never share a folder.
4. `reviewer` reviews each builder's change. `CHANGES REQUESTED` goes back to the same builder.
5. Done = reviewer `APPROVE` + the builder's tests green. Then commit on `feat/<lot>-<topic>`.

Cross-agent handoffs: `docs/04-api.md` is the contract between `backend` and every client;
`docs/token-vectors.json` (written by `backend`) is the contract between `backend` and
`packages/core`. A builder that finds the contract wrong stops and reports; it never patches
another agent's folder.

## Placeholder identity

The design uses a placeholder club identity (ATLAS FC, amber `#F5B700` on midnight
blue `#0B0F1A`). Real club colours and crest replace the `accent` and `club` tokens;
nothing else should need to change.
