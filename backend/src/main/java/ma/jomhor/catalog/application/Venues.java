package ma.jomhor.catalog.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.catalog.domain.Venue.Gate;
import ma.jomhor.catalog.domain.Venue.Zone;

/**
 * Persistence port for venues, zones, gates and gate_zones. Every method is scoped by organizer_id;
 * a row of another organizer (or of another venue than the one given) is treated as absent.
 * Nullable update arguments mean "unchanged".
 */
public interface Venues {

    List<Venue> list(UUID org);

    Venue insertVenue(UUID org, String name, String city, int capacity);

    Optional<Venue> findVenue(UUID org, UUID venueId);

    /** {@code city} null = unchanged, "" = cleared. False if the venue is not found. */
    boolean updateVenue(UUID org, UUID venueId, String name, String city, Integer capacity);

    List<Zone> zones(UUID org, UUID venueId);

    /** Empty if the venue is not found. */
    Optional<Zone> insertZone(UUID org, UUID venueId, String code, String name, int capacity, boolean seated);

    Optional<Zone> updateZone(UUID org, UUID venueId, int zoneId, String code, String name, Integer capacity, Boolean seated);

    /** Deletes the zone and its gate_zones rows. False if not found. */
    boolean deleteZone(UUID org, UUID venueId, int zoneId);

    List<Gate> gates(UUID org, UUID venueId);

    Optional<Gate> findGate(UUID org, UUID venueId, UUID gateId);

    /** Locks the gate row until the end of the transaction; returns its venue_id. */
    Optional<UUID> lockGate(UUID org, UUID gateId);

    /** Empty if the venue is not found. */
    Optional<Gate> insertGate(UUID org, UUID venueId, String code, String name);

    boolean updateGate(UUID org, UUID venueId, UUID gateId, String code, String name);

    /** Deletes the gate and its gate_zones rows. False if not found. */
    boolean deleteGate(UUID org, UUID venueId, UUID gateId);

    void replaceGateZones(UUID org, UUID gateId, Collection<Integer> zoneIds);
}
