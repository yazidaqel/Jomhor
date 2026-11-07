package ma.jomhor.tickets.adapters.pdf;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ma.jomhor.common.HolderNames;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;

/**
 * The printable ticket (docs/04-api.md GET /fan/tickets/{id}/pdf): an A4 page with the details and a dashed
 * wallet-size (85.6 x 54 mm) cut-out holding the static v1 QR (version 4, ECC M, 4-module quiet zone) with the
 * ticket number under it. Holder label only: no full name, no phone. Latin lines use the standard fonts (WinAnsi);
 * a line with Arabic script is shaped (ICU ArabicShaping), put in visual order (ICU Bidi) and drawn with the embedded
 * IBM Plex Sans Arabic (OFL, resources/fonts), subset. The fixed labels stay French (04 §Holder names, PDF).
 * The header carries the Jomhor lockup (design/BRAND.md, PDF ticket), drawn as vector paths from its SVG.
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

    private static final Pattern ARABIC = Pattern.compile("\\p{IsArabic}");
    private static final byte[] ARABIC_REGULAR = resource("/fonts/IBMPlexSansArabic-Regular.ttf");
    private static final byte[] ARABIC_BOLD = resource("/fonts/IBMPlexSansArabic-Bold.ttf");
    /** design/logo/jomhor-lockup-light.svg copied as is: ink J and wordmark outlines, the amber twelfth dot. */
    private static final String LOCKUP =
            new String(resource("/logo/jomhor-lockup-light.svg"), StandardCharsets.UTF_8);
    /** SVG units to points: the drawn lockup is ~123 pt wide, over the 120 px minimum (BRAND.md). */
    private static final float LOCKUP_SCALE = 0.3f;
    private static final Pattern SVG_ELEMENT = Pattern.compile("<(g|circle|path)\\s([^>]*)>|</g>");
    private static final Pattern SVG_TOKEN = Pattern.compile("[A-Za-z]|-?[\\d.]+");
    /** First-strong isolate and its end: the holder label keeps its own direction inside the French line. */
    private static final String FSI = Character.toString(0x2068);
    private static final String PDI = Character.toString(0x2069);

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDDocument doc = new PDDocument();
    /** Loaded on the first Arabic line, so a Latin-only ticket embeds no font. */
    private PDFont arabicRegular;
    private PDFont arabicBold;

    private TicketPdf() {}

    private static byte[] resource(String path) {
        try (var in = TicketPdf.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Resource missing: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

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
        String holder = c.holderLabel() == null ? null : "Titulaire " + FSI + c.holderLabel() + PDI;
        try (doc; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            float pageW = page.getMediaBox().getWidth();
            float pageH = page.getMediaBox().getHeight();
            float left = 56;
            float width = pageW - 2 * left;
            try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
                // The SVG's top-left corner; its own margins (one dot and more) are the lockup's clear space, and
                // its drawn left edge lines up with the text. Above "BILLET": nothing below it moved.
                lockup(s, left - 8.5f, pageH - 24);
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
     * The lockup SVG at {@link #LOCKUP_SCALE}, top-left corner at (x, top). Reads only what the file uses: nested
     * {@code <g transform="translate(..) scale(..)">}, filled circles, filled paths in absolute M L H V C Z.
     */
    private static void lockup(PDPageContentStream s, float x, float top) throws IOException {
        s.saveGraphicsState();
        s.transform(new Matrix(LOCKUP_SCALE, 0, 0, -LOCKUP_SCALE, x, top)); // SVG y runs down
        Matcher e = SVG_ELEMENT.matcher(LOCKUP);
        while (e.find()) {
            String attrs = e.group(2);
            if (e.group(1) == null) {
                s.restoreGraphicsState();
            } else if (e.group(1).equals("g")) {
                float[] t = numbers(attr(attrs, "transform")); // translate(tx,ty) [scale(k)]
                float k = t.length > 2 ? t[2] : 1;
                s.saveGraphicsState();
                s.transform(new Matrix(k, 0, 0, k, t[0], t[1]));
            } else {
                s.setNonStrokingColor(new Color(Integer.parseInt(attr(attrs, "fill").substring(1), 16)));
                if (e.group(1).equals("circle")) {
                    circle(s, numbers(attr(attrs, "cx"))[0], numbers(attr(attrs, "cy"))[0],
                            numbers(attr(attrs, "r"))[0]);
                } else {
                    path(s, attr(attrs, "d"));
                }
                s.fill();
            }
        }
        s.restoreGraphicsState();
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + name + "=\"([^\"]*)\"").matcher(attrs);
        if (!m.find()) {
            throw new IllegalStateException("Lockup SVG: no " + name);
        }
        return m.group(1);
    }

    private static float[] numbers(String text) {
        List<String> n = SVG_TOKEN.matcher(text).results().map(MatchResult::group)
                .filter(t -> !Character.isLetter(t.charAt(0))).toList();
        float[] out = new float[n.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Float.parseFloat(n.get(i));
        }
        return out;
    }

    private static void circle(PDPageContentStream s, float cx, float cy, float r) throws IOException {
        float k = 0.5523f * r; // four cubic arcs
        s.moveTo(cx + r, cy);
        s.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        s.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        s.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        s.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        s.closePath();
    }

    private static void path(PDPageContentStream s, String d) throws IOException {
        List<String> t = SVG_TOKEN.matcher(d).results().map(MatchResult::group).toList();
        char cmd = 'M';
        float x = 0;
        float y = 0;
        for (int i = 0; i < t.size(); ) {
            if (Character.isLetter(t.get(i).charAt(0))) {
                cmd = t.get(i++).charAt(0);
            }
            switch (cmd) {
                case 'M', 'L' -> {
                    x = Float.parseFloat(t.get(i++));
                    y = Float.parseFloat(t.get(i++));
                    if (cmd == 'M') {
                        s.moveTo(x, y);
                        cmd = 'L'; // further pairs after M are line-tos
                    } else {
                        s.lineTo(x, y);
                    }
                }
                case 'H' -> {
                    x = Float.parseFloat(t.get(i++));
                    s.lineTo(x, y);
                }
                case 'V' -> {
                    y = Float.parseFloat(t.get(i++));
                    s.lineTo(x, y);
                }
                case 'C' -> {
                    float[] c = new float[6];
                    for (int j = 0; j < 6; j++) {
                        c[j] = Float.parseFloat(t.get(i++));
                    }
                    s.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                    x = c[4];
                    y = c[5];
                }
                case 'Z' -> {
                    if (i < t.size() && !Character.isLetter(t.get(i).charAt(0))) {
                        throw new IllegalStateException("Lockup SVG: number after Z");
                    }
                    s.closePath();
                }
                default -> throw new IllegalStateException("Lockup SVG: path command " + cmd);
            }
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

    /**
     * One line, cut with "..." to fit {@code maxWidth} (cut in logical order, before shaping); characters the font
     * can't encode become "?". A line with Arabic script takes the embedded font of the same weight.
     */
    private void text(PDPageContentStream s, PDFont latin, float size, float x, float y, String text,
                      float maxWidth, float grey) throws IOException {
        PDFont font = ARABIC.matcher(text).find() ? arabic(latin) : latin;
        String line = text;
        String shown = encodable(font, visual(line));
        if (width(font, size, shown) > maxWidth) {
            do {
                line = line.substring(0, line.length() - 1);
                shown = encodable(font, visual(line.stripTrailing() + "..."));
            } while (!line.isEmpty() && width(font, size, shown) > maxWidth);
        }
        s.setNonStrokingColor(grey, grey, grey);
        s.beginText();
        s.setFont(font, size);
        s.newLineAtOffset(x, y);
        s.showText(shown);
        s.endText();
    }

    private PDFont arabic(PDFont latin) throws IOException {
        if (arabicRegular == null) {
            arabicRegular = PDType0Font.load(doc, new ByteArrayInputStream(ARABIC_REGULAR), true);
            arabicBold = PDType0Font.load(doc, new ByteArrayInputStream(ARABIC_BOLD), true);
        }
        return latin == bold ? arabicBold : arabicRegular;
    }

    /**
     * Logical to drawn text: Arabic letters to their joined presentation forms (lam-alef ligatures included), then
     * the bidi visual order of the line (first strong character decides its direction), controls removed. Latin
     * text comes back unchanged, minus the isolate controls. Harakat and tatweel are dropped first: ICU would turn
     * a mark into a spacing form drawn between two letters, off its own.
     */
    // ponytail: ICU presentation forms + PDFBox, no OpenType layout: harakat are dropped, and organizer text with
    // letters that have no presentation form draws unjoined (no glyph at all: "?"). HarfBuzz, or java.awt
    // GlyphVector layout drawn as outlines, if organizers need full Arabic typography.
    static String visual(String logical) {
        try {
            String shaped = new ArabicShaping(ArabicShaping.LETTERS_SHAPE | ArabicShaping.TEXT_DIRECTION_LOGICAL)
                    .shape(HolderNames.withoutArabicMarks(logical));
            return new Bidi(shaped, Bidi.LEVEL_DEFAULT_LTR)
                    .writeReordered(Bidi.DO_MIRRORING | Bidi.REMOVE_BIDI_CONTROLS);
        } catch (ArabicShapingException e) {
            throw new IllegalStateException("Arabic text not shaped", e);
        }
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    private static String encodable(PDFont font, String text) {
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
