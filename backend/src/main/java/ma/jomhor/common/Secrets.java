package ma.jomhor.common;

import java.nio.charset.StandardCharsets;

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
}
