-- Lot 5 BE-67: till cash taken to the bank (docs/04-api.md §Finance Deposits). Names as docs/03-schema.sql.

create table cash_deposits (
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  deposited_on  date not null,                        -- the bank slip's date
  amount_mad    bigint not null check (amount_mad >= 0),   -- centimes on the slip
  reference     text not null check (char_length(reference) between 1 and 100),   -- slip or bank reference
  recorded_by   uuid not null references staff(id),
  recorded_at   timestamptz not null default now(),
  voided_at     timestamptz,                          -- a wrong entry: its sessions are freed (audit deposit.void keeps their ids)
  voided_by     uuid references staff(id),
  void_reason   text check (char_length(void_reason) between 1 and 200),
  constraint cash_deposits_void check ((voided_at is null) = (voided_by is null)
                                       and (voided_at is null) = (void_reason is null))
);
create index on cash_deposits (organizer_id, deposited_on desc);

-- The live deposit that took a closed session's cash; null = not deposited.
alter table pos_sessions add column deposit_id uuid references cash_deposits(id);
alter table pos_sessions add constraint pos_sessions_deposit check (deposit_id is null or closed_at is not null);
create index on pos_sessions (organizer_id, closed_at) where closed_at is not null and deposit_id is null;   -- à déposer
