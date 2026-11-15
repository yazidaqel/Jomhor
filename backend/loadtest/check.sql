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

-- A PAID order whose tickets (transferred-away ones left out) aren't its places.
select 'TICKETS_PLACES', o.event_id, o.id::text,
       format('tickets %s, places %s', coalesce(t.n, 0), coalesce(i.places, 0))
from orders o
left join (select order_id, sum(qty) as places from order_items group by order_id) i on i.order_id = o.id
left join (select tk.order_id, count(*) as n
           from tickets tk
           where not exists (select 1 from ticket_transfers x where x.ticket_id = tk.id)
           group by tk.order_id) t on t.order_id = o.id
where o.status = 'PAID' and coalesce(t.n, 0) <> coalesce(i.places, 0)

union all

-- An account holding more than per_account_limit places of an event (RESERVED, AWAITING_CASH, PAID: the
-- POST /fan/orders count). A late success reviving an EXPIRED order is counted on purpose: OrderService.reReserve
-- re-checks this same count under the buyer lock and refuses (payment.refund_required PER_ACCOUNT_LIMIT, the order
-- stays EXPIRED), as 04 §Late success lists. Lowering per_account_limit while ON_SALE is not checked against what was
-- already sold (EventService.update), so it can flag buyers who were within the limit when they bought: read such a
-- row against the event's audit trail (event PATCH) before calling it a breach.
select 'PER_ACCOUNT_LIMIT', o.event_id, o.user_id::text,
       format('places %s > per_account_limit %s', sum(i.qty), e.per_account_limit)
from orders o
join order_items i on i.order_id = o.id and i.organizer_id = o.organizer_id
join events e on e.id = o.event_id and e.organizer_id = o.organizer_id
where o.status in ('RESERVED', 'AWAITING_CASH', 'PAID') and o.user_id is not null
group by o.event_id, o.user_id, e.per_account_limit
having sum(i.qty) > e.per_account_limit

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

-- Admission out of rank order: an entry admitted without a rank, or past the room's admitted_through.
select 'ADMISSION_ORDER', w.event_id, q.id::text,
       format('admitted with rank %s, admitted_through %s', coalesce(q.rank::text, 'null'), w.admitted_through)
from queue_entries q
join waiting_rooms w on w.id = q.room_id and w.organizer_id = q.organizer_id
where q.admitted_at is not null and (q.rank is null or q.rank > w.admitted_through);
