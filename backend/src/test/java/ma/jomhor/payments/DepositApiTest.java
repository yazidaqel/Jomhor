package ma.jomhor.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import ma.jomhor.catalog.OrgApiTest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-67): GET /org/finance/deposits, POST /org/finance/deposits and POST /org/finance/deposits/{id}/void
 * (docs/04-api.md §Organizer, §Finance Deposits; plan lot-5 Q233). Sessions are written straight into pos_sessions:
 * a deposit reads only their state and declared_mad.
 */
class DepositApiTest extends OrgApiTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    // ---------- fixtures ----------

    /** A cashier's staff id in the organizer (a new cashier each time, so open sessions never collide). */
    private UUID cashier(UUID org) {
        UUID user = staff(org, randomPhone(), "CASHIER", true);
        return jdbc.sql("select id from staff where organizer_id = ? and user_id = ?").params(org, user)
                .query(UUID.class).single();
    }

    /** A session closed {@code closedAgo} (a Postgres interval) ago with {@code declared} centimes counted. */
    private String closed(UUID org, String label, String closedAgo, long declared) {
        return jdbc.sql("""
                        insert into pos_sessions (organizer_id, staff_id, label, opened_at, closed_at, expected_mad, declared_mad)
                        values (?, ?, ?, now() - interval '30 days', now() - ?::interval, ?, ?) returning id
                        """)
                .params(org, cashier(org), label, closedAgo, declared, declared).query(UUID.class).single().toString();
    }

    private String open(UUID org) {
        return jdbc.sql("insert into pos_sessions (organizer_id, staff_id, label) values (?, ?, 'Ouvert') returning id")
                .params(org, cashier(org)).query(UUID.class).single().toString();
    }

    private static String today() {
        return LocalDate.now(CASABLANCA).toString();
    }

    private static String body(String on, Object amount, String reference, List<String> sessions) {
        String ids = sessions.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(", ", "[", "]"));
        return """
                {"deposited_on": "%s", "amount_mad": %s, "reference": "%s", "session_ids": %s}
                """.formatted(on, amount, reference, ids);
    }

    private ResultActions deposit(Caller c, String json) throws Exception {
        return call(c, post("/org/finance/deposits"), json);
    }

    private String recorded(Caller c, String on, long amount, String... sessions) throws Exception {
        return JsonPath.read(body(deposit(c, body(on, amount, "BORD", List.of(sessions)))
                .andExpect(status().isCreated())), "$.id");
    }

    private ResultActions voidIt(Caller c, String id, String reason) throws Exception {
        return call(c, post("/org/finance/deposits/" + id + "/void"), "{\"reason\": \"" + reason + "\"}");
    }

    private UUID depositOf(String session) {
        return jdbc.sql("select deposit_id from pos_sessions where id = ?::uuid").param(session).query(UUID.class)
                .optional().orElse(null);
    }

    private List<Map<String, Object>> audits(UUID org, String action) {
        return jdbc.sql("select target, payload::text as payload from audit_log where organizer_id = ? and action = ?"
                        + " order by id")
                .params(org, action).query((rs, n) -> Map.<String, Object>of("target", rs.getString("target"),
                        "payload", JsonPath.parse(rs.getString("payload")).<Map<String, Object>>read("$")))
                .list();
    }

    private static void refused(ResultActions r, String field, String reason) throws Exception {
        r.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['" + field + "']").exists());
        if (reason == null) {
            r.andExpect(jsonPath("$.details.reason").doesNotExist());
        } else {
            r.andExpect(jsonPath("$.details.reason").value(reason));
        }
    }

    // ---------- create ----------

    @Test
    void aDepositTakesTwoClosedSessionsAndShowsTheGap() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        String nord = closed(org, "Guichet Nord", "2 days", 70000);
        String sud = closed(org, "Guichet Sud", "1 day", 30000);
        Caller finance = caller(org, "FINANCE");

        String json = body(deposit(finance, body(today(), 99000, "  Bordereau 448812 ", List.of(sud, nord)))
                .andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "deposited_on", "amount_mad", "reference", "recorded_by", "recorded_at", "voided_at",
                "voided_by", "void_reason", "sessions", "declared_mad", "variance_mad");
        assertKeys(json, "$.recorded_by", "id", "name");
        assertKeys(json, "$.sessions[0]", "id", "label", "staff", "closed_at", "declared_mad");
        assertKeys(json, "$.sessions[0].staff", "id", "name");
        assertThat(JsonPath.<String>read(json, "$.deposited_on")).isEqualTo(today());
        assertThat(JsonPath.<Integer>read(json, "$.amount_mad")).isEqualTo(99000);
        assertThat(JsonPath.<String>read(json, "$.reference")).isEqualTo("Bordereau 448812"); // trimmed
        assertThat(JsonPath.<Integer>read(json, "$.declared_mad")).isEqualTo(100000);
        assertThat(JsonPath.<Integer>read(json, "$.variance_mad")).isEqualTo(-1000);
        assertThat(JsonPath.<List<String>>read(json, "$.sessions[*].id")).containsExactly(nord, sud); // by closed_at
        assertThat(JsonPath.<List<String>>read(json, "$.sessions[*].label")).containsExactly("Guichet Nord", "Guichet Sud");
        assertThat(JsonPath.<Object>read(json, "$.voided_at")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.voided_by")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.void_reason")).isNull();
        String recorder = jdbc.sql("select id from staff where organizer_id = ? and role = 'FINANCE'").param(org)
                .query(UUID.class).single().toString();
        assertThat(JsonPath.<String>read(json, "$.recorded_by.id")).isEqualTo(recorder); // the staff row

        String id = JsonPath.read(json, "$.id");
        assertThat(depositOf(nord)).hasToString(id);
        assertThat(depositOf(sud)).hasToString(id);
        assertThat(audits(org, "deposit.create")).containsExactly(Map.of("target", id, "payload",
                Map.of("amount_mad", 99000, "reference", "Bordereau 448812", "session_ids", List.of(sud, nord))));
        // the expected and declared figures never move
        assertThat(jdbc.sql("select sum(expected_mad) + sum(declared_mad) from pos_sessions where organizer_id = ?")
                .param(org).query(Long.class).single()).isEqualTo(200000L);
    }

    @Test
    void sessionRefusalsNameTheFirstBadSessionAndWriteNothing() throws Exception {
        Caller finance = caller(admin().org(), "FINANCE");
        UUID org = finance.org();
        String ok = closed(org, "A", "2 days", 1000);
        String taken = closed(org, "B", "2 days", 1000);
        recorded(finance, today(), 1000, taken);
        String foreign = closed(admin().org(), "Ailleurs", "2 days", 1000);

        refused(deposit(finance, body(today(), 1000, "R", List.of(open(org)))), "session_ids[0]", "OPEN");
        refused(deposit(finance, body(today(), 1000, "R", List.of(ok, taken))), "session_ids[1]", "DEPOSITED");
        refused(deposit(finance, body(today(), 1000, "R", List.of(ok, foreign))), "session_ids[1]", "UNKNOWN");
        refused(deposit(finance, body(today(), 1000, "R", List.of(UUID.randomUUID().toString()))), "session_ids[0]",
                "UNKNOWN");
        refused(deposit(finance, body(today(), 1000, "R", List.of(ok, ok))), "session_ids", null);
        refused(deposit(finance, body(today(), 1000, "R", List.of())), "session_ids", null);
        refused(deposit(finance, body(today(), 1000, "R", IntStream.range(0, 101)
                .mapToObj(i -> UUID.randomUUID().toString()).toList())), "session_ids", null);
        refused(deposit(finance, "{\"deposited_on\": \"" + today() + "\", \"amount_mad\": 1, \"reference\": \"R\"}"),
                "session_ids", null);

        assertThat(depositOf(ok)).isNull();
        assertThat(depositOf(foreign)).isNull();
        assertThat(jdbc.sql("select count(*) from cash_deposits where organizer_id = ?").param(org).query(Long.class)
                .single()).isOne();
        assertThat(audits(org, "deposit.create")).hasSize(1);
    }

    @Test
    void bodyAndDateRefusals() throws Exception {
        Caller finance = caller(admin().org(), "FINANCE");
        UUID org = finance.org();
        String yesterday = closed(org, "Hier", "1 day", 1000);
        String now = closed(org, "Maintenant", "1 minute", 1000);
        LocalDate today = LocalDate.now(CASABLANCA);

        refused(deposit(finance, body(today.plusDays(1).toString(), 1000, "R", List.of(yesterday))), "deposited_on",
                "FUTURE");
        refused(deposit(finance, body(today.minusDays(1).toString(), 1000, "R", List.of(yesterday, now))),
                "deposited_on", "BEFORE_CLOSE");
        refused(deposit(finance, body("2026-13-01", 1000, "R", List.of(yesterday))), "deposited_on", null);
        refused(deposit(finance, body(today.toString(), "990.0", "R", List.of(yesterday))), "amount_mad", null);
        refused(deposit(finance, body(today.toString(), "\"990\"", "R", List.of(yesterday))), "amount_mad", null);
        refused(deposit(finance, body(today.toString(), -1, "R", List.of(yesterday))), "amount_mad", null);
        refused(deposit(finance, body(today.toString(), "null", "R", List.of(yesterday))), "amount_mad", null);
        refused(deposit(finance, body(today.toString(), 1000, "", List.of(yesterday))), "reference", null);
        refused(deposit(finance, body(today.toString(), 1000, "   ", List.of(yesterday))), "reference", null);
        refused(deposit(finance, body(today.toString(), 1000, "x".repeat(101), List.of(yesterday))), "reference", null);
        assertThat(depositOf(yesterday)).isNull();

        // 100 characters, amount 0 and the close day itself are fine
        deposit(finance, body(today.toString(), 0, "x".repeat(100), List.of(now))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.variance_mad").value(-1000));
    }

    @Test
    void twoParallelDepositsOfOneSessionGiveExactlyOne201() throws Exception {
        Caller finance = caller(admin().org(), "FINANCE");
        UUID org = finance.org();
        String a = closed(org, "A", "1 day", 1000);
        String b = closed(org, "B", "1 day", 2000);
        int threads = 6;
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(threads)) {
            var start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                // half take (a, b), half (b, a): the id-ordered locks never deadlock
                List<String> ids = i % 2 == 0 ? List.of(a, b) : List.of(b, a);
                results.add(pool.submit(() -> {
                    start.await();
                    return deposit(finance, body(today(), 3000, "R", ids)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> r : results) {
                statuses.add(r.get());
            }
        }
        assertThat(statuses).containsOnlyOnce(201).filteredOn(s -> s != 201).containsOnly(422);
        assertThat(jdbc.sql("select count(*) from cash_deposits where organizer_id = ?").param(org).query(Long.class)
                .single()).isOne();
        assertThat(depositOf(a)).isEqualTo(depositOf(b)).isNotNull();
    }

    // ---------- void ----------

    @Test
    void anAdminVoidFreesTheSessionsOnceAndTheyCanBeDepositedAgain() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        String nord = closed(org, "Guichet Nord", "2 days", 70000);
        String sud = closed(org, "Guichet Sud", "1 day", 30000);
        Caller finance = caller(org, "FINANCE");
        String id = recorded(finance, today(), 99000, nord, sud);

        voidIt(finance, id, "montant mal saisi").andExpect(status().isForbidden());
        voidIt(caller(org, "OPS"), id, "montant mal saisi").andExpect(status().isForbidden());
        refused(voidIt(admin, id, " "), "reason", null);
        refused(voidIt(admin, id, "x".repeat(201)), "reason", null);
        assertThat(depositOf(nord)).hasToString(id);

        String json = body(voidIt(admin, id, " montant mal saisi ").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$.voided_at")).isNotNull();
        assertKeys(json, "$.voided_by", "id", "name");
        assertThat(JsonPath.<String>read(json, "$.void_reason")).isEqualTo("montant mal saisi");
        assertThat(JsonPath.<List<Object>>read(json, "$.sessions")).isEmpty();
        assertThat(JsonPath.<Object>read(json, "$.declared_mad")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.variance_mad")).isNull();
        assertThat(depositOf(nord)).isNull();
        assertThat(depositOf(sud)).isNull();
        List<Map<String, Object>> voids = audits(org, "deposit.void");
        assertThat(voids).hasSize(1);
        assertThat(voids.getFirst().get("target")).isEqualTo(id);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) voids.getFirst().get("payload");
        assertThat(payload).containsOnlyKeys("reason", "session_ids").containsEntry("reason", "montant mal saisi");
        assertThat(payload.get("session_ids")).asInstanceOf(InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder(nord, sud);

        // again → 200, unchanged, nothing new audited
        String again = body(voidIt(admin, id, "autre raison").andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(again, "$.void_reason")).isEqualTo("montant mal saisi");
        assertThat(JsonPath.<String>read(again, "$.voided_at")).isEqualTo(JsonPath.read(json, "$.voided_at"));
        assertThat(audits(org, "deposit.void")).hasSize(1);

        // the freed sessions take a new deposit
        String second = recorded(finance, today(), 100000, nord, sud);
        assertThat(depositOf(nord)).hasToString(second);

        // unknown or another organizer's → 404
        voidIt(admin, UUID.randomUUID().toString(), "x").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        voidIt(admin(), second, "x").andExpect(status().isNotFound());
        assertThat(depositOf(nord)).hasToString(second);
    }

    // ---------- list ----------

    @Test
    void theListIsByDepositDayVoidedIncludedAndUndepositedOldestFirst() throws Exception {
        Caller finance = caller(admin().org(), "FINANCE");
        UUID org = finance.org();
        LocalDate today = LocalDate.now(CASABLANCA);
        String old = closed(org, "Ancien", "60 days", 1000);
        String mid = closed(org, "Milieu", "10 days", 2000);
        String recent = closed(org, "Récent", "5 days", 3000);
        String left1 = closed(org, "Reste 1", "200 days", 4000);
        String left2 = closed(org, "Reste 2", "3 days", 5000);
        open(org); // never listed
        String early = recorded(finance, today.minusDays(40).toString(), 1000, old);
        String voided = recorded(finance, today.minusDays(4).toString(), 2500, mid);
        voidIt(caller(org, "ADMIN"), voided, "erreur").andExpect(status().isOk());
        String late = recorded(finance, today.minusDays(2).toString(), 5000, mid, recent);
        closed(admin().org(), "Ailleurs", "1 day", 9000); // another organizer's: never listed

        String json = body(call(finance, get("/org/finance/deposits?from=" + today.minusDays(30) + "&to=" + today))
                .andExpect(status().isOk()));
        assertKeys(json, "$", "deposits", "undeposited");
        assertThat(JsonPath.<List<String>>read(json, "$.deposits[*].id")).containsExactly(late, voided);
        assertThat(JsonPath.<List<String>>read(json, "$.deposits[0].sessions[*].id")).containsExactly(mid, recent);
        assertThat(JsonPath.<Integer>read(json, "$.deposits[0].declared_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Integer>read(json, "$.deposits[0].variance_mad")).isZero();
        assertThat(JsonPath.<List<Object>>read(json, "$.deposits[1].sessions")).isEmpty();
        assertThat(JsonPath.<Object>read(json, "$.deposits[1].declared_mad")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.deposits[1].variance_mad")).isNull();
        assertThat(JsonPath.<String>read(json, "$.deposits[1].void_reason")).isEqualTo("erreur");
        // not limited to the range, oldest closed_at first
        assertThat(JsonPath.<List<String>>read(json, "$.undeposited[*].id")).containsExactly(left1, left2);
        assertKeys(json, "$.undeposited[0]", "id", "label", "staff", "closed_at", "declared_mad");
        assertThat(JsonPath.<List<Integer>>read(json, "$.undeposited[*].declared_mad")).containsExactly(4000, 5000);

        // both ends included
        String edge = body(call(finance, get("/org/finance/deposits?from=" + today.minusDays(40) + "&to="
                + today.minusDays(40))).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(edge, "$.deposits[*].id")).containsExactly(early);

        // the /finance/cash range rules
        call(finance, get("/org/finance/deposits?from=2026-01-01&to=2026-04-02")).andExpect(status().isOk());
        for (String bad : List.of("?from=2026-01-01&to=2026-04-03", "?to=2026-01-01", "?from=2026-01-01",
                "?from=2026-01-02&to=2026-01-01", "?from=2026-02-30&to=2026-03-01")) {
            call(finance, get("/org/finance/deposits" + bad)).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void undepositedIsCappedAt200() throws Exception {
        Caller finance = caller(admin().org(), "FINANCE");
        UUID org = finance.org();
        UUID staffId = cashier(org);
        jdbc.sql("""
                        insert into pos_sessions (organizer_id, staff_id, label, opened_at, closed_at, expected_mad, declared_mad)
                        select ?, ?, 'S' || n, now() - interval '400 days', now() - (n || ' hours')::interval, 0, 0
                        from generate_series(1, 201) n
                        """)
                .params(org, staffId).update();
        String json = body(call(finance, get("/org/finance/deposits?from=" + today() + "&to=" + today()))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.undeposited[*].label"))
                .isEqualTo(IntStream.range(0, 200).mapToObj(i -> "S" + (201 - i)).toList()); // S201 .. S2
    }

    // ---------- roles ----------

    @Test
    void opsReadsFinanceAndAdminRecordOthers403() throws Exception {
        Caller admin = admin();
        UUID org = admin.org();
        String q = "?from=" + today() + "&to=" + today();
        String s1 = closed(org, "A", "1 day", 1000);
        String s2 = closed(org, "B", "1 day", 1000);
        for (String role : List.of("ADMIN", "OPS", "FINANCE")) {
            call(caller(org, role), get("/org/finance/deposits" + q)).andExpect(status().isOk());
        }
        deposit(caller(org, "OPS"), body(today(), 1000, "R", List.of(s1))).andExpect(status().isForbidden());
        for (String role : List.of("CASHIER", "GATE", "SUPERVISOR")) {
            Caller c = caller(org, role);
            call(c, get("/org/finance/deposits" + q)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            deposit(c, body(today(), 1000, "R", List.of(s1))).andExpect(status().isForbidden());
        }
        assertThat(depositOf(s1)).isNull();
        recorded(admin, today(), 1000, s1);
        recorded(caller(org, "FINANCE"), today(), 1000, s2);
        mvc.perform(get("/org/finance/deposits" + q).header("X-Organizer-Id", org)).andExpect(status().isUnauthorized());
    }
}
