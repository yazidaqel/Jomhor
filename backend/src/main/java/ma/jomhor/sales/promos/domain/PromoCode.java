package ma.jomhor.sales.promos.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A promo code of one event (docs/04-api.md §Promo codes, 03 promo_codes). {@code priceCategoryId} null = every
 * ALL/MEMBERS category of the event. {@code percent} is set for PERCENT only, {@code amountMad} (centimes) for AMOUNT
 * only; {@code maxUses} null = no cap.
 */
public record PromoCode(UUID id, UUID organizerId, int eventId, UUID priceCategoryId, String code, Kind kind,
                        Integer percent, Long amountMad, Integer maxUses, int perAccountMax, boolean active,
                        Instant createdAt) {

    public enum Kind { PERCENT, AMOUNT }

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9]{4,20}");

    /** What a client typed, trimmed and uppercased (null stays null). */
    public static String normalize(String typed) {
        return typed == null ? null : typed.trim().toUpperCase(Locale.ROOT);
    }

    /** A normalized code matches {@code ^[A-Z0-9]{4,20}$}. */
    public static boolean wellFormed(String normalized) {
        return normalized != null && FORMAT.matcher(normalized).matches();
    }
}
