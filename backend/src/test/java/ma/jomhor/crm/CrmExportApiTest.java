package ma.jomhor.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-68): GET /org/crm/export.csv (docs/04-api.md §Organizer, §Finance CRM export; plan lot-5 Q235) and the
 * summary's {@code exportable}. Personal data: ADMIN only, minimised, audited without names or phones.
 */
class CrmExportApiTest extends CrmTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");
    private static final String HEADER =
            "numero;nom;telephone;langue;membre_depuis;notifications;whatsapp;cotisation_jusqu_au;abonne;matchs";
    /** 23:30 UTC in June: the next local day under every tzdata (Casablanca is +01:00 then). */
    private static final String JOINED = "2026-06-05T23:30:00Z";

    // ---------- fixtures ----------

    private UUID named(String name, String locale) {
        UUID u = user();
        jdbc.sql("update users set display_name = ?, locale = ? where id = ?").params(name, locale, u).update();
        return u;
    }

    private void joinedAt(Club c, UUID user, String at) {
        jdbc.sql("update memberships set joined_at = ?::timestamptz where organizer_id = ? and user_id = ?")
                .params(at, c.org(), user).update();
    }

    private void paidUntil(Club c, UUID user, String at) {
        jdbc.sql("update memberships set paid_until = ?::timestamptz where organizer_id = ? and user_id = ?")
                .params(at, c.org(), user).update();
    }

    /** A pass of {@code status} in a series ending on {@code endsOn}. */
    private void pass(Club c, UUID user, String status, LocalDate endsOn) {
        UUID series = jdbc.sql("insert into event_series (organizer_id, name, starts_on, ends_on) "
                        + "values (?, 'Saison', '2026-01-01', ?) returning id")
                .params(c.org(), endsOn).query(UUID.class).single();
        UUID product = jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, series_id, zone_id)
                        values (?, 'PASS', 'Abonnement', 150000, now() + interval '30 days', 10, ?, ?) returning id""")
                .params(c.org(), series, c.zone()).query(UUID.class).single();
        jdbc.sql("""
                        insert into passes (organizer_id, product_id, series_id, user_id, holder_name, status, suspended_at)
                        values (?, ?, ?, ?, 'Abonné', ?::pass_status, case when ? = 'SUSPENDED' then now() end)""")
                .params(c.org(), product, series, user, status, status).update();
    }

    private static LocalDate today() {
        return LocalDate.now(CASABLANCA);
    }

    private static String local(String instant) {
        return Instant.parse(instant).atZone(CASABLANCA).toLocalDate().toString();
    }

    private ResultActions export(Caller c) throws Exception {
        return call(c, get("/org/crm/export.csv"));
    }

    /** The file's lines after the BOM, the trailing CRLF checked. */
    private List<String> lines(Caller c) throws Exception {
        byte[] bytes = export(c).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).endsWith("\r\n").doesNotContain("\n\n");
        List<String> lines = List.of(text.substring(0, text.length() - 2).split("\r\n", -1));
        assertThat(lines.getFirst()).isEqualTo(HEADER);
        return lines.subList(1, lines.size());
    }

    private List<String> exports(UUID org) {
        return jdbc.sql("select coalesce(target, 'null') || ' ' || payload::text from audit_log "
                + "where organizer_id = ? and action = 'crm.export' order by id").param(org).query(String.class).list();
    }

    /** The demo 5a club: Yazid (push only), Karim (WhatsApp, paid), Nadia (pass), Amine (nothing), Sara (left). */
    private record Demo(Club club, UUID yazid, UUID karim, String karimPhone, String karimToken, UUID nadia) {}

    private Demo demo() throws Exception {
        Club c = club();
        UUID yazid = named("Yazid", "fr");
        member(c, yazid, true, false, false);
        pass(c, yazid, "ACTIVE", today().minusDays(1)); // a pass of an ended series: not "abonné"
        String karimPhone = randomPhone();
        String karimToken = fanToken(karimPhone);
        UUID karim = jdbc.sql("select id from users where phone_e164 = ?").param(karimPhone).query(UUID.class).single();
        jdbc.sql("update users set display_name = 'Karim' where id = ?").param(karim).update();
        member(c, karim, false, true, false);
        paidUntil(c, karim, "2099-06-30T12:00:00Z");
        for (int i = 0; i < 2; i++) { // two events attended
            int e = event(c, "CLOSED");
            scan(c, e, ticket(c, e, karim, "VALID"));
        }
        UUID nadia = named("Nadia", "ar");
        member(c, nadia, false, false, false);
        pass(c, nadia, "ACTIVE", today());
        member(c, named("Amine", "fr"), false, false, false);
        UUID sara = named("Sara", "fr");
        member(c, sara, true, true, true); // left: never exported, even paid
        paidUntil(c, sara, "2099-06-30T12:00:00Z");
        for (UUID u : List.of(yazid, karim, nadia, sara)) {
            joinedAt(c, u, JOINED);
        }
        return new Demo(c, yazid, karim, karimPhone, karimToken, nadia);
    }

    // ---------- rows ----------

    @Test
    void theDemoFileHasYazidKarimAndNadiaOnlyKarimsPhoneAndNadiaAsAbonne() throws Exception {
        Demo d = demo();
        Caller admin = caller(d.club().org(), "ADMIN");
        String joined = local(JOINED);
        assertThat(joined).isEqualTo("2026-06-06");

        assertThat(lines(admin)).containsExactly(
                "1;Yazid;;fr;" + joined + ";oui;non;;non;0",
                "2;Karim;" + d.karimPhone() + ";fr;" + joined + ";non;oui;" + local("2099-06-30T12:00:00Z") + ";non;2",
                "3;Nadia;;ar;" + joined + ";non;non;;oui;0");
        assertThat(exports(d.club().org())).containsExactly("null {\"rows\": 3, \"with_phone\": 1}");

        // matchs = /fan/me/membership events_attended
        String membership = body(mvc.perform(get("/fan/me/membership").param("organizer", d.club().org().toString())
                .header("Authorization", "Bearer " + d.karimToken())).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(membership, "$.loyalty.events_attended")).isEqualTo(2);

        // the summary counts the same rows
        call(caller(d.club().org(), "FINANCE"), get("/org/members/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exportable").value(3)).andExpect(jsonPath("$.marketing_whatsapp").value(1));
    }

    @Test
    void passesActiveOrSuspendedInACurrentSeriesCountAndNonMembersNeverDo() throws Exception {
        Club c = club();
        UUID suspended = named("Suspendu", "fr");
        member(c, suspended, false, false, false);
        pass(c, suspended, "SUSPENDED", today().plusDays(30));
        UUID pending = named("En attente", "fr");
        member(c, pending, false, false, false);
        pass(c, pending, "PENDING", today().plusDays(30));
        UUID cancelled = named("Annulé", "fr");
        member(c, cancelled, false, false, false);
        pass(c, cancelled, "CANCELLED", today().plusDays(30));
        UUID expiredPaid = named("Cotisation échue", "fr");
        member(c, expiredPaid, false, false, false);
        paidUntil(c, expiredPaid, "2020-01-01T00:00:00Z");
        UUID leftHolder = named("Parti", "fr");
        member(c, leftHolder, false, false, true);
        pass(c, leftHolder, "ACTIVE", today().plusDays(30));
        // a pass holder and ticket holder who isn't a member: never exported
        UUID outsider = named("Pas membre", "fr");
        pass(c, outsider, "ACTIVE", today().plusDays(30));
        int e = event(c, "CLOSED");
        scan(c, e, ticket(c, e, outsider, "VALID"));
        // another organizer's member with every reason to be exported
        Club other = club();
        UUID foreign = named("Ailleurs", "fr");
        member(other, foreign, true, true, false);
        pass(other, foreign, "ACTIVE", today().plusDays(30));

        Caller admin = caller(c.org(), "ADMIN");
        List<String> rows = lines(admin);
        assertThat(rows).hasSize(1).allMatch(l -> l.startsWith("1;Suspendu;;fr;") && l.endsWith(";non;non;;oui;0"));
        assertThat(String.join("\n", rows)).doesNotContain("Ailleurs").doesNotContain(phone(foreign))
                .doesNotContain(phone(outsider));
        call(admin, get("/org/members/summary")).andExpect(jsonPath("$.exportable").value(1));
    }

    @Test
    void theNameIsFormulaGuardedThePhoneOnlyWithWhatsAppAndAnEmptyNameStaysEmpty() throws Exception {
        Club c = club();
        UUID formula = named("=HYPERLINK(1)", "en");
        member(c, formula, true, false, false);
        UUID quoted = named("Karim; \"K\"", "fr");
        member(c, quoted, false, true, false);
        UUID noName = user();
        member(c, noName, true, true, false);
        Caller admin = caller(c.org(), "ADMIN");

        List<String> rows = lines(admin);
        assertThat(rows.get(0)).startsWith("1;'=HYPERLINK(1);;en;");
        assertThat(rows.get(1)).startsWith("2;\"Karim; \"\"K\"\"\";" + phone(quoted) + ";fr;");
        assertThat(rows.get(2)).startsWith("3;;" + phone(noName) + ";fr;").contains(";oui;oui;;non;0");
        assertThat(String.join("\n", rows)).doesNotContain(phone(formula));
        assertThat(exports(c.org())).containsExactly("null {\"rows\": 3, \"with_phone\": 2}");
    }

    // ---------- headers, roles, statements ----------

    @Test
    void headersAreNoStoreWithTheLocalDayInTheName() throws Exception {
        Club c = club();
        export(caller(c.org(), "ADMIN")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"fans-" + today() + ".csv\""));
        assertThat(exports(c.org())).containsExactly("null {\"rows\": 0, \"with_phone\": 0}");
    }

    @Test
    void onlyAdminExports() throws Exception {
        Club c = club();
        member(c, named("Yazid", "fr"), true, true, false);
        for (String role : List.of("OPS", "FINANCE", "CASHIER", "GATE", "SUPERVISOR")) {
            export(caller(c.org(), role)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/org/crm/export.csv").header("X-Organizer-Id", c.org())).andExpect(status().isUnauthorized());
        assertThat(exports(c.org())).isEmpty();
    }

    @Test
    void theStatementCountIsTheSameFor3And300Members() throws Exception {
        long few = statementsFor(3);
        long many = statementsFor(300);
        assertThat(many).isEqualTo(few);
    }

    private long statementsFor(int members) throws Exception {
        Club c = club();
        for (int i = 0; i < members; i++) {
            UUID u = user();
            member(c, u, i % 2 == 0, i % 3 == 0, false);
            if (i % 5 == 0) {
                pass(c, u, "ACTIVE", today().plusDays(30));
            }
        }
        Caller admin = caller(c.org(), "ADMIN");
        long before = statements.count();
        int rows = lines(admin).size();
        long used = statements.count() - before;
        assertThat(rows).isPositive();
        return used;
    }
}
