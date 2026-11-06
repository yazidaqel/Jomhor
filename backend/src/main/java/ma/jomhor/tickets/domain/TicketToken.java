package ma.jomhor.tickets.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Ticket token codec, byte for byte the same as {@code packages/core} (docs/04-api.md §Token,
 * docs/02 §Ticket token &amp; QR format):
 *
 * <pre>version(1) | event_id(4, BE) | ticket_id(16) | zone_id(2, BE) | [slot(4, BE)] | tag(16)</pre>
 *
 * v1 static (PDF) is 39 bytes signed with K_e; v2 rotating (fan app) is 43 bytes signed with
 * K_t = HMAC-SHA256(K_e, ticket_id). Base64url without padding (52 / 58 chars). No personal data.
 * Ids are unsigned: event_id and slot 0..0xFFFFFFFF ({@code long}), zone_id 0..0xFFFF ({@code int}).
 * Nothing here logs, and no message or {@code toString} carries a key or a tag.
 */
public final class TicketToken {

    public static final int VERSION_STATIC = 0x01;
    public static final int VERSION_ROTATING = 0x02;
    public static final int SLOT_SECONDS = 30;

    private static final int TAG_LENGTH = 16;
    private static final int KEY_LENGTH = 32;
    private static final int V1_LENGTH = 39;
    private static final int V2_LENGTH = 43;
    private static final long MAX_U32 = 0xFFFFFFFFL;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private TicketToken() {}

    /** Scan result codes this codec can produce (docs/02 §Offline validation algorithm, steps 1-4). */
    public enum Result { BAD_FORMAT, WRONG_EVENT, EXPIRED_SLOT, BAD_SIG }

    /** Not a well-formed token (steps 1-2). The message is a short key-free reason; the input is never quoted. */
    public static final class BadFormatException extends IllegalArgumentException {
        BadFormatException(String reason) {
            super(reason);
        }

        public Result result() {
            return Result.BAD_FORMAT;
        }
    }

    /** A structurally valid token; authenticity is checked by {@link #verify}, not here. */
    public static final class Parsed {
        private final byte[] bytes;

        private Parsed(byte[] bytes) {
            this.bytes = bytes;
        }

        public int version() {
            return bytes[0];
        }

        public long eventId() {
            return Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt(1));
        }

        public UUID ticketId() {
            ByteBuffer b = ByteBuffer.wrap(bytes);
            return new UUID(b.getLong(5), b.getLong(13));
        }

        public int zoneId() {
            return Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort(21));
        }

        /** The slot of a v2 token; empty for v1. */
        public OptionalLong slot() {
            return version() == VERSION_ROTATING
                    ? OptionalLong.of(Integer.toUnsignedLong(ByteBuffer.wrap(bytes).getInt(23)))
                    : OptionalLong.empty();
        }

        private byte[] signed() {
            return Arrays.copyOf(bytes, bytes.length - TAG_LENGTH);
        }

        private byte[] tag() {
            return Arrays.copyOfRange(bytes, bytes.length - TAG_LENGTH, bytes.length);
        }
    }

    /** K_t = HMAC-SHA256(K_e, ticket_id bytes), 32 bytes. Only ever sent to the holder's account. */
    public static byte[] deriveTicketKey(byte[] eventKey, UUID ticketId) {
        checkKey(eventKey, "eventKey");
        return hmac(eventKey, uuidBytes(ticketId));
    }

    /** v1 static token signed with K_e (the PDF QR). */
    public static String encodeStatic(byte[] eventKey, long eventId, UUID ticketId, int zoneId) {
        checkKey(eventKey, "eventKey");
        return encode(eventKey, body(VERSION_STATIC, eventId, ticketId, zoneId, null));
    }

    /** v2 rotating token signed with K_t (see {@link #deriveTicketKey}), over the fields including slot. */
    public static String encodeRotating(byte[] ticketKey, long eventId, UUID ticketId, int zoneId, long slot) {
        checkKey(ticketKey, "ticketKey");
        return encode(ticketKey, body(VERSION_ROTATING, eventId, ticketId, zoneId, slot));
    }

    /** floor(unix seconds / 30). */
    public static long slotAt(Instant time) {
        return Math.floorDiv(time.getEpochSecond(), SLOT_SECONDS);
    }

    /**
     * Steps 1-2: canonical base64url without padding only (no padding, no {@code +}/{@code /}, no non-zero trailing
     * bits, so exactly one string maps to a token), a known version and the exact length for it.
     */
    public static Parsed decode(String token) {
        if (token == null) {
            throw new BadFormatException("empty");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new BadFormatException("not base64url"); // not chained: the decoder's message quotes the input
        }
        if (!B64.encodeToString(bytes).equals(token)) {
            throw new BadFormatException("not canonical base64url");
        }
        if (bytes.length == 0) {
            throw new BadFormatException("empty");
        }
        int expected = switch (bytes[0]) {
            case VERSION_STATIC -> V1_LENGTH;
            case VERSION_ROTATING -> V2_LENGTH;
            default -> throw new BadFormatException("unknown version");
        };
        if (bytes.length != expected) {
            throw new BadFormatException("wrong length for version");
        }
        return new Parsed(bytes);
    }

    /**
     * Steps 3-4 for one loaded event: the first failure ({@code WRONG_EVENT}, {@code EXPIRED_SLOT} for a v2 slot
     * outside {@code nowSlot} ± 1, {@code BAD_SIG}), or empty when the token is authentic. Empty is not OK: manifest,
     * zone and used-set checks (steps 5-8) remain. {@code nowSlot} is the server-time-corrected {@link #slotAt}.
     */
    public static Optional<Result> verify(Parsed token, long eventId, byte[] eventKey, long nowSlot) {
        checkRange(nowSlot, MAX_U32, "nowSlot");
        checkKey(eventKey, "eventKey");
        if (token.eventId() != eventId) {
            return Optional.of(Result.WRONG_EVENT);
        }
        byte[] key = eventKey;
        if (token.version() == VERSION_ROTATING) {
            if (Math.abs(token.slot().orElseThrow() - nowSlot) > 1) {
                return Optional.of(Result.EXPIRED_SLOT);
            }
            key = deriveTicketKey(eventKey, token.ticketId());
        }
        byte[] expected = Arrays.copyOf(hmac(key, token.signed()), TAG_LENGTH);
        boolean ok = MessageDigest.isEqual(expected, token.tag());
        if (key != eventKey) {
            Arrays.fill(key, (byte) 0);
        }
        return ok ? Optional.empty() : Optional.of(Result.BAD_SIG);
    }

    /** docs/04-api.md §Holder names: last 8 hex chars of ticket_id, uppercase, grouped 4-4 ({@code 4D5C 6B7A}). */
    public static String ticketNo(UUID ticketId) {
        String hex = ticketId.toString().toUpperCase(Locale.ROOT);
        return hex.substring(28, 32) + " " + hex.substring(32);
    }

    private static byte[] body(int version, long eventId, UUID ticketId, int zoneId, Long slot) {
        checkRange(eventId, MAX_U32, "eventId");
        checkRange(zoneId, 0xFFFF, "zoneId");
        ByteBuffer b = ByteBuffer.allocate((slot == null ? V1_LENGTH : V2_LENGTH) - TAG_LENGTH)
                .put((byte) version).putInt((int) eventId)
                .putLong(ticketId.getMostSignificantBits()).putLong(ticketId.getLeastSignificantBits())
                .putShort((short) zoneId);
        if (slot != null) {
            checkRange(slot, MAX_U32, "slot");
            b.putInt((int) (long) slot);
        }
        return b.array();
    }

    private static String encode(byte[] key, byte[] body) {
        byte[] out = Arrays.copyOf(body, body.length + TAG_LENGTH);
        System.arraycopy(hmac(key, body), 0, out, body.length, TAG_LENGTH);
        return B64.encodeToString(out);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable"); // not chained: keep key material out of traces
        }
    }

    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    private static void checkKey(byte[] key, String name) {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IllegalArgumentException(name + " must be 32 bytes");
        }
    }

    private static void checkRange(long value, long max, String name) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " out of range 0.." + max);
        }
    }
}
