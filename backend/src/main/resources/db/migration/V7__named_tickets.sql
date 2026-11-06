-- Named tickets (plan Q15/P2, docs/03-schema.sql order_items, tickets).
-- One holder per place, snapshot at reservation: [{"name": "Yazid Aqel", "user_id": "<uuid>"}]. No phone stored.
alter table order_items add column holders jsonb not null default '[]'::jsonb;
-- not valid: Lot 0 rows keep []; every new or updated row is checked.
alter table order_items add constraint order_items_holders_len check (jsonb_array_length(holders) = qty) not valid;

-- Full name as entered (docs/04-api.md §Holder names); never in a QR or manifest. holder_initials is derived from
-- it at issue. Both null on Lot 0 tickets.
alter table tickets add column holder_name text;
