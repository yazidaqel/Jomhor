package ma.jomhor.tickets;

import static ma.jomhor.tickets.domain.TicketToken.Result.BAD_SIG;
import static ma.jomhor.tickets.domain.TicketToken.Result.EXPIRED_SLOT;
import static ma.jomhor.tickets.domain.TicketToken.Result.WRONG_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import ma.jomhor.tickets.domain.TicketToken;
import ma.jomhor.tickets.domain.TicketToken.BadFormatException;
import ma.jomhor.tickets.domain.TicketToken.Parsed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TicketToken: decode (steps 1-2), verify (steps 3-4), ranges, ticket_no (docs/02, docs/04-api.md §Token). */
class TicketTokenTest {

    private static final byte[] KE = TokenVectorsTest.KE_C;
    private static final UUID ID = TokenVectorsTest.V7;
    private static final long EVENT = 4242;
    private static final long SLOT = 59_000_000L;
    private static final String V1 = TicketToken.encodeStatic(KE, EVENT, ID, 12);
    private static final String V2 = TicketToken.encodeRotating(TicketToken.deriveTicketKey(KE, ID), EVENT, ID, 12, SLOT);

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] raw(String token) {
        return Base64.getUrlDecoder().decode(token);
    }

    private static void badFormat(String token) {
        assertThatThrownBy(() -> TicketToken.decode(token)).isInstanceOfSatisfying(BadFormatException.class, e -> {
            assertThat(e.result()).isEqualTo(TicketToken.Result.BAD_FORMAT);
            if (token != null && !token.isEmpty()) {
                assertThat(e.getMessage()).doesNotContain(token);
            }
        });
    }

    // ---------- BAD_FORMAT ----------

    @Test
    void nonCanonicalBase64IsBadFormat() {
        badFormat(null);
        badFormat("");
        badFormat(V1 + "="); // padding
        badFormat(V2 + "=="); // padding where base64 would want it
        badFormat(V2.substring(0, 57) + "="); // padding in place of a char
        badFormat(V1 + "\n");
        badFormat(" " + V1);
        badFormat(V1.substring(0, 20) + " " + V1.substring(20));
        badFormat(V1.substring(0, 51) + "!");
        String std = Base64.getEncoder().withoutPadding().encodeToString(raw(V1));
        assertThat(std).isNotEqualTo(V1); // V1 contains '_': the standard alphabet differs
        badFormat(std); // '+' or '/'
        badFormat(V1.replace('_', '/'));
        badFormat(V1.replace('-', '+').replace('_', '/'));
        badFormat("A"); // impossible length
    }

    @Test
    void nonZeroTrailingBitsAreBadFormat() {
        // 43 bytes = 344 bits -> 58 chars carry 348 bits: the last char's low 2 bits must be 0.
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        int last = alphabet.indexOf(V2.charAt(57));
        assertThat(last & 3).isZero();
        for (int bits = 1; bits <= 3; bits++) {
            badFormat(V2.substring(0, 57) + alphabet.charAt(last | bits));
        }
        // 39 bytes = 312 bits = exactly 52 chars, no spare bits; a 40-byte body has 4 spare bits.
        String forty = b64(Arrays.copyOf(raw(V1), 40));
        int c = alphabet.indexOf(forty.charAt(forty.length() - 1));
        badFormat(forty.substring(0, forty.length() - 1) + alphabet.charAt(c | 1));
    }

    @Test
    void unknownVersionAndWrongLengthAreBadFormat() {
        for (int version : new int[] {0x00, 0x03, 0x7F, 0x80, 0xFF}) {
            byte[] b = raw(V1);
            b[0] = (byte) version;
            badFormat(b64(b));
            byte[] b2 = raw(V2);
            b2[0] = (byte) version;
            badFormat(b64(b2));
        }
        badFormat(b64(Arrays.copyOf(raw(V1), 38)));
        badFormat(b64(Arrays.copyOf(raw(V1), 40)));
        badFormat(b64(Arrays.copyOf(raw(V1), 43))); // v1 at the v2 length
        byte[] v2AsV1 = raw(V2);
        v2AsV1[0] = TicketToken.VERSION_STATIC;
        badFormat(b64(v2AsV1));
        byte[] v1AsV2 = raw(V1);
        v1AsV2[0] = TicketToken.VERSION_ROTATING;
        badFormat(b64(v1AsV2)); // v2 at the v1 length
        badFormat(b64(Arrays.copyOf(raw(V2), 42)));
        badFormat(b64(Arrays.copyOf(raw(V2), 44)));
        badFormat(b64(new byte[] {1}));
        badFormat(b64(new byte[0]));
    }

    // ---------- verify ----------

    @Test
    void slotWindowIsPlusMinusOne() {
        Parsed p = TicketToken.decode(V2);
        assertThat(TicketToken.verify(p, EVENT, KE, SLOT - 2)).contains(EXPIRED_SLOT);
        assertThat(TicketToken.verify(p, EVENT, KE, SLOT - 1)).isEmpty();
        assertThat(TicketToken.verify(p, EVENT, KE, SLOT)).isEmpty();
        assertThat(TicketToken.verify(p, EVENT, KE, SLOT + 1)).isEmpty();
        assertThat(TicketToken.verify(p, EVENT, KE, SLOT + 2)).contains(EXPIRED_SLOT);

        // the slot is in the signed fields: a valid tag for another slot doesn't help
        Parsed max = TicketToken.decode(TicketToken.encodeRotating(TicketToken.deriveTicketKey(KE, ID), EVENT, ID, 12,
                0xFFFFFFFFL));
        assertThat(TicketToken.verify(max, EVENT, KE, 0)).contains(EXPIRED_SLOT);
        assertThat(TicketToken.verify(max, EVENT, KE, 0xFFFFFFFEL)).isEmpty();
        // v1 has no slot: any now is fine
        assertThat(TicketToken.verify(TicketToken.decode(V1), EVENT, KE, 0)).isEmpty();
        assertThatThrownBy(() -> TicketToken.verify(p, EVENT, KE, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TicketToken.verify(p, EVENT, KE, 0x1_0000_0000L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongEventComesFirst() {
        assertThat(TicketToken.verify(TicketToken.decode(V1), EVENT + 1, KE, 0)).contains(WRONG_EVENT);
        assertThat(TicketToken.verify(TicketToken.decode(V2), EVENT + 1, KE, 0)).contains(WRONG_EVENT); // before slot
        assertThat(TicketToken.verify(TicketToken.decode(V2), EVENT, KE, 0)).contains(EXPIRED_SLOT); // before sig
        byte[] otherKey = TokenVectorsTest.KE_D;
        assertThat(TicketToken.verify(TicketToken.decode(V1), EVENT, otherKey, 0)).contains(BAD_SIG);
        assertThat(TicketToken.verify(TicketToken.decode(V2), EVENT, otherKey, SLOT)).contains(BAD_SIG);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyFlippedBitIsBadSigOrBadFormat(boolean rotating) {
        byte[] good = raw(rotating ? V2 : V1);
        for (int i = 0; i < good.length; i++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] b = good.clone();
                b[i] ^= (byte) (1 << bit);
                String t = b64(b);
                if (i == 0) {
                    badFormat(t); // version byte: unknown, or the other version at the wrong length
                    continue;
                }
                Parsed p = TicketToken.decode(t);
                // verify against the token's own (flipped) event and slot so only the tag can fail
                long now = p.slot().orElse(0);
                assertThat(TicketToken.verify(p, p.eventId(), KE, now)).as("byte %d bit %d", i, bit).contains(BAD_SIG);
            }
        }
    }

    @Test
    void eventKeyAndTicketKeyAreNotInterchangeable() {
        byte[] kT = TicketToken.deriveTicketKey(KE, ID);
        // v2 signed with K_e instead of K_t
        Parsed v2WithKe = TicketToken.decode(TicketToken.encodeRotating(KE, EVENT, ID, 12, SLOT));
        assertThat(TicketToken.verify(v2WithKe, EVENT, KE, SLOT)).contains(BAD_SIG);
        // v1 signed with K_t instead of K_e
        Parsed v1WithKt = TicketToken.decode(TicketToken.encodeStatic(kT, EVENT, ID, 12));
        assertThat(TicketToken.verify(v1WithKt, EVENT, KE, 0)).contains(BAD_SIG);
        // verifying with K_t as if it were the event key
        assertThat(TicketToken.verify(TicketToken.decode(V2), EVENT, kT, SLOT)).contains(BAD_SIG);
        // another ticket's K_t
        UUID other = TokenVectorsTest.V4;
        Parsed foreign = TicketToken.decode(TicketToken.encodeRotating(TicketToken.deriveTicketKey(KE, other), EVENT, ID, 12, SLOT));
        assertThat(TicketToken.verify(foreign, EVENT, KE, SLOT)).contains(BAD_SIG);
        // verify does not consume the caller's key
        assertThat(TicketToken.deriveTicketKey(KE, ID)).isEqualTo(kT);
    }

    // ---------- ranges, slot, ticket_no ----------

    @Test
    void rangesAndKeyLengths() {
        byte[] kT = TicketToken.deriveTicketKey(KE, ID);
        for (long bad : new long[] {-1, 0x1_0000_0000L}) {
            assertThatThrownBy(() -> TicketToken.encodeStatic(KE, bad, ID, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TicketToken.encodeRotating(kT, 1, ID, 0, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int bad : new int[] {-1, 0x10000}) {
            assertThatThrownBy(() -> TicketToken.encodeStatic(KE, 1, ID, bad)).isInstanceOf(IllegalArgumentException.class);
        }
        for (byte[] key : new byte[][] {null, new byte[31], new byte[33]}) {
            assertThatThrownBy(() -> TicketToken.encodeStatic(key, 1, ID, 0)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("eventKey must be 32 bytes");
            assertThatThrownBy(() -> TicketToken.encodeRotating(key, 1, ID, 0, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TicketToken.deriveTicketKey(key, ID)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> TicketToken.verify(TicketToken.decode(V1), EVENT, key, 0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // a key never shows up in a message
        byte[] k31 = Arrays.copyOf(KE, 31);
        assertThatThrownBy(() -> TicketToken.encodeStatic(k31, 1, ID, 0))
                .message().doesNotContain(HexFormat.of().formatHex(k31).substring(0, 8));
    }

    @Test
    void slotAt() {
        assertThat(TicketToken.slotAt(Instant.EPOCH)).isZero();
        assertThat(TicketToken.slotAt(Instant.ofEpochSecond(29, 999_999_999))).isZero();
        assertThat(TicketToken.slotAt(Instant.ofEpochSecond(30))).isEqualTo(1);
        assertThat(TicketToken.slotAt(Instant.ofEpochSecond(-1))).isEqualTo(-1);
        assertThat(TicketToken.slotAt(Instant.parse("2026-11-06T20:00:00Z"))).isEqualTo(59_799_840L);
        assertThat(TicketToken.slotAt(Instant.parse("2026-11-06T20:00:29.999Z"))).isEqualTo(59_799_840L);
    }

    @Test
    void ticketNo() {
        assertThat(TicketToken.ticketNo(ID)).isEqualTo("4D5C 6B7A");
        assertThat(TicketToken.ticketNo(TokenVectorsTest.ZEROS)).isEqualTo("0000 0000");
        assertThat(TicketToken.ticketNo(TokenVectorsTest.V4)).isEqualTo("B2C3 D479");
    }
}
