package ma.jomhor.sales.products.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.products.application.Passes;
import ma.jomhor.sales.products.domain.Installment;
import ma.jomhor.sales.products.domain.Pass;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** passes; joined to orders, products and tickets on organizer_id. */
@Repository
class JdbcPasses implements Passes {

    private static final String COLUMNS =
            "p.id, p.organizer_id, p.product_id, p.series_id, p.user_id, p.holder_name, p.status::text as status, p.suspended_at,"
            + " p.created_at";

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
    public Optional<Pass> lock(UUID org, UUID passId) {
        return jdbc.sql("select " + COLUMNS + " from passes p where p.organizer_id = :org and p.id = :id"
                        + " for no key update")
                .param("org", org).param("id", passId).query(JdbcPasses::pass).optional();
    }

    @Override
    public Optional<Pass> lockHeld(UUID userId, UUID passId) {
        return jdbc.sql("select " + COLUMNS + " from passes p where p.user_id = :user and p.id = :id for no key update")
                .param("user", userId).param("id", passId).query(JdbcPasses::pass).optional();
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

    @Override
    public void schedule(UUID org, UUID passId, List<Long> amounts, Duration interval) {
        jdbc.sql("""
                        insert into pass_installments (organizer_id, pass_id, seq, amount_mad, due_at)
                        select p.organizer_id, p.id, s.seq, s.amount,
                               p.created_at + (s.seq - 1) * make_interval(secs => :secs)
                        from passes p, unnest(cast(:amounts as bigint[])) with ordinality as s(amount, seq)
                        where p.organizer_id = :org and p.id = :pass
                        """)
                .param("org", org).param("pass", passId).param("amounts", amounts.toArray(Long[]::new))
                .param("secs", interval.toSeconds()).update();
    }

    @Override
    public List<Installment> installments(UUID org, UUID passId) {
        return jdbc.sql("""
                        select id, pass_id, seq, amount_mad, due_at, order_id, paid_at from pass_installments
                        where organizer_id = :org and pass_id = :pass order by seq
                        """)
                .param("org", org).param("pass", passId)
                .query((rs, n) -> new Installment(rs.getObject("id", UUID.class), rs.getObject("pass_id", UUID.class),
                        rs.getInt("seq"), rs.getLong("amount_mad"), instant(rs, "due_at"),
                        rs.getObject("order_id", UUID.class), instant(rs, "paid_at")))
                .list();
    }

    @Override
    public boolean pay(UUID org, UUID installmentId, UUID orderId) {
        return jdbc.sql("update pass_installments set order_id = :order, paid_at = now()"
                        + " where organizer_id = :org and id = :id and paid_at is null")
                .param("org", org).param("id", installmentId).param("order", orderId).update() == 1;
    }

    @Override
    public Optional<UUID> purchaseOrder(UUID org, UUID passId) {
        return jdbc.sql("select id from orders where organizer_id = :org and pass_id = :pass and event_id is null"
                        + " order by created_at, id limit 1")
                .param("org", org).param("pass", passId).query(UUID.class).optional();
    }

    @Override
    public Optional<UUID> liveOrder(UUID org, UUID passId) {
        return jdbc.sql("""
                        select id from orders
                        where organizer_id = :org and pass_id = :pass and event_id is null
                          and status in ('RESERVED', 'AWAITING_CASH') and reserved_until > now()
                        order by created_at desc, id desc limit 1
                        """)
                .param("org", org).param("pass", passId).query(UUID.class).optional();
    }

    @Override
    public boolean reactivate(UUID org, UUID passId, Duration grace) {
        return jdbc.sql("""
                        update passes p set status = 'ACTIVE', suspended_at = null
                        where p.organizer_id = :org and p.id = :id and p.status = 'SUSPENDED'
                          and not exists (select 1 from pass_installments i
                                          where i.organizer_id = p.organizer_id and i.pass_id = p.id
                                            and i.paid_at is null and i.due_at + make_interval(secs => :secs) <= now())
                        """)
                .param("org", org).param("id", passId).param("secs", grace.toSeconds()).update() == 1;
    }

    // ACTIVE passes with an installment unpaid past its grace at :now (the partial index on due_at where unpaid).
    private static final String OVERDUE = """
            p.status = 'ACTIVE' and (p.organizer_id, p.id) in (
              select i.organizer_id, i.pass_id from pass_installments i
              where i.paid_at is null and i.due_at <= :now - make_interval(secs => :secs))
            """;

    // Two statements: lock what is overdue (skip locked: a settle paying it holds the pass row), then suspend it on a
    // fresh snapshot, so an installment paid between the two is seen. Another instance skips or re-reads the rows.
    @Override
    public List<Suspended> suspend(Instant now, Duration grace) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        UUID[] ids = jdbc.sql("select p.id from passes p where " + OVERDUE + " for no key update skip locked")
                .param("now", at).param("secs", grace.toSeconds()).query(UUID.class).list().toArray(UUID[]::new);
        if (ids.length == 0) {
            return List.of();
        }
        return jdbc.sql("""
                        with s as (
                          update passes p set status = 'SUSPENDED', suspended_at = :now
                          where p.id = any(:ids) and\s""" + OVERDUE + """
                          returning p.organizer_id, p.id)
                        select s.organizer_id, s.id,
                               (select min(i.seq) from pass_installments i
                                where i.organizer_id = s.organizer_id and i.pass_id = s.id and i.paid_at is null
                                  and i.due_at <= :now - make_interval(secs => :secs)) as seq
                        from s order by s.id
                        """)
                .param("ids", ids).param("now", at).param("secs", grace.toSeconds())
                .query((rs, n) -> new Suspended(rs.getObject("organizer_id", UUID.class),
                        rs.getObject("id", UUID.class), rs.getInt("seq")))
                .list();
    }

    // One statement: per pass, its earliest installment due for a reminder (distinct on), locked skip locked (another
    // instance's run or a payment holds it; for update can't sit beside distinct, hence the nesting); the update
    // re-checks reminded_at and paid_at on the latest row version, so each installment is reminded once.
    @Override
    public List<Reminded> remind(Instant now, Duration before) {
        return jdbc.sql("""
                        update pass_installments set reminded_at = :now
                        where id in (select l.id from pass_installments l
                                     where l.id in (select distinct on (i.pass_id) i.id from pass_installments i
                                                    join passes p on p.organizer_id = i.organizer_id
                                                                 and p.id = i.pass_id
                                                    where i.paid_at is null and i.seq > 1 and i.reminded_at is null
                                                      and i.due_at > :now
                                                      and i.due_at <= :now + make_interval(secs => :secs)
                                                      and p.status in ('ACTIVE', 'SUSPENDED')
                                                    order by i.pass_id, i.seq)
                                     for update skip locked)
                          and reminded_at is null and paid_at is null
                        returning organizer_id, pass_id
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC)).param("secs", before.toSeconds())
                .query((rs, n) -> new Reminded(rs.getObject("organizer_id", UUID.class),
                        rs.getObject("pass_id", UUID.class)))
                .list();
    }

    @Override
    public Optional<Installment> remindedAt(UUID org, UUID passId, Instant at) {
        return jdbc.sql("""
                        select id, pass_id, seq, amount_mad, due_at, order_id, paid_at from pass_installments
                        where organizer_id = :org and pass_id = :pass and reminded_at = :at and paid_at is null
                        order by seq limit 1
                        """)
                .param("org", org).param("pass", passId).param("at", at.atOffset(ZoneOffset.UTC))
                .query((rs, n) -> new Installment(rs.getObject("id", UUID.class), rs.getObject("pass_id", UUID.class),
                        rs.getInt("seq"), rs.getLong("amount_mad"), instant(rs, "due_at"),
                        rs.getObject("order_id", UUID.class), instant(rs, "paid_at")))
                .optional();
    }

    @Override
    public InstallmentFigures installmentFigures(UUID org, UUID productId) {
        return jdbc.sql("""
                        select count(*) filter (where i.paid_at is null and i.due_at < now()) as overdue,
                               coalesce(sum(i.amount_mad) filter (where i.paid_at is null), 0) as due,
                               coalesce(sum(i.amount_mad) filter (where i.paid_at is not null), 0) as collected
                        from pass_installments i
                        join passes p on p.id = i.pass_id and p.organizer_id = i.organizer_id
                        where i.organizer_id = :org and p.product_id = :product and p.status in ('ACTIVE', 'SUSPENDED')
                        """)
                .param("org", org).param("product", productId)
                .query((rs, n) -> new InstallmentFigures(rs.getLong("overdue"), rs.getLong("due"),
                        rs.getLong("collected")))
                .single();
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
                Pass.Status.valueOf(rs.getString("status")), instant(rs, "suspended_at"), instant(rs, "created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
