package ma.jomhor.sales.products.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.products.application.Passes;
import ma.jomhor.sales.products.domain.Pass;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** passes; joined to orders, products and tickets on organizer_id. */
@Repository
class JdbcPasses implements Passes {

    private static final String COLUMNS =
            "p.id, p.organizer_id, p.product_id, p.series_id, p.user_id, p.holder_name, p.status::text as status, p.created_at";

    private final JdbcClient jdbc;

    JdbcPasses(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Two-int key (hashtext(user), hashtext(series)), common.EventLock's list: a hash collision only serialises.
    @Override
    public void lockAccount(UUID userId, UUID seriesId) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(hashtext(cast(:user as text)), hashtext(cast(:series as text)))")
                .param("user", userId.toString()).param("series", seriesId.toString()).query(Integer.class).single();
    }

    @Override
    public Optional<Pass> live(UUID org, UUID seriesId, UUID userId) {
        return jdbc.sql("select " + COLUMNS + " from passes p where p.organizer_id = :org and p.series_id = :series"
                        + " and p.user_id = :user and p.status <> 'CANCELLED'")
                .param("org", org).param("series", seriesId).param("user", userId).query(JdbcPasses::pass).optional();
    }

    @Override
    public Optional<Pass> find(UUID org, UUID passId) {
        return jdbc.sql("select " + COLUMNS + " from passes p where p.organizer_id = :org and p.id = :id")
                .param("org", org).param("id", passId).query(JdbcPasses::pass).optional();
    }

    @Override
    public Pass insert(UUID org, UUID productId, UUID seriesId, UUID userId, String holderName) {
        return jdbc.sql("""
                        insert into passes as p (organizer_id, product_id, series_id, user_id, holder_name)
                        values (:org, :product, :series, :user, :name)
                        returning\s""" + COLUMNS)
                .param("org", org).param("product", productId).param("series", seriesId).param("user", userId)
                .param("name", holderName).query(JdbcPasses::pass).single();
    }

    @Override
    public boolean activate(UUID org, UUID passId, Pass.Status from) {
        return jdbc.sql("update passes set status = 'ACTIVE' where organizer_id = :org and id = :id"
                        + " and status = cast(:from as pass_status)")
                .param("org", org).param("id", passId).param("from", from.name()).update() == 1;
    }

    // Guarded by status PENDING: an order expires once (its row is claimed and flipped under lock), and an installment
    // order (3b) of an ACTIVE pass cancels nothing.
    private static final String EXPIRED_PENDING = """
            from passes p join orders o on o.pass_id = p.id and o.organizer_id = p.organizer_id
            where o.id = any(:ids) and o.event_id is null and p.status = 'PENDING'
            """;

    @Override
    public void cancelExpired(Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return;
        }
        UUID[] ids = orderIds.toArray(UUID[]::new);
        jdbc.sql("select pr.id from products pr where pr.id in (select p.product_id " + EXPIRED_PENDING + ")"
                        + " order by pr.id for no key update")
                .param("ids", ids).query(UUID.class).list();
        jdbc.sql("""
                        with cancelled as (
                          update passes set status = 'CANCELLED'
                          where id in (select p.id\s""" + EXPIRED_PENDING + """
                          ) returning organizer_id, product_id)
                        update products pr set sold = pr.sold - c.n
                        from (select organizer_id, product_id, count(*) as n from cancelled group by 1, 2) c
                        where pr.organizer_id = c.organizer_id and pr.id = c.product_id
                        """)
                .param("ids", ids).update();
    }

    // ponytail: no index on passes (user_id) in 03 (the unique index leads with series_id): a scan of passes per call,
    // fine for a club's few thousand; add one if passes grow.
    @Override
    public List<Owned> owned(UUID userId) {
        return jdbc.sql("select " + COLUMNS + """
                        , case when p.status = 'PENDING' then
                            (select o.id from orders o
                             where o.organizer_id = p.organizer_id and o.pass_id = p.id and o.event_id is null
                             order by o.created_at, o.id limit 1) end as order_id
                        from passes p where p.user_id = :user and p.status <> 'CANCELLED'
                        order by p.created_at desc, p.id desc
                        """)
                .param("user", userId)
                .query((rs, n) -> new Owned(pass(rs, n), rs.getObject("order_id", UUID.class))).list();
    }

    @Override
    public Map<Integer, UUID> tickets(UUID org, UUID passId, UUID holderId) {
        Map<Integer, UUID> byEvent = new HashMap<>();
        jdbc.sql("""
                        select o.event_id, t.id
                        from orders o join tickets t on t.order_id = o.id and t.organizer_id = o.organizer_id
                        where o.organizer_id = :org and o.pass_id = :pass and o.event_id is not null
                          and t.holder_user_id = :holder and t.status <> 'CANCELLED'
                        order by t.issued_at, t.id
                        """)
                .param("org", org).param("pass", passId).param("holder", holderId)
                .query((rs, n) -> byEvent.put(rs.getInt("event_id"), rs.getObject("id", UUID.class))).list();
        return byEvent;
    }

    private static final long LOCK_CLASS = 8; // common.EventLock's list

    @Override
    public boolean tryLockEvent(int eventId) {
        return jdbc.sql("select pg_try_advisory_xact_lock(?)").param((LOCK_CLASS << 32) | (eventId & 0xFFFFFFFFL))
                .query(Boolean.class).single();
    }

    // ponytail: no index on passes (series_id, status) beyond the unique (series_id, user_id) one, which serves the
    // series filter; fine for a club's few thousand passes.
    @Override
    public List<Due> due(UUID org, UUID seriesId, int eventId, Collection<Integer> zoneIds, int limit) {
        return jdbc.sql("""
                        select p.id, p.user_id, p.holder_name, pr.zone_id
                        from passes p join products pr on pr.id = p.product_id and pr.organizer_id = p.organizer_id
                        where p.organizer_id = :org and p.series_id = :series and p.status = 'ACTIVE'
                          and pr.zone_id = any(:zones)
                          and not exists (select 1 from orders o
                                          where o.organizer_id = p.organizer_id and o.pass_id = p.id
                                            and o.event_id = :event)
                        order by p.created_at, p.id
                        limit :limit
                        """)
                .param("org", org).param("series", seriesId).param("event", eventId)
                .param("zones", zoneIds.toArray(Integer[]::new)).param("limit", limit)
                .query((rs, n) -> new Due(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getString("holder_name"), rs.getInt("zone_id")))
                .list();
    }

    @Override
    public Map<Pass.Status, Long> countByStatus(UUID org, UUID productId) {
        Map<Pass.Status, Long> counts = new EnumMap<>(Pass.Status.class);
        jdbc.sql("""
                        select status::text as status, count(*) as n from passes
                        where organizer_id = :org and product_id = :product and status <> 'CANCELLED'
                        group by status
                        """)
                .param("org", org).param("product", productId)
                .query((rs, n) -> counts.put(Pass.Status.valueOf(rs.getString("status")), rs.getLong("n"))).list();
        return counts;
    }

    @Override
    public Map<Integer, Long> ticketOrdersByEvent(UUID org, UUID productId) {
        Map<Integer, Long> byEvent = new HashMap<>();
        jdbc.sql("""
                        select o.event_id, count(*) as n
                        from orders o join passes p on p.id = o.pass_id and p.organizer_id = o.organizer_id
                        where o.organizer_id = :org and p.product_id = :product and o.event_id is not null
                        group by o.event_id
                        """)
                .param("org", org).param("product", productId)
                .query((rs, n) -> byEvent.put(rs.getInt("event_id"), rs.getLong("n"))).list();
        return byEvent;
    }

    private static Pass pass(ResultSet rs, int n) throws SQLException {
        return new Pass(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getObject("product_id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("holder_name"),
                Pass.Status.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
