package ma.jomhor.notifications.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Message copy from the {@code messages/notifications} bundles (fr base, ar, en; docs/04-api.md §Notifications: the
 * recipient's users.locale, fr fallback). Placeholders are {@code {name}}, filled with String.replace: MessageFormat
 * would eat the apostrophe of "t'a". The ar push strings carry their own FSI…PDI isolates (plan lot-4 Q125).
 */
public final class MessageCopy {

    private static final Set<String> LOCALES = Set.of("fr", "ar", "en");
    private static final ResourceBundle.Control NO_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private MessageCopy() {}

    /** fr, ar or en; anything else (or null) → fr. */
    public static String locale(String tag) {
        return tag != null && LOCALES.contains(tag) ? tag : "fr";
    }

    /** The {@code key} text in {@code locale} (fr when unknown), each {@code {name}} replaced by its value. */
    public static String text(String locale, String key, Map<String, String> values) {
        // No fallback to the JVM's default locale: fr is the base bundle, whatever Locale.getDefault() says.
        String s = ResourceBundle.getBundle("messages.notifications", Locale.forLanguageTag(locale(locale)),
                NO_FALLBACK).getString(key);
        for (var v : values.entrySet()) {
            s = s.replace("{" + v.getKey() + "}", v.getValue());
        }
        return s;
    }

    /** 25050 → "250,50 DH": integer maths on centimes (as payments' Pages.dirhams). */
    public static String amount(long centimes) {
        // Locale.ROOT: ASCII digits whatever the JVM's default locale.
        return String.format(Locale.ROOT, "%d,%02d DH", centimes / 100, centimes % 100);
    }

    /** "d MMM HH:mm" in the venue's zone; ar uses ar-MA month names with Western digits (the formatter's default). */
    public static String deadline(Instant at, String timezone, String locale) {
        String l = locale(locale);
        Locale fmt = "ar".equals(l) ? Locale.forLanguageTag("ar-MA") : Locale.forLanguageTag(l);
        return DateTimeFormatter.ofPattern("d MMM HH:mm", fmt).withZone(ZoneId.of(timezone)).format(at);
    }
}
