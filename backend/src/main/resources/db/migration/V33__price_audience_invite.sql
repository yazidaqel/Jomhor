-- Lot 5 BE-61: places for invitations and accreditations (docs/04-api.md §Invitations). Alone in its migration:
-- Postgres can't use a new enum value in the transaction that adds it (V34's price_categories_free does).
alter type price_audience add value 'INVITE';
