-- Lot 5 on-sale invariants (plan lot-5 §5 BE-44, §5.5). Every row is a breach: no rows = clean.
--   psql "$DATABASE_URL" -f backend/loadtest/check.sql
-- One statement, so a test can scope it to its own event: select * from (<this file, no ';'>) c where event_id = ?
-- Places: price_categories.sold = held (RESERVED, AWAITING_CASH) + paid (PAID) places, less one per REFUNDED ticket
-- of a PAID order (docs/03-schema.sql price_categories). A transfer or re-issue leaves its old ticket CANCELLED and
-- listed in ticket_transfers.ticket_id: the new one takes its place.

-- Oversell: more VALID tickets than the category's quota.
select 'OVERSELL_TICKETS' as breach, p.event_id, p.id::text as ref,
       format('VALID tickets %s > quota %s', v.n, p.quota) as detail
from price_categories p
join (select organizer_id, price_category_id, count(*) as n
      from tickets where status = 'VALID'
      group by organizer_id, price_category_id) v
  on v.organizer_id = p.organizer_id and v.price_category_id = p.id
where v.n > p.quota

union all

-- Oversell: sold drifted from the places the orders hold.
select 'SOLD_MISMATCH', p.event_id, p.id::text,
       format('sold %s, held %s, paid %s', p.sold, coalesce(h.held, 0), coalesce(h.paid, 0) - coalesce(r.n, 0))
from price_categories p
left join (select i.organizer_id, i.price_category_id,
                  sum(i.qty) filter (where o.status in ('RESERVED', 'AWAITING_CASH')) as held,
                  sum(i.qty) filter (where o.status = 'PAID') as paid
           from order_items i
           join orders o on o.id = i.order_id and o.organizer_id = i.organizer_id
           group by i.organizer_id, i.price_category_id) h
  on h.organizer_id = p.organizer_id and h.price_category_id = p.id
left join (select t.organizer_id, t.price_category_id, count(*) as n
           from tickets t
           join orders o on o.id = t.order_id and o.organizer_id = t.organizer_id
           where t.status = 'REFUNDED' and o.status = 'PAID'
           group by t.organizer_id, t.price_category_id) r
  on r.organizer_id = p.organizer_id and r.price_category_id = p.id
where p.sold <> coalesce(h.held, 0) + coalesce(h.paid, 0) - coalesce(r.n, 0)

union all

-- A pass product whose sold isn't its passes that aren't CANCELLED (docs/03-schema.sql products; a MEMBERSHIP sells
-- no pass: 0). No event: ref = the product id.
select 'PRODUCT_SOLD', null::integer, pr.id::text, format('sold %s, live passes %s', pr.sold, coalesce(n.n, 0))
from products pr
left join (select organizer_id, product_id, count(*) as n from passes where status <> 'CANCELLED'
           group by organizer_id, product_id) n
  on n.organizer_id = pr.organizer_id and n.product_id = pr.id
where pr.sold <> coalesce(n.n, 0)

union all

-- Installments (docs/04-api.md §Passes Installments): a paid installment's order is a PAID product order of its pass
-- for exactly its amount, and a PAID product order of a pass paid exactly one installment. ref = the order id.
select 'INSTALLMENT_PAID', null::integer, o.id::text,
       format('order %s %s of %s for installment %s of %s', o.status, o.total_mad, o.pass_id, i.seq, i.amount_mad)
from pass_installments i
join orders o on o.id = i.order_id and o.organizer_id = i.organizer_id
where o.status <> 'PAID' or o.pass_id is distinct from i.pass_id or o.event_id is not null
   or o.total_mad <> i.amount_mad

union all

select 'INSTALLMENT_PAID', null::integer, o.id::text, format('PAID order of %s paid no installment', o.pass_id)
from orders o
where o.status = 'PAID' and o.pass_id is not null and o.event_id is null
  and not exists (select 1 from pass_installments i where i.organizer_id = o.organizer_id and i.order_id = o.id)

union all

-- Double charge: two SUCCEEDED payments on one order, neither marked payment.refund_required (audit target = the
-- payment id).
select 'DOUBLE_CHARGE', o.event_id, o.id::text,
       format('%s SUCCEEDED payments without payment.refund_required', count(*))
from payments pay
join orders o on o.id = pay.order_id and o.organizer_id = pay.organizer_id
where pay.status = 'SUCCEEDED'
  and not exists (select 1 from audit_log a
                  where a.organizer_id = pay.organizer_id and a.action = 'payment.refund_required'
                    and a.target = pay.id::text)
group by o.event_id, o.id
having count(*) > 1

union all

-- A PAID order whose tickets (transferred-away ones left out) aren't its places. A product order (event_id null,
-- docs/04-api.md §Products) has no place and so no ticket: its item has no price category.
select 'TICKETS_PLACES', o.event_id, o.id::text,
       format('tickets %s, places %s', coalesce(t.n, 0), coalesce(i.places, 0))
from orders o
left join (select order_id, sum(qty) as places from order_items where price_category_id is not null
           group by order_id) i on i.order_id = o.id
left join (select tk.order_id, count(*) as n
           from tickets tk
           where not exists (select 1 from ticket_transfers x where x.ticket_id = tk.id)
           group by tk.order_id) t on t.order_id = o.id
where o.status = 'PAID' and coalesce(t.n, 0) <> coalesce(i.places, 0)

union all

-- An account holding more than per_account_limit places of an event (RESERVED, AWAITING_CASH, PAID, less the
-- REFUNDED tickets of the PAID orders: the POST /fan/orders count, plan lot-5 Q175). A late success reviving an
-- EXPIRED order is counted on purpose: OrderService.reReserve re-checks this same count under the buyer lock and
-- refuses (payment.refund_required PER_ACCOUNT_LIMIT, the order stays EXPIRED), as 04 §Late success lists. Lowering
-- per_account_limit while ON_SALE is not checked against what was already sold (EventService.update), so it can flag
-- buyers who were within the limit when they bought: read such a row against the event's audit trail (event PATCH)
-- before calling it a breach.
-- Deliberately no holder-cap check (04 POST /fan/orders Holder limit): POS sales and a late success's re-reserve
-- bypass it (plan lot-5 Q173), so a row would not be a breach. Pass ticket orders (pass_id and event_id set) are left
-- out: the pass job issues them without the limit (04 §Passes Tickets), so a holder over it is no breach.
select 'PER_ACCOUNT_LIMIT', o.event_id, o.user_id::text,
       format('places %s > per_account_limit %s', sum(i.places - coalesce(r.n, 0)), e.per_account_limit)
from orders o
join (select order_id, sum(qty) as places from order_items group by order_id) i on i.order_id = o.id
left join (select order_id, count(*) as n from tickets where status = 'REFUNDED' group by order_id) r
  on r.order_id = o.id and o.status = 'PAID'
join events e on e.id = o.event_id and e.organizer_id = o.organizer_id
where o.status in ('RESERVED', 'AWAITING_CASH', 'PAID') and o.user_id is not null and o.pass_id is null
group by o.event_id, o.user_id, e.per_account_limit
having sum(i.places - coalesce(r.n, 0)) > e.per_account_limit

union all

-- A ticket still usable (VALID, BLOCKED) on an order that isn't PAID: tickets are issued only when an order turns
-- PAID (card, wallet, cash code, till, late success), and a whole-order refund (order REFUNDED) refunds them all.
select 'TICKET_NOT_PAID', o.event_id, o.id::text,
       format('%s VALID/BLOCKED tickets on a %s order', count(*), o.status)
from tickets t
join orders o on o.id = t.order_id and o.organizer_id = t.organizer_id
where t.status in ('VALID', 'BLOCKED') and o.status <> 'PAID'
group by o.event_id, o.id, o.status

union all

-- Charged without an order: a SUCCEEDED payment whose order is neither PAID nor REFUNDED and that no
-- payment.refund_required names (a late success refused for SOLD_OUT or PER_ACCOUNT_LIMIT has one).
select 'CHARGED_NO_ORDER', o.event_id, pay.id::text,
       format('SUCCEEDED %s payment on a %s order', pay.method, o.status)
from payments pay
join orders o on o.id = pay.order_id and o.organizer_id = pay.organizer_id
where pay.status = 'SUCCEEDED' and o.status not in ('PAID', 'REFUNDED')
  and not exists (select 1 from audit_log a
                  where a.organizer_id = pay.organizer_id and a.action = 'payment.refund_required'
                    and a.target = pay.id::text)

union all

-- Promo codes (docs/04-api.md §Promo codes): an order's total is its places at list price less its discount. ref =
-- the order id.
select 'ORDER_TOTAL', o.event_id, o.id::text,
       format('total %s, items %s, discount %s', o.total_mad, coalesce(i.gross, 0), o.discount_mad)
from orders o
left join (select organizer_id, order_id, sum(qty * unit_price_mad) as gross from order_items
           group by organizer_id, order_id) i
  on i.order_id = o.id and i.organizer_id = o.organizer_id
where o.total_mad <> coalesce(i.gross, 0) - o.discount_mad

union all

-- Refunds of one order above what it was paid (docs/04-api.md §Promo codes Refunds). ref = the order id.
select 'REFUNDS_OVER_TOTAL', o.event_id, o.id::text, format('refunds %s > total %s', r.amount, o.total_mad)
from orders o
join (select organizer_id, order_id, sum(amount_mad) as amount from refunds group by organizer_id, order_id) r
  on r.order_id = o.id and r.organizer_id = o.organizer_id
where r.amount > o.total_mad

union all

-- A refund of 0 written since V34 (docs/04-api.md refund: a FREE place's refund writes no row). ref = the refund id.
select 'ZERO_REFUND', r.event_id, r.id::text, format('refund of 0 for ticket %s', r.ticket_id)
from refunds r
where r.amount_mad = 0
  and r.requested_at > (select installed_on from flyway_schema_history where version = '34')

union all

-- Till deposits (docs/04-api.md §Finance Deposits): only a closed session goes into a deposit (pos_sessions_deposit
-- refuses it too), and a voided deposit holds no session (its void freed them). No event: ref = the session id.
select 'DEPOSIT_OPEN_SESSION', null::integer, p.id::text, format('open session in deposit %s', p.deposit_id)
from pos_sessions p
where p.deposit_id is not null and p.closed_at is null

union all

select 'DEPOSIT_VOIDED', null::integer, p.id::text, format('session still in voided deposit %s', d.id)
from pos_sessions p
join cash_deposits d on d.id = p.deposit_id and d.organizer_id = p.organizer_id
where d.voided_at is not null

union all

-- Admission out of rank order: an entry admitted without a rank, or past the room's admitted_through.
select 'ADMISSION_ORDER', w.event_id, q.id::text,
       format('admitted with rank %s, admitted_through %s', coalesce(q.rank::text, 'null'), w.admitted_through)
from queue_entries q
join waiting_rooms w on w.id = q.room_id and w.organizer_id = q.organizer_id
where q.admitted_at is not null and (q.rank is null or q.rank > w.admitted_through);
