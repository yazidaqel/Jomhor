package ma.jomhor.tickets.adapters.pdf;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * The printable ticket (docs/04-api.md GET /fan/tickets/{id}/pdf): an A4 page with the details and a dashed
 * wallet-size (85.6 x 54 mm) cut-out holding the static v1 QR (version 4, ECC M, 4-module quiet zone) with the
 * ticket number under it. Holder label only: no full name, no phone. Standard fonts (WinAnsi): Latin text only.
 */
public final class TicketPdf {

    /** Everything printed. {@code token} is the v1 QR payload; {@code holderLabel} may be null (Lot 0 ticket). */
    public record Content(String token, String ticketNo, String title, Instant startsAt, String timezone, String venue,
                   String zone, List<String> gateCodes, String holderLabel) {
        @Override
        public String toString() {
            return "TicketPdf.Content[" + ticketNo + "]";
        }
    }

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy '\u00b7' HH:mm", Locale.FRENCH);
    private static final float MM = 72f / 25.4f;
    private static final float CARD_W = 85.6f * MM;
    private static final float CARD_H = 54f * MM;
    private static final int QUIET = 4;
    private static final float GREY = 0.42f;

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private TicketPdf() {}

    /** French date and time of {@code startsAt} in the venue's IANA zone, e.g. "sam. 19 d\u00e9c. 2026 \u00b7 19:00". */
    public static String when(Instant startsAt, String timezone) {
        return DATE.format(startsAt.atZone(ZoneId.of(timezone)));
    }

    public static byte[] render(Content c) {
        return new TicketPdf().draw(c);
    }

    private byte[] draw(Content c) {
        String when = when(c.startsAt(), c.timezone());
        String gates = c.gateCodes().isEmpty() ? null : "Portes " + String.join(", ", c.gateCodes());
        String holder = c.holderLabel() == null ? null : "Titulaire " + c.holderLabel();
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float pageW = page.getMediaBox().getWidth();
            float pageH = page.getMediaBox().getHeight();
            float left = 56;
            float width = pageW - 2 * left;
            try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                float y = pageH - 72;
                text(s, regular, 10, left, y, "BILLET", width, GREY);
                y -= 30;
                text(s, bold, 22, left, y, c.title(), width, 0);
                y -= 24;
                for (String line : new String[] {when, c.venue(), c.zone(), gates, holder,
                        "N\u00b0 de billet " + c.ticketNo()}) {
                    if (line != null) {
                        text(s, regular, 12, left, y, line, width, 0);
                        y -= 18;
                    }
                }
                y -= 10;
                text(s, regular, 10, left, y, "Pr\u00e9sentez le QR code \u00e0 l'entr\u00e9e, imprim\u00e9 ou sur"
                        + " votre t\u00e9l\u00e9phone. Un billet ne permet qu'une seule entr\u00e9e.", width, GREY);
                y -= 14;
                text(s, regular, 10, left, y, "D\u00e9coupez le billet en suivant les pointill\u00e9s.", width, GREY);

                // The cut-out, centred under the details.
                float cardX = (pageW - CARD_W) / 2;
                float cardY = y - 40 - CARD_H;
                s.setStrokingColor(GREY, GREY, GREY);
                s.setLineWidth(0.8f);
                s.setLineDashPattern(new float[] {4, 3}, 0);
                s.addRect(cardX, cardY, CARD_W, CARD_H);
                s.stroke();
                s.setLineDashPattern(new float[] {}, 0);

                // QR block: quiet zone included in its square, ticket number centred under it.
                float qrSide = CARD_H - 22;
                float qrX = cardX + 4;
                float qrTop = cardY + CARD_H - 4;
                qr(s, c.token(), qrX, qrTop - qrSide, qrSide);
                float noWidth = width(bold, 10, c.ticketNo());
                text(s, bold, 10, qrX + (qrSide - noWidth) / 2, cardY + 7, c.ticketNo(), qrSide, 0);

                // Card details beside the QR.
                float tx = qrX + qrSide + 4;
                float tw = cardX + CARD_W - 6 - tx;
                float ty = cardY + CARD_H - 18;
                text(s, bold, 9, tx, ty, c.title(), tw, 0);
                ty -= 13;
                text(s, regular, 7.5f, tx, ty, when, tw, 0);
                ty -= 10;
                text(s, regular, 7.5f, tx, ty, c.venue(), tw, 0);
                ty -= 17;
                text(s, bold, 11, tx, ty, c.zone(), tw, 0);
                ty -= 12;
                for (String line : new String[] {gates, holder}) {
                    if (line != null) {
                        text(s, regular, 7.5f, tx, ty, line, tw, 0);
                        ty -= 10;
                    }
                }

                text(s, regular, 8, left, 40, "Propuls\u00e9 par Jomhor", width, GREY);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Ticket PDF not rendered", e);
        }
    }

    /**
     * Dark modules as filled rectangles (vector: sharp at any print size), on a white square with the quiet zone.
     * Each horizontal run of dark modules is one rectangle, so no antialiasing seam shows between neighbours.
     */
    private static void qr(PDPageContentStream s, String token, float x, float y, float side) throws IOException {
        ByteMatrix m;
        try {
            m = Encoder.encode(token, ErrorCorrectionLevel.M, Map.of(EncodeHintType.QR_VERSION, 4)).getMatrix();
        } catch (WriterException e) {
            throw new IllegalStateException("QR not encoded"); // not chained: the message would quote the token
        }
        int n = m.getWidth() + 2 * QUIET;
        float module = side / n;
        s.setNonStrokingColor(1f, 1f, 1f);
        s.addRect(x, y, side, side);
        s.fill();
        s.setNonStrokingColor(0f, 0f, 0f);
        for (int row = 0; row < m.getHeight(); row++) {
            for (int col = 0; col < m.getWidth(); col++) {
                if (m.get(col, row) == 1) {
                    int start = col;
                    while (col + 1 < m.getWidth() && m.get(col + 1, row) == 1) {
                        col++;
                    }
                    s.addRect(x + (QUIET + start) * module, y + side - (QUIET + row + 1) * module,
                            (col - start + 1) * module, module);
                }
            }
        }
        s.fill();
    }

    /** One line, cut with "..." to fit {@code maxWidth}; characters the standard font can't encode become "?". */
    private static void text(PDPageContentStream s, PDFont font, float size, float x, float y, String text,
                             float maxWidth, float grey) throws IOException {
        String line = winAnsi(font, text);
        if (width(font, size, line) > maxWidth) {
            while (!line.isEmpty() && width(font, size, line + "...") > maxWidth) {
                line = line.substring(0, line.length() - 1);
            }
            line = line.stripTrailing() + "...";
        }
        s.setNonStrokingColor(grey, grey, grey);
        s.beginText();
        s.setFont(font, size);
        s.newLineAtOffset(x, y);
        s.showText(line);
        s.endText();
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    // ponytail: Latin only (plan Q16); Arabic titles and names need an embedded font and shaping (Lot 4).
    private static String winAnsi(PDFont font, String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String ch = Character.toString(cp);
            try {
                font.encode(ch);
                out.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                out.append('?');
            }
        });
        return out.toString();
    }
}
