package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.common.HolderNames;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.gate.domain.ManifestPayload;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BE-62: docs/04-api.md §Invitations and §Organizer POST /org/events/{id}/invitations, GET
 * /org/events/{id}/invitations, GET /org/invitations/{id}/tickets; EVENT_NOT_ON_SALE CLOSED + transfer_until; the
 * Lot 5 step 4 role line.
 */
class InvitationApiTest extends PaymentTest {

    private static final String[] SALE_KEYS = {"id", "status", "channel", "total_mad", "items", "ticket_ids"};

    @Autowired
    EventService events;

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    /** An ON_SALE event: "Presse" (INVITE, quota 40) in PRESSE and "Plein tarif" (ALL, 50 DH) in NORD. */
    private record Setup(Caller admin, int event, int presse, String invite, String sellable) {}

    private Setup setup(boolean publish) throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int presse = zone(a, venue, "PRESSE");
        int nord = zone(a, venue, "NORD");
        int event = event(a, venue);
        String invite = JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Presse", "price_mad": 0, "quota": 40, "audience": "INVITE"}"""
                .formatted(presse)).andExpect(status().isCreated())), "$.id");
        String sellable = price(a, event, nord, "Plein tarif", 10);
        if (publish) {
            call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        }
        return new Setup(a, event, presse, invite, sellable);
    }

    private ResultActions invite(Caller c, int event, UUID id, String category, String holders) throws Exception {
        int qty = JsonPath.<List<Object>>read(holders, "$").size();
        return call(c, post("/org/events/" + event + "/invitations"), """
                {"invitation_id": "%s", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}"""
                .formatted(id, category, qty, holders));
    }

    private static String names(int n) {
        return "[" + String.join(", ", Collections.nCopies(n, "{\"name\": \"Hamza Chraibi\"}")) + "]";
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static void refused(ResultActions r, String field) throws Exception {
        r.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['" + field + "']").isString());
    }

    @Test
    void issuesNamedFreeTicketsListsPrintsAndRevokes() throws Exception {
        Setup s = setup(true);
        Caller ops = caller(s.admin().org(), "OPS");
        String nadia = randomPhone();
        String salma = randomPhone();
        UUID id = UUID.randomUUID();
        String json = body(invite(ops, s.event(), id, s.invite(), """
                [{"name": "Nadia Berrada", "phone": "%s"}, {"name": "Salma Tazi", "phone": "%s"},
                 {"name": "Karim El Idrissi"}]""".formatted(nadia, salma)).andExpect(status().isCreated()));
        assertKeys(json, "$", SALE_KEYS);
        assertKeys(json, "$.items[0]", "price_category_id", "zone_id", "zone_name", "name", "qty", "unit_price_mad",
                "holders");
        assertThat(JsonPath.<String>read(json, "$.id")).isEqualTo(id.toString());
        assertThat(JsonPath.<String>read(json, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<String>read(json, "$.channel")).isEqualTo("POS");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$.items[0].unit_price_mad")).isZero();
        assertThat(JsonPath.<List<String>>read(json, "$.items[0].holders[*].label"))
                .containsExactly("Nadia B.", "Salma T.", "Karim I.");
        assertThat(JsonPath.<List<String>>read(json, "$.ticket_ids")).hasSize(3);
        assertThat(json).doesNotContain(nadia, salma, "Berrada");

        Map<String, Object> order = jdbc.sql("""
                        select o.status::text as status, o.channel::text as channel, o.payment_method::text as method,
                               o.total_mad, o.user_id, o.paid_at is not null as paid, s.role::text as role
                        from orders o join staff s on s.id = o.invited_by where o.id = ?""").param(id).query()
                .singleRow();
        assertThat(order).containsEntry("status", "PAID").containsEntry("channel", "POS")
                .containsEntry("method", "FREE").containsEntry("total_mad", 0L).containsEntry("user_id", null)
                .containsEntry("paid", true).containsEntry("role", "OPS");
        assertThat(count("select count(*) from tickets where order_id = ? and status = 'VALID'", id)).isEqualTo(3);
        assertThat(count("select count(*) from tickets where order_id = ? and holder_user_id is null", id)).isOne();
        assertThat(count("""
                select count(*) from ticket_deliveries d join tickets t on t.id = d.ticket_id
                where t.order_id = ? and t.holder_user_id is not null""", id)).isEqualTo(2);
        assertThat(count("select count(*) from ticket_deliveries d join tickets t on t.id = d.ticket_id "
                + "where t.order_id = ?", id)).isEqualTo(2);
        String audit = jdbc.sql("select payload::text from audit_log where action = 'invitation.create' and target = ?")
                .param(id.toString()).query(String.class).single();
        assertKeys(audit, "$", "order_id", "places", "items");
        assertKeys(audit, "$.items[0]", "price_category_id", "qty");
        assertThat(JsonPath.<Integer>read(audit, "$.places")).isEqualTo(3);
        assertThat(audit).doesNotContain("Nadia", "Berrada", nadia);

        // Idempotency: the same id → 200, the same order, nothing new.
        invite(ops, s.event(), id, s.invite(), names(1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.ticket_ids.length()").value(3));
        assertThat(count("select count(*) from tickets where order_id = ?", id)).isEqualTo(3);
        assertThat(count("select sold from price_categories where id = ?::uuid", s.invite())).isEqualTo(3);

        // The journalist's app: the ticket with its k_t.
        String wallet = body(fan(fanToken(nadia), get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(wallet, "$.tickets[*].k_t")).hasSize(1);

        // The list: newest first, holders and delivery, no phone.
        UUID second = UUID.randomUUID();
        invite(s.admin(), s.event(), second, s.invite(), names(1)).andExpect(status().isCreated());
        json = body(call(caller(s.admin().org(), "FINANCE"), get("/org/events/" + s.event() + "/invitations"))
                .andExpect(status().isOk()));
        assertKeys(json, "$", "invitations");
        assertKeys(json, "$.invitations[0]", "id", "created_at", "invited_by", "places", "items", "tickets");
        assertKeys(json, "$.invitations[0].invited_by", "id", "name");
        assertKeys(json, "$.invitations[0].items[0]", "price_category_id", "name", "zone", "qty");
        assertKeys(json, "$.invitations[0].items[0].zone", "id", "code", "name");
        assertKeys(json, "$.invitations[0].tickets[0]", "id", "ticket_no", "status", "holder_name", "holder_label",
                "delivery");
        assertThat(JsonPath.<List<String>>read(json, "$.invitations[*].id"))
                .containsExactly(second.toString(), id.toString());
        assertThat(JsonPath.<Integer>read(json, "$.invitations[1].places")).isEqualTo(3);
        assertThat(JsonPath.<String>read(json, "$.invitations[1].items[0].name")).isEqualTo("Presse");
        assertThat(JsonPath.<String>read(json, "$.invitations[1].items[0].zone.code")).isEqualTo("PRESSE");
        List<String> ticketNos = JsonPath.read(json, "$.invitations[1].tickets[*].ticket_no");
        assertThat(ticketNos).isSorted().hasSize(3);
        assertThat(JsonPath.<List<String>>read(json, "$.invitations[1].tickets[?(@.delivery == 'PAPER')].holder_name"))
                .containsExactly("Karim El Idrissi");
        assertThat(JsonPath.<List<String>>read(json, "$.invitations[1].tickets[?(@.delivery == 'WHATSAPP')].holder_label"))
                .containsExactlyInAnyOrder("Nadia B.", "Salma T.");
        assertThat(json).doesNotContain(nadia, salma, "+212", "phone");

        // The manifest carries them like any ticket (VALID, initials).
        UUID karim = jdbc.sql("select id from tickets where order_id = ? and holder_user_id is null").param(id)
                .query(UUID.class).single();
        int v1 = manifestService.build(s.admin().org(), s.event()).orElseThrow();
        assertThat(manifests.find(s.admin().org(), s.event(), v1).orElseThrow().payload())
                .contains("T\t" + karim + "\t" + s.presse() + "\tVALID\t" + HolderNames.initials("Karim El Idrissi"));

        // Print: the static v1 QR, verified with K_e; no-store; audited.
        json = body(call(ops, get("/org/invitations/" + id + "/tickets")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertKeys(json, "$", "order_id", "event", "tickets");
        assertKeys(json, "$.tickets[0]", "id", "ticket_no", "status", "zone", "gate_codes", "holder_label",
                "unit_price_mad", "category", "qr");
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].category.name")).containsOnly("Presse");
        assertThat(json).doesNotContain("k_t", "holder_name", nadia);
        String qr = JsonPath.<List<String>>read(json, "$.tickets[?(@.id == '%s')].qr".formatted(karim)).getFirst();
        TicketToken.Parsed parsed = TicketToken.decode(qr);
        assertThat(parsed.version()).isEqualTo(TicketToken.VERSION_STATIC);
        assertThat(parsed.ticketId()).isEqualTo(karim);
        assertThat(parsed.zoneId()).isEqualTo(s.presse());
        Optional<TicketToken.Result> verdict = events.withEventKey(s.admin().org(), s.event(),
                k -> TicketToken.verify(parsed, s.event(), k, TicketToken.slotAt(Instant.now())));
        assertThat(verdict).isEmpty();
        assertThat(JsonPath.<List<Integer>>read(json, "$.tickets[*].unit_price_mad")).containsOnly(0);
        assertThat(jdbc.sql("select payload::text from audit_log where action = 'invitation.print' and target = ?")
                .param(id.toString()).query(String.class).single()).contains("\"tickets\": 3");
        // Not a till's order: the till print answers 404.
        call(s.admin(), get("/org/pos/orders/" + id + "/tickets")).andExpect(status().isNotFound());

        // /sales: paid and methods.FREE count them, revenue 0.
        json = body(call(s.admin(), get("/org/events/" + s.event() + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.paid")).isEqualTo(4);
        assertThat(JsonPath.<Integer>read(json, "$.revenue_mad")).isZero();
        assertThat(JsonPath.<List<Integer>>read(json, "$.methods[?(@.method == 'FREE')].paid")).containsExactly(4);
        // Lot 5 step 5: not a till sale, so not in channels; free.invitations instead
        assertThat(JsonPath.<List<Integer>>read(json, "$.channels[*].paid")).containsOnly(0);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.free")).isEqualTo(Map.of("passes", 0, "invitations", 4));

        // Revoke: REFUNDED, the place back, no refunds row, its QR null, the next delta REFUNDED.
        call(s.admin(), post("/org/tickets/" + karim + "/refund"), "{\"reason\": \"Erreur\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED"));
        assertThat(count("select sold from price_categories where id = ?::uuid", s.invite())).isEqualTo(3);
        assertThat(count("select count(*) from refunds where ticket_id = ?", karim)).isZero();
        json = body(call(ops, get("/org/invitations/" + id + "/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(json, "$.tickets[?(@.id == '%s')].qr".formatted(karim)))
                .containsExactly((Object) null);
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?")
                .param(s.event()).update();
        int v2 = manifestService.build(s.admin().org(), s.event()).orElseThrow();
        String delta = ManifestPayload.delta(manifests.find(s.admin().org(), s.event(), v2).orElseThrow().payload(),
                manifests.find(s.admin().org(), s.event(), v1).orElseThrow().payload(), v1);
        assertThat(delta).contains("T\t" + karim + "\t" + s.presse() + "\tREFUNDED\t");
    }

    /** Two sends of one invitation_id at once: the second waits on the id lock and replays (no second batch). */
    @Test
    void aConcurrentRetryReplaysTheFirstInvitation() throws Exception {
        Setup s = setup(true);
        UUID id = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> codes = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return invite(s.admin(), s.event(), id, s.invite(), names(3)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> f : runs) {
                codes.add(f.get(30, TimeUnit.SECONDS));
            }
        }
        assertThat(codes).containsExactlyInAnyOrder(201, 200);
        assertThat(count("select sold from price_categories where id = ?::uuid", s.invite())).isEqualTo(3);
        assertThat(count("select count(*) from tickets where order_id = ?", id)).isEqualTo(3);
    }

    @Test
    void refusalsWriteNothing() throws Exception {
        Setup s = setup(true);
        Caller a = s.admin();
        // An ALL category → 422 on its price_category_id.
        refused(invite(a, s.event(), UUID.randomUUID(), s.sellable(), names(1)), "items[0].price_category_id");
        // 501 places → 422 on items.
        refused(invite(a, s.event(), UUID.randomUUID(), s.invite(), names(501)), "items");
        // pays → 422; a bad name → 422 on its path.
        refused(invite(a, s.event(), UUID.randomUUID(), s.invite(), """
                [{"name": "Yazid Aqel", "phone": "%s", "pays": true}]""".formatted(randomPhone())),
                "items[0].holders[0].pays");
        refused(invite(a, s.event(), UUID.randomUUID(), s.invite(), "[{\"name\": \"Y\"}]"), "items[0].holders[0].name");
        // Over quota → 409 SOLD_OUT, nothing written.
        UUID over = UUID.randomUUID();
        invite(a, s.event(), over, s.invite(), names(41)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"));
        assertThat(count("select count(*) from orders where id = ?", over)).isZero();
        assertThat(count("select sold from price_categories where id = ?::uuid", s.invite())).isZero();
        assertThat(count("select count(*) from orders where event_id = ?", s.event())).isZero();

        // A till sale's id → 422 TAKEN.
        call(a, post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        UUID sale = UUID.randomUUID();
        call(a, post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d,
                 "items": [{"price_category_id": "%s", "qty": 1, "holders": [{"name": "Yazid Aqel"}]}]}"""
                .formatted(sale, s.event(), s.sellable())).andExpect(status().isCreated());
        invite(a, s.event(), sale, s.invite(), names(1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.reason").value("TAKEN"))
                .andExpect(jsonPath("$.details.fields.invitation_id").isString());
        // The till print never serves an invitation, and the invitation print never serves a till sale.
        call(a, get("/org/invitations/" + sale + "/tickets")).andExpect(status().isNotFound());

        // At transfer_until → 409 CLOSED with transfer_until.
        jdbc.sql("update events set refund_cutoff_at = now() + interval '30 minutes' where id = ?").param(s.event())
                .update();
        invite(a, s.event(), UUID.randomUUID(), s.invite(), names(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"))
                .andExpect(jsonPath("$.details.reason").value("CLOSED"))
                .andExpect(jsonPath("$.details.transfer_until").isString());

        // DRAFT → 409 NOT_ON_SALE.
        Setup d = setup(false);
        invite(d.admin(), d.event(), UUID.randomUUID(), d.invite(), names(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"))
                .andExpect(jsonPath("$.details.reason").value("NOT_ON_SALE"));
    }

    /** No sale window, no limit at issue; the tickets then count toward the holder's limit. */
    @Test
    void beforeTheSaleAndPastTheLimitThenTheHolderLimitApplies() throws Exception {
        Setup s = setup(true);
        jdbc.sql("update events set sale_starts_at = now() + interval '1 day' where id = ?").param(s.event()).update();
        String phone = randomPhone();
        String five = "[" + String.join(", ", Collections.nCopies(5,
                "{\"name\": \"Nadia Berrada\", \"phone\": \"%s\"}".formatted(phone))) + "]";
        invite(s.admin(), s.event(), UUID.randomUUID(), s.invite(), five).andExpect(status().isCreated());
        jdbc.sql("update events set sale_starts_at = null where id = ?").param(s.event()).update();
        fan(fanToken(phone), post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                .formatted(s.event(), s.sellable(), holders(1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"))
                .andExpect(jsonPath("$.details.reason").value("HOLDER"));
    }

    @Test
    void tenancyAndRoles() throws Exception {
        Setup s = setup(true);
        UUID id = UUID.randomUUID();
        invite(s.admin(), s.event(), id, s.invite(), names(1)).andExpect(status().isCreated());

        Caller other = admin();
        invite(other, s.event(), UUID.randomUUID(), s.invite(), names(1)).andExpect(status().isNotFound());
        call(other, get("/org/events/" + s.event() + "/invitations")).andExpect(status().isNotFound());
        call(other, get("/org/invitations/" + id + "/tickets")).andExpect(status().isNotFound());

        UUID org = s.admin().org();
        Caller finance = caller(org, "FINANCE");
        call(finance, get("/org/events/" + s.event() + "/invitations")).andExpect(status().isOk());
        invite(finance, s.event(), UUID.randomUUID(), s.invite(), names(1)).andExpect(status().isForbidden());
        call(finance, get("/org/invitations/" + id + "/tickets")).andExpect(status().isForbidden());
        Caller cashier = caller(org, "CASHIER");
        invite(cashier, s.event(), UUID.randomUUID(), s.invite(), names(1)).andExpect(status().isForbidden());
        call(cashier, get("/org/events/" + s.event() + "/invitations")).andExpect(status().isForbidden());
        call(cashier, get("/org/invitations/" + id + "/tickets")).andExpect(status().isForbidden());
        assertThat(count("select count(*) from orders where event_id = ?", s.event())).isOne();
    }
}
