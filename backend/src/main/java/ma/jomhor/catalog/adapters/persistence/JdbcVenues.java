package ma.jomhor.catalog.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Venues;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.catalog.domain.Venue.Gate;
import ma.jomhor.catalog.domain.Venue.Zone;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Every statement filters on organizer_id (and venue_id where the path carries one). */
@Repository
class JdbcVenues implements Venues {

    private static final String VENUE_COLUMNS = "id, name, city, capacity, timezone";
    private static final String ZONE_COLUMNS = "id, code, name, capacity, seated";
    private static final String GATE_SELECT = """
            select g.id, g.code, g.name,
                   array(select gz.zone_id::int from gate_zones gz
                         where gz.gate_id = g.id and gz.organizer_id = g.organizer_id order by 1) as zone_ids
            from gates g
            where g.organizer_id = :org and g.venue_id = :venue
            """;

    private final JdbcClient jdbc;

    JdbcVenues(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Venue> list(UUID org) {
        return jdbc.sql("select " + VENUE_COLUMNS + " from venues where organizer_id = :org order by name, id")
                .param("org", org).query(JdbcVenues::venue).list();
    }

    @Override
    public Venue insertVenue(UUID org, String name, String city, int capacity, String timezone) {
        return jdbc.sql("""
                        insert into venues (organizer_id, name, city, capacity, timezone)
                        values (:org, :name, :city, :capacity, :timezone)
                        returning\s""" + VENUE_COLUMNS)
                .param("org", org).param("name", name).param("city", city).param("capacity", capacity)
                .param("timezone", timezone)
                .query(JdbcVenues::venue).single();
    }

    @Override
    public Optional<Venue> findVenue(UUID org, UUID venueId) {
        return jdbc.sql("select " + VENUE_COLUMNS + " from venues where organizer_id = :org and id = :id")
                .param("org", org).param("id", venueId).query(JdbcVenues::venue).optional();
    }

    // Must stay for update, never for no key update: only for update conflicts with the for key share an event
    // insert takes on its venue, which makes VENUE_IN_USE race-free (VenueApiTest.timezonePatchWaitsFor...).
    @Override
    public Optional<Venue> lockVenue(UUID org, UUID venueId) {
        return jdbc.sql("select " + VENUE_COLUMNS + " from venues where organizer_id = :org and id = :id for update")
                .param("org", org).param("id", venueId).query(JdbcVenues::venue).optional();
    }

    @Override
    public boolean hasEvents(UUID org, UUID venueId) {
        return jdbc.sql("select exists (select 1 from events where organizer_id = :org and venue_id = :venue)")
                .param("org", org).param("venue", venueId).query(Boolean.class).single();
    }

    @Override
    public boolean updateVenue(UUID org, UUID venueId, String name, String city, Integer capacity, String timezone) {
        return jdbc.sql("""
                        update venues set
                          name = coalesce(cast(:name as text), name),
                          city = case when cast(:city as text) is null then city else nullif(cast(:city as text), '') end,
                          capacity = coalesce(cast(:capacity as integer), capacity),
                          timezone = coalesce(cast(:timezone as text), timezone)
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", venueId)
                .param("name", name).param("city", city).param("capacity", capacity).param("timezone", timezone)
                .update() == 1;
    }

    @Override
    public List<Zone> zones(UUID org, UUID venueId) {
        return jdbc.sql("select " + ZONE_COLUMNS + " from zones where organizer_id = :org and venue_id = :venue order by code")
                .param("org", org).param("venue", venueId).query(JdbcVenues::zone).list();
    }

    // insert ... select from venues: the venue must be this organizer's, in the same statement.
    @Override
    public Optional<Zone> insertZone(UUID org, UUID venueId, String code, String name, int capacity, boolean seated) {
        return jdbc.sql("""
                        insert into zones (organizer_id, venue_id, code, name, capacity, seated)
                        select organizer_id, id, :code, :name, :capacity, :seated
                        from venues where organizer_id = :org and id = :venue
                        returning\s""" + ZONE_COLUMNS)
                .param("org", org).param("venue", venueId)
                .param("code", code).param("name", name).param("capacity", capacity).param("seated", seated)
                .query(JdbcVenues::zone).optional();
    }

    @Override
    public Optional<Zone> updateZone(UUID org, UUID venueId, int zoneId, String code, String name, Integer capacity,
                                     Boolean seated) {
        return jdbc.sql("""
                        update zones set
                          code = coalesce(cast(:code as text), code),
                          name = coalesce(cast(:name as text), name),
                          capacity = coalesce(cast(:capacity as integer), capacity),
                          seated = coalesce(cast(:seated as boolean), seated)
                        where organizer_id = :org and venue_id = :venue and id = :id
                        returning\s""" + ZONE_COLUMNS)
                .param("org", org).param("venue", venueId).param("id", zoneId)
                .param("code", code).param("name", name).param("capacity", capacity).param("seated", seated)
                .query(JdbcVenues::zone).optional();
    }

    @Override
    public boolean deleteZone(UUID org, UUID venueId, int zoneId) {
        jdbc.sql("""
                        delete from gate_zones gz using zones z
                        where gz.zone_id = z.id and gz.organizer_id = :org
                          and z.organizer_id = :org and z.venue_id = :venue and z.id = :id
                        """)
                .param("org", org).param("venue", venueId).param("id", zoneId).update();
        return jdbc.sql("delete from zones where organizer_id = :org and venue_id = :venue and id = :id")
                .param("org", org).param("venue", venueId).param("id", zoneId).update() == 1;
    }

    @Override
    public List<Gate> gates(UUID org, UUID venueId) {
        return jdbc.sql(GATE_SELECT + " order by g.code")
                .param("org", org).param("venue", venueId).query(JdbcVenues::gate).list();
    }

    @Override
    public Optional<Gate> findGate(UUID org, UUID venueId, UUID gateId) {
        return jdbc.sql(GATE_SELECT + " and g.id = :id")
                .param("org", org).param("venue", venueId).param("id", gateId).query(JdbcVenues::gate).optional();
    }

    // for update: concurrent PUTs on one gate's zones run one after the other (see replaceGateZones).
    @Override
    public Optional<UUID> lockGate(UUID org, UUID gateId) {
        return jdbc.sql("select venue_id from gates where organizer_id = :org and id = :id for update")
                .param("org", org).param("id", gateId).query(UUID.class).optional();
    }

    @Override
    public Optional<Gate> insertGate(UUID org, UUID venueId, String code, String name) {
        return jdbc.sql("""
                        insert into gates (organizer_id, venue_id, code, name)
                        select organizer_id, id, :code, :name
                        from venues where organizer_id = :org and id = :venue
                        returning id, code, name
                        """)
                .param("org", org).param("venue", venueId).param("code", code).param("name", name)
                .query((rs, n) -> new Gate(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                        List.of()))
                .optional();
    }

    @Override
    public boolean updateGate(UUID org, UUID venueId, UUID gateId, String code, String name) {
        return jdbc.sql("""
                        update gates set code = coalesce(cast(:code as text), code), name = coalesce(cast(:name as text), name)
                        where organizer_id = :org and venue_id = :venue and id = :id
                        """)
                .param("org", org).param("venue", venueId).param("id", gateId).param("code", code).param("name", name)
                .update() == 1;
    }

    @Override
    public boolean deleteGate(UUID org, UUID venueId, UUID gateId) {
        jdbc.sql("""
                        delete from gate_zones gz using gates g
                        where gz.gate_id = g.id and gz.organizer_id = :org
                          and g.organizer_id = :org and g.venue_id = :venue and g.id = :id
                        """)
                .param("org", org).param("venue", venueId).param("id", gateId).update();
        return jdbc.sql("delete from gates where organizer_id = :org and venue_id = :venue and id = :id")
                .param("org", org).param("venue", venueId).param("id", gateId).update() == 1;
    }

    @Override
    public void replaceGateZones(UUID org, UUID gateId, Collection<Integer> zoneIds) {
        jdbc.sql("delete from gate_zones where organizer_id = :org and gate_id = :gate")
                .param("org", org).param("gate", gateId).update();
        for (int zoneId : zoneIds) {
            jdbc.sql("insert into gate_zones (organizer_id, gate_id, zone_id) values (:org, :gate, :zone)")
                    .param("org", org).param("gate", gateId).param("zone", zoneId).update();
        }
    }

    private static Venue venue(ResultSet rs, int n) throws SQLException {
        return new Venue(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("city"), rs.getInt("capacity"),
                rs.getString("timezone"));
    }

    private static Zone zone(ResultSet rs, int n) throws SQLException {
        return new Zone(rs.getInt("id"), rs.getString("code"), rs.getString("name"), rs.getInt("capacity"),
                rs.getBoolean("seated"));
    }

    private static Gate gate(ResultSet rs, int n) throws SQLException {
        Integer[] zoneIds = (Integer[]) rs.getArray("zone_ids").getArray();
        return new Gate(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), Arrays.asList(zoneIds));
    }
}
