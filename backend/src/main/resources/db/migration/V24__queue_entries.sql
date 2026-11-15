-- Lot 5 BE-41: the waiting room's queue (docs/04-api.md §Waiting room). Names as docs/03-schema.sql.

create table queue_entries (                          -- one per account per room; sub of the queue token
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  room_id       uuid not null references waiting_rooms(id),
  user_id       uuid not null references users(id),
  device_hash   bytea not null check (length(device_hash) = 32),  -- HMAC-SHA256(rooms secret, device_id); never the raw id
  ip_hash       bytea not null check (length(ip_hash) = 32),      -- HMAC-SHA256(rooms secret, "ip|" + client IP via trusted proxies); never the raw IP
  draw_key      bigint not null,                      -- 8 CSPRNG bytes drawn at join; orders the draw; never returned
  joined_at     timestamptz not null default now(),   -- a rejoin after an expired turn resets it (back of the queue)
  rank          integer check (rank > 0),             -- dense per room, set by the room tick; null until ranked
  admitted_at   timestamptz,                          -- first poll that saw rank <= admitted_through; the turn ends 10 min later
  unique (room_id, user_id),
  unique (room_id, rank)
);
create index on queue_entries (room_id, joined_at) where rank is null;
create index on queue_entries (room_id, device_hash);
create index on queue_entries (room_id, ip_hash, joined_at);   -- per-IP join limit (docs/04-api.md §Waiting room)
-- Rows are deleted 30 days after the event's starts_at (rooms.retention).
