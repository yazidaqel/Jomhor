package ma.jomhor.tickets.adapters.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.tickets.domain.TicketToken;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/** docs/04-api.md §Holder names, PDF row: Arabic title, venue and holder label shaped, with an embedded font. */
class TicketPdfTest {

    static final UUID TICKET = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");

    static byte[] render(String title, String venue, String label) {
        String token = TicketToken.encodeStatic(new byte[32], 42, TICKET, 7);
        return TicketPdf.render(new TicketPdf.Content(token, TicketToken.ticketNo(TICKET), title,
                Instant.parse("2026-12-19T19:00:00Z"), "Africa/Casablanca", venue, "NORD", List.of("P1"), label));
    }

    static List<PDFont> fonts(PDDocument doc) throws Exception {
        PDResources r = doc.getPage(0).getResources();
        List<PDFont> fonts = new ArrayList<>();
        for (COSName name : r.getFontNames()) {
            fonts.add(r.getFont(name));
        }
        return fonts;
    }

    @Test
    void arabicTextIsShapedAndDrawnWithTheEmbeddedFont() throws Exception {
        String label = HolderNames.label(HolderNames.normalize("يزيد عقل").orElseThrow());
        byte[] pdf = render("الوداد ضد الرجاء", "الملعب الكبير\u060C الدار البيضاء", label);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).doesNotContain("?");
            String plain = Normalizer.normalize(text, Normalizer.Form.NFKC);
            assertThat(plain).contains("Titulaire", "N° de billet " + TicketToken.ticketNo(TICKET));
            // The shaped glyphs map back to presentation forms, NFKC gives the letters; the stripper reads them back
            // in logical order (it puts the label's dot first: it knows nothing of the isolate).
            assertThat(plain).contains("الوداد ضد الرجاء", "الملعب الكبير", "يزيد ع");
            assertThat(fonts(doc)).anySatisfy(f -> {
                assertThat(f.getName()).contains("IBMPlexSansArabic");
                assertThat(f.isEmbedded()).isTrue();
            });
        }
    }

    @Test
    void aLatinTicketEmbedsNoFont() throws Exception {
        try (PDDocument doc = Loader.loadPDF(render("Atlas FC - Raja CA", "Grand Stade", "Yazid A."))) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Titulaire Yazid A.").doesNotContain("?");
            assertThat(fonts(doc)).allSatisfy(f -> assertThat(f.isEmbedded()).isFalse());
        }
    }

    /** Joined forms are drawn, not isolated letters: every Arabic letter becomes a presentation form (U+FB50 on). */
    @Test
    void arabicIsShapedBeforeDrawing() {
        assertThat(TicketPdf.visual("الوداد ضد يزيد عقل").codePoints().filter(cp -> cp != ' '))
                .allMatch(cp -> cp >= 0xFB50);
        assertThat(TicketPdf.visual("Atlas FC")).isEqualTo("Atlas FC");
    }

    /** Organizer text may carry harakat: they are dropped before shaping, never drawn between two letters. */
    @Test
    void harakatAreDroppedNotDrawnBetweenLetters() throws Exception {
        // m + fatha + r: reh final, meem initial.
        assertThat(TicketPdf.visual("\u0645\u064E\u0631")).isEqualTo("\uFEAE\uFEE3");
        try (PDDocument doc = Loader.loadPDF(render("\u0645\u064E\u0631", "Grand Stade", null))) {
            assertThat(new PDFTextStripper().getText(doc)).doesNotContain("?");
        }
    }

    @Test
    void theHolderLabelIsIsolatedInsideTheFrenchLine() {
        // "Titulaire " stays left; the Arabic label is one RTL run whose final dot sits on its left.
        String drawn = TicketPdf.visual("Titulaire \u2068يزيد ع.\u2069");
        assertThat(drawn).startsWith("Titulaire .");
        assertThat(drawn.codePoints().noneMatch(cp -> cp >= 0x2066 && cp <= 0x2069)).isTrue();
    }

    /** One filled path: its fill colour as 0xRRGGBB and its bounding box in page space. */
    record Fill(int rgb, Rectangle2D box) {}

    static List<Fill> fills(PDDocument doc) throws Exception {
        List<Fill> fills = new ArrayList<>();
        new PDFGraphicsStreamEngine(doc.getPage(0)) {
            final GeneralPath path = new GeneralPath();

            @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
                path.moveTo(p0.getX(), p0.getY());
                path.lineTo(p1.getX(), p1.getY());
                path.lineTo(p2.getX(), p2.getY());
                path.lineTo(p3.getX(), p3.getY());
                path.closePath();
            }
            @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
            @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
            @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
                path.curveTo(x1, y1, x2, y2, x3, y3);
            }
            @Override public Point2D getCurrentPoint() { return path.getCurrentPoint(); }
            @Override public void closePath() { path.closePath(); }
            @Override public void endPath() { path.reset(); }
            @Override public void strokePath() { path.reset(); }
            @Override public void fillPath(int windingRule) throws java.io.IOException {
                fills.add(new Fill(getGraphicsState().getNonStrokingColor().toRGB(), path.getBounds2D()));
                path.reset();
            }
            @Override public void fillAndStrokePath(int windingRule) throws java.io.IOException { fillPath(windingRule); }
            @Override public void drawImage(PDImage image) {}
            @Override public void clip(int windingRule) {}
            @Override public void shadingFill(COSName shadingName) {}

            void run() throws java.io.IOException { processPage(getPage()); }
        }.run();
        return fills;
    }

    /** design/BRAND.md, PDF ticket: the Jomhor lockup in the header, drawn as paths (ink J dots + wordmark, amber 12th). */
    @Test
    void theHeaderCarriesTheJomhorLockupAsPaths() throws Exception {
        try (PDDocument doc = Loader.loadPDF(render("Atlas FC - Raja CA", "Grand Stade", "Yazid A."))) {
            float pageH = doc.getPage(0).getMediaBox().getHeight();
            float billetCapTop = pageH - 72 + 8; // "BILLET", 10 pt: everything above it is the header
            List<Fill> header = fills(doc).stream().filter(f -> f.box().getMinY() > billetCapTop).toList();
            List<Fill> amber = header.stream().filter(f -> f.rgb() == 0xF5B700).toList();
            List<Fill> ink = header.stream().filter(f -> f.rgb() == 0x0B0F1A).toList();
            assertThat(amber).hasSize(1);
            assertThat(ink).hasSizeGreaterThanOrEqualTo(12); // eleven dots and the wordmark
            Rectangle2D lockup = amber.getFirst().box();
            ink.forEach(f -> lockup.add(f.box()));
            assertThat(lockup.getWidth()).isGreaterThanOrEqualTo(120); // BRAND.md minimum lockup width
            assertThat(lockup.getMinX()).isGreaterThanOrEqualTo(56 - 0.5);
            // Retyped with a font it would be text: the first text on the page is still the "BILLET" label.
            assertThat(new PDFTextStripper().getText(doc)).startsWith("BILLET");
        }
    }

    /** The lockup drawn in the PDF is the design file, byte for byte: a logo change in design/ fails here until copied. */
    @Test
    void theLockupResourceIsTheDesignFile() throws Exception {
        Path design = Path.of("..", "design", "logo", "jomhor-lockup-light.svg");
        try (var in = TicketPdf.class.getResourceAsStream("/logo/jomhor-lockup-light.svg")) {
            assertThat(in).isNotNull();
            assertThat(in.readAllBytes()).isEqualTo(Files.readAllBytes(design));
        }
    }

    /** The lockup took no room from the QR: its white square (quiet zone included) sits where it always did. */
    @Test
    void theQrDidNotMove() throws Exception {
        try (PDDocument doc = Loader.loadPDF(render("Atlas FC - Raja CA", "Grand Stade", "Yazid A."))) {
            Rectangle2D qr = fills(doc).stream().filter(f -> f.rgb() == 0xFFFFFF).findFirst().orElseThrow().box();
            Offset<Double> tol = Offset.offset(0.01);
            assertThat(qr.getX()).isCloseTo(QR_X, tol);
            assertThat(qr.getY()).isCloseTo(QR_Y, tol);
            assertThat(qr.getWidth()).isCloseTo(QR_SIDE, tol);
            assertThat(qr.getHeight()).isCloseTo(QR_SIDE, tol);
        }
    }

    /** Measured on the PDF before the lockup was added. */
    static final double QR_X = 180.315, QR_Y = 408.819, QR_SIDE = 131.071;
}
