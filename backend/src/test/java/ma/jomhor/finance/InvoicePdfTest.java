package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.finance.application.SettlementService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract (BE-71): GET /org/finance/invoices/{id}.pdf (docs/04-api.md §Organizer, §Finance Invoice, plan lot-5 Q230).
 * The demo Thursday (online 4 000 DH + tills 1 000 DH at 500 bp) is issued on Fri 27 nov. 2026 at 01:00; the PDF's
 * text, extracted with PDFBox, holds every mention, and is drawn from the stored snapshots.
 */
class InvoicePdfTest extends OrgApiTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    SettlementService service;

    private static Instant local(String at) {
        return LocalDateTime.parse(at).atZone(CASABLANCA).toInstant();
    }

    private record Issued(Caller admin, String invoiceId, String number) {}

    private Issued issued() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        Ledger.activate(jdbc, org, 500, "2026-11-25");
        int match = Ledger.event(jdbc, org, "Match A");
        Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, match, "PAID"), "CARD", "FAKE", 4_000_00,
                local("2026-11-26T12:00"));
        Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, match, "PAID"), "POS_CASH", "POS", 1_000_00,
                local("2026-11-26T18:00"));
        service.runAt(local("2026-11-27T01:00"));
        var row = jdbc.sql("select id, number from invoices where organizer_id = ?").param(org).query().singleRow();
        return new Issued(admin, row.get("id").toString(), (String) row.get("number"));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void thePdfCarriesEveryMentionFromTheStoredRow() throws Exception {
        Issued i = issued();
        // a later change of the club's settings never alters an issued invoice
        Ledger.settings(jdbc, i.admin().org(), """
                {"legal_name": "Autre Nom SA", "address": "Ailleurs", "ice": "999999999999999",
                 "rib": "011780000012345678909999", "fee_rate_bp": 900, "start_on": "2026-11-25"}""");
        byte[] pdf = call(i.admin(), get("/org/finance/invoices/" + i.invoiceId() + ".pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"facture-" + i.number() + ".pdf\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        String text = text(pdf);
        assertThat(i.number()).matches("JMH-2026-\\d{6}");
        assertThat(text).contains("FACTURE", "N° " + i.number(), "Date d'émission : 27/11/2026",
                // issuer (config finance.issuer at issue)
                "Jomhor Test SARL", "SARL au capital de 100 000 DH", "1 rue du Test, Casablanca",
                "RC Casablanca 111111", "IF 22222222", "TP 33333333", "ICE 444444444444444",
                // customer (settings.finance at issue)
                "Atlas FC SA", "Stade de l'Atlas, Casablanca", "ICE 001234567000089",
                // the service, base and rate
                "Frais de service billetterie · relevé du 26/11/2026",
                "encaissements du jour 5000,00 DH", "taux 5 % HT",
                // amounts
                "Total HT", "250,00 DH", "TVA 20 %", "50,00 DH", "Total TTC", "300,00 DH",
                "Réglée par compensation sur le versement du relevé du 26/11/2026");
        assertThat(text).doesNotContain("Autre Nom", "999999999999999", Ledger.RIB, "RIB", "5678901234", "9999");
    }

    @Test
    void onlyAdminAndFinanceOfTheOrganizerGetIt() throws Exception {
        Issued i = issued();
        String path = "/org/finance/invoices/" + i.invoiceId() + ".pdf";
        call(caller(i.admin().org(), "FINANCE"), get(path)).andExpect(status().isOk());
        call(caller(i.admin().org(), "OPS"), get(path)).andExpect(status().isForbidden());
        call(caller(i.admin().org(), "CASHIER"), get(path)).andExpect(status().isForbidden());
        call(admin(), get(path)).andExpect(status().isNotFound()); // org B
        call(i.admin(), get("/org/finance/invoices/" + UUID.randomUUID() + ".pdf")).andExpect(status().isNotFound());
    }
}
