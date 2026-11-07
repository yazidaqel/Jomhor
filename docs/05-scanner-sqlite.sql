-- Jomhor scanner app: SQLite schema (Drift, own isolate). K_e is NOT here: flutter_secure_storage
-- (Android Keystore-wrapped), loaded into memory only in event mode.

create table config (
  id                    integer primary key check (id = 1),
  device_id             text,                    -- null until enrolled
  device_status         text,                    -- PENDING | ACTIVE
  manifest_public_key   text,                    -- server Ed25519 key, pinned at enrolment
  server_time_offset_ms integer not null default 0,   -- server - device wall clock, from every response
  last_server_time_at   integer,                 -- epoch ms (device) of the last offset update
  event_id              integer,
  event_title           text,
  starts_at             integer,                 -- epoch ms
  event_timezone        text,                    -- IANA zone of the venue (GET /gate/events timezone); times on screen use it
  event_status          text,
  gate_id               text,                    -- current gate: assignment, or a supervisor gate change
  manifest_version      integer not null default 0,
  reentry_policy        text not null default 'NONE',
  sync_cursor           integer not null default 0,
  staff_id              text,                    -- unlocked staff; null = locked
  mode                  text not null default 'IN'
);

create table zones (zone_id integer primary key, code text not null, name text not null);
create table gates (gate_id text primary key, code text not null, name text not null,
                    zone_ids text not null);   -- JSON array
create table staff (staff_id text primary key, label text not null, role text not null,
                    pin_verifier text not null);

create table manifest_tickets (
  ticket_id        text primary key,           -- UUID string
  ticket_no        text not null,              -- last 8 hex, uppercase, no space (manual lookup)
  zone_id          integer not null,
  status           text not null,              -- VALID | CANCELLED | REFUNDED | BLOCKED
  holder_initials  text
);
create index idx_manifest_ticket_no on manifest_tickets (ticket_no);

create table used (
  ticket_id   text not null,
  direction   text not null,
  first_at    integer not null,                -- epoch ms, corrected device time
  gate_code   text,
  source      text not null,                   -- LOCAL | REMOTE
  primary key (ticket_id, direction)
);

create table scans (
  scan_id          text primary key,           -- UUIDv7
  ticket_id        text,                       -- null for BAD_FORMAT, WRONG_EVENT, EXPIRED_SLOT, BAD_SIG
  gate_id          text not null,
  staff_id         text not null,
  direction        text not null,
  result           text not null,
  token_version    integer,
  device_time      integer not null,           -- epoch ms, corrected
  synced           integer not null default 0,
  override_reason  text,
  supervisor_id    text
);
create index idx_scans_unsynced on scans (synced) where synced = 0;

-- Validation writes the scan row and the used row in ONE transaction.
-- A full manifest replaces zones, gates and manifest_tickets and sets manifest_version in ONE transaction,
-- only after its signature verified. A delta is applied in one transaction, then the row count is checked.
-- Sync marks synced=1 only for acked scan_ids. A remote used row keeps the earliest first_at.
-- On CLOSED: push every unsynced row, then delete manifest_tickets, used, zones, gates and synced scans,
-- wipe K_e, POST closed-ack, then reset config.event_id (kept until the ack succeeds, so a lost ack is resent).
-- On REVOKED: delete everything and wipe K_e.
