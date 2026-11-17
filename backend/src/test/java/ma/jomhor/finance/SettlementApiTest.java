package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.finance.application.SettlementService;
import ma.jomhor.finance.domain.Payout;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract (BE-70): GET /org/finance/settlements, /org/finance/settlements/{id} and /org/finance/settlements.csv
 * (docs/04-api.md §Organizer, §Finance). Statements are issued by the job at the real now, on payments of 3 and 2 local
 * days ago (always due), and one card payment a minute ago (always open).
 */
class SettlementApiTest extends OrgApiTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    SettlementService service;

    private record Setup(Caller admin, LocalDate today, LocalDate d3, LocalDate d2, String id3, String id2) {}

    private Instant noon(LocalDate d) {
        return d.atTime(12, 0).atZone(CASABLANCA).toInstant();
    }

    private Setup setup() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        LocalDate today = LocalDate.now(CASABLANCA);
        LocalDate d3 = today.minusDays(3);
        LocalDate d2 = today.minusDays(2);
        Ledger.activate(jdbc, org, 500, today.minusDays(20).toString());
        int match = Ledger.event(jdbc, org, "Match A");
        UUID membership = Ledger.product(jdbc, org, "Adhésion");
        Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, match, "PAID"), "CARD", "FAKE", 1_000_00, noon(d3));
        Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, match, "PAID"), "POS_CASH", "POS", 500_00, noon(d3));
        Ledger.pay(jdbc, org, Ledger.productOrder(jdbc, org, membership), "WALLET", "FAKE", 200_00, noon(d3));
        Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, match, "PAID"), "POS_CASH", "POS", 300_00, noon(d2));
        Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, match, "PAID"), "CARD", "FAKE", 30_00,
                Instant.now().minusSeconds(60));
        UUID staff = jdbc.sql("select id from staff where organizer_id = ? and role = 'ADMIN'").param(org)
                .query(UUID.class).single();
        UUID refunded = Ledger.eventOrder(jdbc, org, match, "PAID");
        Ledger.refund(jdbc, org, match, refunded, 50_00, "REQUIRED", staff);
        Ledger.refund(jdbc, org, match, refunded, 70_00, "DONE", staff);
        service.runAt(Instant.now());
        return new Setup(admin, today, d3, d2, id(org, d3), id(org, d2));
    }

    private String id(UUID org, LocalDate day) {
        return jdbc.sql("select id from settlements where organizer_id = ? and day = ?").params(org, day)
                .query(UUID.class).single().toString();
    }

    private String invoiceNumber(String settlementId) {
        return jdbc.sql("select number from invoices where settlement_id = ?::uuid").param(settlementId)
                .query(String.class).single();
    }

    private String list(Caller c, LocalDate from, LocalDate to) throws Exception {
        return body(call(c, get("/org/finance/settlements").param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk()));
    }

    private static final String[] ROW_KEYS = {"id", "day", "status", "overdue", "collected_mad", "till_mad",
        "fee_rate_bp", "fee_ht_mad", "fee_vat_mad", "carried_in_mad", "payout_mad", "due_on", "paid_at", "paid_by",
        "payout_reference", "invoice"};

    @Test
    void theListShowsSettingsOpenMoneyRefundsAndStatementsNewestFirst() throws Exception {
        Setup s = setup();
        UUID org = s.admin().org();
        // an old DUE statement past its due date: overdue
        jdbc.sql("""
                        insert into settlements (organizer_id, day, collected_mad, till_mad, fee_rate_bp, fee_ht_mad,
                                                 fee_vat_mad, carried_in_mad, payout_mad, due_on, status)
                        values (?, ?, 100, 0, 0, 0, 0, 0, 100, ?, 'DUE')""")
                .params(org, s.today().minusDays(15), s.today().minusDays(13)).update();

        String json = list(s.admin(), s.today().minusDays(20), s.today());
        assertKeys(json, "$", "from", "to", "timezone", "settings", "open", "refunds_required", "due", "vat_rate_bp",
                "settlements");
        assertThat(JsonPath.<Integer>read(json, "$.vat_rate_bp")).isEqualTo(2000);
        // DUE statements whatever the range: the d3 statement and the old one (d2 is NONE); overdue: the old one, and
        // d3's when its due_on (d3 + 2, weekend → Monday) is already past
        int overdue = 1 + (Payout.dueOn(s.d3(), 2).isBefore(s.today()) ? 1 : 0);
        Map<String, Object> due = Map.of("count", 2, "amount_mad", 1_098_00 + 100, "overdue", overdue);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.due")).isEqualTo(due);
        assertThat(JsonPath.<Map<String, Object>>read(list(s.admin(), s.today(), s.today()), "$.due")).isEqualTo(due);
        assertThat(JsonPath.<String>read(json, "$.timezone")).isEqualTo("Africa/Casablanca");
        assertThat(JsonPath.<String>read(json, "$.from")).isEqualTo(s.today().minusDays(20).toString());
        assertThat(JsonPath.<String>read(json, "$.to")).isEqualTo(s.today().toString());
        assertKeys(json, "$.settings", "legal_name", "ice", "rib_last4", "fee_rate_bp", "start_on");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.settings")).isEqualTo(Map.of("legal_name", "Atlas FC SA",
                "ice", "001234567000089", "rib_last4", "1234", "fee_rate_bp", 500,
                "start_on", s.today().minusDays(20).toString()));
        assertThat(json).doesNotContain(Ledger.RIB).doesNotContain("12345678901234");

        assertKeys(json, "$.open", "since", "collected_mad", "till_mad", "payments");
        assertThat(Instant.parse(JsonPath.read(json, "$.open.since")))
                .isEqualTo(s.d2().plusDays(1).atStartOfDay(CASABLANCA).toInstant());
        assertThat(JsonPath.<Integer>read(json, "$.open.collected_mad")).isEqualTo(30_00);
        assertThat(JsonPath.<Integer>read(json, "$.open.till_mad")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$.open.payments")).isEqualTo(1);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.refunds_required"))
                .isEqualTo(Map.of("count", 1, "amount_mad", 50_00));

        assertThat(JsonPath.<List<String>>read(json, "$.settlements[*].day")).containsExactly(s.d2().toString(),
                s.d3().toString(), s.today().minusDays(15).toString());
        for (int i = 0; i < 3; i++) {
            assertKeys(json, "$.settlements[" + i + "]", ROW_KEYS);
        }
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.settlements[1]")).containsEntry("id", s.id3())
                .containsEntry("status", "DUE").containsEntry("collected_mad", 1_200_00)
                .containsEntry("till_mad", 500_00).containsEntry("fee_rate_bp", 500).containsEntry("fee_ht_mad", 85_00)
                .containsEntry("fee_vat_mad", 17_00).containsEntry("carried_in_mad", 0)
                .containsEntry("payout_mad", 1_098_00).containsEntry("due_on", Payout.dueOn(s.d3(), 2).toString())
                .containsEntry("overdue", Payout.dueOn(s.d3(), 2).isBefore(s.today()))
                .containsEntry("paid_at", null).containsEntry("paid_by", null).containsEntry("payout_reference", null);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.settlements[1].invoice")).isEqualTo(Map.of("id",
                jdbc.sql("select id from invoices where settlement_id = ?::uuid").param(s.id3()).query(UUID.class)
                        .single().toString(), "number", invoiceNumber(s.id3())));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.settlements[0]")).containsEntry("id", s.id2())
                .containsEntry("status", "NONE").containsEntry("till_mad", 300_00).containsEntry("payout_mad", 0)
                .containsEntry("overdue", false);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.settlements[2]")).containsEntry("overdue", true)
                .containsEntry("invoice", null);

        // the range: newest day first, only days of the range
        assertThat(JsonPath.<List<String>>read(list(s.admin(), s.d3(), s.d3()), "$.settlements[*].id"))
                .containsExactly(s.id3());
    }

    @Test
    void theRangeIsCheckedLikeTheCashReport() throws Exception {
        Caller admin = admin();
        call(admin, get("/org/finance/settlements").param("from", "2026-11-10").param("to", "2026-11-09"))
                .andExpect(status().isUnprocessableContent());
        call(admin, get("/org/finance/settlements").param("from", "2026-01-01").param("to", "2026-04-03"))
                .andExpect(status().isUnprocessableContent()); // 93 days
        call(admin, get("/org/finance/settlements").param("from", "2026-01-01").param("to", "2026-04-02"))
                .andExpect(status().isOk()); // 92 days
        call(admin, get("/org/finance/settlements").param("to", "2026-04-01"))
                .andExpect(status().isUnprocessableContent());
        call(admin, get("/org/finance/settlements.csv").param("from", "2026-11-10").param("to", "2026-11-9"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anOrganizerWithoutSettingsSeesNullSettingsAndOpen() throws Exception {
        Caller admin = admin();
        String json = list(admin, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"));
        assertThat(JsonPath.<Object>read(json, "$.settings")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.open")).isNull();
        assertThat(JsonPath.<List<Object>>read(json, "$.settlements")).isEmpty();
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.due")).isEqualTo(Map.of("count", 0, "amount_mad", 0, "overdue", 0));
        assertThat(JsonPath.<Integer>read(json, "$.vat_rate_bp")).isEqualTo(2000);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.refunds_required"))
                .isEqualTo(Map.of("count", 0, "amount_mad", 0));
    }

    @Test
    void theDetailListsLinesByTitleMethodCollector() throws Exception {
        Setup s = setup();
        String json = body(call(s.admin(), get("/org/finance/settlements/" + s.id3())).andExpect(status().isOk()));
        String[] keys = java.util.Arrays.copyOf(ROW_KEYS, ROW_KEYS.length + 1);
        keys[ROW_KEYS.length] = "lines";
        assertKeys(json, "$", keys);
        assertThat(JsonPath.<String>read(json, "$.id")).isEqualTo(s.id3());
        assertThat(JsonPath.<Integer>read(json, "$.payout_mad")).isEqualTo(1_098_00);
        assertKeys(json, "$.lines[0]", "event_id", "product_id", "title", "method", "collector", "collector_name",
                "payments", "amount_mad");
        assertThat(JsonPath.<List<String>>read(json, "$.lines[*].title")).containsExactly("Adhésion", "Match A",
                "Match A");
        assertThat(JsonPath.<List<String>>read(json, "$.lines[*].method")).containsExactly("WALLET", "CARD",
                "POS_CASH");
        assertThat(JsonPath.<List<String>>read(json, "$.lines[*].collector")).containsExactly("FAKE", "FAKE", "POS");
        assertThat(JsonPath.<List<String>>read(json, "$.lines[*].collector_name")).containsExactly("Payzone", "Payzone",
                "Guichet");
        assertThat(JsonPath.<List<Integer>>read(json, "$.lines[*].amount_mad")).containsExactly(200_00, 1_000_00,
                500_00);
        assertThat(JsonPath.<List<Integer>>read(json, "$.lines[*].payments")).containsExactly(1, 1, 1);
        assertThat(JsonPath.<Object>read(json, "$.lines[0].event_id")).isNull();
        assertThat(JsonPath.<String>read(json, "$.lines[0].product_id")).isNotNull();
        assertThat(JsonPath.<Integer>read(json, "$.lines[1].event_id")).isNotNull();
        assertThat(JsonPath.<Object>read(json, "$.lines[1].product_id")).isNull();

        call(s.admin(), get("/org/finance/settlements/" + UUID.randomUUID())).andExpect(status().isNotFound());
        call(admin(), get("/org/finance/settlements/" + s.id3())).andExpect(status().isNotFound()); // org B
    }

    @Test
    void theCsvHasAReleveRowThenItsLinesPerStatement() throws Exception {
        Setup s = setup();
        var r = call(s.admin(), get("/org/finance/settlements.csv").param("from", s.d3().toString())
                .param("to", s.d2().toString())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"releves-" + s.d3() + "-" + s.d2() + ".csv\""))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(r[0]).isEqualTo((byte) 0xEF);
        assertThat(r[1]).isEqualTo((byte) 0xBB);
        assertThat(r[2]).isEqualTo((byte) 0xBF);
        String csv = new String(r, 3, r.length - 3, StandardCharsets.UTF_8);
        String d2 = s.d2().toString();
        String d3 = s.d3().toString();
        assertThat(csv).isEqualTo(String.join("\r\n",
                "kind;jour;libelle;moyen;encaisseur;paiements;montant;en_ligne;guichets;frais_ht;tva;report;a_verser;"
                        + "echeance;statut;facture;reference",
                "releve;" + d2 + ";;;;;;0,00;300,00;15,00;3,00;0,00;0,00;" + Payout.dueOn(s.d2(), 2) + ";NONE;"
                        + invoiceNumber(s.id2()) + ";",
                "ligne;" + d2 + ";Match A;POS_CASH;Guichet;1;300,00;;;;;;;;;;",
                "releve;" + d3 + ";;;;;;1200,00;500,00;85,00;17,00;0,00;1098,00;" + Payout.dueOn(s.d3(), 2) + ";DUE;"
                        + invoiceNumber(s.id3()) + ";",
                "ligne;" + d3 + ";Adhésion;WALLET;Payzone;1;200,00;;;;;;;;;;",
                "ligne;" + d3 + ";Match A;CARD;Payzone;1;1000,00;;;;;;;;;;",
                "ligne;" + d3 + ";Match A;POS_CASH;Guichet;1;500,00;;;;;;;;;;") + "\r\n");
    }

    @Test
    void onlyAdminAndFinanceReadStatements() throws Exception {
        Setup s = setup();
        UUID org = s.admin().org();
        String range = "?from=" + s.d3() + "&to=" + s.d2();
        for (String role : List.of("ADMIN", "FINANCE", "OPS", "CASHIER")) {
            Caller c = caller(org, role);
            boolean allowed = role.equals("ADMIN") || role.equals("FINANCE");
            for (String path : List.of("/org/finance/settlements" + range, "/org/finance/settlements.csv" + range,
                    "/org/finance/settlements/" + s.id3())) {
                call(c, get(path)).andExpect(allowed ? status().isOk() : status().isForbidden());
            }
        }
    }
}
