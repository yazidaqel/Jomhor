-- Lot 5 BE-48: one waiting room per sale phase of an event (docs/04-api.md §Waiting room Phases). Names as
-- docs/03-schema.sql. Step-1 rooms become phase 1 (starts_at null: phase 1 follows events.sale_starts_at).

alter table waiting_rooms drop constraint waiting_rooms_event_id_key;

alter table waiting_rooms
  add column phase smallint not null default 1 check (phase between 1 and 5),
  add column label text check (char_length(label) between 1 and 40),
  add column starts_at timestamptz,
  add constraint waiting_rooms_event_id_phase_key unique (event_id, phase),
  add constraint waiting_rooms_starts check ((phase = 1) = (starts_at is null));
