package ma.jomhor.finance.adapters.pdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ma.jomhor.finance.domain.Invoice;
import ma.jomhor.payments.application.CashReport;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Jomhor's fee invoice (docs/04-api.md GET /org/finance/invoices/{id}.pdf, §Finance Invoice, plan lot-5 Q230): A4, FR,
 * drawn from the stored row on each request. Issuer and customer come from the snapshots copied in at issue, never from
 * today's config or settings; the customer snapshot holds no RIB. Amounts are DH with a comma by integer maths. Long
 * lines wrap (a mandatory mention is never cut); characters outside WinAnsi print as "?".
 */
public final class InvoicePdf {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final float LEFT = 56;
    private static final float GREY = 0.42f;

    private final PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private PDPageContentStream s;
    private float width;
    private float y;

    private InvoicePdf() {}

    public static byte[] render(Invoice invoice) {
        return new InvoicePdf().draw(invoice);
    }

    /** Basis points as a French percentage: 2000 → "20", 1250 → "12,5", 5 → "0,05". */
    static String percent(int bp) {
        String decimals = bp % 100 == 0 ? "" : ",%02d".formatted(bp % 100).replaceAll("0$", "");
        return bp / 100 + decimals;
    }

    private static String date(LocalDate d) {
        return DATE.format(d);
    }

    private byte[] draw(Invoice i) {
        Map<String, String> issuer = i.issuer();
        Map<String, String> customer = i.customer();
        String day = date(i.day());
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            width = page.getMediaBox().getWidth() - 2 * LEFT;
            y = page.getMediaBox().getHeight() - 64;
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                s = stream;
                line(bold, 22, "FACTURE", 0);
                y -= 6;
                line(regular, 11, "N° " + i.number(), 0);
                line(regular, 11, "Date d'émission : " + date(i.issuedOn()), 0);
                y -= 14;

                line(regular, 9, "ÉMETTEUR", GREY);
                line(bold, 11, get(issuer, "legal_name"), 0);
                line(regular, 10, get(issuer, "legal_form") + " au capital de " + get(issuer, "capital"), 0);
                line(regular, 10, get(issuer, "address"), 0);
                line(regular, 10, "RC " + get(issuer, "rc") + " · IF " + get(issuer, "if") + " · TP "
                        + get(issuer, "tp") + " · ICE " + get(issuer, "ice"), 0);
                y -= 14;

                line(regular, 9, "CLIENT", GREY);
                line(bold, 11, get(customer, "legal_name"), 0);
                line(regular, 10, get(customer, "address"), 0);
                line(regular, 10, "ICE " + get(customer, "ice"), 0);
                y -= 20;

                line(regular, 9, "DÉSIGNATION", GREY);
                amountLine(bold, 11, "Frais de service billetterie · relevé du " + day, i.amountHtMad());
                line(regular, 10, "Base : encaissements du jour " + CashReport.dh(i.baseMad()) + " DH · taux "
                        + percent(i.feeRateBp()) + " % HT", GREY);
                y -= 16;
                amountLine(regular, 11, "Total HT", i.amountHtMad());
                amountLine(regular, 11, "TVA " + percent(i.vatRateBp()) + " %", i.vatMad());
                amountLine(bold, 12, "Total TTC", i.amountTtcMad());
                y -= 16;
                line(regular, 10, "Réglée par compensation sur le versement du relevé du " + day + ".", 0);
                line(regular, 9, "Montants en dirhams (DH).", GREY);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Invoice PDF not rendered", e);
        }
    }

    private static String get(Map<String, String> snapshot, String key) {
        return snapshot.getOrDefault(key, "");
    }

    /** {@code text} at the left margin, wrapped on spaces to the page width; then the next line down. */
    private void line(PDFont font, float size, String text, float grey) throws IOException {
        for (String part : wrap(font, size, encodable(font, text), width)) {
            show(font, size, LEFT, part, grey);
            y -= size * 1.45f;
        }
    }

    /** A label and its DH amount right-aligned on one line. */
    private void amountLine(PDFont font, float size, String label, long centimes) throws IOException {
        String amount = CashReport.dh(centimes) + " DH";
        float amountWidth = width(font, size, amount);
        List<String> parts = wrap(font, size, encodable(font, label), width - amountWidth - 16);
        show(font, size, LEFT + width - amountWidth, amount, 0);
        for (String part : parts) {
            show(font, size, LEFT, part, 0);
            y -= size * 1.45f;
        }
    }

    private void show(PDFont font, float size, float x, String text, float grey) throws IOException {
        s.setNonStrokingColor(grey, grey, grey);
        s.beginText();
        s.setFont(font, size);
        s.newLineAtOffset(x, y);
        s.showText(text);
        s.endText();
    }

    private static List<String> wrap(PDFont font, float size, String text, float max) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = current.isEmpty() ? word : current + " " + word;
            if (!current.isEmpty() && width(font, size, next) > max) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(next);
            }
        }
        lines.add(current.toString());
        return lines;
    }

    private static float width(PDFont font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000 * size;
    }

    /** Characters the standard font can't encode become "?" (an Arabic legal name, say). */
    private static String encodable(PDFont font, String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints().forEach(c -> {
            String ch = Character.toString(c);
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
