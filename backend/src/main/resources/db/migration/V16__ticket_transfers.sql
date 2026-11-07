-- BE-28 (Lot 4): ticket transfer and lost-phone re-issue (docs/03-schema.sql ticket_transfers; plan Q59, P48).
-- The old ticket goes CANCELLED by status only; a new ticket (new id, so a new K_t) is issued in the same order.

create table ticket_transfers (                       -- transfer or re-issue: the old ticket goes CANCELLED, a new one is issued
  id              uuid primary key default gen_random_uuid(),   -- (new ticket_id, so a new K_t; the old QR dies with the next manifest)
  organizer_id    uuid not null references organizers(id),
  ticket_id       uuid not null unique references tickets(id),  -- the ticket cancelled
  new_ticket_id   uuid not null unique references tickets(id),  -- same order, event, zone and price category
  from_user_id    uuid not null references users(id),
  to_user_id      uuid not null references users(id),           -- = from_user_id for a re-issue (lost phone)
  transferred_at  timestamptz not null default now()
);
create index on ticket_transfers (from_user_id, transferred_at desc);
