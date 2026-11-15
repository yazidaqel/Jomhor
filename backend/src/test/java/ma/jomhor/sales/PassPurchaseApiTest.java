package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract (BE-53): docs/04-api.md POST /fan/product-orders for a PASS (PASS_HELD, SOLD_OUT product_id), §Passes
 * Lifecycle (PENDING, ACTIVE at PAID, CANCELLED with sold − 1 at expiry, the late success's re-take or refusal) and
 * GET /fan/passes.
 */
class PassPurchaseApiTest extends PaymentTest {

    private static final long PRICE = 150_000;
    private static final String CLOSE = "2099-06-30T22:00:00Z";

    @Autowired
    OrderService orderService;

    /**
     * {@code events}: the series' published upcoming matches at the zone's venue, by starts_at; {@code started}: a
     * published one there that has already started.
     */
    private record Club(Caller admin, String series, int zone, String pass, List<Integer> events, String price,
                        int started) {}

    private record Fan(String phone, String token, UUID id) {}

    private String seriesOf(Caller a, String name) throws Exception {
        return JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"%s\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}".formatted(name))
                .andExpect(status().isCreated())), "$.id");
    }

    private int match(Caller a, String venue, String series, String startsAt) throws Exception {
        return JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match %s", "starts_at": "%s",
                 "series_id": "%s"}""".formatted(venue, startsAt, startsAt, series))
                .andExpect(status().isCreated())), "$.id");
    }

    private void publish(Caller a, int event) throws Exception {
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
    }

    private String passProduct(Caller a, String series, int zone, String name, int quota) throws Exception {
        return JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "%s", "price_mad": %d, "quota": %d, "series_id": "%s", "zone_id": %d,
                 "sales_close_at": "%s", "on_sale": true}""".formatted(name, PRICE, quota, series, zone, CLOSE))
                .andExpect(status().isCreated())), "$.id");
    }

    /**
     * An organizer with a cash point and a series with, at the zone's venue: two published matches, a draft and a
     * closed one; and one published match at another venue. A PASS product of {@code quota} in the zone.
     */
    private Club club(int quota) throws Exception {
        Caller a = admin();
        jdbc.sql("update organizers set settings = settings || '{\"cash_points\": [{\"name\": \"Guichet\"}]}'::jsonb "
                + "where id = ?").param(a.org()).update();
        String stade = venue(a, "Stade");
        int est = zone(a, stade, "EST");
        String series = seriesOf(a, "Saison 2026-27");
        int second = match(a, stade, series, "2099-03-01T19:00:00Z");
        int first = match(a, stade, series, "2099-02-01T19:00:00Z");
        String price = price(a, first, est, "Plein tarif", 10);
        price(a, second, est, "Plein tarif", 10);
        publish(a, first);
        publish(a, second);
        match(a, stade, series, "2099-04-01T19:00:00Z"); // DRAFT
        int closed = match(a, stade, series, "2099-01-15T19:00:00Z");
        price(a, closed, est, "Plein tarif", 10);
        publish(a, closed);
        setStatus(closed, "CLOSED");
        String salle = venue(a, "Salle");
        int nord = zone(a, salle, "NORD");
        int elsewhere = match(a, salle, series, "2099-02-15T19:00:00Z");
        price(a, elsewhere, nord, "Plein tarif", 10);
        publish(a, elsewhere);
        int started = match(a, stade, series, "2099-01-10T19:00:00Z");
        price(a, started, est, "Plein tarif", 10);
        publish(a, started);
        jdbc.sql("update events set starts_at = now() - interval '1 hour' where id = ?").param(started).update();
        return new Club(a, series, est, passProduct(a, series, est, "Abonnement Tribune Est", quota),
                List.of(first, second), price, started);
    }

    private Fan newFan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(phone, token, jdbc.sql("select id from users where phone_e164 = ?").param(phone)
                .query(UUID.class).single());
    }

    private ResultActions buy(Fan f, String product) throws Exception {
        return fan(f.token(), post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "APP", "holder_name": "Yazid Aqel"}""".formatted(product));
    }

    private String bought(Fan f, String product) throws Exception {
        return JsonPath.read(body(buy(f, product).andExpect(status().isCreated())), "$.id");
    }

    private String passOf(String orderId) {
        return jdbc.sql("select pass_id::text from orders where id = ?::uuid").param(orderId).query(String.class)
                .single();
    }

    private String passStatus(String passId) {
        return jdbc.sql("select status::text from passes where id = ?::uuid").param(passId).query(String.class)
                .single();
    }

    private int productSold(String product) {
        return jdbc.sql("select sold from products where id = ?::uuid").param(product).query(Integer.class).single();
    }

    private void due(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId)
                .update();
    }

    private String refundReason(String paymentId) {
        return jdbc.sql("select payload ->> 'reason' from audit_log where action = 'payment.refund_required' "
                + "and target = ?").param(paymentId).query(String.class).single();
    }

    /** Each call at once (real threads, one start signal); their results in call order. */
    private static <T> List<T> race(List<Callable<T>> calls) throws Exception {
        var start = new CountDownLatch(1);
        List<T> results = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<T>> runs = new ArrayList<>();
            for (Callable<T> c : calls) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            for (var r : runs) {
                results.add(r.get());
            }
        }
        return results;
    }

    // ---------- POST /fan/product-orders ----------

    @Test
    void anOrderMakesAPendingPassAndASecondOneOfTheSeriesIsHeld() throws Exception {
        Club c = club(10);
        Fan f = newFan();
        String json = body(buy(f, c.pass()).andExpect(status().isCreated()));
        assertKeys(json, "$", "id", "status", "reserved_until", "total_mad", "product", "pass_id", "items", "group");
        String pass = JsonPath.read(json, "$.pass_id");
        assertThat(pass).isEqualTo(passOf(JsonPath.read(json, "$.id")));
        assertThat(JsonPath.<String>read(json, "$.product.kind")).isEqualTo("PASS");
        assertThat(JsonPath.<Integer>read(json, "$.total_mad")).isEqualTo((int) PRICE);
        assertThat(JsonPath.<Integer>read(json, "$.items[0].zone_id")).isEqualTo(c.zone());
        assertThat(JsonPath.<String>read(json, "$.items[0].zone_name")).isEqualTo("Zone EST");
        assertThat(JsonPath.<String>read(json, "$.items[0].holders[0].label")).isEqualTo("Yazid A.");
        assertThat(passStatus(pass)).isEqualTo("PENDING");
        assertThat(productSold(c.pass())).isEqualTo(1);
        assertThat(jdbc.sql("select holder_name || '|' || user_id from passes where id = ?::uuid").param(pass)
                .query(String.class).single()).isEqualTo("Yazid Aqel|" + f.id());

        // The same product, then another product of the same series: PASS_HELD, nothing taken.
        String other = passProduct(c.admin(), c.series(), c.zone(), "Abonnement Bis", 10);
        for (String product : List.of(c.pass(), other)) {
            buy(f, product).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASS_HELD"))
                    .andExpect(jsonPath("$.details.pass_id").value(pass))
                    .andExpect(jsonPath("$.details.status").value("PENDING"));
        }
        assertThat(List.of(productSold(c.pass()), productSold(other))).containsExactly(1, 0);

        // Another series: a second pass.
        String coupe = seriesOf(c.admin(), "Coupe");
        String cup = passProduct(c.admin(), coupe, c.zone(), "Abonnement Coupe", 10);
        bought(f, cup);
        assertThat(productSold(cup)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from passes where user_id = ?").param(f.id()).query(Integer.class)
                .single()).isEqualTo(2);
    }

    @Test
    void theQuotaIsCheckedAfterTheHeldPass() throws Exception {
        Club c = club(1);
        Fan f = newFan();
        bought(f, c.pass());
        buy(f, c.pass()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PASS_HELD"));
        buy(newFan(), c.pass()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.details.product_id").value(c.pass()));
        assertThat(productSold(c.pass())).isEqualTo(1);
    }

    @Test
    void twoParallelOrdersOfOneAccountMakeOnePass() throws Exception {
        Club c = club(10);
        String other = passProduct(c.admin(), c.series(), c.zone(), "Abonnement Bis", 10);
        Fan f = newFan();
        // Same product, then two products of one series (two product rows, so only the series lock orders them).
        for (List<String> products : List.of(List.of(c.pass(), c.pass()), List.of(c.pass(), other))) {
            jdbc.sql("update passes set status = 'CANCELLED' where user_id = ?").param(f.id()).update();
            jdbc.sql("update products set sold = 0 where id in (?::uuid, ?::uuid)").params(c.pass(), other).update();
            List<Callable<String>> calls = products.stream().<Callable<String>>map(p -> () -> {
                var response = buy(f, p).andReturn().getResponse();
                return response.getStatus() == 201 ? "201"
                        : response.getStatus() + " " + JsonPath.read(response.getContentAsString(), "$.code");
            }).toList();
            assertThat(race(calls)).containsExactlyInAnyOrder("201", "409 PASS_HELD");
            assertThat(productSold(c.pass()) + productSold(other)).isEqualTo(1);
        }
    }

    // ---------- pay, expiry ----------

    @Test
    void payingActivatesThePass() throws Exception {
        Club c = club(10);
        Fan f = newFan();
        String order = bought(f, c.pass());
        callback(approve(payment(f.token(), order), PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(passStatus(passOf(order))).isEqualTo("ACTIVE");
        assertThat(productSold(c.pass())).isEqualTo(1);
    }

    /** A held order whose pass isn't PENDING (a broken invariant) is never PAID: a refund, NOT_PAYABLE. */
    @Test
    void aPassNotPendingIsNotPaid() throws Exception {
        Club c = club(10);
        Fan f = newFan();
        String order = bought(f, c.pass());
        String payment = payment(f.token(), order);
        jdbc.sql("update passes set status = 'CANCELLED' where id = ?::uuid").param(passOf(order)).update();
        callback(approve(payment, PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isNotEqualTo("PAID");
        assertThat(passStatus(passOf(order))).isEqualTo("CANCELLED");
        assertThat(refundReason(payment)).isEqualTo("NOT_PAYABLE");
    }

    @Test
    void expiryCancelsThePassAndGivesItsPlaceBackOnce() throws Exception {
        Club c = club(10);
        Fan f = newFan();
        Fan g = newFan();
        String a = bought(f, c.pass());
        String b = bought(g, c.pass());
        fan(g.token(), post("/fan/orders/" + b + "/pay"), "{\"method\": \"CASH_AGENCY\"}").andExpect(status().isOk());
        Fan h = newFan();
        String kept = bought(h, c.pass());
        assertThat(productSold(c.pass())).isEqualTo(3);
        due(a);
        due(b);

        // Two expiry passes at once (two instances): each order is claimed by one of them.
        race(List.<Callable<Integer>>of(orderService::expireDue, orderService::expireDue));
        assertThat(List.of(orderStatus(a), orderStatus(b), orderStatus(kept))).containsExactly("EXPIRED", "EXPIRED",
                "RESERVED");
        assertThat(List.of(passStatus(passOf(a)), passStatus(passOf(b)), passStatus(passOf(kept))))
                .containsExactly("CANCELLED", "CANCELLED", "PENDING");
        assertThat(productSold(c.pass())).isEqualTo(1);
        orderService.expireDue();
        assertThat(productSold(c.pass())).isEqualTo(1);

        // The fan can buy again: a new pass.
        String again = bought(f, c.pass());
        assertThat(passOf(again)).isNotEqualTo(passOf(a));
        assertThat(productSold(c.pass())).isEqualTo(2);
    }

    // ---------- late success ----------

    @Test
    void aLateSuccessReTakesThePass() throws Exception {
        Club c = club(1);
        Fan f = newFan();
        String order = bought(f, c.pass());
        String payment = payment(f.token(), order);
        due(order);
        orderService.expireDue();
        assertThat(productSold(c.pass())).isZero();

        callback(approve(payment, PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("PAID");
        assertThat(passStatus(passOf(order))).isEqualTo("ACTIVE");
        assertThat(productSold(c.pass())).isEqualTo(1);
    }

    @Test
    void aLateSuccessWithTheQuotaGoneIsARefund() throws Exception {
        Club c = club(1);
        Fan f = newFan();
        String order = bought(f, c.pass());
        String payment = payment(f.token(), order);
        due(order);
        orderService.expireDue();
        bought(newFan(), c.pass()); // the last pass, taken by someone else

        callback(approve(payment, PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("EXPIRED");
        assertThat(passStatus(passOf(order))).isEqualTo("CANCELLED");
        assertThat(refundReason(payment)).isEqualTo("SOLD_OUT");
        assertThat(productSold(c.pass())).isEqualTo(1);
    }

    @Test
    void aLateSuccessWithAnotherLivePassIsARefund() throws Exception {
        Club c = club(10);
        Fan f = newFan();
        String order = bought(f, c.pass());
        String payment = payment(f.token(), order);
        due(order);
        orderService.expireDue();
        String again = bought(f, c.pass()); // the fan bought a new one meanwhile

        callback(approve(payment, PRICE)).andExpect(status().isSeeOther());
        assertThat(orderStatus(order)).isEqualTo("EXPIRED");
        assertThat(passStatus(passOf(order))).isEqualTo("CANCELLED");
        assertThat(passStatus(passOf(again))).isEqualTo("PENDING");
        assertThat(refundReason(payment)).isEqualTo("PER_ACCOUNT_LIMIT");
        assertThat(productSold(c.pass())).isEqualTo(1);
    }

    // ---------- GET /fan/passes ----------

    @Test
    void theFansPasses() throws Exception {
        Club c = club(10);
        Fan f = newFan();
        String expired = bought(f, c.pass());
        due(expired);
        orderService.expireDue(); // CANCELLED: not listed
        String paid = bought(f, c.pass());
        callback(approve(payment(f.token(), paid), PRICE)).andExpect(status().isSeeOther());
        String coupe = seriesOf(c.admin(), "Coupe");
        String pending = bought(f, passProduct(c.admin(), coupe, c.zone(), "Abonnement Coupe", 10));
        bought(newFan(), c.pass()); // someone else's

        // A pass ticket for the first match (as the pass job will issue it, plan lot-5 BE-55).
        UUID ticketOrder = jdbc.sql("""
                insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, payment_method,
                                    reserved_until, paid_at, pass_id)
                values (?, ?, ?, 'APP', 'PAID', 0, 'FREE', now(), now(), ?::uuid) returning id""")
                .params(c.admin().org(), c.events().getFirst(), f.id(), passOf(paid)).query(UUID.class).single();
        UUID ticket = jdbc.sql("""
                insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, status, holder_user_id)
                values (?, ?, ?, ?, ?::uuid, 'VALID', ?) returning id""")
                .params(c.admin().org(), ticketOrder, c.events().getFirst(), c.zone(), c.price(), f.id())
                .query(UUID.class).single();

        var result = fan(f.token(), get("/fan/passes")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
        String json = body(result);
        assertThat(json).doesNotContain(f.phone()).doesNotContain("Aqel");
        assertKeys(json, "$", "passes");
        assertThat(JsonPath.<List<Object>>read(json, "$.passes")).hasSize(2);
        assertKeys(json, "$.passes[0]", "id", "status", "product", "organizer", "series", "zone", "timezone",
                "holder_label", "created_at", "order_id", "events");
        // Newest first: the PENDING one, with its purchase order to resume.
        assertThat(JsonPath.<String>read(json, "$.passes[0].id")).isEqualTo(passOf(pending));
        assertThat(JsonPath.<String>read(json, "$.passes[0].status")).isEqualTo("PENDING");
        assertThat(JsonPath.<String>read(json, "$.passes[0].order_id")).isEqualTo(pending);
        assertThat(JsonPath.<String>read(json, "$.passes[0].series.name")).isEqualTo("Coupe");
        assertThat(JsonPath.<List<Object>>read(json, "$.passes[0].events")).isEmpty();

        assertThat(JsonPath.<String>read(json, "$.passes[1].id")).isEqualTo(passOf(paid));
        assertThat(JsonPath.<String>read(json, "$.passes[1].status")).isEqualTo("ACTIVE");
        assertThat(JsonPath.<Object>read(json, "$.passes[1].order_id")).isNull();
        assertKeys(json, "$.passes[1].product", "id", "name");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.passes[1].product"))
                .isEqualTo(Map.of("id", c.pass(), "name", "Abonnement Tribune Est"));
        assertKeys(json, "$.passes[1].organizer", "id", "slug", "name", "crest_url");
        assertThat(JsonPath.<String>read(json, "$.passes[1].organizer.id")).isEqualTo(c.admin().org().toString());
        assertKeys(json, "$.passes[1].series", "id", "name", "starts_on", "ends_on");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.passes[1].series")).isEqualTo(Map.of(
                "id", c.series(), "name", "Saison 2026-27", "starts_on", "2026-08-01", "ends_on", "2099-06-30"));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.passes[1].zone"))
                .isEqualTo(Map.of("id", c.zone(), "code", "EST", "name", "Zone EST"));
        assertThat(JsonPath.<String>read(json, "$.passes[1].timezone")).isEqualTo("Africa/Casablanca");
        assertThat(JsonPath.<String>read(json, "$.passes[1].holder_label")).isEqualTo("Yazid A.");
        // The series' published matches at the zone's venue, by starts_at, the started one included (no draft, closed
        // or other venue); GET /fan/products lists the upcoming ones only.
        assertThat(JsonPath.<List<Integer>>read(json, "$.passes[1].events[*].id"))
                .containsExactly(c.started(), c.events().get(0), c.events().get(1));
        assertThat(JsonPath.<List<Integer>>read(body(mvc.perform(get("/fan/products/" + c.pass()))
                .andExpect(status().isOk())), "$.pass.events[*].id")).isEqualTo(c.events());
        assertKeys(json, "$.passes[1].events[1]", "id", "title", "event_type", "starts_at", "ticket_id");
        assertThat(JsonPath.<String>read(json, "$.passes[1].events[1].event_type")).isEqualTo("FOOTBALL_MATCH");
        assertThat(JsonPath.<String>read(json, "$.passes[1].events[1].starts_at")).isEqualTo("2099-02-01T19:00:00Z");
        assertThat(JsonPath.<String>read(json, "$.passes[1].events[1].ticket_id")).isEqualTo(ticket.toString());
        assertThat(JsonPath.<Object>read(json, "$.passes[1].events[0].ticket_id")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.passes[1].events[2].ticket_id")).isNull();

        // Transferred away: no longer the holder's ticket.
        jdbc.sql("update tickets set status = 'CANCELLED' where id = ?").param(ticket).update();
        assertThat(JsonPath.<Object>read(body(fan(f.token(), get("/fan/passes"))), "$.passes[1].events[1].ticket_id"))
                .isNull();

        // No token: 401; another fan sees only theirs.
        mvc.perform(get("/fan/passes")).andExpect(status().isUnauthorized());
        assertThat(JsonPath.<List<Object>>read(body(fan(newFan().token(), get("/fan/passes"))), "$.passes")).isEmpty();
    }
}
