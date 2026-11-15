-- Lot 5 BE-47: members-only prices and the holder limit (docs/04-api.md POST /fan/orders). Names as docs/03-schema.sql.

create type price_audience as enum ('ALL','MEMBERS');
alter table price_categories
  add column audience price_audience not null default 'ALL';  -- MEMBERS: each place held by a live member of the organizer
                                                               -- who joined before events.sale_starts_at

create index on order_items using gin (holders jsonb_path_ops);  -- holder limit: places one account holds
