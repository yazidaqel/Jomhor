package ma.jomhor.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-71): POST /org/finance/settlements/{id}/paid, "Versement reçu" (docs/04-api.md §Organizer, §Finance
 * Payout, plan lot-5 Q227). Statements are written straight into settlements: the payout reads only their status.
 */
class SettlementPaidApiTest extends OrgApiTest {

    private String statement(UUID org, int daysAgo, long payout) {
        return jdbc.sql("""
                        insert into settlements (organizer_id, day, collected_mad, till_mad, fee_rate_bp, fee_ht_mad,
                                                 fee_vat_mad, carried_in_mad, payout_mad, due_on, status)
                        values (?, current_date - ?, ?, 0, 0, 0, 0, 0, ?, current_date, ?::settlement_status)
                        returning id""")
                .params(org, daysAgo, payout, payout, payout > 0 ? "DUE" : "NONE").query(UUID.class).single()
                .toString();
    }

    private ResultActions paid(Caller c, String id, String reference) throws Exception {
        return call(c, post("/org/finance/settlements/" + id + "/paid"),
                reference == null ? "{}" : "{\"reference\": \"" + reference + "\"}");
    }

    private List<Map<String, Object>> audits(UUID org) {
        return jdbc.sql("select target, payload::text as payload from audit_log where organizer_id = ? "
                        + "and action = 'settlement.paid' order by id")
                .param(org).query().listOfRows();
    }

    private UUID staffId(UUID org, String role) {
        return jdbc.sql("select id from staff where organizer_id = ? and role = ?::staff_role").params(org, role)
                .query(UUID.class).single();
    }

    @Test
    void dueBecomesPaidOnceWithTheReferenceAndAnAudit() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        String id = statement(org, 3, 3_700_00);
        Caller finance = caller(org, "FINANCE");

        String json = body(paid(finance, id, "  VIR 0042 ").andExpect(status().isOk()));
        assertKeys(json, "$", "id", "day", "status", "overdue", "collected_mad", "till_mad", "fee_rate_bp",
                "fee_ht_mad", "fee_vat_mad", "carried_in_mad", "payout_mad", "due_on", "paid_at", "paid_by",
                "payout_reference", "invoice");
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<Boolean>read(json, "$.overdue")).isFalse();
        assertThat(JsonPath.<String>read(json, "$.payout_reference")).isEqualTo("VIR 0042"); // trimmed
        assertThat(JsonPath.<String>read(json, "$.paid_at")).isNotNull();
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.paid_by"))
                .isEqualTo(Map.of("id", staffId(org, "FINANCE").toString(), "name", "Test"));
        assertThat(audits(org)).singleElement().satisfies(a -> {
            assertThat(a.get("target")).isEqualTo(id);
            assertThat(JsonPath.<Map<String, Object>>read((String) a.get("payload"), "$"))
                    .isEqualTo(Map.of("reference", "VIR 0042"));
        });

        // a replay (even with another reference) answers the row unchanged and audits nothing
        String again = body(paid(admin, id, "VIR 9999").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(again, "$.payout_reference")).isEqualTo("VIR 0042");
        assertThat(JsonPath.<String>read(again, "$.paid_at")).isEqualTo(JsonPath.read(json, "$.paid_at"));
        assertThat(JsonPath.<String>read(again, "$.paid_by.id")).isEqualTo(staffId(org, "FINANCE").toString());
        assertThat(audits(org)).hasSize(1);
    }

    @Test
    void parallelVersementsRecordOnePayerAndOneAudit() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        String id = statement(org, 6, 500_00);
        List<Caller> callers = List.of(admin, caller(org, "FINANCE"), admin, caller(org, "FINANCE"));
        List<String> bodies = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(callers.size())) {
            var start = new CountDownLatch(1);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < callers.size(); i++) {
                Caller c = callers.get(i);
                String ref = "VIR " + i;
                results.add(pool.submit(() -> {
                    start.await();
                    return body(paid(c, id, ref).andExpect(status().isOk()));
                }));
            }
            start.countDown();
            for (Future<String> r : results) {
                bodies.add(r.get());
            }
        }
        assertThat(audits(org)).hasSize(1);
        Map<String, Object> row = jdbc.sql("select paid_by::text as paid_by, payout_reference from settlements "
                + "where id = ?::uuid").param(id).query().singleRow();
        assertThat(row.get("paid_by")).isNotNull();
        // every answer is the one recorded payout: same payer, same reference
        for (String b : bodies) {
            assertThat(JsonPath.<String>read(b, "$.paid_by.id")).isEqualTo(row.get("paid_by"));
            assertThat(JsonPath.<String>read(b, "$.payout_reference")).isEqualTo(row.get("payout_reference"));
        }
        assertThat(JsonPath.<Map<String, Object>>read((String) audits(org).getFirst().get("payload"), "$"))
                .isEqualTo(Map.of("reference", row.get("payout_reference")));
    }

    @Test
    void nothingToPayIsAConflict() throws Exception {
        Caller admin = admin();
        String id = statement(admin.org(), 2, 0);
        paid(admin, id, "VIR 1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_STATUS"))
                .andExpect(jsonPath("$.details.status").value("NONE"));
        assertThat(jdbc.sql("select status::text from settlements where id = ?::uuid").param(id).query(String.class)
                .single()).isEqualTo("NONE");
        assertThat(audits(admin.org())).isEmpty();
    }

    @Test
    void theReferenceIsOneToAHundredCharacters() throws Exception {
        Caller admin = admin();
        String id = statement(admin.org(), 4, 100);
        for (String bad : new String[] {"", "   ", "x".repeat(101), null}) {
            paid(admin, id, bad).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.details.fields.reference").exists());
        }
        paid(admin, id, "x".repeat(100)).andExpect(status().isOk());
    }

    @Test
    void onlyAdminAndFinanceOfTheOrganizerRecordIt() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        String id = statement(org, 5, 100);
        paid(caller(org, "OPS"), id, "VIR 1").andExpect(status().isForbidden());
        paid(caller(org, "CASHIER"), id, "VIR 1").andExpect(status().isForbidden());
        paid(admin(), id, "VIR 1").andExpect(status().isNotFound()); // org B
        paid(admin, UUID.randomUUID().toString(), "VIR 1").andExpect(status().isNotFound());
        assertThat(audits(org)).isEmpty();
        paid(admin, id, "VIR 1").andExpect(status().isOk());
    }
}
