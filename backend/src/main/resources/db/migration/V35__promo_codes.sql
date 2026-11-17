-- Lot 5 BE-64: promo codes (docs/04-api.md §Promo codes). Names as docs/03-schema.sql.

create type promo_kind as enum ('PERCENT','AMOUNT');

create table promo_codes (
  id                uuid primary key default gen_random_uuid(),
  organizer_id      uuid not null references organizers(id),
  event_id          integer not null references events(id),
  price_category_id uuid references price_categories(id),   -- null = every ALL/MEMBERS category of the event
  code              text not null check (code ~ '^[A-Z0-9]{4,20}$'),   -- uppercase; fans type any case
  kind              promo_kind not null,
  percent           smallint check (percent between 1 and 99),   -- PERCENT: floor(eligible × percent / 100)
  amount_mad        bigint check (amount_mad > 0),               -- AMOUNT: centimes off, once per order
  max_uses          integer check (max_uses > 0),                -- orders RESERVED/AWAITING_CASH/PAID with it; null = no cap
  per_account_max   smallint not null default 1 check (per_account_max between 1 and 100),
  active            boolean not null default true,
  created_at        timestamptz not null default now(),
  unique (event_id, code),
  constraint promo_codes_kind check ((kind = 'PERCENT') = (percent is not null)
                                     and (kind = 'AMOUNT') = (amount_mad is not null))
);

-- The code a buyer's order used; total_mad = Σ qty × unit_price_mad − discount_mad. Existing orders get 0.
alter table orders add column promo_code_id uuid references promo_codes(id);
alter table orders add column discount_mad bigint not null default 0 check (discount_mad >= 0);
alter table orders add constraint orders_promo check (promo_code_id is not null or discount_mad = 0);
create index on orders (promo_code_id, status) where promo_code_id is not null;
