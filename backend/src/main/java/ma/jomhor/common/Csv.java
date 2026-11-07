package ma.jomhor.common;

import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;

/** The CSV exports of docs/04-api.md: {@code ;}-separated, CRLF, UTF-8 with a BOM (Excel FR opens them as they are). */
public final class Csv {

    public static final MediaType MEDIA_TYPE = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private Csv() {}

    /** The UTF-8 bytes of {@code text} after the BOM. */
    public static byte[] withBom(String text) {
        byte[] t = text.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[BOM.length + t.length];
        System.arraycopy(BOM, 0, body, 0, BOM.length);
        System.arraycopy(t, 0, body, BOM.length, t.length);
        return body;
    }

    /**
     * A free-text cell (a label, a name typed by staff): a leading {@code = + - @}, tab or CR gets a {@code '} first,
     * so a spreadsheet never runs it as a formula (OWASP CSV injection); then RFC 4180 quoting when it holds the
     * separator, a quote or a line break. Numbers and amounts are not text: never pass them here.
     */
    public static String text(String s) {
        String v = !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
        return v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")
                ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }
}
