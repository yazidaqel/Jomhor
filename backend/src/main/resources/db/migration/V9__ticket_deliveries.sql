-- BE-14 (Lot 1 plan P9): WhatsApp ticket delivery outbox, polled by the notifications job. Names as docs/03-schema.sql.

create type delivery_status as enum ('PENDING','SENT','FAILED');

create table ticket_deliveries (                       -- outbox: WhatsApp ticket messages (only channel in Lot 1)
  id               uuid primary key default gen_random_uuid(),
  organizer_id     uuid not null references organizers(id),
  ticket_id        uuid not null references tickets(id),  -- recipient = tickets.holder_user_id, phone read at send time
  status           delivery_status not null default 'PENDING',
  attempts         smallint not null default 0,
  next_attempt_at  timestamptz not null default now(),
  last_error       text,                               -- provider error code only; no phone, no message body
  created_at       timestamptz not null default now(),
  sent_at          timestamptz
);
create index on ticket_deliveries (next_attempt_at) where status = 'PENDING';
create index on ticket_deliveries (ticket_id, created_at desc);   -- re-send limit
