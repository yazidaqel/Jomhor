-- Lot 5 BE-57: pass installments (docs/04-api.md §Passes Installments, Suspension). Names as docs/03-schema.sql.

alter table products add column max_installments smallint not null default 1
  check (max_installments between 1 and 10);
  -- PASS: the most payments a fan may choose (1 = in full only); docs/04-api.md §Passes Installments
alter table products add constraint products_installments check (kind = 'PASS' or max_installments = 1);

alter table passes add column suspended_at timestamptz;  -- an installment overdue past passes.grace; null unless SUSPENDED
alter table passes add constraint passes_suspended check ((status = 'SUSPENDED') = (suspended_at is not null));

create table pass_installments (                      -- a pass's payment schedule (docs/04-api.md §Passes Installments)
  id            uuid primary key default gen_random_uuid(),
  organizer_id  uuid not null references organizers(id),
  pass_id       uuid not null references passes(id),
  seq           smallint not null check (seq between 1 and 10),
  amount_mad    bigint not null check (amount_mad > 0),   -- centimes; Σ over the pass = the product price at purchase
  due_at        timestamptz not null,                     -- seq 1: the purchase; seq k: created_at + (k − 1) × passes.installment-interval
  order_id      uuid unique references orders(id),        -- the PAID order that paid it
  paid_at       timestamptz,
  reminded_at   timestamptz,                              -- INSTALLMENT_DUE queued (once)
  unique (pass_id, seq),
  constraint pass_installments_paid check ((order_id is null) = (paid_at is null))
);
create index on pass_installments (due_at) where paid_at is null;

-- Step 3a passes were bought in full: seq 1 = the purchase order (the pass's first product order), paid when it is.
-- CANCELLED ones too: a late success may still revive one (docs/04-api.md §Passes Lifecycle) and pays its seq 1.
insert into pass_installments (organizer_id, pass_id, seq, amount_mad, due_at, order_id, paid_at)
select p.organizer_id, p.id, 1, o.total_mad, p.created_at,
       case when o.status = 'PAID' then o.id end, case when o.status = 'PAID' then o.paid_at end
from passes p
join lateral (select id, total_mad, status, paid_at from orders
              where organizer_id = p.organizer_id and pass_id = p.id and event_id is null
              order by created_at, id limit 1) o on true;

alter table fan_messages add column pass_id uuid references passes(id);   -- INSTALLMENT_DUE

alter table fan_messages drop constraint fan_messages_subject;
alter table fan_messages add constraint fan_messages_subject check (
    (ticket_id is not null) = (kind = 'TICKET_RECEIVED')
    and (order_id is not null) = (kind in ('SPLIT_INVITE','PAY_CODE','ORDER_PAID'))
    and (event_id is not null) = (kind = 'WAITLIST_OPEN')
    and (pass_id is not null) = (kind = 'INSTALLMENT_DUE'));
-- No unique index for INSTALLMENT_DUE: pass_installments.reminded_at, set in the same transaction, makes it once.
