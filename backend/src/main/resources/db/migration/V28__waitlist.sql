-- Lot 5 BE-49: the sold-out waitlist (docs/04-api.md §Waitlist). Names as docs/03-schema.sql.

create table waitlist_entries (                       -- sold-out waitlist (docs/04-api.md §Waitlist); one per account per event
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  event_id      integer not null references events(id),
  user_id       uuid not null references users(id),
  joined_at     timestamptz not null default now(),   -- notice order; a join after a notice resets it (back of the list)
  notified_at   timestamptz,                          -- set with the WAITLIST_OPEN messages, in one transaction
  unique (event_id, user_id)
);
create index on waitlist_entries (event_id, notified_at, joined_at);
-- Rows are deleted on leave and 30 days after the event's starts_at (waitlist.retention).

alter table fan_messages add column event_id integer references events(id);   -- WAITLIST_OPEN

alter table fan_messages drop constraint fan_messages_subject;
alter table fan_messages add constraint fan_messages_subject check (
    (ticket_id is not null) = (kind = 'TICKET_RECEIVED')
    and (order_id is not null) = (kind in ('SPLIT_INVITE','PAY_CODE','ORDER_PAID'))
    and (event_id is not null) = (kind = 'WAITLIST_OPEN'));
create index on fan_messages (event_id, created_at) where kind = 'WAITLIST_OPEN';  -- waitlist cooldown
-- No unique index for WAITLIST_OPEN: waitlist_entries.notified_at, set in the same transaction, makes it once per wait.
