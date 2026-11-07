-- BE-17 (Lot 2): the whole gate section of docs/03-schema.sql (devices, assignments, manifests, scans). Names as 03.

create type device_status as enum ('PENDING','ACTIVE','REVOKED');

create table devices (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  label         text not null,                        -- typed at enrolment ('PDA 12'), 1-40 chars
  public_key    bytea not null unique,                -- ECDSA P-256 SubjectPublicKeyInfo (DER); private key non-exportable in Android Keystore
  status        device_status not null default 'PENDING',
  enrolled_at   timestamptz not null default now(),
  approved_at   timestamptz,
  revoked_at    timestamptz,
  last_seen_at  timestamptz                           -- last request with a valid device signature
);
create index on devices (organizer_id, status);

create table device_assignments (
  organizer_id      uuid not null references organizers(id),
  device_id         uuid not null references devices(id),
  event_id          integer not null references events(id),
  gate_id           uuid not null references gates(id),   -- a gate of the event's venue; the device's starting gate
  manifest_version  integer,                               -- last version this device downloaded (full or delta)
  closed_ack_at     timestamptz,                           -- device confirmed its wipe after close
  primary key (device_id, event_id)
);
create index on device_assignments (event_id);

create table manifests (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  version       integer not null check (version >= 1),
  built_at      timestamptz not null default now(),
  row_count     integer not null,
  signature     bytea not null,                       -- Ed25519 (server gate key) over the uncompressed full payload
  body          bytea not null,                       -- gzip of the full payload text (docs/04-api.md §Manifest payload)
  unique (event_id, version)
);
-- A version is built only when its T/G/Z lines differ from the previous one. A delta is the diff of two stored
-- versions, signed when served.

create type scan_direction as enum ('IN','OUT');
create type scan_result as enum ('OK','DUPLICATE','BAD_SIG','BAD_FORMAT','UNKNOWN',
                                 'CANCELLED','BLOCKED','WRONG_ZONE','WRONG_EVENT','EXPIRED_SLOT','OVERRIDE');
create type override_reason as enum ('ID_CHECKED','DEVICE_FAULT','DAMAGED_TICKET','OTHER');

create table scans (
  scan_id         uuid primary key,                   -- UUIDv7 generated on the device
  seq             bigint generated always as identity unique,  -- server arrival order: the used_since cursor
  organizer_id    uuid not null references organizers(id),
  event_id        integer not null references events(id),
  ticket_id       uuid references tickets(id),        -- a ticket of event_id; null when the token was not authenticated
                                                      -- (BAD_FORMAT, WRONG_EVENT, EXPIRED_SLOT, BAD_SIG)
  device_id       uuid not null references devices(id),
  gate_id         uuid not null references gates(id), -- the gate the device was on (a supervisor may move it)
  staff_id        uuid not null references staff(id), -- staff unlocked on the device
  direction       scan_direction not null default 'IN',  -- IN only while re-entry is NONE (plan lot-2 Q33)
  result          scan_result not null,               -- the device's verdict; never updated
  token_version   smallint,
  device_time     timestamptz not null,               -- device clock corrected by the server offset
  received_at     timestamptz not null default now(),
  override_reason override_reason,
  supervisor_id   uuid references staff(id),
  constraint scans_override check ((result = 'OVERRIDE') = (override_reason is not null and supervisor_id is not null))
);
create index on scans (event_id, ticket_id, device_time);   -- canonical admitted scan per ticket
create index on scans (event_id, seq);                      -- used_since
create index on scans (event_id, gate_id, device_time);     -- live dashboard per gate

-- Append-only: no UPDATE or DELETE on scans, ever. Canonical admitted scan per (ticket_id, direction): the OK or
-- OVERRIDE row with the earliest device_time (tie: lowest scan_id). Later OK/OVERRIDE rows are reported as
-- DUPLICATE_LATE (derived, never stored). Ingestion holds pg_advisory_xact_lock per event, so seq commits in
-- order and a cursor never skips a row.
