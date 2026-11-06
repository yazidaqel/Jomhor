package ma.jomhor.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.catalog.domain.Venue.Detail;
import ma.jomhor.catalog.domain.Venue.Gate;
import ma.jomhor.catalog.domain.Venue.Zone;
import ma.jomhor.common.web.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Venues, zones, gates and the gate → zone matrix (docs/04-api.md §Organizer). {@code org} is always
 * OrgContext.organizerId(); ids of another organizer, or of another venue than the path's, are 404.
 * Text inputs are trimmed; code is stored as given otherwise.
 */
@Service
public class VenueService {

    private final Venues venues;

    public VenueService(Venues venues) {
        this.venues = venues;
    }

    public List<Venue> list(UUID org) {
        return venues.list(org);
    }

    public Venue create(UUID org, String name, String city, int capacity) {
        String c = trim(city);
        return venues.insertVenue(org, name.trim(), c == null || c.isEmpty() ? null : c, capacity);
    }

    public Detail get(UUID org, UUID venueId) {
        Venue v = venues.findVenue(org, venueId).orElseThrow(notFound("Venue"));
        return new Detail(v.id(), v.name(), v.city(), v.capacity(), venues.zones(org, venueId), venues.gates(org, venueId));
    }

    @Transactional
    public Detail update(UUID org, UUID venueId, String name, String city, Integer capacity) {
        if (!venues.updateVenue(org, venueId, trim(name), trim(city), capacity)) {
            throw notFound("Venue").get();
        }
        return get(org, venueId);
    }

    public Zone createZone(UUID org, UUID venueId, String code, String name, int capacity, boolean seated) {
        return codeUnique(() -> venues.insertZone(org, venueId, code.trim(), name.trim(), capacity, seated))
                .orElseThrow(notFound("Venue"));
    }

    public Zone updateZone(UUID org, UUID venueId, int zoneId, String code, String name, Integer capacity, Boolean seated) {
        return codeUnique(() -> venues.updateZone(org, venueId, zoneId, trim(code), trim(name), capacity, seated))
                .orElseThrow(notFound("Zone"));
    }

    /** Referenced zones (price_categories, later order_items and tickets) are refused by their FKs. */
    @Transactional
    public void deleteZone(UUID org, UUID venueId, int zoneId) {
        boolean deleted;
        try {
            deleted = venues.deleteZone(org, venueId, zoneId);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException("ZONE_IN_USE", HttpStatus.CONFLICT, "Zone is referenced");
        }
        if (!deleted) {
            throw notFound("Zone").get();
        }
    }

    public Gate createGate(UUID org, UUID venueId, String code, String name) {
        return codeUnique(() -> venues.insertGate(org, venueId, code.trim(), name.trim())).orElseThrow(notFound("Venue"));
    }

    @Transactional
    public Gate updateGate(UUID org, UUID venueId, UUID gateId, String code, String name) {
        if (!codeUnique(() -> venues.updateGate(org, venueId, gateId, trim(code), trim(name)))) {
            throw notFound("Gate").get();
        }
        return venues.findGate(org, venueId, gateId).orElseThrow();
    }

    // ponytail: no GATE_IN_USE yet, nothing references gates in Lot 0; Lot 2 (device_assignments, scans) must add it.
    // The gate lock orders this after (or before) a concurrent PUT of its zones: never a gate_zones FK error.
    @Transactional
    public void deleteGate(UUID org, UUID venueId, UUID gateId) {
        boolean ofThisVenue = venues.lockGate(org, gateId).filter(venueId::equals).isPresent();
        if (!ofThisVenue || !venues.deleteGate(org, venueId, gateId)) {
            throw notFound("Gate").get();
        }
    }

    /**
     * Replaces the gate's zone set; every zone must belong to the gate's venue. The gate row lock makes
     * concurrent PUTs on one gate serial, so the result is exactly one payload, never a merge.
     */
    @Transactional
    public Gate replaceGateZones(UUID org, UUID gateId, List<Integer> zoneIds) {
        UUID venueId = venues.lockGate(org, gateId).orElseThrow(notFound("Gate"));
        Set<Integer> wanted = new TreeSet<>(zoneIds);
        Set<Integer> ofVenue = venues.zones(org, venueId).stream().map(Zone::id).collect(Collectors.toSet());
        if (!ofVenue.containsAll(wanted)) {
            throw zonesNotInVenue();
        }
        try {
            venues.replaceGateZones(org, gateId, wanted);
        } catch (DataIntegrityViolationException e) {
            if (e instanceof DuplicateKeyException) {
                throw e; // can't happen under the gate lock with a de-duplicated set: a real bug, let it 500
            }
            throw zonesNotInVenue(); // zone_id FK: a zone deleted since the check above
        }
        return venues.findGate(org, venueId, gateId).orElseThrow();
    }

    /** unique (venue_id, code) is the only unique key a zone or gate write can hit besides the PK. */
    private static <T> T codeUnique(Supplier<T> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException e) {
            throw new ApiException("CODE_TAKEN", HttpStatus.CONFLICT, "Code already used in this venue");
        }
    }

    private static ApiException zonesNotInVenue() {
        return new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                Map.of("fields", Map.of("zone_ids", "every zone must belong to the gate's venue")));
    }

    private static Supplier<ApiException> notFound(String what) {
        return () -> ApiException.notFound(what + " not found");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
