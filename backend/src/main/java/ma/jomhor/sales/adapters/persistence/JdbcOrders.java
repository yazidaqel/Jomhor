package ma.jomhor.sales.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory.Hold;
import ma.jomhor.sales.application.Orders;
import ma.jomhor.sales.application.Orders.Expired;
import ma.jomhor.sales.domain.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** orders and order_items. Items are always joined on the order's organizer_id. */
@Repository
class JdbcOrders implements Orders {

    private static final String ORDER_COLUMNS =
            "id, organizer_id, event_id, user_id, channel, status, total_mad, reserved_until, paid_at, group_order_id";

    private final JdbcClient jdbc;

    JdbcOrders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Transaction-scoped advisory lock on (hash of the user, event): released at commit, after the new order is
    // visible to the next waiter. A hash collision only serialises two unrelated buyers, never lets one through.
    @Override
    public void lockBuyer(UUID userId, int eventId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtext(cast(:user as text)), :event)")
                .param("user", userId.toString()).param("event", eventId).query((rs, n) -> 1).single();
    }

    @Override
    public long held(UUID org, UUID userId, int eventId) {
        // Two sums, each on its own index (orders user_id; orders group_order_id, V20), not one OR over the event.
        return jdbc.sql("select (" + OWN_HELD + """
                             ) + (select coalesce(sum(i.qty), 0)
                                from orders g
                                join orders m on m.group_order_id = g.id and m.organizer_id = g.organizer_id
                                join order_items i on i.order_id = m.id and i.organizer_id = m.organizer_id
                                where g.organizer_id = :org and g.user_id = :user and g.event_id = :event
                                  and m.status in ('RESERVED', 'AWAITING_CASH'))
                        """)
                .param("org", org).param("user", userId).param("event", eventId).query(Long.class).single();
    }

    private static final String OWN_HELD = """
            select coalesce(sum(i.qty), 0)
            from orders o join order_items i on i.order_id = o.id and i.organizer_id = o.organizer_id
            where o.organizer_id = :org and o.user_id = :user and o.event_id = :event
              and o.status in ('RESERVED', 'AWAITING_CASH', 'PAID')
            """;

    @Override
    public long ownHeld(UUID org, UUID userId, int eventId) {
        return jdbc.sql(OWN_HELD).param("org", org).param("user", userId).param("event", eventId).query(Long.class)
                .single();
    }

    @Override
    public Instant now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public Order insert(UUID org, int eventId, UUID userId, Order.Channel channel, long totalMad,
                        List<Order.Item> items) {
        Order order = jdbc.sql("""
                        insert into orders (organizer_id, event_id, user_id, channel, total_mad, reserved_until)
                        values (:org, :event, :user, cast(:channel as sales_channel), :total, now() + interval '8 minutes')
                        returning\s""" + ORDER_COLUMNS)
                .param("org", org).param("event", eventId).param("user", userId).param("channel", channel.name())
                .param("total", totalMad)
                .query((rs, n) -> order(rs, items)).single();
        insertItems(org, order.id(), items);
        return order;
    }

    @Override
    public Order insertMember(UUID org, int eventId, UUID userId, Order.Channel channel, UUID groupOrderId,
                              Instant reservedUntil, long totalMad, List<Order.Item> items) {
        Order order = jdbc.sql("""
                        insert into orders (organizer_id, event_id, user_id, channel, total_mad, reserved_until,
                                            group_order_id)
                        values (:org, :event, :user, cast(:channel as sales_channel), :total, :until, :group)
                        returning\s""" + ORDER_COLUMNS)
                .param("org", org).param("event", eventId).param("user", userId).param("channel", channel.name())
                .param("total", totalMad).param("until", reservedUntil.atOffset(ZoneOffset.UTC))
                .param("group", groupOrderId)
                .query((rs, n) -> order(rs, items)).single();
        insertItems(org, order.id(), items);
        return order;
    }

    // Index orders (group_order_id) where group_order_id is not null (V20).
    @Override
    public List<Order> members(UUID org, UUID groupOrderId) {
        // ponytail: one items read per member (a split has a handful); join them if groups grow.
        return jdbc.sql("select " + ORDER_COLUMNS + " from orders where organizer_id = :org and group_order_id = :group"
                        + " order by id")
                .param("org", org).param("group", groupOrderId)
                .query((rs, n) -> order(rs, null)).list().stream()
                .map(o -> o.withItems(items(o.organizerId(), o.id()))).toList();
    }

    @Override
    public Optional<Order> insertPos(UUID id, UUID org, int eventId, long totalMad, List<Order.Item> items) {
        Optional<Order> order = jdbc.sql("""
                        insert into orders (id, organizer_id, event_id, user_id, channel, status, total_mad, payment_method,
                                            reserved_until, paid_at)
                        values (:id, :org, :event, null, 'POS', 'PAID', :total, 'POS_CASH', now(), now())
                        on conflict (id) do nothing
                        returning\s""" + ORDER_COLUMNS)
                .param("id", id).param("org", org).param("event", eventId).param("total", totalMad)
                .query((rs, n) -> order(rs, items)).optional();
        order.ifPresent(o -> insertItems(org, o.id(), items));
        return order;
    }

    @Override
    public boolean exists(UUID orderId) {
        return jdbc.sql("select exists (select 1 from orders where id = ?)").param(orderId).query(Boolean.class).single();
    }

    @Override
    public Optional<Order> findInOrg(UUID org, UUID orderId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from orders where organizer_id = :org and id = :id")
                .param("org", org).param("id", orderId)
                .query((rs, n) -> order(rs, null)).optional()
                .map(o -> o.withItems(items(o.organizerId(), o.id())));
    }

    private void insertItems(UUID org, UUID orderId, List<Order.Item> items) {
        for (Order.Item item : items) {
            // holders as [{"name", "user_id"}] in place order, built by Postgres from two parallel arrays.
            jdbc.sql("""
                            insert into order_items (organizer_id, order_id, price_category_id, qty, unit_price_mad, holders)
                            values (:org, :order, :category, :qty, :price,
                                    (select coalesce(jsonb_agg(jsonb_build_object('name', h.name, 'user_id', h.user_id)
                                                               order by h.n), '[]'::jsonb)
                                     from unnest(cast(:names as text[]), cast(:users as uuid[])) with ordinality
                                          as h(name, user_id, n)))
                            """)
                    .param("org", org).param("order", orderId).param("category", item.priceCategoryId())
                    .param("qty", item.qty()).param("price", item.unitPriceMad())
                    .param("names", item.holders().stream().map(Order.Holder::name).toArray(String[]::new))
                    .param("users", item.holders().stream().map(Order.Holder::userId).toArray(UUID[]::new))
                    .update();
        }
    }

    @Override
    public long activeHolds(UUID org, int eventId) {
        return jdbc.sql("""
                        select count(*) from orders
                        where organizer_id = :org and event_id = :event and status = 'RESERVED' and reserved_until > now()
                        """)
                .param("org", org).param("event", eventId).query(Long.class).single();
    }

    @Override
    public Optional<Order.Channel> channel(UUID org, UUID orderId) {
        return jdbc.sql("select channel::text from orders where organizer_id = :org and id = :id")
                .param("org", org).param("id", orderId).query(String.class).optional().map(Order.Channel::valueOf);
    }

    @Override
    public Optional<Order> find(UUID userId, UUID orderId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from orders where id = :id and user_id = :user")
                .param("id", orderId).param("user", userId)
                .query((rs, n) -> order(rs, null)).optional()
                .map(o -> o.withItems(items(o.organizerId(), o.id())));
    }

    @Override
    public Optional<Locked> lock(UUID orderId) {
        return jdbc.sql("select " + ORDER_COLUMNS + ", reserved_until > now() as holding from orders where id = :id"
                        + " for no key update")
                .param("id", orderId)
                .query((rs, n) -> new Locked(order(rs, null), rs.getBoolean("holding"))).optional()
                .map(l -> new Locked(l.order().withItems(items(l.order().organizerId(), l.order().id())), l.holding()));
    }

    @Override
    public void markPaid(UUID org, UUID orderId, Order.PaymentMethod method, String paymentRef) {
        jdbc.sql("""
                        update orders set status = 'PAID', paid_at = now(), payment_method = cast(:method as payment_method),
                          payment_ref = :ref
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", orderId).param("method", method.name()).param("ref", paymentRef).update();
    }

    @Override
    public Optional<Instant> awaitCash(UUID org, UUID orderId, Instant salesCloseAt, Duration minWindow) {
        return jdbc.sql("""
                        update orders set status = 'AWAITING_CASH',
                          reserved_until = least(now() + interval '24 hours', :close)
                        where organizer_id = :org and id = :id and status = 'RESERVED'
                          and least(now() + interval '24 hours', :close) >= now() + make_interval(secs => :min)
                        returning reserved_until
                        """)
                .param("org", org).param("id", orderId).param("close", salesCloseAt.atOffset(ZoneOffset.UTC))
                .param("min", minWindow.toSeconds())
                .query((rs, n) -> instant(rs, "reserved_until")).optional();
    }

    @Override
    public void mirrorMembers(UUID org, UUID orderId, Instant until) {
        jdbc.sql("""
                        update orders set reserved_until = :until
                        where organizer_id = :org and group_order_id = :id and status = 'RESERVED'
                        """)
                .param("org", org).param("id", orderId).param("until", until.atOffset(ZoneOffset.UTC)).update();
    }

    @Override
    public List<UUID> extendMembers(UUID org, UUID orderId, Duration hold, Instant salesCloseAt) {
        // least() skips a null cutoff (an event without one is never on sale, so never paid here).
        return jdbc.sql("""
                        update orders set reserved_until = least(now() + make_interval(secs => :hold), :close)
                        where organizer_id = :org and group_order_id = :id and status = 'RESERVED'
                        returning id
                        """)
                .param("org", org).param("id", orderId).param("hold", hold.toSeconds())
                .param("close", salesCloseAt == null ? null : salesCloseAt.atOffset(ZoneOffset.UTC))
                .query(UUID.class).list();
    }

    @Override
    public void lockMembers(UUID org, UUID orderId) {
        jdbc.sql("""
                        select id from orders
                        where organizer_id = :org and group_order_id = :id and status = 'RESERVED'
                        order by id
                        for no key update
                        """)
                .param("org", org).param("id", orderId).query(UUID.class).list();
    }

    @Override
    public boolean holdsFor(UUID org, UUID orderId, Duration left) {
        return jdbc.sql("""
                        select exists (select 1 from orders
                                       where organizer_id = :org and id = :id and status in ('RESERVED', 'AWAITING_CASH')
                                         and reserved_until > now() + make_interval(secs => :left))
                        """)
                .param("org", org).param("id", orderId).param("left", left.toSeconds()).query(Boolean.class).single();
    }

    @Override
    public long cashHeld(UUID org, int eventId) {
        return jdbc.sql("""
                        select coalesce(sum(i.qty), 0)
                        from orders o join order_items i on i.order_id = o.id and i.organizer_id = o.organizer_id
                        where o.organizer_id = :org and o.event_id = :event and o.status = 'AWAITING_CASH'
                          and o.reserved_until > now()
                        """)
                .param("org", org).param("event", eventId).query(Long.class).single();
    }

    @Override
    public List<Listed> listed(UUID userId, int limit) {
        return jdbc.sql("""
                        select id, organizer_id, event_id, channel, status, total_mad, reserved_until, created_at,
                               case when group_order_id is not null then 'MEMBER'
                                    when exists (select 1 from orders m
                                                 where m.organizer_id = o.organizer_id and m.group_order_id = o.id)
                                    then 'INITIATOR' end as group_role
                        from orders o where user_id = :user
                        order by (status = 'AWAITING_CASH'
                                  or (group_order_id is not null and status = 'RESERVED' and reserved_until > now())) desc,
                                 created_at desc, id desc
                        limit :limit
                        """)
                .param("user", userId).param("limit", limit)
                .query((rs, n) -> new Listed(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                        rs.getInt("event_id"), Order.Channel.valueOf(rs.getString("channel")),
                        Order.Status.valueOf(rs.getString("status")), rs.getLong("total_mad"),
                        instant(rs, "reserved_until"), instant(rs, "created_at"),
                        rs.getString("group_role") == null ? null : Order.GroupRole.valueOf(rs.getString("group_role"))))
                .list();
    }

    @Override
    public List<UUID> claimDue(int limit, Collection<UUID> exclude) {
        return jdbc.sql("""
                        select id from orders
                        where status in ('RESERVED', 'AWAITING_CASH') and reserved_until < now()
                          and not (id = any(:exclude))
                          and (group_order_id is null
                               or not exists (select 1 from orders g where g.id = orders.group_order_id
                                              and g.status in ('RESERVED', 'AWAITING_CASH')))
                        order by reserved_until
                        limit :limit
                        for no key update skip locked
                        """)
                .param("exclude", exclude.toArray(UUID[]::new)).param("limit", limit)
                .query(UUID.class).list();
    }

    @Override
    public List<UUID> claimMembers(Collection<UUID> buyerOrderIds, Collection<UUID> exclude) {
        if (buyerOrderIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select id from orders
                        where group_order_id = any(:buyers) and status = 'RESERVED' and not (id = any(:exclude))
                        order by id
                        for no key update skip locked
                        """)
                .param("buyers", buyerOrderIds.toArray(UUID[]::new)).param("exclude", exclude.toArray(UUID[]::new))
                .query(UUID.class).list();
    }

    @Override
    public List<Expired> expire(Collection<UUID> orderIds) {
        return jdbc.sql("""
                        with expired as (
                          update orders set status = 'EXPIRED'
                          where id = any(:ids) and status in ('RESERVED', 'AWAITING_CASH')
                          returning id, organizer_id, event_id)
                        select e.organizer_id, e.event_id, i.price_category_id, sum(i.qty) as qty,
                               array_agg(e.id order by e.id) as order_ids
                        from expired e join order_items i on i.order_id = e.id and i.organizer_id = e.organizer_id
                        group by e.organizer_id, e.event_id, i.price_category_id
                        """)
                .param("ids", orderIds.toArray(UUID[]::new))
                .query((rs, n) -> new Expired(new Hold(rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"),
                        rs.getObject("price_category_id", UUID.class), rs.getInt("qty")),
                        List.of((UUID[]) rs.getArray("order_ids").getArray())))
                .list();
    }

    private List<Order.Item> items(UUID org, UUID orderId) {
        return jdbc.sql("""
                        select price_category_id, qty, unit_price_mad,
                               array(select h->>'name' from jsonb_array_elements(holders) with ordinality as e(h, n)
                                     order by n) as names,
                               array(select cast(h->>'user_id' as uuid) from jsonb_array_elements(holders)
                                     with ordinality as e(h, n) order by n) as users
                        from order_items
                        where organizer_id = :org and order_id = :order order by price_category_id
                        """)
                .param("org", org).param("order", orderId)
                .query((rs, n) -> new Order.Item(rs.getObject("price_category_id", UUID.class), rs.getInt("qty"),
                        rs.getLong("unit_price_mad"), holders(rs)))
                .list();
    }

    private static List<Order.Holder> holders(ResultSet rs) throws SQLException {
        String[] names = (String[]) rs.getArray("names").getArray();
        UUID[] users = (UUID[]) rs.getArray("users").getArray();
        List<Order.Holder> holders = new ArrayList<>(names.length);
        for (int i = 0; i < names.length; i++) {
            holders.add(new Order.Holder(names[i], users[i]));
        }
        return holders;
    }

    private static Order order(ResultSet rs, List<Order.Item> items) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"),
                rs.getObject("user_id", UUID.class), Order.Channel.valueOf(rs.getString("channel")),
                Order.Status.valueOf(rs.getString("status")), rs.getLong("total_mad"),
                instant(rs, "reserved_until"), instant(rs, "paid_at"), rs.getObject("group_order_id", UUID.class),
                items);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    @Override
    public List<SalesLine> salesLines(UUID org, int eventId) {
        return jdbc.sql("""
                        select i.price_category_id, o.channel::text as channel, o.payment_method::text as payment_method,
                               coalesce(sum(i.qty) filter (where o.status = 'PAID'), 0) as paid,
                               coalesce(sum(i.qty) filter (where o.status = 'RESERVED' and o.reserved_until > now()), 0) as held,
                               coalesce(sum(i.qty) filter (where o.status = 'AWAITING_CASH' and o.reserved_until > now()), 0)
                                   as awaiting_cash,
                               coalesce(sum(i.qty * i.unit_price_mad) filter (where o.status = 'PAID'), 0) as revenue_mad
                        from orders o join order_items i on i.order_id = o.id and i.organizer_id = o.organizer_id
                        where o.organizer_id = :org and o.event_id = :event
                          and o.status in ('PAID', 'RESERVED', 'AWAITING_CASH')
                        group by i.price_category_id, o.channel, o.payment_method
                        """)
                .param("org", org).param("event", eventId)
                .query((rs, n) -> new SalesLine(rs.getObject("price_category_id", UUID.class), rs.getString("channel"),
                        rs.getString("payment_method"), rs.getLong("paid"), rs.getLong("held"),
                        rs.getLong("awaiting_cash"), rs.getLong("revenue_mad")))
                .list();
    }

    // audit_log (common) written by PaymentService.refundRequired, whose payload keys this reads; the event comes
    // from the order the payload names. Index audit_log (organizer_id, action, at), V10.
    @Override
    public List<RefundRequired> refundsRequired(UUID org, int eventId) {
        return jdbc.sql("""
                        select a.payload ->> 'payment_id' as payment_id, o.id as order_id,
                               cast(a.payload ->> 'amount_mad' as bigint) as amount_mad, a.payload ->> 'reason' as reason, a.at
                        from audit_log a
                        join orders o on o.organizer_id = a.organizer_id and o.id = cast(a.payload ->> 'order_id' as uuid)
                        where a.organizer_id = :org and a.action = 'payment.refund_required' and o.event_id = :event
                        order by a.at desc, a.id desc
                        """)
                .param("org", org).param("event", eventId)
                .query((rs, n) -> new RefundRequired(UUID.fromString(rs.getString("payment_id")),
                        rs.getObject("order_id", UUID.class), rs.getLong("amount_mad"), rs.getString("reason"),
                        instant(rs, "at")))
                .list();
    }
}
