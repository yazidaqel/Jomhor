-- Jomhor — PostgreSQL schema draft (V1). Derived from docs/02-gate-validation-and-data-model.md.
-- Normative for table and column names. Money columns are bigint centimes (MAD).
-- Flyway: split into V1__tenancy.sql, V2__catalog.sql, ... when implementing.

create extension if not exists pgcrypto;

-- Tenant rule: every table carries organizer_id, except platform identity tables
-- organizers, users, otp_challenges (one fan account across organizers).
-- audit_log.organizer_id is nullable for SYSTEM actions.
-- Every query on a tenant table filters by organizer_id (no composite FKs: app scoping
-- is needed anyway to return 404 on foreign ids; tested per endpoint).

-- ---------- tenancy ----------
create type organizer_kind as enum ('SPORTS_CLUB','PROMOTER','VENUE','OTHER');

create table organizers (
  id            uuid primary key default gen_random_uuid(),
  name          text not null,
  kind          organizer_kind not null default 'OTHER',
  settings      jsonb not null default '{}'::jsonb,
  created_at    timestamptz not null default now()
);
-- organizers.settings shape: {"theme": {"accent": "#F5B700", "on_accent": "#0B0F1A",
--   "crest_url": "...", "display_font": null}}

create table users (
  id            uuid primary key default gen_random_uuid(),
  phone_e164    text not null unique,
  display_name  text,
  locale        text not null default 'fr',
  created_at    timestamptz not null default now()
);

create type staff_role as enum ('ADMIN','OPS','GATE','SUPERVISOR','FINANCE');

create table staff (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  user_id       uuid references users(id),            -- back-office staff log in by phone OTP; null = PIN-only gate staff
  name          text not null,
  role          staff_role not null,
  pin_hash      text,                                 -- scanner PIN (Lot 2)
  active        boolean not null default true,
  created_at    timestamptz not null default now(),
  unique (organizer_id, user_id)
);

create table otp_challenges (
  id            uuid primary key default gen_random_uuid(),
  phone_e164    text not null,
  code_hash     text not null,                        -- HMAC-SHA256(server secret, code); the code is never stored
  attempts      smallint not null default 0 check (attempts <= 5),
  expires_at    timestamptz not null,                 -- created_at + 5 min
  consumed_at   timestamptz,
  created_at    timestamptz not null default now()
);
create index on otp_challenges (phone_e164, created_at desc);   -- rate limit: max 3 per phone per 10 min

-- ---------- catalog ----------
create table venues (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  name          text not null,
  city          text,
  capacity      integer not null check (capacity > 0)
);

create table zones (
  id            smallserial primary key,              -- 2-byte zone_id in the QR; global sequence, ceiling 32,767 zones platform-wide
  organizer_id  uuid not null references organizers(id),
  venue_id      uuid not null references venues(id),
  code          text not null,                        -- 'NORD', 'EST'
  name          text not null,
  capacity      integer not null check (capacity >= 0),
  seated        boolean not null default false,
  unique (venue_id, code)
);

create table gates (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  venue_id      uuid not null references venues(id),
  code          text not null,                        -- 'P4'
  name          text not null,
  unique (venue_id, code)
);

create table gate_zones (
  organizer_id  uuid not null references organizers(id),
  gate_id       uuid not null references gates(id),
  zone_id       smallint not null references zones(id),
  primary key (gate_id, zone_id)
);

create table event_series (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  name          text not null,                        -- 'Saison 2026-27'
  starts_on     date not null,
  ends_on       date not null
);

create type event_type   as enum ('FOOTBALL_MATCH','CONCERT','FESTIVAL_DAY','CONFERENCE','OTHER');
create type event_status as enum ('DRAFT','ON_SALE','GATES_OPEN','CLOSED');
create type reentry_policy as enum ('NONE','ONE_EXIT');

create table events (
  id                integer generated always as identity primary key,  -- 4-byte event_id travels in the QR
  organizer_id      uuid not null references organizers(id),
  venue_id          uuid not null references venues(id),
  series_id         uuid references event_series(id),
  event_type        event_type not null default 'OTHER',
  title             text not null,
  starts_at         timestamptz not null,             -- T0
  doors_open_at     timestamptz,
  status            event_status not null default 'DRAFT',
  event_key_enc     bytea not null,                   -- K_e, 32 bytes, encrypted with the master key (KMS)
  refund_cutoff_at  timestamptz,                      -- default T0 - 60 min, set on publish
  reentry_policy    reentry_policy not null default 'NONE',
  per_account_limit smallint not null default 4,
  metadata          jsonb not null default '{}'::jsonb, -- keys per event_type: docs/04-api.md §Event metadata
  created_at        timestamptz not null default now()
);
create index on events (organizer_id, starts_at);

create table price_categories (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  zone_id       smallint not null references zones(id),   -- must belong to the event's venue (checked in the service)
  name          text not null,                        -- 'Plein tarif', 'Abonné'
  price_mad     bigint not null check (price_mad >= 0),  -- centimes
  quota         integer not null check (quota >= 0),
  sold          integer not null default 0 check (sold >= 0 and sold <= quota),
                -- held (RESERVED, AWAITING_CASH) + PAID; incremented atomically on reserve,
                -- decremented when an order expires or is cancelled
  unique (event_id, zone_id, name)
);

-- ---------- sales ----------
create type sales_channel as enum ('APP','WEB','POS');
create type order_status  as enum ('RESERVED','AWAITING_CASH','PAID','EXPIRED','CANCELLED','REFUNDED');
create type payment_method as enum ('CARD','CASH_AGENCY','WALLET','POS_CASH','FREE');

create table orders (
  id              uuid primary key default gen_random_uuid(),
  organizer_id    uuid not null references organizers(id),
  event_id        integer not null references events(id),
  user_id         uuid references users(id),
  channel         sales_channel not null,
  status          order_status not null default 'RESERVED',
  total_mad       bigint not null check (total_mad >= 0),
  payment_method  payment_method,
  payment_ref     text,                               -- provider_ref of the succeeding payment, cash code (Lot 3), wallet txn
  reserved_until  timestamptz not null,
  paid_at         timestamptz,
  created_at      timestamptz not null default now()
);
create index on orders (event_id, status);
create index on orders (user_id, created_at desc);

create table order_items (
  id                uuid primary key default gen_random_uuid(),
  organizer_id      uuid not null references organizers(id),
  order_id          uuid not null references orders(id),
  price_category_id uuid not null references price_categories(id),
  qty               smallint not null check (qty > 0),
  unit_price_mad    bigint not null check (unit_price_mad >= 0),  -- snapshot at reservation
  unique (order_id, price_category_id)
);

create type ticket_status as enum ('VALID','CANCELLED','REFUNDED','BLOCKED');

create table tickets (
  id                uuid primary key default gen_random_uuid(),   -- 16 bytes in the QR
  organizer_id      uuid not null references organizers(id),
  order_id          uuid not null references orders(id),
  event_id          integer not null references events(id),
  zone_id           smallint not null references zones(id),
  price_category_id uuid not null references price_categories(id),
  holder_user_id    uuid references users(id),
  holder_initials   text,                              -- 'Y. A.' — never the full name in the manifest
  seat_label        text,
  status            ticket_status not null default 'VALID',
  issued_at         timestamptz not null default now()
);
create index on tickets (event_id, status);
create index on tickets (holder_user_id);

create table ticket_transfers (
  id              uuid primary key default gen_random_uuid(),
  organizer_id    uuid not null references organizers(id),
  ticket_id       uuid not null references tickets(id),
  from_user_id    uuid not null references users(id),
  to_user_id      uuid not null references users(id),
  transferred_at  timestamptz not null default now()
);

create type pass_status as enum ('ACTIVE','SUSPENDED','CANCELLED');

create table passes (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  series_id     uuid not null references event_series(id),
  user_id       uuid not null references users(id),
  zone_id       smallint not null references zones(id),
  status        pass_status not null default 'ACTIVE',
  created_at    timestamptz not null default now()
);
-- a pass materialises as one tickets row per event in the series (order channel APP, method FREE)

-- ---------- payments ----------
create type payment_status as enum ('PENDING','SUCCEEDED','FAILED');

create table payments (
  id            uuid primary key default gen_random_uuid(),   -- sent to the gateway as its order reference
  organizer_id  uuid not null references organizers(id),
  order_id      uuid not null references orders(id),
  method        payment_method not null,
  provider      text not null,                        -- 'FAKE' (simulated, non-prod), 'PAYZONE'
  amount_mad    bigint not null check (amount_mad >= 0),
  status        payment_status not null default 'PENDING',
  provider_ref  text,                                 -- gateway transaction id from the callback
  callback      jsonb,                                -- last verified callback, card fields stripped
  created_at    timestamptz not null default now(),
  completed_at  timestamptz
);
create index on payments (order_id);
create unique index on payments (provider, provider_ref) where provider_ref is not null;

-- ---------- gate ----------
create type device_status as enum ('PENDING','ACTIVE','REVOKED');

create table devices (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  label         text not null,
  public_key    bytea not null,                       -- Ed25519, generated in Android Keystore
  status        device_status not null default 'PENDING',
  last_seen_at  timestamptz
);

create table device_assignments (
  organizer_id  uuid not null references organizers(id),
  device_id     uuid not null references devices(id),
  event_id      integer not null references events(id),
  gate_id       uuid not null references gates(id),
  primary key (device_id, event_id)
);

create table manifests (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  version       integer not null,
  built_at      timestamptz not null default now(),
  row_count     integer not null,
  signature     bytea not null,                       -- Ed25519 over the canonical body
  body          bytea not null,                       -- gzipped canonical rows
  unique (event_id, version)
);

create type scan_direction as enum ('IN','OUT');
create type scan_result as enum ('OK','DUPLICATE','DUPLICATE_LATE','BAD_SIG','BAD_FORMAT','UNKNOWN',
                                 'CANCELLED','BLOCKED','WRONG_ZONE','WRONG_EVENT','EXPIRED_SLOT','OVERRIDE');
create type override_reason as enum ('ID_CHECKED','DEVICE_FAULT','DAMAGED_TICKET','OTHER');

create table scans (
  scan_id         uuid primary key,                   -- UUIDv7 generated on the device
  organizer_id    uuid not null references organizers(id),
  event_id        integer not null references events(id),
  ticket_id       uuid,                               -- null when BAD_FORMAT/BAD_SIG
  device_id       uuid not null references devices(id),
  gate_id         uuid not null references gates(id),
  staff_id        uuid references staff(id),
  direction       scan_direction not null default 'IN',
  result          scan_result not null,
  token_version   smallint,
  device_time     timestamptz not null,
  received_at     timestamptz not null default now(),
  override_reason override_reason,
  supervisor_id   uuid references staff(id)
);
create index on scans (ticket_id, direction, device_time);
create index on scans (event_id, received_at);
create index on scans (event_id, gate_id, device_time);

-- canonical entry per ticket = earliest OK by device_time; later OKs are relabelled DUPLICATE_LATE
-- by the ingestion service (not by a unique index, on purpose: arrival order != scan order).

-- ---------- finance ----------
create table settlements (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer references events(id),
  period_start  date not null,
  period_end    date not null,
  gross_mad     bigint not null,
  fees_mad      bigint not null,
  net_mad       bigint not null,
  status        text not null default 'DRAFT',        -- DRAFT, ISSUED, PAID
  issued_at     timestamptz,
  paid_at       timestamptz
);

-- ---------- audit ----------
create table audit_log (
  id            bigserial primary key,
  organizer_id  uuid,
  actor_type    text not null,                        -- STAFF, USER, SYSTEM, DEVICE
  actor_id      uuid,
  action        text not null,                        -- 'ticket.block', 'event.publish', ...
  target        text,
  payload       jsonb,
  at            timestamptz not null default now()
);
