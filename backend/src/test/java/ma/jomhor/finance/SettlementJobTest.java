package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.IntegrationTest;
import ma.jomhor.finance.application.SettlementService;
import ma.jomhor.tenancy.domain.FinanceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * BE-70: the statement job on a fixed clock (docs/04-api.md §Finance Statement and Invoice, plan lot-5 §13.1 exit 5b).
 * The job serves every activated organizer, so assertions stay on this test's organizer; invoice numbers are checked
 * for the whole table (one sequence for every organizer).
 */
@ExtendWith(OutputCaptureExtension.class)
class SettlementJobTest extends IntegrationTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    SettlementService service;

    @Autowired
    JsonMapper json;

    private static Instant local(String at) {
        return LocalDateTime.parse(at).atZone(CASABLANCA).toInstant();
    }

    /** The demo club: two events, an installment product and a membership; returns them. */
    private record Club(UUID org, int matchA, int matchB, UUID installment, UUID membership) {}

    private Club club(int rateBp, String startOn) {
        UUID org = organizer("Atlas " + UUID.randomUUID());
        Ledger.activate(jdbc, org, rateBp, startOn);
        return new Club(org, Ledger.event(jdbc, org, "Match A"), Ledger.event(jdbc, org, "Match B"),
                Ledger.product(jdbc, org, "Abonnement saison"), Ledger.product(jdbc, org, "Adhésion 2026"));
    }

    private void pay(Club c, int eventId, String method, String provider, long amount, String at) {
        Ledger.pay(jdbc, c.org(), Ledger.eventOrder(jdbc, c.org(), eventId, "PAID"), method, provider, amount, local(at));
    }

    private void payProduct(Club c, UUID product, String method, long amount, String at) {
        Ledger.pay(jdbc, c.org(), Ledger.productOrder(jdbc, c.org(), product), method, "FAKE", amount, local(at));
    }

    /** §13.1 exit 5b days 1 and 3: Thu 26 nov. online 4 000 DH + tills 1 000 DH, Fri 27 till 800 DH, Sat 28 card 250. */
    private Club demo() {
        Club c = club(500, "2026-11-25");
        pay(c, c.matchA(), "CARD", "FAKE", 999_00, "2026-11-24T12:00"); // before start_on: never settled
        pay(c, c.matchA(), "CARD", "FAKE", 2_000_00, "2026-11-26T00:00");
        pay(c, c.matchB(), "CARD", "FAKE", 1_000_00, "2026-11-26T10:00");
        payProduct(c, c.installment(), "CARD", 400_00, "2026-11-26T11:00");
        payProduct(c, c.membership(), "WALLET", 150_00, "2026-11-26T12:00");
        pay(c, c.matchA(), "CASH_AGENCY", "FAKE_CASH", 450_00, "2026-11-26T13:00");
        pay(c, c.matchA(), "POS_CASH", "POS", 750_00, "2026-11-26T18:00");
        pay(c, c.matchA(), "CASH_AGENCY", "POS", 250_00, "2026-11-26T23:59:59"); // a code collected at a till
        // a FREE order (pass ticket or invitation) has no payment: no line
        Ledger.eventOrder(jdbc, c.org(), c.matchA(), "PAID");
        pay(c, c.matchB(), "POS_CASH", "POS", 800_00, "2026-11-27T15:00");
        pay(c, c.matchA(), "CARD", "FAKE", 250_00, "2026-11-28T10:00");
        return c;
    }

    private List<Map<String, Object>> statements(UUID org) {
        return jdbc.sql("""
                        select day::text as day, collected_mad, till_mad, fee_rate_bp, fee_ht_mad, fee_vat_mad,
                               carried_in_mad, payout_mad, due_on::text as due_on, status::text as status,
                               paid_at, payout_reference
                        from settlements where organizer_id = ? order by day""")
                .param(org).query().listOfRows();
    }

    private List<String> lines(UUID org, String day) {
        return jdbc.sql("""
                        select coalesce(e.title, p.name) || ' ' || l.method || ' ' || l.collector || ' '
                               || l.payments || ' ' || l.amount_mad
                        from settlement_lines l join settlements s on s.id = l.settlement_id
                          left join events e on e.id = l.event_id left join products p on p.id = l.product_id
                        where s.organizer_id = ? and s.day = ?::date and l.organizer_id = s.organizer_id
                        order by 1""")
                .params(org, day).query(String.class).list();
    }

    private Map<String, Object> invoice(UUID org, String day) {
        return jdbc.sql("""
                        select i.year, i.seq, i.number, i.issued_on::text as issued_on, i.issuer::text as issuer,
                               i.customer::text as customer, i.amount_ht_mad, i.vat_rate_bp, i.vat_mad, i.amount_ttc_mad
                        from invoices i join settlements s on s.id = i.settlement_id
                        where s.organizer_id = ? and s.day = ?::date and i.organizer_id = s.organizer_id""")
                .params(org, day).query().singleRow();
    }

    private List<Map<String, Object>> issueAudits(UUID org) {
        return jdbc.sql("select actor_type, payload::text as payload from audit_log where organizer_id = ? "
                        + "and action = 'settlement.issue' order by id")
                .param(org).query().listOfRows();
    }

    private void assertNoInvoiceGap() {
        assertThat(jdbc.sql("""
                        select count(*) from (select seq, number, year,
                                                     row_number() over (partition by year order by seq) as n
                                              from invoices) i
                        where seq <> n or number <> 'JMH-' || year || '-' || lpad(seq::text, 6, '0')""")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void theDemoDaysAreIssuedInOrderOnceEachDayIsOver() {
        Club c = demo();

        service.runAt(local("2026-11-27T00:59:59")); // Thursday ended, but not 1 h ago
        assertThat(statements(c.org())).isEmpty();

        service.runAt(local("2026-11-27T01:00"));
        List<Map<String, Object>> rows = statements(c.org());
        assertThat(rows).hasSize(1);
        Map<String, Object> thu = rows.getFirst();
        assertThat(thu).containsEntry("day", "2026-11-26").containsEntry("collected_mad", 4_000_00L)
                .containsEntry("till_mad", 1_000_00L).containsEntry("fee_rate_bp", 500)
                .containsEntry("fee_ht_mad", 250_00L).containsEntry("fee_vat_mad", 50_00L)
                .containsEntry("carried_in_mad", 0L).containsEntry("payout_mad", 3_700_00L)
                .containsEntry("due_on", "2026-11-30").containsEntry("status", "DUE");
        assertThat(lines(c.org(), "2026-11-26")).containsExactlyInAnyOrder(
                "Abonnement saison CARD FAKE 1 40000",
                "Adhésion 2026 WALLET FAKE 1 15000",
                "Match A CARD FAKE 1 200000",
                "Match A CASH_AGENCY FAKE_CASH 1 45000",
                "Match A CASH_AGENCY POS 1 25000",
                "Match A POS_CASH POS 1 75000",
                "Match B CARD FAKE 1 100000");
        assertThat(jdbc.sql("select count(*) from settlement_lines l join settlements s on s.id = l.settlement_id "
                        + "where s.organizer_id = ? and l.product_id is not null and l.event_id is null")
                .param(c.org()).query(Integer.class).single()).isEqualTo(2);

        Map<String, Object> inv = invoice(c.org(), "2026-11-26");
        assertThat(((Number) inv.get("year")).intValue()).isEqualTo(2026);
        assertThat(inv).containsEntry("issued_on", "2026-11-27")
                .containsEntry("amount_ht_mad", 250_00L).containsEntry("vat_rate_bp", 2000)
                .containsEntry("vat_mad", 50_00L).containsEntry("amount_ttc_mad", 300_00L);
        assertThat((String) inv.get("number")).isEqualTo("JMH-2026-%06d".formatted((Integer) inv.get("seq")));
        assertThat(JsonPath.<Map<String, Object>>read((String) inv.get("customer"), "$")).isEqualTo(Map.of(
                "legal_name", "Atlas FC SA", "address", "Stade de l'Atlas, Casablanca", "ice", "001234567000089"));
        assertThat((String) inv.get("customer")).doesNotContain(Ledger.RIB);
        assertThat(JsonPath.<Map<String, Object>>read((String) inv.get("issuer"), "$")).isEqualTo(Map.of(
                "legal_name", "Jomhor Test SARL", "legal_form", "SARL", "capital", "100 000 DH",
                "address", "1 rue du Test, Casablanca", "rc", "Casablanca 111111", "if", "22222222", "tp", "33333333",
                "ice", "444444444444444"));
        assertThat(issueAudits(c.org())).singleElement().satisfies(a -> {
            assertThat(a.get("actor_type")).hasToString("SYSTEM");
            assertThat(JsonPath.<Map<String, Object>>read((String) a.get("payload"), "$")).isEqualTo(Map.of(
                    "day", "2026-11-26", "payout_mad", 370000, "invoice", inv.get("number")));
        });

        service.runAt(local("2026-11-28T01:00")); // Friday: till cash only
        rows = statements(c.org());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("day", "2026-11-27").containsEntry("collected_mad", 0L)
                .containsEntry("till_mad", 800_00L).containsEntry("fee_ht_mad", 40_00L)
                .containsEntry("fee_vat_mad", 8_00L).containsEntry("carried_in_mad", 0L)
                .containsEntry("payout_mad", 0L).containsEntry("status", "NONE")
                .containsEntry("due_on", "2026-11-30");
        assertThat(invoice(c.org(), "2026-11-27")).containsEntry("amount_ht_mad", 40_00L)
                .containsEntry("vat_mad", 8_00L);
        assertThat((Integer) invoice(c.org(), "2026-11-27").get("seq")).isGreaterThan((Integer) inv.get("seq"));

        service.runAt(local("2026-11-29T01:00")); // Saturday: the demo's card payment, the debt carried in
        rows = statements(c.org());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2)).containsEntry("day", "2026-11-28").containsEntry("collected_mad", 250_00L)
                .containsEntry("carried_in_mad", -48_00L).containsEntry("fee_ht_mad", 12_50L)
                .containsEntry("fee_vat_mad", 2_50L).containsEntry("payout_mad", 250_00L - 15_00L - 48_00L)
                .containsEntry("status", "DUE").containsEntry("due_on", "2026-11-30");
        assertThat(issueAudits(c.org())).hasSize(3);
        assertNoInvoiceGap();

        // a later run changes nothing, and a refund afterwards changes no issued statement
        List<Map<String, Object>> before = statements(c.org());
        List<String> thursdayLines = lines(c.org(), "2026-11-26");
        UUID admin = jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
        UUID staff = jdbc.sql("insert into staff (organizer_id, user_id, name, role) values (?, ?, 'Admin', 'ADMIN') "
                + "returning id").params(c.org(), admin).query(UUID.class).single();
        UUID refunded = Ledger.eventOrder(jdbc, c.org(), c.matchA(), "PAID");
        Ledger.pay(jdbc, c.org(), refunded, "CARD", "FAKE", 100_00, local("2026-11-26T09:00"));
        Ledger.refund(jdbc, c.org(), c.matchA(), refunded, 100_00, "REQUIRED", staff);
        service.runAt(local("2026-12-05T01:00"));
        assertThat(statements(c.org())).isEqualTo(before);
        assertThat(lines(c.org(), "2026-11-26")).isEqualTo(thursdayLines);
        assertThat(issueAudits(c.org())).hasSize(3);
    }

    @Test
    void aCatchUpRunBuildsTheSameDaysInOrderAndSkipsEmptyOnes() {
        Club c = demo();
        service.runAt(local("2026-12-02T09:00")); // Wed 25 had no payment: no row
        assertThat(statements(c.org())).extracting(r -> r.get("day"))
                .containsExactly("2026-11-26", "2026-11-27", "2026-11-28");
        assertThat(statements(c.org())).extracting(r -> r.get("payout_mad"))
                .containsExactly(3_700_00L, 0L, 250_00L - 15_00L - 48_00L);
        assertThat(statements(c.org())).extracting(r -> r.get("carried_in_mad")).containsExactly(0L, 0L, -48_00L);
        assertNoInvoiceGap();
    }

    @Test
    void aLateSuccessIsCountedAndAZeroFeeGetsNoInvoice() {
        Club c = club(0, "2026-11-01");
        // a payment that succeeded on an expired order (payment.refund_required): collected money, counted
        Ledger.pay(jdbc, c.org(), Ledger.eventOrder(jdbc, c.org(), c.matchA(), "EXPIRED"), "CARD", "FAKE", 120_00,
                local("2026-11-10T20:00"));
        service.runAt(local("2026-11-11T01:00"));
        assertThat(statements(c.org())).singleElement().satisfies(r -> assertThat(r)
                .containsEntry("collected_mad", 120_00L).containsEntry("fee_ht_mad", 0L)
                .containsEntry("fee_vat_mad", 0L).containsEntry("payout_mad", 120_00L));
        assertThat(jdbc.sql("select count(*) from invoices where organizer_id = ?").param(c.org()).query(Integer.class)
                .single()).isZero();
        assertThat(issueAudits(c.org())).singleElement().satisfies(a -> assertThat(JsonPath.<Object>read(
                (String) a.get("payload"), "$.invoice")).isNull());
    }

    @Test
    void missingOrMalformedSettingsGiveNoStatementAndMalformedOnesOneWarn(CapturedOutput output) {
        UUID missing = organizer("Sans contrat " + UUID.randomUUID());
        UUID malformed = organizer("Mal saisi " + UUID.randomUUID());
        Ledger.settings(jdbc, malformed, """
                {"legal_name": "Club", "address": "Rue", "ice": "001234567000089", "rib": "123",
                 "fee_rate_bp": 500, "start_on": "2026-11-01"}""");
        for (UUID org : List.of(missing, malformed)) {
            int e = Ledger.event(jdbc, org, "Match");
            Ledger.pay(jdbc, org, Ledger.eventOrder(jdbc, org, e, "PAID"), "CARD", "FAKE", 100_00,
                    local("2026-11-10T20:00"));
        }
        service.runAt(local("2026-11-12T01:00"));
        assertThat(statements(missing)).isEmpty();
        assertThat(statements(malformed)).isEmpty();
        assertThat(output.getAll().split("Malformed settings.finance of organizer " + malformed, -1)).hasSize(2);
        assertThat(output.getAll()).doesNotContain("Malformed settings.finance of organizer " + missing);
        assertThat(service.list(missing, "2026-11-01", "2026-11-30").settings()).isNull();
        assertThat(service.list(malformed, "2026-11-01", "2026-11-30").open()).isNull();
    }

    @Test
    void anOrderWithoutSubjectStopsItsOrganizerAndIsNamedInTheLog(CapturedOutput output) {
        Club c = club(500, "2026-11-01");
        UUID order = jdbc.sql("""
                        insert into orders (organizer_id, channel, status, total_mad, reserved_until)
                        values (?, 'APP', 'PAID', 0, now()) returning id""")
                .param(c.org()).query(UUID.class).single(); // a product order with no item: no subject
        Ledger.pay(jdbc, c.org(), order, "CARD", "FAKE", 100_00, local("2026-11-10T20:00"));
        service.runAt(local("2026-11-11T01:00"));
        assertThat(statements(c.org())).isEmpty();
        assertThat(output.getAll()).contains("Settlement statement failed for organizer " + c.org(),
                "No event or product for order " + order + " of organizer " + c.org());
    }

    @Test
    void settingsAreStrictlyValidated() {
        String valid = """
                {"legal_name": "Club", "address": "Rue", "ice": "001234567000089", "rib": "011780000012345678901234",
                 "fee_rate_bp": 500, "start_on": "2026-11-01"}""";
        assertThat(parse(valid)).isNotNull();
        assertThat(parse(valid).ribLast4()).isEqualTo("1234");
        for (String[] bad : new String[][] {{"\"fee_rate_bp\": 500", "\"fee_rate_bp\": \"500\""},
                {"\"fee_rate_bp\": 500", "\"fee_rate_bp\": 500.5"}, {"\"fee_rate_bp\": 500", "\"fee_rate_bp\": 10001"},
                {"\"fee_rate_bp\": 500", "\"fee_rate_bp\": -1"}, {"001234567000089", "00123456700008"},
                {"011780000012345678901234", "01178000001234567890123"}, {"2026-11-01", "01/11/2026"},
                {"\"legal_name\": \"Club\"", "\"legal_name\": \" \""}, {"\"address\": \"Rue\", ", ""},
                {"\"address\": \"Rue\"", "\"address\": \"Rue\", \"if\": 12"}}) {
            assertThat(parse(valid.replace(bad[0], bad[1]))).as(bad[1]).isNull();
        }
        assertThat(parse("[]")).isNull();
        // the RIB never reaches a log in full through the record's toString
        assertThat(parse(valid).toString()).contains("rib=****1234").doesNotContain("011780000012345678901234");
    }

    private FinanceSettings parse(String raw) {
        return FinanceSettings.parse(json.readTree(raw)).orElse(null);
    }
}
