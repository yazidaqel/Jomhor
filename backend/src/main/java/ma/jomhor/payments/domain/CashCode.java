package ma.jomhor.payments.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Cash pay codes (docs/04-api.md §Cash codes): 6 Crockford base32 symbols (30 bits) from SecureRandom, stored
 * uppercase in payments.pay_code, shown {@code JMH 4F7K 29}. No check digit: a lookup shows event and amount.
 */
public final class CashCode {

    public static final String SYMBOLS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    public static final int LENGTH = 6;
    /** Draws per code before giving up (a collision on insert draws again). */
    static final int MAX_DRAWS = 5;
    private static final String PREFIX = "JMH";
    private static final RandomGenerator RANDOM = new SecureRandom();

    private CashCode() {}

    public static String draw() {
        return draw(RANDOM);
    }

    /** Never a code starting with JMH (drawn again), so a typed JMH prefix is never ambiguous (04 §Cash codes). */
    public static String draw(RandomGenerator random) {
        String code;
        do {
            StringBuilder b = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                b.append(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
            }
            code = b.toString();
        } while (code.startsWith(PREFIX));
        return code;
    }

    /** {@code 4F7K29} → {@code JMH 4F7K 29}. */
    public static String display(String code) {
        return PREFIX + " " + code.substring(0, 4) + " " + code.substring(4);
    }

    /**
     * What a person typed → the stored code, or empty (not found). Case-insensitive; spaces, hyphens and a leading
     * JMH are ignored (no code starts with JMH); O → 0, I and L → 1.
     */
    public static Optional<String> normalise(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String s = input.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "");
        if (s.startsWith(PREFIX)) {
            s = s.substring(PREFIX.length());
        }
        s = s.replace('O', '0').replace('I', '1').replace('L', '1');
        if (s.length() != LENGTH || !s.chars().allMatch(c -> SYMBOLS.indexOf(c) >= 0)) {
            return Optional.empty();
        }
        return Optional.of(s);
    }

    /**
     * Draws until {@code insert} stores one (it answers empty on a pay_code collision), at most 5 times; then an
     * IllegalStateException (500).
     */
    public static <T> T issue(Supplier<String> draw, Function<String, Optional<T>> insert) {
        for (int i = 0; i < MAX_DRAWS; i++) {
            Optional<T> stored = insert.apply(draw.get());
            if (stored.isPresent()) {
                return stored.get();
            }
        }
        throw new IllegalStateException("No free cash code after " + MAX_DRAWS + " draws");
    }
}
