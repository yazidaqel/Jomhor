package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.payments.application.CashReport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-26): GET /org/finance/cash and /org/finance/cash.csv (docs/04-api.md §Organizer, plan Q50). The same
 * properties as CashCallbackTest (one shared context): the simulated network is a collector.
 */
@TestPropertySource(properties = {"payments.cash.networks=fake", "payments.wallet.provider=fake"})
class CashReportTest extends PaymentTest {

    private static final String POINTS = "[{\"name\": \"Guichet Stade\"}]";

    // ---------- fixtures ----------

    private ResultActions report(Caller c, String query) throws Exception {
        return call(c, get(URI.create("/org/finance/cash" + query))); // %2B stays a +, not re-encoded
    }

    private static String range(LocalDate from, LocalDate to, String timezone) {
        return "?from=" + from + "&to=" + to + (timezone == null ? "" : "&timezone=" + timezone);
    }

    /** Today ± 1 day: whatever the zone, every payment made by the test is inside. */
    private static String aroundNow() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return range(today.minusDays(1), today.plusDays(1), null);
    }

    private String openSession(Caller c, String label) throws Exception {
        return JsonPath.read(body(call(c, post("/org/pos/sessions"), "{\"label\": \"%s\"}".formatted(label))
                .andExpect(status().isCreated())), "$.id");
    }

    private String sell(Caller c, int eventId, String priceId, int qty) throws Exception {
        String holders = String.join(", ", Collections.nCopies(qty, "{\"name\": \"Yazid Aqel\"}"));
        return JsonPath.read(body(call(c, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d, "items": [{"price_category_id": "%s", "qty": %d, "holders": [%s]}]}
                """.formatted(UUID.randomUUID(), eventId, priceId, qty, holders)).andExpect(status().isCreated())), "$.id");
    }

    /** A fan order of one place on {@code s} with a cash code: the code (6 symbols) and the order. */
    private String[] cashCode(Sale s) throws Exception {
        jdbc.sql("update organizers set settings = settings || jsonb_build_object('cash_points', ?::jsonb) where id = ?")
                .params(POINTS, s.org().org()).update();
        String fan = fanToken(randomPhone());
        String orderId = order(fan, s, 1);
        String json = body(fan(fan, post("/fan/orders/" + orderId + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk()));
        return new String[] {JsonPath.read(json, "$.pay_code"), orderId};
    }

    private void completedAt(String orderId, String at) {
        jdbc.sql("update payments set completed_at = ?::timestamptz where order_id = ?::uuid").params(at, orderId).update();
    }

    // ---------- days ----------

    // Casablanca is +01:00 in June 2026 under every tzdata (from 2026-09-20 the current tzdata has it at +00:00, so
    // the fixed November checks use Paris): the day follows the zone, not UTC.
    @Test
    void aPaymentAt2330UtcLandsOnTheNextLocalDay() throws Exception {
        Sale s = onSale(10);
        Caller cashier = caller(s.org().org(), "CASHIER");
        openSession(cashier, "Guichet Nord");
        String order = sell(cashier, s.eventId(), s.priceId(), 1);
        completedAt(order, "2026-06-05T23:30:00Z");

        String json = body(report(s.org(), "?from=2026-06-01&to=2026-06-07").andExpect(status().isOk()));
        assertKeys(json, "$", "from", "to", "timezone", "days", "sessions", "totals");
        assertKeys(json, "$.days[0]", "day", "collector", "collector_name", "method", "payments", "amount_mad");
        assertKeys(json, "$.totals", "collected_mad", "declared_mad", "variance_mad", "open_sessions");
        assertThat(JsonPath.<String>read(json, "$.timezone")).isEqualTo("Africa/Casablanca"); // the default
        assertThat(JsonPath.<String>read(json, "$.from")).isEqualTo("2026-06-01"); // the local dates, echoed
        assertThat(JsonPath.<String>read(json, "$.to")).isEqualTo("2026-06-07");
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$.days")).containsExactly(Map.of("day", "2026-06-06",
                "collector", "POS", "collector_name", "Guichet", "method", "POS_CASH", "payments", 1, "amount_mad", 5000));
        assertThat(JsonPath.<Integer>read(json, "$.totals.collected_mad")).isEqualTo(5000);
        // New York (-04:00): the same payment is on the 5th there.
        String ny = body(report(s.org(), range(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-07"),
                "America/New_York")).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(ny, "$.timezone")).isEqualTo("America/New_York");
        assertThat(JsonPath.<List<String>>read(ny, "$.days[*].day")).containsExactly("2026-06-05");

        // November in Paris (+01:00): the next day too. The bounds are local midnights by the server's tzdata: a
        // payment at the start of the day after `to` is out, one at the start of `from` is in.
        String nov = "?from=2026-11-01&to=2026-11-07&timezone=Europe/Paris";
        completedAt(order, "2026-11-05T23:30:00Z");
        report(s.org(), nov).andExpect(jsonPath("$.days[0].day").value("2026-11-06"));
        completedAt(order, "2026-11-07T23:00:00Z");
        report(s.org(), nov).andExpect(jsonPath("$.days").isEmpty()).andExpect(jsonPath("$.totals.collected_mad").value(0));
        completedAt(order, "2026-10-31T23:00:00Z");
        report(s.org(), nov).andExpect(jsonPath("$.days[0].day").value("2026-11-01"));

        // Casablanca in November: whatever the client believes, the server's own zone rules decide the day.
        completedAt(order, "2026-11-05T23:30:00Z");
        String local = Instant.parse("2026-11-05T23:30:00Z").atZone(ZoneId.of("Africa/Casablanca")).toLocalDate().toString();
        report(s.org(), "?from=2026-11-01&to=2026-11-07").andExpect(jsonPath("$.days[0].day").value(local));
    }

    @Test
    void daysAreByDayThenCollectorAndSessionsShowExpectedSoFarThenTheGapAtClose() throws Exception {
        Sale s = onSale(20);
        Caller cashier = caller(s.org().org(), "CASHIER");
        String session = openSession(cashier, "Guichet Nord");
        sell(cashier, s.eventId(), s.priceId(), 2); // 100 DH POS_CASH
        String[] collected = cashCode(s);
        call(cashier, post("/org/pos/codes/" + collected[0] + "/collect"), "{\"amount_mad\": 5000}")
                .andExpect(status().isOk()); // 50 DH CASH_AGENCY at the till
        String[] network = cashCode(s);
        var callback = post("/payments/cash/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        CashCallbackTest.signed(network[0], 5000, "NET-" + UUID.randomUUID()).forEach(callback::param);
        mvc.perform(callback).andExpect(status().isOk()); // 50 DH by the simulated network
        cashCode(s); // a code not paid: not collected
        // Another organizer's till sale in the same hours: never in this report.
        Sale other = onSale(5);
        Caller foreign = caller(other.org().org(), "CASHIER");
        openSession(foreign, "Ailleurs");
        sell(foreign, other.eventId(), other.priceId(), 1);

        String json = body(report(caller(s.org().org(), "FINANCE"), aroundNow()).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.days[*].collector")).containsExactly("FAKE_CASH", "POS", "POS");
        assertThat(JsonPath.<List<String>>read(json, "$.days[*].collector_name"))
                .containsExactly("Réseau cash simulé", "Guichet", "Guichet");
        assertThat(JsonPath.<List<String>>read(json, "$.days[*].method"))
                .containsExactly("CASH_AGENCY", "CASH_AGENCY", "POS_CASH");
        assertThat(JsonPath.<List<Integer>>read(json, "$.days[*].amount_mad")).containsExactly(5000, 5000, 10000);
        assertThat(JsonPath.<List<Integer>>read(json, "$.days[*].payments")).containsExactly(1, 1, 1);
        assertKeys(json, "$.sessions[0]", "id", "label", "staff", "opened_at", "closed_at", "orders", "tickets",
                "prints", "expected_mad", "declared_mad", "variance_mad");
        assertThat(JsonPath.<List<String>>read(json, "$.sessions[*].id")).containsExactly(session);
        assertThat(JsonPath.<Integer>read(json, "$.sessions[0].expected_mad")).isEqualTo(15000); // so far
        assertThat(JsonPath.<Object>read(json, "$.sessions[0].closed_at")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.sessions[0].declared_mad")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.sessions[0].variance_mad")).isNull();
        assertThat(JsonPath.<Integer>read(json, "$.sessions[0].orders")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.sessions[0].tickets")).isEqualTo(3);
        assertThat(JsonPath.<String>read(json, "$.sessions[0].staff.name")).isNotBlank();
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.totals")).isEqualTo(Map.of("collected_mad", 20000,
                "declared_mad", 0, "variance_mad", 0, "open_sessions", 1));

        call(cashier, post("/org/pos/sessions/" + session + "/close"), "{\"declared_mad\": 14000}")
                .andExpect(status().isOk());
        json = body(report(s.org(), aroundNow()).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.sessions[0].expected_mad")).isEqualTo(15000);
        assertThat(JsonPath.<Integer>read(json, "$.sessions[0].declared_mad")).isEqualTo(14000);
        assertThat(JsonPath.<Integer>read(json, "$.sessions[0].variance_mad")).isEqualTo(-1000);
        assertThat(JsonPath.<String>read(json, "$.sessions[0].closed_at")).matches("\\d{4}-\\d{2}-\\d{2}T.*Z");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.totals")).isEqualTo(Map.of("collected_mad", 20000,
                "declared_mad", 14000, "variance_mad", -1000, "open_sessions", 0));

        // A session opened before the range is not listed; its payments in the range still are.
        jdbc.sql("update pos_sessions set opened_at = now() - interval '3 days' where id = ?::uuid").param(session).update();
        report(s.org(), aroundNow()).andExpect(jsonPath("$.sessions").isEmpty())
                .andExpect(jsonPath("$.totals.collected_mad").value(20000))
                .andExpect(jsonPath("$.totals.declared_mad").value(0));
    }

    // ---------- CSV ----------

    @Test
    void theCsvHasABomSemicolonsCrlfAndDhWithAComma() throws Exception {
        Sale s = onSale(10);
        String price = JsonPath.read(body(call(s.org(), post("/org/events/" + s.eventId() + "/prices"),
                "{\"zone_id\": %d, \"name\": \"Loge\", \"price_mad\": 125050, \"quota\": 5}".formatted(s.zoneId()))
                .andExpect(status().isCreated())), "$.id");
        Caller cashier = caller(s.org().org(), "CASHIER");
        String session = openSession(cashier, "Guichet; \\\"Nord\\\""); // JSON-escaped: Guichet; "Nord"
        String order = sell(cashier, s.eventId(), price, 1);
        completedAt(order, "2026-11-05T23:30:00Z");
        jdbc.sql("update pos_sessions set opened_at = '2026-11-05T17:00:00Z' where id = ?::uuid").param(session).update();
        call(cashier, post("/org/pos/sessions/" + session + "/close"), "{\"declared_mad\": 124050}")
                .andExpect(status().isOk());
        jdbc.sql("update pos_sessions set closed_at = '2026-11-06T00:15:00Z' where id = ?::uuid").param(session).update();
        String staff = jdbc.sql("select s.name from pos_sessions p join staff s on s.id = p.staff_id where p.id = ?::uuid")
                .param(session).query(String.class).single();
        // A till label typed as a formula: escaped as text; the negative gap above stays a plain number.
        Caller other = caller(s.org().org(), "CASHIER");
        String formula = openSession(other, "=1+1");
        jdbc.sql("update pos_sessions set opened_at = '2026-11-06T09:00:00Z' where id = ?::uuid").param(formula).update();
        String otherStaff = jdbc.sql("select s.name from pos_sessions p join staff s on s.id = p.staff_id where p.id = ?::uuid")
                .param(formula).query(String.class).single();

        var res = call(caller(s.org().org(), "FINANCE"),
                get("/org/finance/cash.csv?from=2026-11-01&to=2026-11-07&timezone=Europe/Paris"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"encaissements-2026-11-01-2026-11-07.csv\""))
                .andReturn().getResponse();
        byte[] bytes = res.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).endsWith("\r\n");
        assertThat(text.split("\r\n", -1)).containsExactly(
                "kind;jour;encaisseur;moyen;paiements;montant;caisse;caissier;ouverture;cloture;attendu;declare;ecart",
                "jour;2026-11-06;Guichet;POS_CASH;1;1250,50;;;;;;;",
                "caisse;;;;;;\"Guichet; \"\"Nord\"\"\";" + staff + ";2026-11-05 18:00;2026-11-06 01:15;1250,50;1240,50;-10,00",
                "caisse;;;;;;'=1+1;" + otherStaff + ";2026-11-06 10:00;;0,00;;",
                "");
        // the same range checks as the JSON
        call(s.org(), get("/org/finance/cash.csv?from=2026-01-01&to=2026-06-01"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void dhIsIntegerMaths() {
        assertThat(CashReport.dh(125050)).isEqualTo("1250,50");
        assertThat(CashReport.dh(5)).isEqualTo("0,05");
        assertThat(CashReport.dh(0)).isEqualTo("0,00");
        assertThat(CashReport.dh(-1000)).isEqualTo("-10,00");
        assertThat(CashReport.dh(-5)).isEqualTo("-0,05");
        assertThat(CashReport.dh(Long.MAX_VALUE)).isEqualTo("92233720368547758,07");
    }

    // ---------- range and roles ----------

    @Test
    void ninetyTwoLocalDaysPassNinetyThreeAre422() throws Exception {
        Sale s = onSale(10);
        // 2026-01-01 .. 2026-04-02 = 92 local days, both included; one day (from = to) is fine.
        report(s.org(), "?from=2026-01-01&to=2026-04-02").andExpect(status().isOk());
        report(s.org(), "?from=2026-11-07&to=2026-11-07").andExpect(status().isOk());
        report(s.org(), "?from=2026-01-01&to=2026-04-03")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.to").exists());
        for (String bad : List.of("?to=2026-11-07", "?from=2026-11-01",
                "?from=2026-11-01T00:00:00Z&to=2026-11-07T00:00:00Z", "?from=2026-11-01&to=2026-11-31",
                "?from=2026-11-08&to=2026-11-07",
                "?from=2026-11-01&to=2026-11-07&timezone=UTC",
                "?from=2026-11-01&to=2026-11-07&timezone=%2B01:00",
                "?from=2026-11-01&to=2026-11-07&timezone=Mars/Olympus")) {
            report(s.org(), bad).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void adminOpsFinanceRead_cashierGateSupervisor403() throws Exception {
        Sale s = onSale(10);
        String q = aroundNow();
        for (String role : List.of("FINANCE", "OPS", "ADMIN")) {
            report(caller(s.org().org(), role), q).andExpect(status().isOk());
            call(caller(s.org().org(), role), get("/org/finance/cash.csv" + q)).andExpect(status().isOk());
        }
        for (String role : List.of("CASHIER", "GATE", "SUPERVISOR")) {
            report(caller(s.org().org(), role), q).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            call(caller(s.org().org(), role), get("/org/finance/cash.csv" + q)).andExpect(status().isForbidden());
        }
        mvc.perform(get("/org/finance/cash" + q).header("X-Organizer-Id", s.org().org()))
                .andExpect(status().isUnauthorized());
    }
}
