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
