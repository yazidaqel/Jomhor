-- ---------- sales ----------
create type sales_channel as enum ('APP','WEB','POS');
create type order_status  as enum ('RESERVED','AWAITING_CASH','PAID','EXPIRED','CANCELLED','REFUNDED');
create type payment_method as enum ('CARD','CASH_AGENCY','WALLET','POS_CASH','FREE');

create table orders (
  id              uuid primary key default gen_random_uuid(),
  organizer_id    uuid not null references organizers(id),
  event_id        integer not null references events(id),
  user_id         uuid references users(id),
  channel         sales_channel not null,
  status          order_status not null default 'RESERVED',
  total_mad       bigint not null check (total_mad >= 0),
  payment_method  payment_method,
  payment_ref     text,                               -- provider_ref of the succeeding payment, cash code (Lot 3), wallet txn
  reserved_until  timestamptz not null,
  paid_at         timestamptz,
  created_at      timestamptz not null default now()
);
create index on orders (event_id, status);
create index on orders (user_id, created_at desc);

create table order_items (
  id                uuid primary key default gen_random_uuid(),
  organizer_id      uuid not null references organizers(id),
  order_id          uuid not null references orders(id),
  price_category_id uuid not null references price_categories(id),
  qty               smallint not null check (qty > 0),
  unit_price_mad    bigint not null check (unit_price_mad >= 0),  -- snapshot at reservation
  unique (order_id, price_category_id)
);

create type ticket_status as enum ('VALID','CANCELLED','REFUNDED','BLOCKED');

create table tickets (
  id                uuid primary key default gen_random_uuid(),   -- 16 bytes in the QR
  organizer_id      uuid not null references organizers(id),
  order_id          uuid not null references orders(id),
  event_id          integer not null references events(id),
  zone_id           smallint not null references zones(id),
  price_category_id uuid not null references price_categories(id),
  holder_user_id    uuid references users(id),
  holder_initials   text,                              -- 'Y. A.' — never the full name in the manifest
  seat_label        text,
  status            ticket_status not null default 'VALID',
  issued_at         timestamptz not null default now()
);
create index on tickets (event_id, status);
create index on tickets (holder_user_id);

