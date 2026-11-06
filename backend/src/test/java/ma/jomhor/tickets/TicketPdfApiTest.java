package ma.jomhor.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.jayway.jsonpath.JsonPath;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.tickets.domain.TicketToken;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Contract: GET /fan/tickets/{id}/pdf (docs/04-api.md \u00a7Fan; plan BE-13). Holder only (404), VALID only (409
 * TICKET_NOT_VALID), application/pdf attachment billet-XXXX-XXXX.pdf, no-store; the QR decodes to a v1 token
 * signed with K_e; the date is in the venue's zone; no full name, phone or key in the document.
 */
class TicketPdfApiTest extends PaymentTest {

    private static final String TITLE = "Atlas FC - Raja CA";

    @Autowired
    EventService events;

    /** A published event at a venue in {@code timezone}, zone NORD with gates P2 and P1. */
    private Sale sale(String timezone, String startsAt) throws Exception {
        Caller org = admin();
        String venueId = JsonPath.read(body(call(org, post("/org/venues"), """
                {"name": "Grand Stade", "city": "Casablanca", "capacity": 1000, "timezone": "%s"}
                """.formatted(timezone)).andExpect(status().isCreated())), "$.id");
        int zoneId = zone(org, venueId, "NORD");
        for (String code : List.of("P2", "P1")) {
            String gate = JsonPath.read(body(call(org, post("/org/venues/" + venueId + "/gates"),
                    "{\"code\": \"%s\", \"name\": \"Porte %s\"}".formatted(code, code))
                    .andExpect(status().isCreated())), "$.id");
            call(org, put("/org/gates/" + gate + "/zones"), "{\"zone_ids\": [%d]}".formatted(zoneId))
                    .andExpect(status().isOk());
        }
        int eventId = JsonPath.read(body(call(org, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "%s", "starts_at": "%s"}
                """.formatted(venueId, TITLE, startsAt)).andExpect(status().isCreated())), "$.id");
        String priceId = price(org, eventId, zoneId, "Plein tarif", 100);
        call(org, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());
        return new Sale(org, zoneId, eventId, priceId);
    }

    private void buy(String token, Sale s, int qty, String holders) throws Exception {
        String orderId = JsonPath.read(body(fan(token, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(s.eventId(), s.priceId(), qty, holders)).andExpect(status().isCreated())), "$.id");
        callback(approve(payment(token, orderId), total(orderId))).andExpect(status().isSeeOther());
    }

    private UUID ticketOf(String phone) {
        return jdbc.sql("select t.id from tickets t join users u on u.id = t.holder_user_id where u.phone_e164 = ?")
                .param(phone).query(UUID.class).single();
    }

    private byte[] pdf(String token, UUID ticketId) throws Exception {
        return fan(token, get("/fan/tickets/" + ticketId + "/pdf")).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    /** Renders the page and decodes its one QR code. */
    private static Result qr(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            BufferedImage img = new PDFRenderer(doc).renderImageWithDPI(0, 200);
            int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                            new RGBLuminanceSource(img.getWidth(), img.getHeight(), pixels))),
                    Map.of(DecodeHintType.TRY_HARDER, true,
                            DecodeHintType.POSSIBLE_FORMATS, List.of(BarcodeFormat.QR_CODE)));
        }
    }

    @Test
    void theHolderGetsAPrintableTicketWithAV1QrSignedWithTheEventKey() throws Exception {
        Sale s = sale("Africa/Casablanca", "2026-12-19T19:00:00Z");
        String phone = randomPhone();
        String fan = fanToken(phone);
        buy(fan, s, 1, "[{\"name\": \"Yazid Aqel\"}]");
        UUID ticketId = ticketOf(phone);
        String ticketNo = TicketToken.ticketNo(ticketId);

        byte[] pdf = fan(fan, get("/fan/tickets/" + ticketId + "/pdf")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"billet-" + ticketNo.replace(' ', '-') + ".pdf\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();

        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            PDRectangle box = doc.getPage(0).getMediaBox();
            assertThat(box.getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(box.getHeight()).isEqualTo(PDRectangle.A4.getHeight());
        }
        String text = text(pdf);
        assertThat(text).contains(TITLE).contains("Zone NORD").contains(ticketNo).contains("Yazid A.")
                .contains("Grand Stade, Casablanca").contains("Portes P1, P2")
                // The hour per the JDK tzdata: Morocco sets its offset by decree (2026e: UTC+0 in December).
                .contains("sam. 19 d\u00e9c. 2026 \u00b7 " + Instant.parse("2026-12-19T19:00:00Z")
                        .atZone(ZoneId.of("Africa/Casablanca")).format(DateTimeFormatter.ofPattern("HH:mm")));

        Result qr = qr(pdf);
        assertThat(qr.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL)).isEqualTo("M");
        TicketToken.Parsed token = TicketToken.decode(qr.getText());
        assertThat(token.version()).isEqualTo(TicketToken.VERSION_STATIC);
        assertThat(token.eventId()).isEqualTo(s.eventId());
        assertThat(token.ticketId()).isEqualTo(ticketId);
        assertThat(token.zoneId()).isEqualTo(s.zoneId());
        boolean verified = events.withEventKey(s.org().org(), s.eventId(),
                k -> TicketToken.verify(token, s.eventId(), k, 0).isEmpty());
        assertThat(verified).isTrue();

        // No full name, phone, K_e, K_t, or token text. The extracted text is the real check: page content is
        // Flate-compressed, so the raw bytes only cover the uncompressed parts (trailer, document info).
        byte[] eventKey = events.withEventKey(s.org().org(), s.eventId(), byte[]::clone);
        String kt = JsonPath.read(body(fan(fan, get("/fan/tickets?with_keys=true"))), "$.tickets[0].k_t");
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        for (String haystack : List.of(text, raw)) {
            assertThat(haystack).doesNotContain("Aqel").doesNotContain(phone).doesNotContain(phone.substring(1))
                    .doesNotContain(Base64.getEncoder().withoutPadding().encodeToString(eventKey))
                    .doesNotContain(Base64.getUrlEncoder().withoutPadding().encodeToString(eventKey))
                    .doesNotContain(kt).doesNotContain(qr.getText());
        }
    }

    @Test
    void theDateIsInTheVenueTimeZone() throws Exception {
        Sale s = sale("Asia/Tokyo", "2099-05-01T19:00:00Z");
        String phone = randomPhone();
        String fan = fanToken(phone);
        buy(fan, s, 1, "[{\"name\": \"Yazid Aqel\"}]");
        assertThat(text(pdf(fan, ticketOf(phone)))).contains("sam. 2 mai 2099 \u00b7 04:00");
    }

    @Test
    void onlyTheHolderGetsThePdf() throws Exception {
        Sale s = sale("Africa/Casablanca", "2099-05-01T19:00:00Z");
        String buyerPhone = randomPhone();
        String friendPhone = randomPhone();
        String buyer = fanToken(buyerPhone);
        buy(buyer, s, 2, """
                [{"name": "Yazid Aqel"}, {"name": "Samir Benali", "phone": "%s"}]""".formatted(friendPhone));
        UUID friendTicket = ticketOf(friendPhone);

        // The buyer of a friend's ticket gets a 404, the same as for an unknown ticket.
        for (UUID id : List.of(friendTicket, UUID.randomUUID())) {
            fan(buyer, get("/fan/tickets/" + id + "/pdf")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        fan(buyer, get("/fan/tickets/" + friendTicket + "/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isNotFound());
        String friendText = text(pdf(fanToken(friendPhone), friendTicket));
        assertThat(friendText).contains("Samir B.").doesNotContain("Benali").doesNotContain("Yazid");
        assertThat(text(pdf(buyer, ticketOf(buyerPhone)))).contains("Yazid A.").doesNotContain("Samir");

        mvc.perform(get("/fan/tickets/" + friendTicket + "/pdf")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fan/tickets/" + friendTicket + "/pdf").header("Authorization", "Bearer " + s.org().token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aTicketNotValidIsRefused() throws Exception {
        Sale s = sale("Africa/Casablanca", "2099-05-01T19:00:00Z");
        String phone = randomPhone();
        String fan = fanToken(phone);
        buy(fan, s, 1, "[{\"name\": \"Yazid Aqel\"}]");
        UUID ticketId = ticketOf(phone);
        for (String status : List.of("BLOCKED", "CANCELLED", "REFUNDED")) {
            jdbc.sql("update tickets set status = ?::ticket_status where id = ?").params(status, ticketId).update();
            fan(fan, get("/fan/tickets/" + ticketId + "/pdf")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TICKET_NOT_VALID"));
        }
        fan(fan, get("/fan/tickets/" + ticketId + "/pdf").accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isConflict());
    }

    /** A malformed id is a 400 BAD_REQUEST in JSON, also when the client asks for a PDF. */
    @Test
    void aMalformedIdIsABadRequest() throws Exception {
        String fan = fanToken(randomPhone());
        for (MediaType accept : List.of(MediaType.ALL, MediaType.APPLICATION_PDF)) {
            fan(fan, get("/fan/tickets/not-a-uuid/pdf").accept(accept)).andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
    }

    /** A Lot 0 ticket has no holder name: the PDF is served without a holder line. */
    @Test
    void aLot0TicketHasNoHolderLine() throws Exception {
        Sale s = sale("Africa/Casablanca", "2099-05-01T19:00:00Z");
        String phone = randomPhone();
        String fan = fanToken(phone);
        buy(fan, s, 1, "[{\"name\": \"Yazid Aqel\"}]");
        UUID ticketId = ticketOf(phone);
        jdbc.sql("update tickets set holder_name = null, holder_initials = null where id = ?").param(ticketId).update();
        assertThat(text(pdf(fan, ticketId))).doesNotContain("Titulaire").contains(TicketToken.ticketNo(ticketId));
    }
}
