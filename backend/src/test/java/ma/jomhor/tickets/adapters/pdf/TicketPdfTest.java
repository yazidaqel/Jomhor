package ma.jomhor.tickets.adapters.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.tickets.domain.TicketToken;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
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
}
