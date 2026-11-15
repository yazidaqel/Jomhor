-- Lot 5 BE-51: products, season passes, product orders and paid membership (docs/04-api.md §Products, §Passes).
-- Names as docs/03-schema.sql.

-- PASS: issued by the pass job only, never sold to fans or at a till.
alter table price_categories add constraint price_categories_pass check (audience <> 'PASS' or price_mad = 0);

create type product_kind as enum ('PASS','MEMBERSHIP');

create table products (                               -- what an order sells besides event places (docs/04-api.md §Products)
  id              uuid primary key default gen_random_uuid(),
  organizer_id    uuid not null references organizers(id),
  kind            product_kind not null,
  name            text not null check (char_length(name) between 1 and 60),   -- organizer content, shown as typed
  price_mad       bigint not null check (price_mad > 0),                      -- centimes; snapshot in order_items
  on_sale         boolean not null default false,
  sales_close_at  timestamptz not null,                 -- no product order at or after; caps a cash code's hold
  quota           integer check (quota >= 0),           -- PASS: passes on sale; null for MEMBERSHIP (no cap)
  sold            integer not null default 0 check (sold >= 0 and (quota is null or sold <= quota)),
                  -- PASS: passes PENDING, ACTIVE or SUSPENDED; 0 for MEMBERSHIP
  series_id       uuid references event_series(id),     -- PASS: the events it covers
  zone_id         smallint references zones(id),        -- PASS: the zone its tickets admit to; events at that zone's venue only
  valid_until     timestamptz,                          -- MEMBERSHIP: the memberships.paid_until it grants
  created_at      timestamptz not null default now(),
  constraint products_kind check (
    (kind = 'PASS') = (series_id is not null and zone_id is not null and quota is not null)
    and (kind = 'MEMBERSHIP') = (valid_until is not null))
);
create index on products (organizer_id, kind);

create type pass_status as enum ('PENDING','ACTIVE','SUSPENDED','CANCELLED');

create table passes (                                 -- a season pass (docs/04-api.md §Passes): one ticket per event of its series
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  product_id    uuid not null references products(id),     -- kind PASS (zone, price)
  series_id     uuid not null references event_series(id), -- = the product's; one live pass per account per series
  user_id       uuid not null references users(id),        -- buyer and holder
  holder_name   text not null,                              -- §Holder names; copied to its tickets; never in a QR or manifest
  status        pass_status not null default 'PENDING',     -- PENDING until its purchase order is PAID; CANCELLED if that expires
  created_at    timestamptz not null default now()
);
create unique index on passes (series_id, user_id) where status <> 'CANCELLED';
-- a pass's ticket for an event comes from an order with pass_id and event_id set (channel APP, method FREE,
-- one PASS-category place); see docs/04-api.md §Passes

alter table orders alter column event_id drop not null;  -- null = a product order (pass purchase, membership)
alter table orders add column pass_id uuid references passes(id);
  -- event_id null: the pass's purchase order; event_id set: the pass's ticket order for that event (PAID, FREE, total 0)
alter table orders add constraint orders_product
  check (event_id is not null or (channel in ('APP','WEB') and group_order_id is null));
create unique index on orders (pass_id, event_id) where pass_id is not null and event_id is not null;  -- one ticket order per pass per event
create index on orders (pass_id) where pass_id is not null;

alter table order_items alter column price_category_id drop not null;  -- an event place; null on a product item
alter table order_items add column product_id uuid references products(id);  -- a product item (qty 1, one holder)
alter table order_items add constraint order_items_subject check ((price_category_id is null) <> (product_id is null));

alter table memberships add column paid_until timestamptz;
  -- the latest MEMBERSHIP product's valid_until paid for (docs/04-api.md §Products); null = never paid
