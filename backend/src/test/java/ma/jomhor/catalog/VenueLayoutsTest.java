package ma.jomhor.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import ma.jomhor.catalog.domain.VenueLayouts;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * docs/venue-layouts.json (docs/04-api.md §Venue map), the contract with the three clients: {@link VenueLayouts} holds
 * exactly its ids in its order, and the geometry stays drawable (integer rects inside the 400x300 canvas, slots at
 * least 55 on a side, nothing overlapping, the centre included). Read only, never written.
 */
class VenueLayoutsTest {

    private static final String ID = "^[A-Z][A-Z0-9_]{0,31}$";
    private static final int W = 400;
    private static final int H = 300;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> file() throws Exception {
        Path docs = Stream.of(Path.of("docs"), Path.of("..", "docs"))
                .filter(d -> Files.exists(d.resolve("04-api.md"))).findFirst()
                .orElseThrow(() -> new AssertionError("docs/ not found from " + Path.of("").toAbsolutePath()));
        // LinkedHashMap all the way down: the layouts' order is part of the contract.
        return JsonMapper.builder().build().readValue(Files.readString(docs.resolve("venue-layouts.json")),
                LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> layouts(Map<String, Object> file) {
        return (Map<String, Map<String, Object>>) file.get("layouts");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> slots(Map<String, Object> layout) {
        return (List<Map<String, Object>>) layout.get("slots");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> center(Map<String, Object> layout) {
        return (Map<String, Object>) layout.get("center");
    }

    @Test
    void idsAreTheFilesInItsOrder() throws Exception {
        Map<String, List<String>> fromFile = new LinkedHashMap<>();
        layouts(file()).forEach((id, l) -> fromFile.put(id, slots(l).stream().map(s -> (String) s.get("id")).toList()));
        assertThat(new ArrayList<>(VenueLayouts.LAYOUTS.sequencedKeySet())).containsExactlyElementsOf(fromFile.keySet());
        fromFile.forEach((id, slots) -> assertThat(VenueLayouts.LAYOUTS.get(id)).as(id).containsExactlyElementsOf(slots));
        assertThat(VenueLayouts.isLayout("STADIUM")).isTrue();
        assertThat(VenueLayouts.isLayout("stadium")).isFalse();
        assertThat(VenueLayouts.isLayout(null)).isFalse();
        assertThat(VenueLayouts.hasSlot("STADIUM", "EAST")).isTrue();
        assertThat(VenueLayouts.hasSlot("THEATRE", "EAST")).isFalse();
        assertThat(VenueLayouts.hasSlot("NOPE", "EAST")).isFalse();
    }

    @Test
    void canvasAndVersion() throws Exception {
        Map<String, Object> file = file();
        assertThat(file.get("canvas")).isEqualTo(Map.of("w", W, "h", H));
        assertThat(file.get("version")).isInstanceOf(Integer.class);
        assertThat((Integer) file.get("version")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void idsMatchThePatternAndAreUniquePerLayout() throws Exception {
        layouts(file()).forEach((id, l) -> {
            assertThat(id).matches(ID);
            assertThat(center(l).get("kind")).as(id).isIn("FIELD", "STAGE");
            List<String> slotIds = slots(l).stream().map(s -> (String) s.get("id")).toList();
            assertThat(slotIds).as(id).isNotEmpty().doesNotHaveDuplicates().allSatisfy(s -> assertThat(s).matches(ID));
        });
    }

    @Test
    void rectsAreIntegerInBoundsLargeEnoughAndApart() throws Exception {
        layouts(file()).forEach((id, l) -> {
            List<int[]> all = new ArrayList<>();
            all.add(rect(id + " center", center(l).get("rect")));
            for (Map<String, Object> s : slots(l)) {
                int[] r = rect(id + " " + s.get("id"), s.get("rect"));
                assertThat(Math.min(r[2], r[3])).as(id + " " + s.get("id") + " side").isGreaterThanOrEqualTo(55);
                all.add(r);
            }
            for (int i = 0; i < all.size(); i++) {
                for (int j = i + 1; j < all.size(); j++) {
                    assertThat(overlap(all.get(i), all.get(j))).as(id + " rects " + i + " and " + j).isFalse();
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static int[] rect(String what, Object value) {
        List<Object> r = (List<Object>) value;
        assertThat(r).as(what).hasSize(4).allSatisfy(n -> assertThat(n).as(what).isInstanceOf(Integer.class));
        int x = (Integer) r.get(0), y = (Integer) r.get(1), w = (Integer) r.get(2), h = (Integer) r.get(3);
        assertThat(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= W && y + h <= H).as(what + " in bounds").isTrue();
        return new int[] {x, y, w, h};
    }

    /** Shared edges are not an overlap. */
    private static boolean overlap(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }
}
