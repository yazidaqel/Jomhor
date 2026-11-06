package ma.jomhor.common;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Env secrets checked at startup. Messages name the variable only, never the value. */
public final class Secrets {

    private Secrets() {}

    /** Key bytes of an env secret; fails startup if it is shorter than 32 bytes. */
    public static byte[] atLeast32Bytes(String name, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(name + " must be at least 32 bytes");
        }
        return bytes;
    }

    /** An AES-256 key from env: standard base64 of exactly 32 bytes, else startup fails. */
    public static byte[] base64Of32Bytes(String name, String value) {
        byte[] bytes;
        try {
            bytes = value == null ? new byte[0] : Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            bytes = new byte[0]; // not chained: the decoder's message quotes a character of the secret
        }
        if (bytes.length != 32) {
            throw new IllegalStateException(name + " must be base64 of exactly 32 bytes");
        }
        return bytes;
    }
}
