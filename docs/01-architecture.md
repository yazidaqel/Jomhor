# Architecture

The server is the only source of truth; the scanner is the one component built to work
with the server unreachable.

```mermaid
flowchart TB
  subgraph channels[Sales channels]
    FAN[Fan app · Flutter]
    WEB[Web shop]
    POS[Cash POS · thermal print]
  end
  BO[Back-office · React]
  API[Ticketing + gate sync API · Spring Boot]
  PG[(PostgreSQL)]
  KAFKA[[Kafka · scan events, notifications]]
  WA[WhatsApp Business API]
  PAY[Payzone card · cash agencies · wallet]
  subgraph gates[Stadium gates · assume no signal]
    SC[Scanner app · Flutter + SQLite\nmanifest + event key, local scan log]
  end
  FAN --> API
  WEB --> API
  POS --> API
  BO --> API
  API --> PG
  API --> KAFKA
  API --> WA
  API <--> PAY
  SC <-. manifest + K_e down, scans up, only when online .-> API
```

## Components

| Component | Responsibility | Holds |
| --- | --- | --- |
| Ticketing API | Tenancy, catalog (venues, zones, gates, events, pricing), sales (orders, tickets, passes), on-sale waiting room (rooms, queue, admission), payments, manifests, scan ingestion and duplicate resolution, finance, notifications | Master key, per-event keys (encrypted at rest), all records |
| Fan app | Discover, buy, hold tickets, rotating QR offline, transfer, split pay, membership, matchday companion, push | Per-ticket keys K_t for the user's own tickets |
| Web shop | Same purchase flow, PDF with static QR | Nothing after issue |
| Scanner app | Enrolment, manifest download, offline validation, local log, background sync, supervisor override | K_e in Keystore, manifest + scans in SQLite |
| Back-office | Event setup, zone/gate mapping, devices, live dashboard, reconciliation, finance, CRM, campaigns | Nothing (API client) |
| Kafka | Target: `scan.ingested` → dashboard projection; `order.paid` → ticket issue + WhatsApp delivery. Lot 2: the dashboard reads the scans table directly (polled every 5 s). Lot 4: fan messages and campaigns use the `fan_messages` DB outbox; the fan entry state is polled through `/fan/gates/{id}/load`. Kafka arrives only if the Lot 6 load test shows an async boundary. Lot 5: the waiting room needs none (synchronous joins and orders, 1 s tick). | — |

Lot 1: `order.paid` → WhatsApp delivery goes through a DB outbox (`ticket_deliveries`) polled by
`notifications`.

## Backend modules (modular monolith)

`tenancy` · `catalog` · `sales` · `payments` · `tickets` · `gate` · `finance` ·
`notifications` · `crm`. Modules talk through application services or domain events,
never through each other's repositories. One exception: a read-only report query may join another
module's tables (tenant-filtered, no writes) when one SQL statement is what keeps it fast, e.g. the gate
reports joining `tickets` for zone and status.

## Key flows

0. On-sale with a room: join (logged in) → random draw at sale opening for early arrivals, then arrival order → admission token at your turn (10 min) → reserve with the token → pay.
1. Purchase: reserve (8 min hold) → pay (card/wallet: hosted page + callback; cash: AWAITING_CASH + pay code, collected at a club till or by a network callback) → `order.paid` →
   tickets issued (K_t derived) → delivered (app, PDF, WhatsApp).
2. Gate: device enrol → event assignment → manifest + K_e download at T0 − 3 h → offline
   validate → background sync → server resolves first-scan-wins → dashboard.
3. Close: event closed → devices wipe → reconciliation report (sold / scanned / rejected /
   overrides per zone and gate; computed on read, final once every device acked) → settlement.

Details: `02-gate-validation-and-data-model.md`.

## Deployment

The API runs as N identical, stateless instances behind any HTTP load balancer; no hosting provider is assumed. Instances coordinate only through Postgres: row locks with `skip locked` (expiry, deliveries, fan messages, campaigns, room tick), advisory locks (orders, scans, manifest build, the fan-message claim). Per-instance memory is a cache or a limiter, never a source of truth: gate load 15 s, room state 1 s, last poll per queue entry 60 s. Secrets (`JWT_SECRET`, `OTP_SECRET`, `ROOMS_TOKEN_SECRET`, the master key) are identical on every instance and come from the environment. Per-IP limits live in the app and count in Postgres (OTP sends, queue joins), so they hold across instances; the queue poll limit is per entry and per instance (at most N polls/s per entry with N instances). A CDN or WAF can sit in front later without an API change; its addresses join `TRUSTED_PROXIES_REGEX`. Lost on an instance's death and accepted: OTP codes still queued (the fan asks again) and the simulated gateway's delayed callbacks (non-prod only). Health: `/v1/actuator/health/{liveness,readiness}`; graceful shutdown drains in-flight requests.
