package ma.jomhor.catalog.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A place with zones (ticket areas) and gates (entrances). Never carries organizer_id: it comes from OrgContext.
 * {@code timezone} is an IANA zone id: the wall clock of every event held there.
 */
public record Venue(UUID id, String name, String city, int capacity, String timezone) {

    /** venues.timezone default (plan Q7). */
    public static final String DEFAULT_TIMEZONE = "Africa/Casablanca";

    /** {@code id} is the 2-byte zone_id that travels in the QR. */
    public record Zone(int id, String code, String name, int capacity, boolean seated) {}

    /**
     * {@code directions}: how to reach the gate (fan app only, never in the manifest). {@code latitude} /
     * {@code longitude}: WGS 84, both or neither, as numeric(8,6) / numeric(9,6) read them (scale 6, written plain).
     */
    public record Gate(UUID id, String code, String name, String directions, BigDecimal latitude, BigDecimal longitude,
                       List<Integer> zoneIds) {}

    public record Detail(UUID id, String name, String city, int capacity, String timezone, List<Zone> zones,
                         List<Gate> gates) {}
}
