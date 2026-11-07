package ma.jomhor.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * docs/04-api.md §Holder names over {@code docs/holder-name-vectors.json}, the case list shared with the Dart and TS
 * copies: {@code [{input, ok, normalized?, label?, initials?}]}.
 */
class HolderNamesTest {

    @Test
    void sharedVectors() throws Exception {
        List<Map<String, Object>> cases = JsonPath.read(Files.readString(Path.of("../docs/holder-name-vectors.json")), "$");
        assertThat(cases).hasSizeGreaterThan(50);
        for (Map<String, Object> c : cases) {
            String input = (String) c.get("input");
            String as = input.codePoints().mapToObj(cp -> cp < 0x80 ? Character.toString(cp) : "U+%04X".formatted(cp))
                    .reduce("", String::concat);
            if ((Boolean) c.get("ok")) {
                String normalized = HolderNames.normalize(input).orElse(null);
                assertThat(normalized).as(as).isEqualTo(c.get("normalized"));
                assertThat(HolderNames.label(normalized)).as(as).isEqualTo(c.get("label"));
                assertThat(HolderNames.initials(normalized)).as(as).isEqualTo(c.get("initials"));
            } else {
                assertThat(HolderNames.normalize(input)).as(as).isEmpty();
            }
        }
    }

    @Test
    void nullIsRefused() {
        assertThat(HolderNames.normalize(null)).isEmpty();
    }
}
