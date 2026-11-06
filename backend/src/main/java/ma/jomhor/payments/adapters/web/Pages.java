package ma.jomhor.payments.adapters.web;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The few browser pages of payments: plain FR HTML, no script but an auto-submit, nothing secret. */
public final class Pages {

    private Pages() {}

    /** {@code body} must already be escaped. */
    public static ResponseEntity<String> html(HttpStatus status, String title, String body) {
        String page = """
                <!DOCTYPE html>
                <html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                <title>%s</title></head>
                <body style="font-family: system-ui, sans-serif; max-width: 32rem; margin: 3rem auto; padding: 0 1rem">
                %s
                </body></html>
                """.formatted(title, body);
        return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Cache-Control", "no-store").body(page);
    }

    /** Centimes as "1234,50 DH", integer maths only. */
    public static String dirhams(long centimes) {
        return "%d,%02d DH".formatted(centimes / 100, centimes % 100);
    }
}
