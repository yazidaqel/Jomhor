package ma.jomhor.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** docs/04-api.md §Holder names: input rule, holder_label and holder_initials. Latin script only (plan Q16). */
public final class HolderNames {

    // Latin letters only (not Latin-script symbols such as Roman numerals). The intersection is nested: in a flat
    // class "&&" would bind looser than the space, apostrophe, dot and hyphen and drop them.
    private static final Pattern NAME =
            Pattern.compile("[\\p{IsLatin}&&\\p{L}][[\\p{IsLatin}&&\\p{L}] '.\\-]{1,59}");
    /** Unicode whitespace, no-break spaces (U+00A0, U+202F) included. */
    private static final Pattern SPACES = Pattern.compile("(?U)\\s+");
    /** Letter numbers (Roman numerals, U+2160 on): NFKC would turn them into ASCII letters, so they are refused before it. */
    private static final Pattern LETTER_NUMBER = Pattern.compile("\\p{Nl}");

    private HolderNames() {}

    /**
     * 04 order: NFKC (fullwidth → ASCII) → U+2019 to ' → whitespace runs to one space → strip → 2–60 Latin letters,
     * space, apostrophe, dot, hyphen. Empty if it doesn't pass.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || LETTER_NUMBER.matcher(raw).find()) {
            return Optional.empty();
        }
        String name = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace('\u2019', '\'');
        name = SPACES.matcher(name).replaceAll(" ").strip();
        return NAME.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    /** "Yazid aqel" → "Yazid A." (first word as typed, last initial uppercase); one word → as is. Normalized input. */
    public static String label(String name) {
        String[] words = name.split(" ");
        String last = firstLetter(words[words.length - 1]).toUpperCase(Locale.ROOT);
        return words.length == 1 || last.isEmpty() ? words[0] : words[0] + " " + last + ".";
    }

    /** "Yazid Aqel" → "Y. A."; one word → "Y.". {@code name} is normalized. */
    public static String initials(String name) {
        String[] words = name.split(" ");
        String first = firstLetter(words[0]).toUpperCase(Locale.ROOT);
        String last = firstLetter(words[words.length - 1]).toUpperCase(Locale.ROOT);
        return words.length == 1 || last.isEmpty() ? first + "." : first + ". " + last + ".";
    }

    /** The first letter of a word (a word may start with an apostrophe or a hyphen), or "". */
    private static String firstLetter(String word) {
        return word.codePoints().filter(Character::isLetter).mapToObj(Character::toString).findFirst().orElse("");
    }
}
