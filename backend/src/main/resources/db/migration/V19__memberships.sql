-- Lot 4 BE-31: a fan who joined an organizer's club (free); loyalty is computed on read (docs/03-schema.sql).
create table memberships (                            -- a fan who joined an organizer's club (free, Lot 4); loyalty is computed on read
  id                  uuid primary key default gen_random_uuid(),
  organizer_id        uuid not null references organizers(id),
  user_id             uuid not null references users(id),
  number              integer not null check (number > 0),   -- member number per organizer, from 1, never reused
  joined_at           timestamptz not null default now(),
  left_at             timestamptz,                           -- left the club; a rejoin clears it and keeps the number and joined_at
  marketing_push      boolean not null default false,        -- opt-ins for MEMBERS campaigns (changes audited: membership.consent)
  marketing_whatsapp  boolean not null default false,
  unique (organizer_id, user_id),
  unique (organizer_id, number)
);
