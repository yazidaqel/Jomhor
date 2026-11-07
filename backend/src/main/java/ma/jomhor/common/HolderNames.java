package ma.jomhor.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * docs/04-api.md §Holder names: input rule, holder_label and holder_initials. One script per name, Latin or Arabic
 * (plan lot-4 Q66).
 */
public final class HolderNames {

    // Latin letters only (Script=Latin, not Latin-script symbols such as Roman numerals). The intersection is nested:
    // in a flat class "&&" would bind looser than the space, apostrophe, dot and hyphen and drop them. Java regex
    // counts code points, so {1,59} is the 04 code-point length.
    private static final Pattern LATIN =
            Pattern.compile("[\\p{IsLatin}&&\\p{L}][[\\p{IsLatin}&&\\p{L}] '.\\-]{1,59}");
    /** The 36 base Arabic letters only (CIN line, shaped by the PDF): extended letters, digits, punctuation refused. */
    private static final Pattern ARABIC = Pattern.compile(
            "[\\x{0621}-\\x{063A}\\x{0641}-\\x{064A}][[\\x{0621}-\\x{063A}\\x{0641}-\\x{064A}] \\-]{1,59}");
    /** Tatweel and the Arabic combining marks (harakat, Quranic marks): removed from every name after NFKC. */
    private static final Pattern ARABIC_MARKS =
            Pattern.compile("[\\u0640\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]");
    /** The White_Space property: no-break spaces and U+0085 included, U+FEFF and U+200B not. */
    private static final Pattern SPACES = Pattern.compile("\\p{IsWhite_Space}+");
    /** Letter numbers (Roman numerals, U+2160 on): NFKC would turn them into ASCII letters, so they are refused before it. */
    private static final Pattern LETTER_NUMBER = Pattern.compile("\\p{Nl}");

    /** The 422 field message of any name refused by {@link #normalize}. */
    public static final String INVALID = "2 to 60 letters of one script: Latin with spaces, apostrophes, dots or hyphens,"
            + " or Arabic with spaces or hyphens";

    private HolderNames() {}

    /**
     * 04 order: NFKC (fullwidth → ASCII, Arabic presentation forms → letters) → U+2019 to ' → tatweel and Arabic marks
     * removed (any script) → White_Space runs to one space → strip → 2–60 code points, either Latin letters, space,
     * apostrophe, dot, hyphen or the 36 base Arabic letters, space, hyphen. Empty if it doesn't pass.
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || LETTER_NUMBER.matcher(raw).find()) {
            return Optional.empty();
        }
        String name = Normalizer.normalize(raw, Normalizer.Form.NFKC).replace('\u2019', '\'');
        name = withoutArabicMarks(name);
        name = SPACES.matcher(name).replaceAll(" ").strip();
        return LATIN.matcher(name).matches() || ARABIC.matcher(name).matches() ? Optional.of(name) : Optional.empty();
    }

    /** {@code text} without tatweel and Arabic combining marks (also used by the ticket PDF, which can't place marks). */
    public static String withoutArabicMarks(String text) {
        return ARABIC_MARKS.matcher(text).replaceAll("");
    }

    /**
     * "Yazid aqel" → "Yazid A." (first word as typed, last initial uppercase); one word → as is. Normalized input.
     * Arabic has no case: "يزيد عقل" → "يزيد ع.".
     */
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
