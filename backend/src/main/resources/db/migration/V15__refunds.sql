-- BE-25 (Lot 3): the refunds ledger (docs/03-schema.sql refund_status, refunds; plan Q52).
-- A refunded ticket owes its unit price back; the money goes back by hand, then FINANCE marks it DONE.
create type refund_status as enum ('REQUIRED','DONE');

create table refunds (                                 -- money owed back for a refunded ticket; paid back by hand
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  ticket_id     uuid not null unique references tickets(id),
  order_id      uuid not null references orders(id),
  payment_id    uuid references payments(id),           -- the order's succeeding payment (its method says how to pay back)
  amount_mad    bigint not null check (amount_mad >= 0),  -- the item's unit_price_mad snapshot
  reason        text not null,                          -- 1-200 chars
  status        refund_status not null default 'REQUIRED',
  requested_by  uuid not null references staff(id),
  requested_at  timestamptz not null default now(),
  done_by       uuid references staff(id),
  done_at       timestamptz,
  reference     text,                                   -- how it was paid back: transfer, gateway refund id, receipt no
  constraint refunds_done check ((status = 'DONE') = (done_at is not null and done_by is not null and reference is not null))
);
create index on refunds (organizer_id, status, requested_at);
