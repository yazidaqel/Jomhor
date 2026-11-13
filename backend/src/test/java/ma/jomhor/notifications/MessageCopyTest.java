package ma.jomhor.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import ma.jomhor.notifications.domain.MessageCopy;
import org.junit.jupiter.api.Test;

/** The notification bundles (docs/04-api.md §Notifications: users.locale, fr fallback) and MessageCopy's formats. */
class MessageCopyTest {

    private static Properties bundle(String suffix) throws Exception {
        Properties p = new Properties();
        String path = "/messages/notifications" + suffix + ".properties";
        try (InputStream in = MessageCopyTest.class.getResourceAsStream(path)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void frArAndEnHaveTheSameKeys() throws Exception {
        Properties fr = bundle("");
        assertThat(fr.stringPropertyNames()).contains("push.ticket_received.title", "push.ticket_received.body",
                "push.split_invite.title", "push.split_invite.title_friend", "push.split_invite.body",
                "push.order_paid.title", "push.order_paid.body", "friend");
        assertThat(bundle("_ar").stringPropertyNames()).isEqualTo(fr.stringPropertyNames());
        assertThat(bundle("_en").stringPropertyNames()).isEqualTo(fr.stringPropertyNames());
    }

    @Test
    void theApostropheStaysAndPlaceholdersAreFilled() {
        assertThat(MessageCopy.text("fr", "push.split_invite.title", Map.of("label", "Yazid A.")))
                .isEqualTo("Yazid A. t'a gardé une place");
        assertThat(MessageCopy.text("fr", "push.split_invite.title_friend", Map.of()))
                .isEqualTo("Un ami t'a gardé une place");
        assertThat(MessageCopy.text("fr", "push.split_invite.body",
                Map.of("event", "Match", "amount", "50,00 DH", "deadline", "1 mai 21:00")))
                .isEqualTo("Match · 50,00 DH · avant 1 mai 21:00");
    }

    @Test
    void anUnknownLocaleIsFrench() {
        assertThat(MessageCopy.locale("xx")).isEqualTo("fr");
        assertThat(MessageCopy.locale(null)).isEqualTo("fr");
        assertThat(MessageCopy.text("xx", "push.order_paid.title", Map.of())).isEqualTo("Paiement reçu");
        assertThat(MessageCopy.text("en", "push.order_paid.title", Map.of())).isEqualTo("Payment received");
    }

    @Test
    void aFrenchUserGetsFrenchWhateverTheJvmDefault() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            assertThat(MessageCopy.text("fr", "push.order_paid.body", Map.of())).isEqualTo("Tes billets sont prêts");
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void amountsAreIntegerCentimes() {
        assertThat(MessageCopy.amount(25050)).isEqualTo("250,50 DH");
        assertThat(MessageCopy.amount(5000)).isEqualTo("50,00 DH");
        assertThat(MessageCopy.amount(7)).isEqualTo("0,07 DH");
    }

    @Test
    void amountsKeepWesternDigitsUnderAnArabicJvmDefault() {
        Locale before = Locale.getDefault();
        try {
            for (String tag : new String[] {"ar", "ar-EG"}) {
                Locale.setDefault(Locale.forLanguageTag(tag));
                assertThat(MessageCopy.amount(25050)).as(tag).isEqualTo("250,50 DH");
            }
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void theDeadlineIsInTheVenueZoneWithWesternDigits() {
        Instant at = Instant.parse("2027-05-01T19:30:00Z"); // 21:30 in Paris (UTC+2 in May)
        assertThat(MessageCopy.deadline(at, "Europe/Paris", "fr")).isEqualTo("1 mai 21:30");
        assertThat(MessageCopy.deadline(at, "Europe/Paris", "en")).isEqualTo("1 May 21:30");
        assertThat(MessageCopy.deadline(at, "Europe/Paris", "ar")).startsWith("1 ").endsWith(" 21:30")
                .doesNotContainPattern("[\\x{0660}-\\x{0669}]");
    }
}
