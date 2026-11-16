package ma.jomhor.sales.products.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.sales.products.application.Products;
import ma.jomhor.sales.products.domain.Product;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every organizer statement filters on organizer_id; the public reads return the row's organizer_id. */
@Repository
class JdbcProducts implements Products {

    private static final String COLUMNS = """
            id, organizer_id, kind::text as kind, name, price_mad, on_sale, sales_close_at, quota, sold, series_id,
            zone_id, valid_until, max_installments, created_at""";
    // kind in its enum order (PASS, MEMBERSHIP: the column, not the text alias); names byte-wise, the same on every
    // database locale.
    private static final String ORDER = " order by products.kind, name collate \"C\", id";
    private static final String KIND = " and (cast(:kind as product_kind) is null or kind = cast(:kind as product_kind))";

    private final JdbcClient jdbc;

    JdbcProducts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Product> list(UUID org, Product.Kind kind) {
        return jdbc.sql("select " + COLUMNS + " from products where organizer_id = :org" + KIND + ORDER)
                .param("org", org).param("kind", kind == null ? null : kind.name())
                .query(JdbcProducts::product).list();
    }

    @Override
    public Optional<Product> find(UUID org, UUID productId) {
        return jdbc.sql("select " + COLUMNS + " from products where organizer_id = :org and id = :id")
                .param("org", org).param("id", productId).query(JdbcProducts::product).optional();
    }

    @Override
    public Optional<Product> lock(UUID org, UUID productId) {
        return jdbc.sql("select " + COLUMNS + " from products where organizer_id = :org and id = :id for no key update")
                .param("org", org).param("id", productId).query(JdbcProducts::product).optional();
    }

    @Override
    public Product insert(UUID org, Product p) {
        return jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, on_sale, sales_close_at, quota,
                                              series_id, zone_id, valid_until, max_installments)
                        values (:org, cast(:kind as product_kind), :name, :price, :onSale, :close, :quota, :series,
                                :zone, :validUntil, :installments)
                        returning\s""" + COLUMNS)
                .param("org", org).param("kind", p.kind().name()).param("name", p.name()).param("price", p.priceMad())
                .param("onSale", p.onSale()).param("close", ts(p.salesCloseAt())).param("quota", p.quota())
                .param("series", p.seriesId()).param("zone", p.zoneId()).param("validUntil", ts(p.validUntil()))
                .param("installments", p.maxInstallments()).query(JdbcProducts::product).single();
    }

    // sold <= quota in the same statement: a concurrent pass purchase can't slip between the check and the write.
    @Override
    public Optional<Product> update(UUID org, Product p) {
        return jdbc.sql("""
                        update products set name = :name, price_mad = :price, quota = :quota, on_sale = :onSale,
                          sales_close_at = :close, valid_until = :validUntil, max_installments = :installments
                        where organizer_id = :org and id = :id
                          and (cast(:quota as integer) is null or sold <= cast(:quota as integer))
                        returning\s""" + COLUMNS)
                .param("org", org).param("id", p.id()).param("name", p.name()).param("price", p.priceMad())
                .param("quota", p.quota()).param("onSale", p.onSale()).param("close", ts(p.salesCloseAt()))
                .param("validUntil", ts(p.validUntil())).param("installments", p.maxInstallments())
                .query(JdbcProducts::product).optional();
    }

    @Override
    public List<Product> onSale(UUID org, Product.Kind kind) {
        return jdbc.sql("select " + COLUMNS + " from products where organizer_id = :org and on_sale" + KIND + ORDER)
                .param("org", org).param("kind", kind == null ? null : kind.name())
                .query(JdbcProducts::product).list();
    }

    @Override
    public Optional<Product> onSale(UUID productId) {
        return jdbc.sql("select " + COLUMNS + " from products where id = :id and on_sale")
                .param("id", productId).query(JdbcProducts::product).optional();
    }

    @Override
    public boolean take(UUID org, UUID productId) {
        return jdbc.sql("update products set sold = sold + 1 where organizer_id = :org and id = :id and kind = 'PASS'"
                        + " and sold < quota")
                .param("org", org).param("id", productId).update() == 1;
    }

    @Override
    public void giveBack(UUID org, UUID productId) {
        jdbc.sql("update products set sold = sold - 1 where organizer_id = :org and id = :id and kind = 'PASS'")
                .param("org", org).param("id", productId).update();
    }

    @Override
    public boolean hasPassTickets(UUID org, int eventId) {
        return jdbc.sql("select exists (select 1 from orders where organizer_id = :org and event_id = :event"
                        + " and pass_id is not null)")
                .param("org", org).param("event", eventId).query(Boolean.class).single();
    }

    @Override
    public long revenue(UUID org, UUID productId) {
        return jdbc.sql("""
                        select coalesce(sum(o.total_mad), 0)
                        from orders o join order_items i on i.order_id = o.id and i.organizer_id = o.organizer_id
                        where o.organizer_id = :org and o.event_id is null and o.status = 'PAID' and i.product_id = :product
                        """)
                .param("org", org).param("product", productId).query(Long.class).single();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Product product(ResultSet rs, int n) throws SQLException {
        return new Product(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                Product.Kind.valueOf(rs.getString("kind")), rs.getString("name"), rs.getLong("price_mad"),
                rs.getBoolean("on_sale"), instant(rs, "sales_close_at"), rs.getObject("quota", Integer.class),
                rs.getInt("sold"), rs.getObject("series_id", UUID.class), rs.getObject("zone_id", Integer.class),
                instant(rs, "valid_until"), rs.getInt("max_installments"), instant(rs, "created_at"));
    }
}
