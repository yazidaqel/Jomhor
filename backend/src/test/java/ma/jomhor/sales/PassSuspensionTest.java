package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.gate.domain.ManifestPayload;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.products.application.PassTicketService;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BE-58 (plan lot-5 Q197, Q198; docs/04-api.md §Passes Suspension, Effective status): the pass job suspends a pass
 * whose installment is unpaid 7 days past its due_at, once, on a fixed clock; its tickets then read BLOCKED in the
 * manifest, the wallet (no k_t), PDF, re-send, transfer, re-issue and the staff lookup, while the stored status stays
 * VALID; a ticket of an event already frozen, a transferred one and a staff block are left as they are; paying
 * reactivates it, the next manifest delta unblocks, and the job issues what it missed.
 */
class PassSuspensionTest extends PaymentTest {

    private static final long PRICE = 90_000; // 3 × 30,000
    private static final long PART = 30_000;
    private static final Duration GRACE = Duration.ofDays(7);

    @Autowired
    PassTicketService job;

    @Autowired
    TicketService tickets;

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    private record Fan(String token, UUID id, String pass) {}

    private Fan passHolder(String product, String name) throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        UUID id = jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
        String json = body(fan(token, post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "APP", "holder_name": "%s", "installments": 3}"""
                .formatted(product, name)).andExpect(status().isCreated()));
        callback(approve(payment(token, JsonPath.read(json, "$.id")), PART)).andExpect(status().isSeeOther());
        return new Fan(token, id, JsonPath.read(json, "$.pass_id"));
    }

    private int match(Caller a, String venue, String series, Instant startsAt, int zone) throws Exception {
        int id = JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "%s",
                 "series_id": "%s"}""".formatted(venue, startsAt, series)).andExpect(status().isCreated())), "$.id");
        call(a, post("/org/events/" + id + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": 10, "audience": "PASS"}""".formatted(zone))
                .andExpect(status().isCreated());
        call(a, post("/org/events/" + id + "/publish")).andExpect(status().isOk());
        return id;
    }

    private Instant due(Fan f, int seq) {
        return jdbc.sql("select due_at from pass_installments where pass_id = ?::uuid and seq = ?").params(f.pass(), seq)
                .query(OffsetDateTime.class).single().toInstant();
    }

    private UUID ticket(Fan f, int event) {
        return jdbc.sql("select t.id from tickets t join orders o on o.id = t.order_id"
                        + " where o.pass_id = ?::uuid and o.event_id = ? and t.holder_user_id = ?")
                .params(f.pass(), event, f.id()).query(UUID.class).single();
    }

    private int ticketOrders(Fan f, int event) {
        return jdbc.sql("select count(*) from orders where pass_id = ?::uuid and event_id = ?").params(f.pass(), event)
                .query(Integer.class).single();
    }

    private String passStatus(Fan f) {
        return jdbc.sql("select status::text from passes where id = ?::uuid").param(f.pass()).query(String.class)
                .single();
    }

    private List<String> audits(String action, Fan f) {
        return jdbc.sql("select payload::text from audit_log where action = ? and target = ?").params(action, f.pass())
                .query(String.class).list();
    }

    private String stored(UUID ticket) {
        return jdbc.sql("select status::text from tickets where id = ?").param(ticket).query(String.class).single();
    }

    private Ticket.Status inManifest(UUID org, int event, UUID ticket) {
        return tickets.manifestRows(org, event).stream().filter(r -> r.id().equals(ticket)).findFirst().orElseThrow()
                .status();
    }

    /** A new manifest version of the event (the previous one made 10 min older) → the T lines of its delta. */
    private List<String> deltaRows(UUID org, int event) {
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(event)
                .update();
        int version = manifestService.build(org, event).orElseThrow();
        String delta = ManifestPayload.delta(manifests.find(org, event, version).orElseThrow().payload(),
                manifests.find(org, event, version - 1).orElseThrow().payload(), version - 1);
        return delta.lines().filter(l -> l.startsWith("T\t")).toList();
    }

    private static String row(UUID ticket, int zone, String status, String holder) {
        return "T\t" + ticket + "\t" + zone + "\t" + status + "\t" + HolderNames.initials(holder);
    }

    private Map<String, Object> walletRow(Fan f, UUID ticket) throws Exception {
        String json = body(fan(f.token(), get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
        return JsonPath.<List<Map<String, Object>>>read(json, "$.tickets[?(@.id == '" + ticket + "')]").getFirst();
    }

    private Map<String, Object> lookup(Caller a, int event, UUID ticket) throws Exception {
        String no = ticket.toString().substring(28);
        String json = body(call(a, post("/org/events/" + event + "/tickets/search"), "{\"q\": \"" + no + "\"}")
                .andExpect(status().isOk()));
        return JsonPath.<List<Map<String, Object>>>read(json, "$[?(@.id == '" + ticket + "')]").getFirst();
    }

    private static void notValid(ResultActions r) throws Exception {
        r.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_NOT_VALID"));
    }

    @Test
    void anOverdueInstallmentSuspendsThePassAndItsTicketsReadBlockedUntilItIsPaid() throws Exception {
        Caller a = admin();
        UUID org = a.org();
        String stade = venue(a, "Stade");
        int est = zone(a, stade, "EST");
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        String product = JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement Est", "price_mad": %d, "quota": 10, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true, "max_installments": 3}"""
                .formatted(PRICE, series, est)).andExpect(status().isCreated())), "$.id");
        Fan nadia = passHolder(product, "Nadia Berrada");
        Fan karim = passHolder(product, "Karim El Idrissi");
        Instant suspendAt = due(nadia, 2).plus(GRACE);

        // m1, m2 far ahead; m3 starts 30 min after the suspension, so its refund_cutoff_at (T0 − 60 min) is before it.
        int m1 = match(a, stade, series, Instant.parse("2099-02-01T19:00:00Z"), est);
        int m2 = match(a, stade, series, Instant.parse("2099-02-15T19:00:00Z"), est);
        int m3 = match(a, stade, series, suspendAt.truncatedTo(ChronoUnit.SECONDS).plus(Duration.ofMinutes(30)), est);
        job.runAt(due(karim, 2).plus(Duration.ofDays(1)));
        UUID n1 = ticket(nadia, m1);
        UUID n2 = ticket(nadia, m2);
        UUID n3 = ticket(nadia, m3);
        UUID k2 = ticket(karim, m2);

        // Before: Karim transfers his m1 ticket (the recipient's stays VALID), staff block Nadia's m2.
        String to = randomPhone();
        fan(karim.token(), post("/fan/tickets/" + ticket(karim, m1) + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"Hamza Chraibi\"}".formatted(to)).andExpect(status().isCreated());
        String hamza = fanToken(to);
        UUID h1 = UUID.fromString(JsonPath.read(body(fan(hamza, get("/fan/tickets")).andExpect(status().isOk())),
                "$.tickets[0].id"));
        call(a, post("/org/tickets/" + n2 + "/block"), "{\"reason\": \"Interdit de stade\"}").andExpect(status().isOk());
        assertThat(manifestService.build(org, m1)).contains(1);
        assertThat(manifestService.build(org, m2)).contains(1);

        // 1 s before the grace ends: still ACTIVE. At due + 7 d: SUSPENDED, once over two runs.
        job.runAt(suspendAt.minusSeconds(1));
        assertThat(passStatus(nadia)).isEqualTo("ACTIVE");
        assertThat(audits("pass.suspend", nadia)).isEmpty();
        job.runAt(suspendAt);
        job.runAt(due(karim, 2).plus(GRACE));
        job.runAt(due(karim, 2).plus(GRACE));
        assertThat(passStatus(nadia)).isEqualTo("SUSPENDED");
        assertThat(passStatus(karim)).isEqualTo("SUSPENDED");
        assertThat(audits("pass.suspend", nadia)).containsExactly("{\"seq\": 2}");
        assertThat(audits("pass.suspend", karim)).containsExactly("{\"seq\": 2}");
        assertThat(jdbc.sql("select suspended_at from passes where id = ?::uuid").param(nadia.pass())
                .query(OffsetDateTime.class).single().toInstant()).isEqualTo(suspendAt.truncatedTo(ChronoUnit.MICROS));

        // Effective status: BLOCKED in the manifest, the stored status untouched.
        assertThat(inManifest(org, m1, n1)).isEqualTo(Ticket.Status.BLOCKED);
        assertThat(stored(n1)).isEqualTo("VALID");
        assertThat(inManifest(org, m3, n3)).isEqualTo(Ticket.Status.VALID); // m3 froze before the suspension
        assertThat(inManifest(org, m1, h1)).isEqualTo(Ticket.Status.VALID); // transferred: not the pass holder's
        assertThat(inManifest(org, m2, n2)).isEqualTo(Ticket.Status.BLOCKED);
        assertThat(deltaRows(org, m1)).containsExactly(row(n1, est, "BLOCKED", "Nadia Berrada"));
        assertThat(deltaRows(org, m2)).containsExactly(row(k2, est, "BLOCKED", "Karim El Idrissi"));

        // The wallet: BLOCKED by the pass (no k_t), by staff, VALID with its k_t (frozen event); the recipient's too.
        Map<String, Object> w1 = walletRow(nadia, n1);
        assertThat(w1).containsEntry("status", "BLOCKED").containsEntry("blocked_by", "PASS")
                .containsEntry("pass_id", nadia.pass()).doesNotContainKey("k_t");
        assertThat(walletRow(nadia, n2)).containsEntry("status", "BLOCKED").containsEntry("blocked_by", "STAFF");
        assertThat(walletRow(nadia, n3)).containsEntry("status", "VALID").containsEntry("blocked_by", null)
                .containsKey("k_t");
        assertThat(walletRow(new Fan(hamza, null, null), h1)).containsEntry("status", "VALID")
                .containsEntry("pass_id", null).containsKey("k_t");

        // PDF, re-send, transfer and re-issue need the effective status VALID.
        notValid(fan(nadia.token(), get("/fan/tickets/" + n1 + "/pdf")));
        notValid(fan(nadia.token(), post("/fan/tickets/" + n1 + "/whatsapp")));
        notValid(fan(nadia.token(), post("/fan/tickets/" + n1 + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"Salma Tazi\"}".formatted(randomPhone())));
        notValid(fan(nadia.token(), post("/fan/tickets/" + n1 + "/reissue")));
        fan(nadia.token(), get("/fan/tickets/" + n3 + "/pdf")).andExpect(status().isOk());
        assertThat(stored(n1)).isEqualTo("VALID");

        // The staff lookup: effective status, the pass, staff_blocked; unblocking a pass-only block → PASS_SUSPENDED.
        assertThat(lookup(a, m1, n1)).containsEntry("status", "BLOCKED").containsEntry("staff_blocked", false)
                .containsEntry("pass", Map.of("status", "SUSPENDED"));
        assertThat(lookup(a, m2, n2)).containsEntry("status", "BLOCKED").containsEntry("staff_blocked", true);
        assertThat(lookup(a, m1, h1)).containsEntry("status", "VALID").containsEntry("pass", null);
        call(a, post("/org/tickets/" + n1 + "/unblock"), "{\"reason\": \"Paiement\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS"))
                .andExpect(jsonPath("$.details.reason").value("PASS_SUSPENDED"))
                .andExpect(jsonPath("$.details.status").value("BLOCKED"));

        // No new ticket while suspended.
        int m4 = match(a, stade, series, Instant.parse("2099-03-01T19:00:00Z"), est);
        Instant later = due(karim, 2).plus(GRACE).plusSeconds(60);
        job.runAt(later);
        assertThat(ticketOrders(nadia, m4)).isZero();
        assertThat(ticketOrders(karim, m4)).isZero();

        // Nadia pays seq 2 → ACTIVE; the next delta carries her ticket VALID; m2 is unchanged (staff block stays).
        String order = JsonPath.read(body(fan(nadia.token(), post("/fan/passes/" + nadia.pass() + "/installments"))
                .andExpect(status().isCreated())), "$.id");
        callback(approve(payment(nadia.token(), order), PART)).andExpect(status().isSeeOther());
        assertThat(passStatus(nadia)).isEqualTo("ACTIVE");
        assertThat(audits("pass.reactivate", nadia)).containsExactly("{\"seq\": 2}");
        assertThat(deltaRows(org, m1)).containsExactly(row(n1, est, "VALID", "Nadia Berrada"));
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(m2)
                .update();
        assertThat(manifestService.build(org, m2)).isEmpty();
        assertThat(walletRow(nadia, n1)).containsEntry("status", "VALID").containsEntry("blocked_by", null)
                .containsKey("k_t");
        assertThat(walletRow(nadia, n2)).containsEntry("blocked_by", "STAFF");

        // The missing ticket is issued on the next run (the same clock: seq 3 isn't overdue); Karim's still isn't.
        job.runAt(later);
        assertThat(ticketOrders(nadia, m4)).isOne();
        assertThat(ticketOrders(karim, m4)).isZero();
        assertThat(audits("pass.suspend", nadia)).hasSize(1);

        // A refund of a ticket blocked by its pass still works, on the stored status.
        call(a, post("/org/tickets/" + k2 + "/refund"), "{\"reason\": \"Abonnement\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(stored(k2)).isEqualTo("REFUNDED");
    }
}
