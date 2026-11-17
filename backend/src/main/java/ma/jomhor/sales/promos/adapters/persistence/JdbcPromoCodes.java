package ma.jomhor.sales.promos.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.promos.application.PromoCodes;
import ma.jomhor.sales.promos.domain.PromoCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id and event_id. */
@Repository
class JdbcPromoCodes implements PromoCodes {

    private static final String COLUMNS = """
            p.id, p.organizer_id, p.event_id, p.price_category_id, p.code, p.kind::text as kind, p.percent,
            p.amount_mad, p.max_uses, p.per_account_max, p.active, p.created_at""";
    // Figures from orders (index on promo_code_id, status); "still held" as /sales' held.
    private static final String LISTED = "select " + COLUMNS + """
            ,
              count(o.id) filter (where o.status in ('RESERVED','AWAITING_CASH') and o.reserved_until > now()) as held,
              count(o.id) filter (where o.status = 'PAID') as paid,
              coalesce(sum(o.discount_mad) filter (where o.status = 'PAID'), 0) as discount_mad
            from promo_codes p
            left join orders o on o.promo_code_id = p.id and o.organizer_id = p.organizer_id
            where p.organizer_id = :org and p.event_id = :event""";
    // Codes are A-Z 0-9: byte order, the same on every database locale.
    private static final String GROUP = " group by p.id order by p.code collate \"C\"";

    private final JdbcClient jdbc;

    JdbcPromoCodes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Listed> list(UUID org, int eventId) {
        return jdbc.sql(LISTED + GROUP).param("org", org).param("event", eventId)
                .query(JdbcPromoCodes::listed).list();
    }

    @Override
    public Optional<Listed> find(UUID org, int eventId, UUID promoId) {
        return jdbc.sql(LISTED + " and p.id = :id" + GROUP).param("org", org).param("event", eventId)
                .param("id", promoId).query(JdbcPromoCodes::listed).optional();
    }

    @Override
    public Optional<PromoCode> insert(UUID org, PromoCode p) {
        return jdbc.sql("""
                        insert into promo_codes as p (organizer_id, event_id, price_category_id, code, kind, percent,
                                                      amount_mad, max_uses, per_account_max, active)
                        values (:org, :event, :category, :code, cast(:kind as promo_kind), :percent, :amount, :maxUses,
                                :perAccount, :active)
                        on conflict (event_id, code) do nothing
                        returning\s""" + COLUMNS)
                .param("org", org).param("event", p.eventId()).param("category", p.priceCategoryId())
                .param("code", p.code()).param("kind", p.kind().name()).param("percent", p.percent())
                .param("amount", p.amountMad()).param("maxUses", p.maxUses()).param("perAccount", p.perAccountMax())
                .param("active", p.active()).query(JdbcPromoCodes::promo).optional();
    }

    @Override
    public void update(UUID org, int eventId, UUID promoId, Boolean active, Integer maxUses, Integer perAccountMax) {
        jdbc.sql("""
                        update promo_codes set active = coalesce(cast(:active as boolean), active),
                          max_uses = coalesce(cast(:maxUses as integer), max_uses),
                          per_account_max = coalesce(cast(:perAccount as smallint), per_account_max)
                        where organizer_id = :org and event_id = :event and id = :id""")
                .param("org", org).param("event", eventId).param("id", promoId).param("active", active)
                .param("maxUses", maxUses).param("perAccount", perAccountMax).update();
    }

    // No key update: an order's FK check (key share) on this row doesn't wait for it; the orders with this code do.
    @Override
    public Optional<PromoCode> lockActive(UUID org, int eventId, String code) {
        return jdbc.sql("select " + COLUMNS + " from promo_codes p where p.organizer_id = :org and p.event_id = :event"
                        + " and p.code = :code and p.active for no key update")
                .param("org", org).param("event", eventId).param("code", code).query(JdbcPromoCodes::promo).optional();
    }

    // Index orders (promo_code_id, status).
    @Override
    public long uses(UUID org, UUID promoId, UUID buyer) {
        return jdbc.sql("""
                        select count(*) from orders
                        where organizer_id = :org and promo_code_id = :promo
                          and status in ('RESERVED', 'AWAITING_CASH', 'PAID')
                          and (cast(:buyer as uuid) is null or user_id = cast(:buyer as uuid))
                        """)
                .param("org", org).param("promo", promoId).param("buyer", buyer).query(Long.class).single();
    }

    private static Listed listed(ResultSet rs, int n) throws SQLException {
        return new Listed(promo(rs, n), rs.getLong("held"), rs.getLong("paid"), rs.getLong("discount_mad"));
    }

    private static PromoCode promo(ResultSet rs, int n) throws SQLException {
        return new PromoCode(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getInt("event_id"), rs.getObject("price_category_id", UUID.class), rs.getString("code"),
                PromoCode.Kind.valueOf(rs.getString("kind")), rs.getObject("percent", Integer.class),
                rs.getObject("amount_mad", Long.class), rs.getObject("max_uses", Integer.class),
                rs.getInt("per_account_max"), rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
