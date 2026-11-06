package ma.jomhor.sales.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
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
            "id, organizer_id, event_id, status, total_mad, reserved_until, paid_at";

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
            jdbc.sql("""
                            insert into order_items (organizer_id, order_id, price_category_id, qty, unit_price_mad)
                            values (:org, :order, :category, :qty, :price)
                            """)
                    .param("org", org).param("order", order.id()).param("category", item.priceCategoryId())
                    .param("qty", item.qty()).param("price", item.unitPriceMad()).update();
        }
        return order;
    }

    @Override
    public Optional<Order> find(UUID userId, UUID orderId) {
        return jdbc.sql("select " + ORDER_COLUMNS + " from orders where id = :id and user_id = :user")
                .param("id", orderId).param("user", userId)
                .query((rs, n) -> order(rs, null)).optional()
                .map(o -> new Order(o.id(), o.organizerId(), o.eventId(), o.status(), o.totalMad(), o.reservedUntil(),
                        o.paidAt(), items(o.organizerId(), o.id())));
    }

    // ponytail: reads payments (BE-6's table) for the Q8 skip; becomes a payments query once that module exists.
    @Override
    public List<Expired> expireDue(int limit) {
        return jdbc.sql("""
                        with expired as (
                          update orders set status = 'EXPIRED'
                          where id in (
                            select o.id from orders o
                            where o.status = 'RESERVED' and o.reserved_until < now()
                              and not exists (select 1 from payments p
                                              where p.order_id = o.id and p.organizer_id = o.organizer_id
                                                and p.status = 'PENDING' and p.created_at > now() - interval '20 minutes')
                            order by o.reserved_until
                            limit :limit
                            for no key update skip locked)
                          returning id, organizer_id, event_id)
                        select e.organizer_id, e.event_id, i.price_category_id, sum(i.qty) as qty,
                               array_agg(e.id order by e.id) as order_ids
                        from expired e join order_items i on i.order_id = e.id and i.organizer_id = e.organizer_id
                        group by e.organizer_id, e.event_id, i.price_category_id
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Expired(new Hold(rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"),
                        rs.getObject("price_category_id", UUID.class), rs.getInt("qty")),
                        List.of((UUID[]) rs.getArray("order_ids").getArray())))
                .list();
    }

    private List<Order.Item> items(UUID org, UUID orderId) {
        return jdbc.sql("""
                        select price_category_id, qty, unit_price_mad from order_items
                        where organizer_id = :org and order_id = :order order by price_category_id
                        """)
                .param("org", org).param("order", orderId)
                .query((rs, n) -> new Order.Item(rs.getObject("price_category_id", UUID.class), rs.getInt("qty"),
                        rs.getLong("unit_price_mad")))
                .list();
    }

    private static Order order(ResultSet rs, List<Order.Item> items) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"),
                Order.Status.valueOf(rs.getString("status")), rs.getLong("total_mad"), instant(rs, "reserved_until"),
                instant(rs, "paid_at"), items);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
