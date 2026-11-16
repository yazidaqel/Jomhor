-- Lot 5 BE-61: invitations and zero-amount refunds (docs/04-api.md §Invitations, refund). Names as docs/03-schema.sql.

-- INVITE: issued from the back-office only, never sold to fans or at a till; price 0 like PASS.
alter table price_categories drop constraint price_categories_pass;
alter table price_categories add constraint price_categories_free check (audience not in ('PASS','INVITE') or price_mad = 0);

-- An invitation: the staff who issued it; PAID, channel POS, user_id null, payment_method FREE, total 0.
alter table orders add column invited_by uuid references staff(id);
alter table orders add constraint orders_invited check (invited_by is null
    or (event_id is not null and channel = 'POS' and user_id is null and pass_id is null));
create index on orders (event_id, created_at desc) where invited_by is not null;

-- A FREE place's refund writes no row since Lot 5 step 4 (plan lot-5 Q210); DONE rows of 0 stay as history.
delete from refunds where amount_mad = 0 and status = 'REQUIRED';
