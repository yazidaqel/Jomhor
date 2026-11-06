-- Jomhor scanner app — SQLite schema (Drift). K_e is NOT here: it lives in Android Keystore.

create table config (
  id                  integer primary key check (id = 1),
  device_id           text not null,
  event_id            integer,
  gate_id             text,
  gate_code           text,
  allowed_zone_ids    text,          -- JSON array of smallint
  manifest_version    integer not null default 0,
  reentry_policy      text not null default 'NONE',
  server_time_offset_ms integer not null default 0,
  sync_cursor         integer not null default 0,
  staff_id            text,
  session_token       text,
  mode                text not null default 'IN'   -- IN | OUT
);

create table manifest_tickets (
  ticket_id        text primary key,   -- UUID string
  zone_id          integer not null,
  status           text not null,      -- VALID | CANCELLED | REFUNDED | BLOCKED
  holder_initials  text
);
create index idx_manifest_zone on manifest_tickets (zone_id);

create table used (
  ticket_id   text not null,
  direction   text not null,           -- IN | OUT
  first_at    integer not null,        -- epoch ms, device time
  gate_code   text,
  source      text not null,           -- LOCAL | REMOTE
  primary key (ticket_id, direction)
);

create table scans (
  scan_id          text primary key,   -- UUIDv7
  ticket_id        text,
  direction        text not null,
  result           text not null,
  token_version    integer,
  device_time      integer not null,   -- epoch ms
  synced           integer not null default 0,
  override_reason  text,
  supervisor_id    text
);
create index idx_scans_unsynced on scans (synced) where synced = 0;

-- Validation writes the scan row and the used row in ONE transaction.
-- Sync marks synced=1 only after the server acks the scan_id.
-- On event close (server says CLOSED): delete from manifest_tickets, used, scans where synced=1;
-- wipe K_e from Keystore; reset config.event_id.
