package ma.jomhor.sales.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
            "id, organizer_id, event_id, user_id, channel, status, total_mad, reserved_until, paid_at";

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
        return jdbc.sql("""
                        select coalesce(sum(i.qty), 0)
                        from orders o join order_items i on i.order_id = o.id and i.organizer_id = o.organizer_id
                        where o.organizer_id = :org and o.user_id = :user and o.event_id = :event
                          and o.status in ('RESERVED', 'AWAITING_CASH', 'PAID')
                        """)
                .param("org", org).param("user", userId).param("event", eventId).query(Long.class).single();
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
                    .param("org", org).param("order", order.id()).param("category", item.priceCategoryId())
                    .param("qty", item.qty()).param("price", item.unitPriceMad())
                    .param("names", item.holders().stream().map(Order.Holder::name).toArray(String[]::new))
                    .param("users", item.holders().stream().map(Order.Holder::userId).toArray(UUID[]::new))
                    .update();
        }
        return order;
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
    public void markPaid(UUID org, UUID orderId, String paymentRef) {
        jdbc.sql("""
                        update orders set status = 'PAID', paid_at = now(), payment_method = 'CARD', payment_ref = :ref
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", orderId).param("ref", paymentRef).update();
    }

    @Override
    public List<UUID> claimDue(int limit, Collection<UUID> exclude) {
        return jdbc.sql("""
                        select id from orders
                        where status = 'RESERVED' and reserved_until < now() and not (id = any(:exclude))
                        order by reserved_until
                        limit :limit
                        for no key update skip locked
                        """)
                .param("exclude", exclude.toArray(UUID[]::new)).param("limit", limit)
                .query(UUID.class).list();
    }

    @Override
    public List<Expired> expire(Collection<UUID> orderIds) {
        return jdbc.sql("""
                        with expired as (
                          update orders set status = 'EXPIRED'
                          where id = any(:ids) and status = 'RESERVED'
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
                instant(rs, "reserved_until"), instant(rs, "paid_at"), items);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
