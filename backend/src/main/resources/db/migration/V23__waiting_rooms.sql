-- Lot 5 BE-40: users.verified_at and waiting rooms (docs/04-api.md §Waiting room). Names as docs/03-schema.sql.

alter table users add column verified_at timestamptz;  -- first successful fan OTP login; account age for eligibility

-- A fan session exists only after a fan OTP login, so the earliest one is the first login.
update users u set verified_at = (select min(created_at) from fan_sessions s where s.user_id = u.id)
where exists (select 1 from fan_sessions s where s.user_id = u.id);

create type room_status as enum ('SCHEDULED','PRE_QUEUE','OPEN','PAUSED','ENDED');

create table waiting_rooms (                          -- high-demand on-sale; one per event in Lot 5 step 1
  id                      uuid primary key default gen_random_uuid(),
  organizer_id            uuid not null references organizers(id),
  event_id                integer not null unique references events(id),
  status                  room_status not null default 'SCHEDULED',
  pre_queue_min           smallint not null default 30 check (pre_queue_min between 0 and 1440),
                          -- joins open at events.sale_starts_at - pre_queue_min; joins before sale_starts_at are drawn at random
  admission_rate_per_min  integer not null default 500 check (admission_rate_per_min between 1 and 6000),
  min_account_age_days    smallint check (min_account_age_days between 1 and 365),  -- eligibility, each null = not required:
  member_joined_before    timestamptz,                                              --   verified_at <= sale_starts_at - n days;
  min_events_attended     smallint check (min_events_attended between 1 and 50),    --   member since before; events attended
  joined_count            integer not null default 0,     -- written by the room tick
  ranked_count            integer not null default 0,     -- entries with a rank: ranks are 1..ranked_count
  admitted_through        integer not null default 0,     -- ranks <= this are admitted
  measured_rate_per_min   integer not null default 0,     -- admitted over the last full minute
  rate_mark_at            timestamptz,
  rate_mark_through       integer not null default 0,
  sold_out                boolean not null default false, -- every category remaining = 0 at the last tick
  last_tick_at            timestamptz,
  drawn_at                timestamptz,
  created_at              timestamptz not null default now(),
  constraint waiting_rooms_admitted check (admitted_through <= ranked_count)
);
create index on waiting_rooms (status) where status <> 'ENDED';
