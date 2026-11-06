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

