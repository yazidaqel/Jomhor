-- Lot 4 BE-30: push tokens and the fan_messages outbox (docs/04-api.md §Notifications). Names as docs/03-schema.sql.
-- fan_messages.campaign_id, fan_messages_campaign and the campaign indexes come with V22 (BE-33).

create type message_channel as enum ('PUSH','WHATSAPP');
create type message_kind    as enum ('TICKET_RECEIVED','SPLIT_INVITE','PAY_CODE','ORDER_PAID','CAMPAIGN');

create table push_tokens (                            -- FCM registration tokens of the Jomhor fan app (one app, every club)
  id            uuid primary key default gen_random_uuid(),   -- platform identity like fan_sessions: no organizer_id;
  user_id       uuid not null references users(id),          -- per-club marketing consent is memberships.marketing_push
  session_id    uuid not null references fan_sessions(id), -- used only while this session is live; deleted when it is revoked
  token         text not null unique,
  platform      text not null check (platform in ('android','ios')),
  updated_at    timestamptz not null default now()
);
create index on push_tokens (user_id);
create index on push_tokens (session_id);   -- deleted with their session (logout, re-issue)

create table fan_messages (                           -- outbox: push and WhatsApp messages other than ticket PDFs (ticket_deliveries)
  id               uuid primary key default gen_random_uuid(),
  organizer_id     uuid not null references organizers(id),
  user_id          uuid not null references users(id),     -- recipient; phone, locale and push tokens read at send time
  channel          message_channel not null,
  kind             message_kind not null,
  order_id         uuid references orders(id),             -- SPLIT_INVITE, PAY_CODE, ORDER_PAID
  ticket_id        uuid references tickets(id),            -- TICKET_RECEIVED
  status           delivery_status not null default 'PENDING',
  attempts         smallint not null default 0,
  next_attempt_at  timestamptz not null default now(),
  last_error       text,                                   -- provider code or NO_DEVICE / OPTED_OUT / NOT_CONFIGURED / STALE; never a phone or body
  created_at       timestamptz not null default now(),
  sent_at          timestamptz,
  constraint fan_messages_subject check (
    (ticket_id is not null) = (kind = 'TICKET_RECEIVED')
    and (order_id is not null) = (kind in ('SPLIT_INVITE','PAY_CODE','ORDER_PAID')))
);
create index on fan_messages (next_attempt_at) where status = 'PENDING';
create unique index on fan_messages (order_id, kind, channel) where kind in ('SPLIT_INVITE','PAY_CODE','ORDER_PAID');  -- once
