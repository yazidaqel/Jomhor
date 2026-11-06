package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.catalog.application.Events;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.sales.application.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contract: POST /fan/orders, GET /fan/orders/{id} (docs/04-api.md §Fan), plus quota under concurrency, the
 * per-account limit, all-or-nothing items and the expiry job (plan Q8).
 */
class OrderApiTest extends OrgApiTest {

    private static final String[] RESERVED_KEYS = {"id", "status", "reserved_until", "total_mad", "items"};
    private static final String[] ORDER_KEYS =
            {"id", "event_id", "status", "total_mad", "reserved_until", "paid_at", "items", "ticket_ids"};
    private static final String[] ITEM_KEYS = {"price_category_id", "zone_id", "name", "qty", "unit_price_mad"};

    @Autowired
    OrderService orderService;

    @Autowired
    Inventory inventory;

    @Autowired
    Events events;

    @Autowired
    PlatformTransactionManager transactions;

    /** A published event with one category of {@code quota} at 50 DH on zone NORD. */
    record Sale(Caller org, String venueId, int zoneId, int eventId, String priceId) {}

    private Sale onSale(int quota) throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int zoneId = zone(a, venueId, "NORD");
        int eventId = event(a, venueId);
        String priceId = price(a, eventId, zoneId, "Plein tarif", quota);
        call(a, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());
        return new Sale(a, venueId, zoneId, eventId, priceId);
    }

    private ResultActions order(String token, int eventId, String items) throws Exception {
        return mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\": %d, \"channel\": \"APP\", \"items\": [%s]}".formatted(eventId, items)));
    }

    private static String item(String priceId, int qty) {
        return "{\"price_category_id\": \"%s\", \"qty\": %d}".formatted(priceId, qty);
    }

    private String reserve(String token, Sale s, int qty) throws Exception {
        return JsonPath.read(body(order(token, s.eventId(), item(s.priceId(), qty)).andExpect(status().isCreated())), "$.id");
    }

    private ResultActions fetch(String token, String orderId) throws Exception {
        return mvc.perform(get("/fan/orders/" + orderId).header("Authorization", "Bearer " + token));
    }

    private int sold(String priceId) {
        return jdbc.sql("select sold from price_categories where id = ?::uuid").param(priceId).query(Integer.class).single();
    }

    private String orderStatus(String orderId) {
        return jdbc.sql("select status::text from orders where id = ?::uuid").param(orderId).query(String.class).single();
    }

    private void due(String orderId) {
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(orderId).update();
    }

    private void pendingPayment(String orderId, String age) {
        jdbc.sql("""
                insert into payments (organizer_id, order_id, method, provider, amount_mad, created_at)
                select organizer_id, id, 'CARD', 'FAKE', total_mad, now() - cast(? as interval) from orders where id = ?::uuid
                """).params(age, orderId).update();
    }

    private static void error(ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }

    // ---------- contract ----------

    @Test
    void createThenGet() throws Exception {
        Sale s = onSale(10);
        String vip = JsonPath.read(body(call(s.org(), post("/org/events/" + s.eventId() + "/prices"),
                "{\"zone_id\": %d, \"name\": \"VIP\", \"price_mad\": 12550, \"quota\": 5}".formatted(s.zoneId()))
                .andExpect(status().isCreated())), "$.id");
        String fan = fanToken(randomPhone());

        String json = body(order(fan, s.eventId(), item(s.priceId(), 2) + "," + item(vip, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.total_mad").value(2 * 5000 + 12550))
                .andExpect(jsonPath("$.items[0].price_category_id").value(s.priceId()))
                .andExpect(jsonPath("$.items[0].zone_id").value(s.zoneId()))
                .andExpect(jsonPath("$.items[0].name").value("Plein tarif"))
                .andExpect(jsonPath("$.items[0].qty").value(2))
                .andExpect(jsonPath("$.items[0].unit_price_mad").value(5000))
                .andExpect(jsonPath("$.items[1].price_category_id").value(vip))
                .andExpect(jsonPath("$.items[1].unit_price_mad").value(12550)));
        assertKeys(json, "$", RESERVED_KEYS);
        assertKeys(json, "$.items[0]", ITEM_KEYS);
        Instant until = Instant.parse(JsonPath.read(json, "$.reserved_until"));
        assertThat(until).isCloseTo(Instant.now().plus(8, ChronoUnit.MINUTES), within(1, ChronoUnit.MINUTES));
        String id = JsonPath.read(json, "$.id");
        assertThat(sold(s.priceId())).isEqualTo(2);
        assertThat(sold(vip)).isEqualTo(1);
        assertThat(jdbc.sql("""
                select count(*) from orders o join order_items i on i.order_id = o.id
                where o.id = ?::uuid and o.organizer_id = ? and i.organizer_id = ? and o.channel = 'APP'
                """).params(id, s.org().org(), s.org().org()).query(Integer.class).single()).isEqualTo(2);

        // A later price change doesn't touch the snapshot.
        call(s.org(), patch("/org/events/" + s.eventId() + "/prices/" + vip), "{\"price_mad\": 99900}")
                .andExpect(status().isOk());
        json = body(fetch(fan, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.event_id").value(s.eventId()))
                .andExpect(jsonPath("$.status").value("RESERVED"))
                .andExpect(jsonPath("$.total_mad").value(2 * 5000 + 12550))
                .andExpect(jsonPath("$.paid_at").isEmpty())
                .andExpect(jsonPath("$.ticket_ids", empty()))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[?(@.price_category_id == '%s')].unit_price_mad".formatted(vip)).value(12550)));
        assertKeys(json, "$", ORDER_KEYS);
        assertKeys(json, "$.items[0]", ITEM_KEYS);
        assertThat(Instant.parse(JsonPath.read(json, "$.reserved_until"))).isEqualTo(until);
    }

    @Test
    void fanTokenRequired() throws Exception {
        Sale s = onSale(10);
        String body = "{\"event_id\": %d, \"channel\": \"WEB\", \"items\": [%s]}".formatted(s.eventId(), item(s.priceId(), 1));
        error(mvc.perform(post("/fan/orders").contentType(MediaType.APPLICATION_JSON).content(body)), 401, "UNAUTHORIZED");
        error(mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + s.org().token())
                .contentType(MediaType.APPLICATION_JSON).content(body)), 401, "UNAUTHORIZED");
        String fan = fanToken(randomPhone());
        String id = reserve(fan, s, 1);
        error(mvc.perform(get("/fan/orders/" + id).header("Authorization", "Bearer " + s.org().token())), 401, "UNAUTHORIZED");
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    @Test
    void anotherUsersOrderIs404() throws Exception {
        Sale s = onSale(10);
        String id = reserve(fanToken(randomPhone()), s, 1);
        String other = fanToken(randomPhone());
        error(fetch(other, id), 404, "NOT_FOUND");
        error(fetch(other, "00000000-0000-0000-0000-000000000000"), 404, "NOT_FOUND");
    }

    @Test
    void eventMustExistAndBeOnSale() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        int draft = event(s.org(), s.venueId());
        String draftPrice = price(s.org(), draft, s.zoneId(), "Plein tarif", 10);
        error(order(fan, draft, item(draftPrice, 1)), 409, "EVENT_NOT_ON_SALE");
        error(order(fan, 999_999_999, item(s.priceId(), 1)), 404, "NOT_FOUND");
        setStatus(s.eventId(), "CLOSED");
        error(order(fan, s.eventId(), item(s.priceId(), 1)), 409, "EVENT_NOT_ON_SALE");
        assertThat(sold(s.priceId())).isZero();
        assertThat(sold(draftPrice)).isZero();
    }

    @Test
    void badItemsAre422() throws Exception {
        Sale s = onSale(10);
        Sale other = onSale(10);
        String fan = fanToken(randomPhone());
        order(fan, s.eventId(), "").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.details.fields.items").isString());
        order(fan, s.eventId(), item(s.priceId(), 0)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        order(fan, s.eventId(), item(s.priceId(), 1) + "," + item(s.priceId(), 1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields['items[1].price_category_id']").isString());
        order(fan, s.eventId(), item(other.priceId(), 1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['items[0].price_category_id']").isString());
        mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + fan).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event_id\": %d, \"channel\": \"POS\", \"items\": [%s]}".formatted(s.eventId(), item(s.priceId(), 1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields.channel").isString());
        order(fan, s.eventId(), "{\"price_category_id\": \"%s\", \"qty\": 1.5}".formatted(s.priceId()))
                .andExpect(status().isUnprocessableContent());
        assertThat(sold(s.priceId())).isZero();
        assertThat(sold(other.priceId())).isZero();
    }

    // ---------- quota and limit ----------

    @Test
    void twentyBuyersOnQuotaFive() throws Exception {
        Sale s = onSale(5);
        List<String> fans = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fans.add(fanToken(randomPhone()));
        }
        List<String> results = parallel(fans, s, 1);
        assertThat(results).filteredOn("201 RESERVED"::equals).hasSize(5);
        assertThat(results).filteredOn("409 SOLD_OUT"::equals).hasSize(15);
        assertThat(sold(s.priceId())).isEqualTo(5);
        assertThat(jdbc.sql("select count(*) from orders where event_id = ?").param(s.eventId()).query(Integer.class).single())
                .isEqualTo(5);
    }

    @Test
    void oneBuyerInParallelStaysWithinTheLimit() throws Exception {
        Sale s = onSale(100); // per_account_limit defaults to 4
        String fan = fanToken(randomPhone());
        List<String> results = parallel(List.of(fan, fan, fan, fan, fan), s, 2);
        assertThat(results).filteredOn("201 RESERVED"::equals).hasSize(2);
        assertThat(results).filteredOn("409 PER_ACCOUNT_LIMIT"::equals).hasSize(3);
        assertThat(sold(s.priceId())).isEqualTo(4);
        order(fan, s.eventId(), item(s.priceId(), 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"))
                .andExpect(jsonPath("$.details.limit").value(4))
                .andExpect(jsonPath("$.details.held").value(4));
        // Another buyer is not affected.
        reserve(fanToken(randomPhone()), s, 4);
    }

    @Test
    void expiredOrdersDontCountTowardsTheLimit() throws Exception {
        Sale s = onSale(100);
        String fan = fanToken(randomPhone());
        String first = reserve(fan, s, 4);
        due(first);
        orderService.expireDue();
        reserve(fan, s, 4);
    }

    @Test
    void secondItemSoldOutReservesNothing() throws Exception {
        Sale s = onSale(10);
        String scarce = price(s.org(), s.eventId(), s.zoneId(), "Abonné", 1);
        setSold(scarce, 1);
        String fan = fanToken(randomPhone());
        order(fan, s.eventId(), item(s.priceId(), 2) + "," + item(scarce, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOLD_OUT"))
                .andExpect(jsonPath("$.details.price_category_id").value(scarce));
        assertThat(sold(s.priceId())).isZero();
        assertThat(sold(scarce)).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from orders where event_id = ?").param(s.eventId()).query(Integer.class).single())
                .isZero();
    }

    /** Every token orders {@code qty} at once (real threads, one start signal); "status code" per call. */
    private List<String> parallel(List<String> tokens, Sale s, int qty) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tokens.size())) {
            List<Future<String>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    start.await();
                    var response = order(token, s.eventId(), item(s.priceId(), qty)).andReturn().getResponse();
                    String json = response.getContentAsString();
                    return response.getStatus() + " " + JsonPath.read(json, response.getStatus() == 201 ? "$.status" : "$.code");
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }

    // ---------- expiry ----------

    @Test
    void expiryReleasesExactlyOnce() throws Exception {
        Sale s = onSale(10);
        String fan = fanToken(randomPhone());
        String kept = reserve(fan, s, 1);
        String id = reserve(fanToken(randomPhone()), s, 3);
        assertThat(sold(s.priceId())).isEqualTo(4);

        orderService.expireDue(); // nothing due yet
        assertThat(orderStatus(id)).isEqualTo("RESERVED");
        assertThat(sold(s.priceId())).isEqualTo(4);

        due(id);
        orderService.expireDue();
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertThat(orderStatus(kept)).isEqualTo("RESERVED");
        assertThat(sold(s.priceId())).isEqualTo(1);

        orderService.expireDue();
        assertThat(orderStatus(id)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    @Test
    void expirySkipsAFreshPendingPayment() throws Exception {
        Sale s = onSale(10);
        String fresh = reserve(fanToken(randomPhone()), s, 1);
        String stale = reserve(fanToken(randomPhone()), s, 2);
        pendingPayment(fresh, "5 minutes");
        pendingPayment(stale, "21 minutes");
        due(fresh);
        due(stale);

        orderService.expireDue();
        assertThat(orderStatus(fresh)).isEqualTo("RESERVED");
        assertThat(orderStatus(stale)).isEqualTo("EXPIRED");
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    @Test
    void oneBatchAcrossCategoriesAndEvents() throws Exception {
        Sale a = onSale(10);
        String vip = price(a.org(), a.eventId(), a.zoneId(), "VIP", 10);
        Sale b = onSale(10);
        String fan = fanToken(randomPhone());
        List<String> due = List.of(
                JsonPath.read(body(order(fan, a.eventId(), item(a.priceId(), 1) + "," + item(vip, 2))
                        .andExpect(status().isCreated())), "$.id"),
                reserve(fanToken(randomPhone()), a, 3),
                JsonPath.read(body(order(fanToken(randomPhone()), a.eventId(), item(vip, 1))
                        .andExpect(status().isCreated())), "$.id"),
                reserve(fan, b, 2),
                reserve(fanToken(randomPhone()), b, 4));
        String kept = reserve(fanToken(randomPhone()), b, 1);
        due.forEach(this::due);

        assertThat(orderService.expireDue()).isGreaterThanOrEqualTo(5);
        due.forEach(id -> assertThat(orderStatus(id)).isEqualTo("EXPIRED"));
        assertThat(orderStatus(kept)).isEqualTo("RESERVED");
        assertThat(sold(a.priceId())).isZero();
        assertThat(sold(vip)).isZero();
        assertThat(sold(b.priceId())).isEqualTo(1);
    }

    @Test
    void aPassLoopsOverBatches() throws Exception {
        Sale s = onSale(10);
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            due.add(reserve(fanToken(randomPhone()), s, 1));
        }
        due.forEach(this::due);
        assertThat(orderService.expireDue(2)).isGreaterThanOrEqualTo(5);
        due.forEach(id -> assertThat(orderStatus(id)).isEqualTo("EXPIRED"));
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void twoParallelPassesReleaseOnce() throws Exception {
        Sale s = onSale(20);
        List<String> due = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            due.add(reserve(fanToken(randomPhone()), s, 2));
        }
        String kept = reserve(fanToken(randomPhone()), s, 1);
        due.forEach(this::due);
        List<Callable<Integer>> passes = List.of(orderService::expireDue, orderService::expireDue);
        List<Integer> expired = concurrently(passes);
        assertThat(expired.get(0) + expired.get(1)).isGreaterThanOrEqualTo(8);
        due.forEach(id -> assertThat(orderStatus(id)).isEqualTo("EXPIRED"));
        assertThat(orderStatus(kept)).isEqualTo("RESERVED");
        assertThat(sold(s.priceId())).isEqualTo(1);
    }

    @Test
    void reserveOnlyInsideTheCallersTransaction() throws Exception {
        Sale s = onSale(10);
        assertThatThrownBy(() -> inventory.reserve(s.org().org(), s.eventId(), Map.of(UUID.fromString(s.priceId()), 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> inventory.release(List.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(sold(s.priceId())).isZero();
    }

    @Test
    void getListsItemsByPriceCategoryId() throws Exception {
        Sale s = onSale(10);
        List<String> ids = new ArrayList<>(List.of(s.priceId()));
        for (String name : List.of("A", "B", "C")) { // 4 places: the per-account limit
            ids.add(price(s.org(), s.eventId(), s.zoneId(), name, 10));
        }
        String fan = fanToken(randomPhone());
        String id = JsonPath.read(body(order(fan, s.eventId(),
                String.join(",", ids.stream().map(p -> item(p, 1)).toList())).andExpect(status().isCreated())), "$.id");
        List<String> listed = JsonPath.read(body(fetch(fan, id).andExpect(status().isOk())), "$.items[*].price_category_id");
        // Postgres orders uuids by their bytes, the same as their lowercase hex text.
        assertThat(listed).containsExactlyElementsOf(ids.stream().sorted().toList());
    }

    @Test
    void firstOrderAboveTheLimit() throws Exception {
        Sale s = onSale(100);
        order(fanToken(randomPhone()), s.eventId(), item(s.priceId(), 5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PER_ACCOUNT_LIMIT"))
                .andExpect(jsonPath("$.details.limit").value(4))
                .andExpect(jsonPath("$.details.held").value(0));
        assertThat(sold(s.priceId())).isZero();
    }

    // ---------- orders vs catalog writers ----------

    /**
     * An organizer transaction holds the event lock (as a price PATCH does) while an order takes the category
     * and inserts its order row (FK on the event): the order must not wait. Then the organizer writes that
     * category. With the event locked "for update" the order blocks and the two deadlock.
     */
    @Test
    void anOrderPassesWhileAnOrganizerHoldsTheEvent() throws Exception {
        Sale s = onSale(5);
        String fan = fanToken(randomPhone());
        UUID org = s.org().org();
        UUID priceId = UUID.fromString(s.priceId());
        try (var pool = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactions).executeWithoutResult(t -> {
                events.lock(org, s.eventId()).orElseThrow();
                Future<Integer> placed = pool.submit(() ->
                        order(fan, s.eventId(), item(s.priceId(), 1)).andReturn().getResponse().getStatus());
                try {
                    assertThat(placed.get(10, TimeUnit.SECONDS)).isEqualTo(201);
                } catch (Exception e) {
                    throw new AssertionError("order blocked behind the event lock", e);
                }
                assertThat(events.updatePrice(org, s.eventId(), priceId, null, null, 8)).isPresent();
            });
        }
        assertThat(sold(s.priceId())).isEqualTo(1);
        assertThat(jdbc.sql("select quota from price_categories where id = ?::uuid").param(s.priceId())
                .query(Integer.class).single()).isEqualTo(8);
    }

    @Test
    void quotaRaisesDuringParallelOrders() throws Exception {
        Sale s = onSale(5);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String fan = fanToken(randomPhone());
            calls.add(() -> {
                var r = order(fan, s.eventId(), item(s.priceId(), 1)).andReturn().getResponse();
                return r.getStatus() + " " + JsonPath.read(r.getContentAsString(), r.getStatus() == 201 ? "$.status" : "$.code");
            });
        }
        for (int quota = 6; quota <= 15; quota++) {
            int q = quota;
            calls.add(() -> {
                var r = call(s.org(), patch("/org/events/" + s.eventId() + "/prices/" + s.priceId()),
                        "{\"quota\": %d}".formatted(q)).andReturn().getResponse();
                return r.getStatus() == 200 ? "200 PATCH" : r.getStatus() + " " + JsonPath.read(r.getContentAsString(), "$.code");
            });
        }
        List<String> results = concurrently(calls);
        // A raise that lands after sales passed its value is refused (QUOTA_BELOW_SOLD); nothing else, never a 500.
        assertThat(results.subList(20, 30)).allMatch(r -> r.equals("200 PATCH") || r.equals("422 QUOTA_BELOW_SOLD"))
                .contains("200 PATCH");
        assertThat(results.subList(0, 20)).allMatch(r -> r.equals("201 RESERVED") || r.equals("409 SOLD_OUT"));
        long reserved = results.stream().filter("201 RESERVED"::equals).count();
        int quota = jdbc.sql("select quota from price_categories where id = ?::uuid").param(s.priceId())
                .query(Integer.class).single();
        assertThat(sold(s.priceId())).isEqualTo((int) reserved).isLessThanOrEqualTo(quota);
        assertThat(reserved).isGreaterThanOrEqualTo(5);
    }

    /** Runs every call on its own thread from one start signal; results in call order. */
    private static <T> List<T> concurrently(List<Callable<T>> calls) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> c : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return c.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
