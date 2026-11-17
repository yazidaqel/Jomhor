package ma.jomhor.catalog.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/**
 * The venue map templates of docs/venue-layouts.json (docs/04-api.md §Venue map): layout id → slot ids, both in the
 * file's order. Only the ids: the geometry is the clients'. Ids are append-only in the JSON; VenueLayoutsTest fails
 * when this list and the file disagree.
 */
public final class VenueLayouts {

    public static final SequencedMap<String, List<String>> LAYOUTS = layouts();

    private VenueLayouts() {}

    private static SequencedMap<String, List<String>> layouts() {
        var m = new LinkedHashMap<String, List<String>>();
        m.put("STADIUM", List.of("NORTH_WEST", "NORTH", "NORTH_EAST", "WEST", "EAST", "SOUTH_WEST", "SOUTH",
                "SOUTH_EAST"));
        m.put("END_STAGE", List.of("FLOOR_FRONT", "LEFT", "RIGHT", "FLOOR_BACK", "TIER_A", "TIER_B"));
        m.put("CENTER_STAGE", List.of("NORTH", "FLOOR_NORTH", "WEST", "EAST", "FLOOR_SOUTH", "SOUTH"));
        m.put("THEATRE", List.of("ORCHESTRA", "BOX_LEFT", "BOX_RIGHT", "BALCONY", "GALLERY"));
        return Collections.unmodifiableSequencedMap(m);
    }

    public static boolean isLayout(String layout) {
        return LAYOUTS.containsKey(layout);
    }

    public static boolean hasSlot(String layout, String slot) {
        return LAYOUTS.getOrDefault(layout, List.of()).contains(slot);
    }
}
