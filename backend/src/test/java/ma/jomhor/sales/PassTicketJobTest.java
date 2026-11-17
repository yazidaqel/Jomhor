package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.common.HolderNames;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.products.application.PassTicketService;
import ma.jomhor.tickets.application.TicketService;
import ma.jomhor.tickets.domain.Ticket;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * BE-55: the pass tickets job (docs/04-api.md §Passes Tickets) on a fixed clock, the pass ticket as any ticket
 * (wallet, manifest, transfer, refund, limits, /sales) and the product figures of GET /org/products/{id}.
 */
class PassTicketJobTest extends PaymentTest {

    private static final long PRICE = 150_000;
    /** Before every match below; at the "past" match's transfer_until (19:00 − 60 min cutoff − 30 min). */
    private static final Instant NOW = Instant.parse("2099-01-20T17:30:00Z");

    @Autowired
    PassTicketService job;

    @Autowired
    OrderService orderService;

    @Autowired
    TicketService ticketService;

    @Autowired
    EventService eventService;

    private record Fan(String token, UUID id, String name) {}

    /**
     * {@code match}: published at the zone's venue, a PASS category of 2 ("Abonnés") and a sellable one; {@code past}:
     * published there with PASS room, but NOW is its transfer_until; {@code draft} there and {@code elsewhere} (another
     * venue) both with PASS room. Passes: {@code active} (3, oldest first), one PENDING and one CANCELLED.
     */
    private record Club(Caller admin, String product, int zone, int match, String passPrice, String sellable,
                        int past, int draft, int elsewhere, List<Fan> active, Fan pending) {}

    private int match(Caller a, String venue, String series, String startsAt) throws Exception {
        return JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match %s", "starts_at": "%s",
                 "series_id": "%s"}""".formatted(venue, startsAt, startsAt, series))
                .andExpect(status().isCreated())), "$.id");
    }

    private String passPrice(Caller a, int event, int zone, int quota) throws Exception {
        return JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": %d, "audience": "PASS"}"""
                .formatted(zone, quota)).andExpect(status().isCreated())), "$.id");
    }

    private void publish(Caller a, int event) throws Exception {
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
    }

    private Fan newFan(String name) throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(token, jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class)
                .single(), name);
    }

    private String buy(Fan f, String product) throws Exception {
        return JsonPath.read(body(fan(f.token(), post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "APP", "holder_name": "%s"}""".formatted(product, f.name()))
                .andExpect(status().isCreated())), "$.id");
    }

    private Club club() throws Exception {
        Caller a = admin();
        String stade = venue(a, "Stade");
        int est = zone(a, stade, "EST");
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        int match = match(a, stade, series, "2099-02-01T19:00:00Z");
        String passPrice = passPrice(a, match, est, 2);
        String sellable = price(a, match, est, "Plein tarif", 10);
        publish(a, match);
        int past = match(a, stade, series, "2099-01-20T19:00:00Z");
        passPrice(a, past, est, 10);
        publish(a, past);
        int draft = match(a, stade, series, "2099-03-01T19:00:00Z");
        passPrice(a, draft, est, 10);
        String salle = venue(a, "Salle");
        int nord = zone(a, salle, "NORD");
        int elsewhere = match(a, salle, series, "2099-02-15T19:00:00Z");
        passPrice(a, elsewhere, nord, 10);
        publish(a, elsewhere);
        String product = JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement Est", "price_mad": %d, "quota": 10, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}"""
                .formatted(PRICE, series, est)).andExpect(status().isCreated())), "$.id");

        List<Fan> active = List.of(newFan("Nadia Berrada"), newFan("Karim El Idrissi"), newFan("Salma Tazi"));
        for (Fan f : active) {
            String order = buy(f, product);
            callback(approve(payment(f.token(), order), PRICE)).andExpect(status().isSeeOther());
        }
        Fan pending = newFan("Omar Alaoui");
        buy(pending, product);
        String expired = buy(newFan("Leila Bennani"), product);
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(expired)
                .update();
        orderService.expireDue();
        assertThat(jdbc.sql("select status::text from passes where product_id = ?::uuid order by created_at, id")
                .param(product).query(String.class).list())
                .containsExactly("ACTIVE", "ACTIVE", "ACTIVE", "PENDING", "CANCELLED");
        return new Club(a, product, est, match, passPrice, sellable, past, draft, elsewhere, active, pending);
    }

    /**
     * The pass ticket orders of the event with their tickets, by pass (oldest first; one batch's orders share their
     * created_at): order id, user, pass, status, channel, method, total, ticket.
     */
    private List<Map<String, Object>> passOrders(int event) {
        return jdbc.sql("""
                        select o.id, o.user_id, o.pass_id, o.status::text as status, o.channel::text as channel,
                               o.payment_method::text as method, o.total_mad, o.paid_at,
                               t.id as ticket_id, t.status::text as ticket_status, t.holder_user_id, t.holder_name,
                               t.holder_initials, t.price_category_id, i.qty, i.unit_price_mad
                        from orders o
                        join order_items i on i.order_id = o.id
                        join tickets t on t.order_id = o.id
                        join passes p on p.id = o.pass_id
                        where o.event_id = ? and o.pass_id is not null
                        order by p.created_at, t.issued_at, t.id""").param(event).query().listOfRows();
    }

    private int orderCount(int event) {
        return jdbc.sql("select count(*) from orders where event_id = ?").param(event).query(Integer.class).single();
    }

    private int organizerOrders(Club c) {
        return jdbc.sql("select count(*) from orders where organizer_id = ?").param(c.admin().org())
                .query(Integer.class).single();
    }

    private UUID passOf(Fan f) {
        return jdbc.sql("select id from passes where user_id = ?").param(f.id()).query(UUID.class).single();
    }

    @Test
    void oneTicketPerActivePassWhileThereIsRoomAndTheFiguresFollow() throws Exception {
        Club c = club();
        job.runAt(NOW);

        List<Map<String, Object>> rows = passOrders(c.match());
        assertThat(rows).hasSize(2);
        for (int i = 0; i < 2; i++) {
            Fan f = c.active().get(i);
            Map<String, Object> r = rows.get(i);
            assertThat(r).containsEntry("user_id", f.id()).containsEntry("pass_id", passOf(f))
                    .containsEntry("status", "PAID").containsEntry("channel", "APP").containsEntry("method", "FREE")
                    .containsEntry("total_mad", 0L).containsEntry("ticket_status", "VALID")
                    .containsEntry("holder_user_id", f.id()).containsEntry("holder_name", f.name())
                    .containsEntry("holder_initials", HolderNames.initials(f.name()))
                    .containsEntry("price_category_id", UUID.fromString(c.passPrice()))
                    .containsEntry("unit_price_mad", 0L);
            assertThat(r.get("paid_at")).isNotNull();
            assertThat(((Number) r.get("qty")).intValue()).isOne();
        }
        assertThat(sold(c.passPrice())).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from ticket_deliveries where ticket_id = any(?)")
                .param(rows.stream().map(r -> r.get("ticket_id")).toArray(UUID[]::new)).query(Integer.class).single())
                .isZero();
        // Not issued: NOW is the past match's transfer_until; a DRAFT; another venue's zone.
        for (int event : List.of(c.past(), c.draft(), c.elsewhere())) {
            assertThat(orderCount(event)).as("event %d", event).isZero();
        }

        // The figures: the third pass waits for room.
        String json = body(call(c.admin(), get("/org/products/" + c.product())).andExpect(status().isOk()));
        assertKeys(json, "$", "id", "kind", "name", "price_mad", "on_sale", "sales_close_at", "quota", "sold",
                "valid_until", "series", "zone", "max_installments", "figures", "refunds_required");
        assertKeys(json, "$.figures", "passes", "revenue_mad", "events", "installments");
        assertKeys(json, "$.figures.passes", "pending", "active", "suspended");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.figures.passes"))
                .containsEntry("pending", 1).containsEntry("active", 3).containsEntry("suspended", 0);
        assertThat(JsonPath.<Number>read(json, "$.figures.revenue_mad").longValue()).isEqualTo(3 * PRICE);
        assertThat(JsonPath.<List<Integer>>read(json, "$.figures.events[*].event_id"))
                .containsExactly(c.past(), c.match(), c.draft()); // by starts_at; not the other venue's
        assertKeys(json, "$.figures.events[1]", "event_id", "title", "starts_at", "status", "issued", "expected",
                "pass_remaining");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.figures.events[1]"))
                .containsEntry("status", "ON_SALE").containsEntry("issued", 2).containsEntry("expected", 3)
                .containsEntry("pass_remaining", 0).containsEntry("starts_at", "2099-02-01T19:00:00Z");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.figures.events[2]"))
                .containsEntry("status", "DRAFT").containsEntry("issued", 0).containsEntry("pass_remaining", 10);
        assertThat(JsonPath.<List<Object>>read(json, "$.refunds_required")).isEmpty();

        // A run with nothing to do writes nothing.
        int orders = organizerOrders(c);
        job.runAt(NOW);
        assertThat(organizerOrders(c)).isEqualTo(orders);
        assertThat(sold(c.passPrice())).isEqualTo(2);

        // Room for the third: issued on the next run, once.
        call(c.admin(), patch("/org/events/" + c.match() + "/prices/" + c.passPrice()), "{\"quota\": 3}")
                .andExpect(status().isOk());
        job.runAt(NOW);
        job.runAt(NOW);
        rows = passOrders(c.match());
        assertThat(rows).hasSize(3);
        assertThat(rows.get(2)).containsEntry("user_id", c.active().get(2).id());
        assertThat(sold(c.passPrice())).isEqualTo(3);
        assertThat(organizerOrders(c)).isEqualTo(orders + 1);
        call(c.admin(), get("/org/products/" + c.product()))
                .andExpect(jsonPath("$.figures.events[1].issued").value(3))
                .andExpect(jsonPath("$.figures.events[1].pass_remaining").value(0));

        // GET /fan/passes: the ticket id; PENDING pass: no ticket.
        Fan a = c.active().getFirst();
        String passes = body(fan(a.token(), get("/fan/passes")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(passes, "$.passes[0].events[?(@.id == " + c.match() + ")].ticket_id"))
                .containsExactly(rows.getFirst().get("ticket_id").toString());
        assertThat(passOrders(c.match()).stream().map(r -> r.get("user_id"))).doesNotContain(c.pending().id());
    }

    @Test
    void aPassTicketIsATicketLikeAnyOther() throws Exception {
        Club c = club();
        job.runAt(NOW);
        Fan a = c.active().getFirst();
        Map<String, Object> mine = passOrders(c.match()).getFirst();
        UUID ticket = (UUID) mine.get("ticket_id");
        UUID ticketOrder = (UUID) mine.get("id");

        // Wallet: pass_id and K_t.
        String json = body(fan(a.token(), get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[?(@.id == '" + ticket + "')].pass_id"))
                .containsExactly(passOf(a).toString());
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[?(@.id == '" + ticket + "')].k_t")).hasSize(1);

        // The manifest's rows: VALID, initials only.
        assertThat(ticketService.manifestRows(c.admin().org(), c.match()))
                .contains(new Ticket.ManifestRow(ticket, c.zone(), Ticket.Status.VALID, HolderNames.initials(a.name())));

        // Not in the fan's orders; the pass purchase order is.
        json = body(fan(a.token(), get("/fan/orders")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.orders[*].id")).doesNotContain(ticketOrder.toString())
                .hasSize(1);

        // /sales: paid, FREE with revenue 0.
        json = body(call(c.admin(), get("/org/events/" + c.match() + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.paid")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$.revenue_mad")).isZero();
        assertThat(JsonPath.<List<Integer>>read(json, "$.methods[?(@.method == 'FREE')].paid")).containsExactly(2);
        // Lot 5 step 5: not an app sale, so not in channels; free.passes instead
        assertThat(JsonPath.<List<Integer>>read(json, "$.channels[*].paid")).containsOnly(0);
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.free")).isEqualTo(Map.of("passes", 2, "invitations", 0));
        assertThat(JsonPath.<List<Integer>>read(json, "$.methods[?(@.method == 'FREE')].revenue_mad"))
                .containsExactly(0);

        // Limits count it: 3 singles + the pass ticket at the default limit 4, then 1 more → PER_ACCOUNT_LIMIT.
        Sale s = new Sale(c.admin(), c.zone(), c.match(), c.sellable());
        order(a.token(), s, 3);
        fan(a.token(), post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}
                """.formatted(c.match(), c.sellable(), holders(1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"));

        // Transfer: 201; the recipient's ticket reads no pass (04: pass_id only for the pass's holder).
        Fan b = c.active().get(1);
        UUID bTicket = (UUID) passOrders(c.match()).get(1).get("ticket_id");
        String to = randomPhone();
        fan(b.token(), post("/fan/tickets/" + bTicket + "/transfer"),
                "{\"to_phone\": \"%s\", \"name\": \"Hamza Chraibi\"}".formatted(to)).andExpect(status().isCreated());
        String recipient = fanToken(to);
        json = body(fan(recipient, get("/fan/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(json, "$.tickets[*]")).hasSize(1);
        assertThat(JsonPath.<Object>read(json, "$.tickets[0].pass_id")).isNull();

        // A staff refund: never re-issued, though its place is free again.
        call(c.admin(), post("/org/tickets/" + ticket + "/refund"), "{\"reason\": \"Doublon\"}")
                .andExpect(status().isOk());
        job.runAt(NOW);
        UUID passA = passOf(a);
        assertThat(jdbc.sql("select count(*) from orders where pass_id = ? and event_id = ?")
                .params(passA, c.match()).query(Integer.class).single()).isOne();
        assertThat(jdbc.sql("select count(*) from tickets t join orders o on o.id = t.order_id "
                + "where o.pass_id = ? and o.event_id = ?").params(passA, c.match()).query(Integer.class).single())
                .isOne();
        // The freed place went to the pass that was waiting.
        assertThat(passOrders(c.match()).stream().map(r -> r.get("user_id"))).contains(c.active().get(2).id());
    }

    @Test
    void anEventPatchChangingTheSeriesIsRefusedOnceTicketsAreIssued() throws Exception {
        Club c = club();
        // The job's for share read is only ever taken inside a transaction.
        assertThatThrownBy(() -> eventService.share(c.admin().org(), c.match()))
                .isInstanceOf(IllegalTransactionStateException.class);
        String other = JsonPath.read(body(call(c.admin(), post("/org/series"),
                "{\"name\": \"Coupe\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        job.runAt(NOW);
        call(c.admin(), patch("/org/events/" + c.match()), "{\"series_id\": \"%s\"}".formatted(other))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.details.reason").value("PASS_TICKETS"));
        // Moved before any ticket: the job follows the new series (none of its passes).
        call(c.admin(), patch("/org/events/" + c.draft()), "{\"series_id\": \"%s\"}".formatted(other))
                .andExpect(status().isOk());
        publish(c.admin(), c.draft());
        job.runAt(NOW);
        assertThat(orderCount(c.draft())).isZero();
    }
}
