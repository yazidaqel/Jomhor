-- BE-23 (Lot 3): point of sale (docs/03-schema.sql staff_role, pos_sessions, payments.pos_session_id).
-- CASHIER: back-office OTP login, /org/pos/** only (docs/04-api.md §Point of sale). No PIN. Unused inside V14
-- (a new enum value can't be used in the transaction that adds it).
alter type staff_role add value 'CASHIER';

create table pos_sessions (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  staff_id      uuid not null references staff(id),      -- the cashier (CASHIER, OPS or ADMIN)
  label         text not null,                          -- 'Guichet Nord', 1-40 chars
  opened_at     timestamptz not null default now(),
  closed_at     timestamptz,
  expected_mad  bigint,                                 -- sum of SUCCEEDED payments of the session, frozen at close (centimes)
  declared_mad  bigint check (declared_mad >= 0),       -- cash counted by the cashier at close (centimes)
  constraint pos_sessions_close check ((closed_at is null) = (expected_mad is null)
                                       and (closed_at is null) = (declared_mad is null))
);
create unique index on pos_sessions (organizer_id, staff_id) where closed_at is null;   -- one open till per cashier

-- Cash taken at a club till (POS sale or code collected there).
alter table payments add column pos_session_id uuid references pos_sessions(id);
create index on payments (pos_session_id) where pos_session_id is not null;
