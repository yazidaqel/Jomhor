package ma.jomhor.catalog.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.Events;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Event.PriceCategory;
import ma.jomhor.common.Crests;
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
            series_id, event_type, title, starts_at, doors_open_at, sale_starts_at, status, refund_cutoff_at,
            reentry_policy, per_account_limit, metadata::text as metadata, created_at""";
    // zone_name and zone_code by scalar subqueries, so the same list works after select and returning.
    private static final String PRICE_COLUMNS = """
            id, zone_id, name, price_mad, quota, sold,
            (select z.name from zones z where z.id = price_categories.zone_id
               and z.organizer_id = price_categories.organizer_id) as zone_name,
            (select z.code from zones z where z.id = price_categories.zone_id
               and z.organizer_id = price_categories.organizer_id) as zone_code""";

    private final JdbcClient jdbc;
    private final Crests crests;

    JdbcEvents(JdbcClient jdbc, Crests crests) {
        this.jdbc = jdbc;
        this.crests = crests;
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
                                  Instant doorsOpenAt, Instant saleStartsAt, Event.ReentryPolicy reentryPolicy,
                                  int perAccountLimit, String metadata, byte[] eventKeyEnc) {
        return jdbc.sql("""
                        insert into events (id, organizer_id, venue_id, event_type, title, starts_at, doors_open_at,
                                            sale_starts_at, event_key_enc, reentry_policy, per_account_limit, metadata)
                        overriding system value
                        select :id, organizer_id, id, cast(:type as event_type), :title, :starts, :doors, :sale,
                               :key, cast(:reentry as reentry_policy), :limit, cast(:metadata as jsonb)
                        from venues where organizer_id = :org and id = :venue
                        returning\s""" + EVENT_COLUMNS)
                .param("id", eventId).param("org", org).param("venue", venueId).param("type", type.name()).param("title", title)
                .param("starts", ts(startsAt)).param("doors", ts(doorsOpenAt)).param("sale", ts(saleStartsAt))
                .param("key", eventKeyEnc)
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
    public Instant now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public Optional<Event> lock(UUID org, int eventId) {
        return jdbc.sql("select " + EVENT_COLUMNS + " from events where organizer_id = :org and id = :id for no key update")
                .param("org", org).param("id", eventId).query(JdbcEvents::event).optional();
    }

    @Override
    public Event update(UUID org, Event e) {
        return jdbc.sql("""
                        update events set title = :title, starts_at = :starts, doors_open_at = :doors, sale_starts_at = :sale,
                          status = cast(:status as event_status), refund_cutoff_at = :cutoff,
                          reentry_policy = cast(:reentry as reentry_policy), per_account_limit = :limit,
                          metadata = cast(:metadata as jsonb)
                        where organizer_id = :org and id = :id
                        returning\s""" + EVENT_COLUMNS)
                .param("org", org).param("id", e.id()).param("title", e.title()).param("starts", ts(e.startsAt()))
                .param("doors", ts(e.doorsOpenAt())).param("sale", ts(e.saleStartsAt())).param("status", e.status().name())
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

    // Database clock: every instance and the order insert agree on now(). sale_starts_at < refund_cutoff_at by
    // EventService's rule, so NOT_STARTED and CLOSED never overlap.
    @Override
    public Optional<Inventory.SaleEvent> forSale(int eventId) {
        return jdbc.sql("""
                        select e.organizer_id, e.sale_starts_at, e.refund_cutoff_at, e.per_account_limit,
                          case when e.status <> 'ON_SALE' then 'NOT_ON_SALE'
                               when e.refund_cutoff_at is null or now() >= e.refund_cutoff_at then 'CLOSED'
                               when now() < e.sale_starts_at then 'NOT_STARTED'
                               else 'OPEN' end as sale_window,
                          (select coalesce(sum(pc.quota), 0) from price_categories pc
                           where pc.organizer_id = e.organizer_id and pc.event_id = e.id) as quota
                        from events e where e.id = :id
                        """)
                .param("id", eventId)
                .query((rs, n) -> new Inventory.SaleEvent(rs.getObject("organizer_id", UUID.class),
                        Inventory.SaleWindow.valueOf(rs.getString("sale_window")), instant(rs, "sale_starts_at"),
                        instant(rs, "refund_cutoff_at"), rs.getInt("per_account_limit"), rs.getLong("quota")))
                .optional();
    }

    // Cross-organizer on purpose (a job, not a request): each row carries its organizer_id, which scopes the rest.
    @Override
    public List<Event.Ref> inGateWindow(Duration lead, Duration after) {
        return jdbc.sql("""
                        select organizer_id, id from events
                        where status in ('ON_SALE', 'GATES_OPEN')
                          and now() between starts_at - cast(:lead as bigint) * interval '1 second'
                                        and starts_at + cast(:after as bigint) * interval '1 second'
                        order by starts_at, id
                        """)
                .param("lead", lead.toSeconds()).param("after", after.toSeconds())
                .query((rs, n) -> new Event.Ref(rs.getObject("organizer_id", UUID.class), rs.getInt("id")))
                .list();
    }

    // Public reads. on_sale mirrors forSale's OPEN; remaining = quota - sold, never sold or quota themselves.
    // Of organizers only slug, name and the crest (04 §Club directory); o.id is its key, so grouping by it is enough.
    private static final String LISTING = """
            select e.id, e.organizer_id, o.slug as organizer_slug, o.name as organizer_name,
                   o.settings ->> 'crest_url' as organizer_crest_url, e.status, e.event_type, e.title, e.starts_at,
                   e.doors_open_at, v.timezone, v.name as venue_name, v.city as venue_city, e.metadata::text as metadata, e.sale_starts_at,
                   e.refund_cutoff_at, e.per_account_limit,
                   coalesce(e.status = 'ON_SALE' and now() >= coalesce(e.sale_starts_at, '-infinity')
                            and now() < e.refund_cutoff_at, false) as on_sale,
                   coalesce(bool_and(pc.sold >= pc.quota), true) as sold_out,
                   min(pc.price_mad) filter (where pc.sold < pc.quota) as from_price_mad
            from events e
            join organizers o on o.id = e.organizer_id
            join venues v on v.id = e.venue_id and v.organizer_id = e.organizer_id
            left join price_categories pc on pc.event_id = e.id and pc.organizer_id = e.organizer_id
            """;

    // The organizer filter is one of two fixed fragments; the ids are bound as one uuid[] parameter. Listed-only is
    // inside the query, so the limit counts listed events only.
    @Override
    public List<Event.Listing> listings(List<UUID> orgs, Instant from, int limit) {
        return jdbc.sql(LISTING + "where e.status in ('ON_SALE', 'GATES_OPEN') and "
                        + (orgs.isEmpty() ? "o.slug is not null" : "e.organizer_id = any(cast(:orgs as uuid[]))") + """
                          and e.starts_at >= coalesce(cast(:from as timestamptz), now())
                        group by e.id, v.id, o.id
                        order by e.starts_at, e.id
                        limit :limit
                        """)
                .param("orgs", orgs.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}")))
                .param("from", ts(from)).param("limit", limit)
                .query((rs, n) -> listing(rs)).list();
    }

    @Override
    public Optional<Event.Listing> listing(int eventId) {
        return jdbc.sql(LISTING + " where e.id = :id and e.status <> 'DRAFT' group by e.id, v.id, o.id")
                .param("id", eventId).query((rs, n) -> listing(rs)).optional();
    }

    @Override
    public List<Event.ZoneListing> zoneListings(UUID org, int eventId) {
        Map<Integer, List<Event.PriceListing>> prices = jdbc.sql("""
                        select id, zone_id, name, price_mad, quota - sold as remaining from price_categories
                        where organizer_id = :org and event_id = :event order by zone_id, name, id
                        """)
                .param("org", org).param("event", eventId)
                .query((rs, n) -> new Event.PriceListing(rs.getObject("id", UUID.class), rs.getInt("zone_id"),
                        rs.getString("name"), rs.getLong("price_mad"), rs.getInt("remaining")))
                .list().stream()
                .collect(Collectors.groupingBy(Event.PriceListing::zoneId));
        // collate "C": codes ascending byte-wise, the same on every database locale.
        return jdbc.sql("""
                        select z.id, z.code, z.name, z.seated,
                               array(select g.code from gate_zones gz
                                     join gates g on g.id = gz.gate_id and g.organizer_id = gz.organizer_id
                                     where gz.zone_id = z.id and gz.organizer_id = z.organizer_id
                                     order by g.code collate "C") as gate_codes
                        from zones z
                        where z.organizer_id = :org and z.id = any(cast(:zones as smallint[]))
                        order by z.id
                        """)
                .param("org", org).param("zones", prices.keySet().stream().map(String::valueOf)
                        .collect(Collectors.joining(",", "{", "}")))
                .query((rs, n) -> new Event.ZoneListing(rs.getInt("id"), rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("seated"), List.of((String[]) rs.getArray("gate_codes").getArray()),
                        prices.get(rs.getInt("id"))))
                .list();
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
                instant(rs, "doors_open_at"), instant(rs, "sale_starts_at"), Event.Status.valueOf(rs.getString("status")),
                instant(rs, "refund_cutoff_at"), Event.ReentryPolicy.valueOf(rs.getString("reentry_policy")),
                rs.getInt("per_account_limit"), rs.getString("metadata"), instant(rs, "created_at"));
    }

    private Event.Listing listing(ResultSet rs) throws SQLException {
        return new Event.Listing(rs.getInt("id"), rs.getObject("organizer_id", UUID.class),
                new Event.Organizer(rs.getString("organizer_slug"), rs.getString("organizer_name"),
                        crests.url(rs.getString("organizer_crest_url"))),
                Event.Status.valueOf(rs.getString("status")), Event.Type.valueOf(rs.getString("event_type")), rs.getString("title"), instant(rs, "starts_at"),
                instant(rs, "doors_open_at"), rs.getString("timezone"), rs.getString("venue_name"),
                rs.getString("venue_city"), rs.getString("metadata"), instant(rs, "sale_starts_at"),
                instant(rs, "refund_cutoff_at"), rs.getBoolean("on_sale"), rs.getBoolean("sold_out"),
                rs.getObject("from_price_mad", Long.class), rs.getInt("per_account_limit"));
    }

    private static PriceCategory price(ResultSet rs, int n) throws SQLException {
        return new PriceCategory(rs.getObject("id", UUID.class), rs.getInt("zone_id"), rs.getString("name"),
                rs.getLong("price_mad"), rs.getInt("quota"), rs.getInt("sold"), rs.getString("zone_name"),
                rs.getString("zone_code"));
    }
}
