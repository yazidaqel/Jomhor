package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import ma.jomhor.payments.domain.CashCode;
import org.junit.jupiter.api.Test;

/** docs/04-api.md §Cash codes: symbols, display, input normalisation, collision redraw. */
class CashCodeTest {

    @Test
    void theAlphabetIsCrockfordBase32() {
        assertThat(CashCode.SYMBOLS).hasSize(32).doesNotContain("I", "L", "O", "U");
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String code = CashCode.draw();
            assertThat(code).hasSize(6).matches("[0-9A-HJKMNP-TV-Z]{6}");
            code.chars().forEach(c -> seen.add((char) c));
        }
        assertThat(seen).hasSize(32); // every symbol is drawn
    }

    /** A draw starting with JMH is drawn again (04 §Cash codes), so the typed prefix can always be stripped. */
    @Test
    void aDrawStartingWithJmhIsDrawnAgain() {
        // Symbol indexes: J=18, M=20, H=17, then 0,1,2; the redraw is 4F7K29.
        Iterator<Integer> picks = List.of(18, 20, 17, 0, 1, 2, 4, 15, 7, 19, 2, 9).iterator();
        RandomGenerator forced = new RandomGenerator() {
            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int nextInt(int bound) {
                assertThat(bound).isEqualTo(32);
                return picks.next();
            }
        };
        assertThat(CashCode.draw(forced)).isEqualTo("4F7K29");
        assertThat(picks.hasNext()).isFalse();
    }

    @Test
    void displayGroupsFourThenTwo() {
        assertThat(CashCode.display("4F7K29")).isEqualTo("JMH 4F7K 29");
    }

    @Test
    void inputIsNormalised() {
        assertThat(CashCode.normalise("jmh 4f7k-2o")).contains("4F7K20");
        assertThat(CashCode.normalise("JMH 4F7K 29")).contains("4F7K29");
        assertThat(CashCode.normalise("JMH-4F7K-29")).contains("4F7K29");
        assertThat(CashCode.normalise(" 4f7k29 ")).contains("4F7K29");
        assertThat(CashCode.normalise("il0oL1")).contains("110011");
    }

    @Test
    void anythingElseIsNotFound() {
        for (String bad : new String[] {"4F7K2U", "4F7K290", "JMH 4F7K 2", "4F7K2", "", "JMH", "JMHABC", "4F7K2*",
                                        "4F7K2É"}) {
            assertThat(CashCode.normalise(bad)).as(bad).isEmpty();
        }
        assertThat(CashCode.normalise(null)).isEmpty();
    }

    @Test
    void aCollisionDrawsAgain() {
        Iterator<String> draws = List.of("AAAAAA", "AAAAAA", "BBBBBB").iterator();
        List<String> tried = new ArrayList<>();
        String stored = CashCode.issue(draws::next, code -> {
            tried.add(code);
            return code.equals("AAAAAA") ? Optional.empty() : Optional.of(code);
        });
        assertThat(stored).isEqualTo("BBBBBB");
        assertThat(tried).containsExactly("AAAAAA", "AAAAAA", "BBBBBB");
    }

    @Test
    void fiveCollisionsGiveUp() {
        List<String> tried = new ArrayList<>();
        assertThatThrownBy(() -> CashCode.issue(() -> "AAAAAA", code -> {
            tried.add(code);
            return Optional.empty();
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tried).hasSize(5);
    }
}
