-- Lot 5 BE-51: places for season-pass holders (docs/04-api.md §Passes). Alone in its migration: Postgres can't use a
-- new enum value in the transaction that adds it (V30's price_categories_pass does).
alter type price_audience add value 'PASS';
