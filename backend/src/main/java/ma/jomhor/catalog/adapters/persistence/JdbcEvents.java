package ma.jomhor.catalog.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Events;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id (and event_id for prices). event_key_enc is selected by eventKeyEnc only. */
@Repository
class JdbcEvents implements Events {

    // timezone comes from the venue, by a scalar subquery so the same list works after select, returning and a lock.
    private static final String EVENT_COLUMNS = """
            id, venue_id,
            (select v.timezone from venues v where v.id = events.venue_id and v.organizer_id = events.organizer_id)
              as timezone,
            series_id, event_type, title, starts_at, doors_open_at, status, refund_cutoff_at,
            reentry_policy, per_account_limit, metadata::text as metadata, created_at""";
    private static final String PRICE_COLUMNS = "id, zone_id, name, price_mad, quota, sold";

    private final JdbcClient jdbc;

    JdbcEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Event.Summary> list(UUID org, Event.Status status, Instant from) {
        return jdbc.sql("""
                        select e.id, e.title, e.event_type, e.starts_at, e.status, v.id as venue_id, v.name as venue_name,
                               v.timezone as venue_timezone,
                               coalesce(sum(pc.quota), 0) as quota, coalesce(sum(pc.sold), 0) as sold
                        from events e
                        join venues v on v.id = e.venue_id and v.organizer_id = e.organizer_id
                        left join price_categories pc on pc.event_id = e.id and pc.organizer_id = e.organizer_id
                        where e.organizer_id = :org
                          and (cast(:status as event_status) is null or e.status = cast(:status as event_status))
                          and (cast(:from as timestamptz) is null or e.starts_at >= cast(:from as timestamptz))
                        group by e.id, v.id
                        order by e.starts_at, e.id
                        """)
                .param("org", org).param("status", status == null ? null : status.name()).param("from", ts(from))
                .query((rs, n) -> new Event.Summary(rs.getInt("id"), rs.getString("title"),
                        Event.Type.valueOf(rs.getString("event_type")), instant(rs, "starts_at"),
                        Event.Status.valueOf(rs.getString("status")), rs.getObject("venue_id", UUID.class),
                        rs.getString("venue_name"), rs.getString("venue_timezone"), rs.getLong("quota"), rs.getLong("sold")))
                .list();
    }

    @Override
    public int nextId() {
        return jdbc.sql("select nextval(pg_get_serial_sequence('events', 'id'))").query(Integer.class).single();
    }

    // insert ... select from venues: the venue must be this organizer's, in the same statement.
    @Override
    public Optional<Event> insert(UUID org, int eventId, UUID venueId, Event.Type type, String title, Instant startsAt,
                                  Instant doorsOpenAt, Event.ReentryPolicy reentryPolicy, int perAccountLimit,
                                  String metadata, byte[] eventKeyEnc) {
        return jdbc.sql("""
                        insert into events (id, organizer_id, venue_id, event_type, title, starts_at, doors_open_at,
                                            event_key_enc, reentry_policy, per_account_limit, metadata)
                        overriding system value
                        select :id, organizer_id, id, cast(:type as event_type), :title, :starts, :doors,
                               :key, cast(:reentry as reentry_policy), :limit, cast(:metadata as jsonb)
                        from venues where organizer_id = :org and id = :venue
                        returning\s""" + EVENT_COLUMNS)
                .param("id", eventId).param("org", org).param("venue", venueId).param("type", type.name()).param("title", title)
                .param("starts", ts(startsAt)).param("doors", ts(doorsOpenAt)).param("key", eventKeyEnc)
                .param("reentry", reentryPolicy.name()).param("limit", perAccountLimit).param("metadata", metadata)
                .query(JdbcEvents::event).optional();
    }

    @Override
    public Optional<Event> find(UUID org, int eventId) {
        return jdbc.sql("select " + EVENT_COLUMNS + " from events where organizer_id = :org and id = :id")
                .param("org", org).param("id", eventId).query(JdbcEvents::event).optional();
    }

    @Override
    public Optional<byte[]> eventKeyEnc(UUID org, int eventId) {
        return jdbc.sql("select event_key_enc from events where organizer_id = :org and id = :id")
                .param("org", org).param("id", eventId).query(byte[].class).optional();
    }

    // for no key update, not for update: catalog writers still queue on the event, but an order's insert (its FK
    // takes for key share on the event) isn't blocked. With for update, an order holding a price row and an
    // organizer holding the event while writing that price row deadlock.
    @Override
    public Optional<Event> lock(UUID org, int eventId) {
        return jdbc.sql("select " + EVENT_COLUMNS + " from events where organizer_id = :org and id = :id for no key update")
                .param("org", org).param("id", eventId).query(JdbcEvents::event).optional();
    }

    @Override
    public Event update(UUID org, Event e) {
        return jdbc.sql("""
                        update events set title = :title, starts_at = :starts, doors_open_at = :doors,
                          status = cast(:status as event_status), refund_cutoff_at = :cutoff,
                          reentry_policy = cast(:reentry as reentry_policy), per_account_limit = :limit,
                          metadata = cast(:metadata as jsonb)
                        where organizer_id = :org and id = :id
                        returning\s""" + EVENT_COLUMNS)
                .param("org", org).param("id", e.id()).param("title", e.title()).param("starts", ts(e.startsAt()))
                .param("doors", ts(e.doorsOpenAt())).param("status", e.status().name())
                .param("cutoff", ts(e.refundCutoffAt())).param("reentry", e.reentryPolicy().name())
                .param("limit", e.perAccountLimit()).param("metadata", e.metadata())
                .query(JdbcEvents::event).single();
    }

    @Override
    public List<PriceCategory> prices(UUID org, int eventId) {
        return jdbc.sql("select " + PRICE_COLUMNS + " from price_categories where organizer_id = :org and event_id = :event"
                        + " order by zone_id, name, id")
                .param("org", org).param("event", eventId).query(JdbcEvents::price).list();
    }

    @Override
    public Optional<PriceCategory> findPrice(UUID org, int eventId, UUID priceId) {
        return jdbc.sql("select " + PRICE_COLUMNS
                        + " from price_categories where organizer_id = :org and event_id = :event and id = :id")
                .param("org", org).param("event", eventId).param("id", priceId).query(JdbcEvents::price).optional();
    }

    @Override
    public Optional<Integer> zoneCapacity(UUID org, UUID venueId, int zoneId) {
        return jdbc.sql("select capacity from zones where organizer_id = :org and venue_id = :venue and id = :id")
                .param("org", org).param("venue", venueId).param("id", zoneId).query(Integer.class).optional();
    }

    @Override
    public long quotaOnZone(UUID org, int eventId, int zoneId, UUID except) {
        return jdbc.sql("""
                        select coalesce(sum(quota), 0) from price_categories
                        where organizer_id = :org and event_id = :event and zone_id = :zone
                          and (cast(:except as uuid) is null or id <> cast(:except as uuid))
                        """)
                .param("org", org).param("event", eventId).param("zone", zoneId).param("except", except)
                .query(Long.class).single();
    }

    @Override
    public boolean hasQuota(UUID org, int eventId) {
        return jdbc.sql("select exists (select 1 from price_categories where organizer_id = :org and event_id = :event"
                        + " and quota > 0)")
                .param("org", org).param("event", eventId).query(Boolean.class).single();
    }

    @Override
    public PriceCategory insertPrice(UUID org, int eventId, int zoneId, String name, long priceMad, int quota) {
        return jdbc.sql("""
                        insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota)
                        values (:org, :event, :zone, :name, :price, :quota)
                        returning\s""" + PRICE_COLUMNS)
                .param("org", org).param("event", eventId).param("zone", zoneId).param("name", name)
                .param("price", priceMad).param("quota", quota)
                .query(JdbcEvents::price).single();
    }

    // sold <= new quota in the same statement: a concurrent sale can't slip between check and write.
    @Override
    public Optional<PriceCategory> updatePrice(UUID org, int eventId, UUID priceId, String name, Long priceMad,
                                               Integer quota) {
        return jdbc.sql("""
                        update price_categories set
                          name = coalesce(cast(:name as text), name),
                          price_mad = coalesce(cast(:price as bigint), price_mad),
                          quota = coalesce(cast(:quota as integer), quota)
                        where organizer_id = :org and event_id = :event and id = :id
                          and sold <= coalesce(cast(:quota as integer), quota)
                        returning\s""" + PRICE_COLUMNS)
                .param("org", org).param("event", eventId).param("id", priceId)
                .param("name", name).param("price", priceMad).param("quota", quota)
                .query(JdbcEvents::price).optional();
    }

    @Override
    public boolean deletePriceIfUnsold(UUID org, int eventId, UUID priceId) {
        return jdbc.sql("delete from price_categories where organizer_id = :org and event_id = :event and id = :id"
                        + " and sold = 0")
                .param("org", org).param("event", eventId).param("id", priceId).update() == 1;
    }

    @Override
    public Optional<Inventory.SaleEvent> forSale(int eventId) {
        return jdbc.sql("select organizer_id, status = 'ON_SALE' as on_sale, per_account_limit from events where id = :id")
                .param("id", eventId)
                .query((rs, n) -> new Inventory.SaleEvent(rs.getObject("organizer_id", UUID.class),
                        rs.getBoolean("on_sale"), rs.getInt("per_account_limit")))
                .optional();
    }

    // Check and increment in one statement under the row lock: two buyers can't both take the last place.
    @Override
    public Optional<PriceCategory> take(UUID org, int eventId, UUID priceId, int qty) {
        return jdbc.sql("""
                        update price_categories set sold = sold + :qty
                        where organizer_id = :org and event_id = :event and id = :id and sold + :qty <= quota
                        returning\s""" + PRICE_COLUMNS)
                .param("org", org).param("event", eventId).param("id", priceId).param("qty", qty)
                .query(JdbcEvents::price).optional();
    }

    @Override
    public boolean giveBack(UUID org, int eventId, UUID priceId, int qty) {
        return jdbc.sql("""
                        update price_categories set sold = sold - :qty
                        where organizer_id = :org and event_id = :event and id = :id and sold >= :qty
                        """)
                .param("org", org).param("event", eventId).param("id", priceId).param("qty", qty).update() == 1;
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Event event(ResultSet rs, int n) throws SQLException {
        return new Event(rs.getInt("id"), rs.getObject("venue_id", UUID.class), rs.getString("timezone"),
                rs.getObject("series_id", UUID.class),
                Event.Type.valueOf(rs.getString("event_type")), rs.getString("title"), instant(rs, "starts_at"),
                instant(rs, "doors_open_at"), Event.Status.valueOf(rs.getString("status")),
                instant(rs, "refund_cutoff_at"), Event.ReentryPolicy.valueOf(rs.getString("reentry_policy")),
                rs.getInt("per_account_limit"), rs.getString("metadata"), instant(rs, "created_at"));
    }

    private static PriceCategory price(ResultSet rs, int n) throws SQLException {
        return new PriceCategory(rs.getObject("id", UUID.class), rs.getInt("zone_id"), rs.getString("name"),
                rs.getLong("price_mad"), rs.getInt("quota"), rs.getInt("sold"));
    }
}
