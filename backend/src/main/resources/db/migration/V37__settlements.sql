-- Lot 5 BE-70: daily settlement statements, their lines and Jomhor's fee invoices (docs/04-api.md §Finance). Names as
-- docs/03-schema.sql. The draft settlements table was never migrated: plain create.

create type settlement_status as enum ('DUE','PAID','NONE');   -- NONE: payout 0 (fees covered nothing, or a carried debt)

create table settlements (                            -- daily statement (docs/04-api.md §Finance); immutable but for the payout fields
  id                uuid primary key default gen_random_uuid(),
  organizer_id      uuid not null references organizers(id),
  day               date not null,                    -- local day (finance.timezone) of the payments' completed_at
  collected_mad     bigint not null check (collected_mad >= 0),   -- SUCCEEDED payments held by Jomhor (provider <> 'POS')
  till_mad          bigint not null check (till_mad >= 0),        -- SUCCEEDED payments taken at club tills (provider 'POS'); kept by the club
  fee_rate_bp       integer not null check (fee_rate_bp between 0 and 10000),   -- settings.finance.fee_rate_bp at issue
  fee_ht_mad        bigint not null check (fee_ht_mad >= 0),      -- floor((collected + till) × rate / 10 000)
  fee_vat_mad       bigint not null check (fee_vat_mad >= 0),     -- floor((fee_ht × vat_rate_bp + 5 000) / 10 000)
  carried_in_mad    bigint not null check (carried_in_mad <= 0),  -- least(0, previous statement's collected − fees + carried_in)
  payout_mad        bigint not null check (payout_mad >= 0),      -- greatest(0, collected − fee_ht − fee_vat + carried_in)
  due_on            date not null,                    -- day + finance.payout-days; Saturday/Sunday → Monday
  status            settlement_status not null,       -- DUE when payout > 0, else NONE; DUE → PAID by the organizer
  issued_at         timestamptz not null default now(),
  paid_at           timestamptz,                      -- "Versement reçu"
  paid_by           uuid references staff(id),
  payout_reference  text check (char_length(payout_reference) between 1 and 100),   -- the bank transfer's reference
  unique (organizer_id, day),
  constraint settlements_status check ((status = 'NONE') = (payout_mad = 0)
                                       and (status = 'PAID') = (paid_at is not null)
                                       and (paid_at is null) = (paid_by is null)
                                       and (paid_at is null) = (payout_reference is null))
);

create table settlement_lines (                       -- the statement's payments by subject × method × collector
  id              uuid primary key default gen_random_uuid(),
  organizer_id    uuid not null references organizers(id),
  settlement_id   uuid not null references settlements(id),
  event_id        integer references events(id),      -- an event order's payments
  product_id      uuid references products(id),       -- a product order's (pass purchase, installment, membership)
  method          payment_method not null,
  collector       text not null,                      -- payments.provider ('POS' = club till)
  payments        integer not null check (payments > 0),
  amount_mad      bigint not null check (amount_mad >= 0),
  constraint settlement_lines_subject check ((event_id is null) <> (product_id is null))
);
create index on settlement_lines (settlement_id);

create table invoices (                               -- Jomhor's fee invoice to the organizer (docs/04-api.md §Finance Invoice)
  id              uuid primary key default gen_random_uuid(),
  organizer_id    uuid not null references organizers(id),   -- the customer
  settlement_id   uuid not null unique references settlements(id),
  year            smallint not null,                  -- local year of issued_on
  seq             integer not null check (seq > 0),   -- max + 1 under advisory lock (11 << 32) | year: no gaps
  number          text not null unique,               -- 'JMH-2026-000001'
  issued_on       date not null,
  issuer          jsonb not null,                     -- finance.issuer at issue (legal mentions)
  customer        jsonb not null,                     -- settings.finance at issue: legal_name, address, ice (no RIB)
  amount_ht_mad   bigint not null check (amount_ht_mad > 0),   -- = settlements.fee_ht_mad
  vat_rate_bp     integer not null check (vat_rate_bp between 0 and 10000),
  vat_mad         bigint not null check (vat_mad >= 0),
  amount_ttc_mad  bigint not null,
  created_at      timestamptz not null default now(),
  unique (year, seq),
  constraint invoices_ttc check (amount_ttc_mad = amount_ht_mad + vat_mad)
);
create function invoices_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'invoices are immutable: % refused', tg_op using errcode = 'restrict_violation';
end
$$;
create trigger invoices_immutable before update or delete or truncate on invoices
  for each statement execute function invoices_immutable();
