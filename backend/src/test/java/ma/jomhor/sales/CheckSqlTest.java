package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.products.application.PassTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * backend/loadtest/check.sql (plan lot-5 §5 BE-44): a clean sale has no row, and each of its checks fires, alone,
 * on one crafted breach, each on a fresh event (the file is scoped by event_id: the shared test database holds other
 * classes' deliberate breaches; a product order's rows, by its ref). Also the definition of sold: a REFUNDED ticket on a PAID order counts one place less.
 */
class CheckSqlTest extends QueueFixture {

    @Autowired
    OrderService orderService;

    @Autowired
    PassTicketService passTickets;

    /**
     * A sale with no breach, on a 3-place category (sold out): the fan's order A of 2 places is PAID (2 VALID tickets,
     * the approved card attempt and a second one left PENDING), order B of 1 place is RESERVED with a PENDING card
     * attempt; the fan's room entry is unranked and the room ENDED (orders need no token).
     */
    private record Sale(Room room, Fan fan, String paid, String held) {}

    private Sale sale() throws Exception {
        Room r = room("{}", 3);
        tick(OPENS);
        Fan f = fan();
        join(f, r.event()).andExpect(status().isCreated());
        call(r.admin(), post("/org/events/" + r.event() + "/rooms/1/end")).andExpect(status().isOk());
        memo.invalidate();
        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(r.event())
                .update();
        String paid = JsonPath.read(body(order(f, r, null, 2).andExpect(status().isCreated())), "$.id");
        String approved = pay(f, paid);
        pay(f, paid);
        callback(PaymentTest.approve(approved, 10_000)).andExpect(status().isSeeOther());
        String held = JsonPath.read(body(order(f, r, null, 1).andExpect(status().isCreated())), "$.id");
        pay(f, held);
        assertThat(breaches(r.event())).isEmpty();
        return new Sale(r, f, paid, held);
    }

    private String pay(Fan f, String orderId) throws Exception {
        return JsonPath.read(body(mvc.perform(post("/fan/orders/" + orderId + "/pay")
                .header("Authorization", "Bearer " + f.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"CARD\"}")).andExpect(status().isOk())), "$.payment_id");
    }

    private List<Object> found(Sale s) throws Exception {
        return breaches(s.room().event()).stream().map(b -> b.get("breach")).toList();
    }

    private void sql(String sql, Object... params) {
        jdbc.sql(sql).params(params).update();
    }

    /**
     * Plan lot-5 Q175: a staff refund frees its place in the buyer's limit, so buying it back at the limit is no
     * PER_ACCOUNT_LIMIT row; a limit below what the buyer then holds still is.
     */
    @Test
    void aRefundThenRebuyIsNoLimitBreach() throws Exception {
        Sale s = sale(); // the fan holds 3 places: A (2, PAID) and B (1, RESERVED)
        sql("update events set per_account_limit = 3 where id = ?", s.room().event());
        UUID refunded = jdbc.sql("select min(id::text)::uuid from tickets where order_id = ?::uuid").param(s.paid())
                .query(UUID.class).single();
        call(s.room().admin(), post("/org/tickets/" + refunded + "/refund"), "{\"reason\": \"Doublon\"}")
                .andExpect(status().isOk());
        order(s.fan(), s.room(), null, 1).andExpect(status().isCreated());
        assertThat(found(s)).isEmpty();

        sql("update events set per_account_limit = 2 where id = ?", s.room().event());
        assertThat(found(s)).containsExactly("PER_ACCOUNT_LIMIT");
    }

    /** check.sql rows about one ref (a product order's rows have no event_id). */
    private List<Object> foundFor(String ref) throws Exception {
        String check = Files.readString(Path.of("loadtest/check.sql")).strip();
        return jdbc.sql("select * from (" + check.substring(0, check.length() - 1) + "\n) c where ref = ?")
                .param(ref).query().listOfRows().stream().map(b -> b.get("breach")).toList();
    }

    /**
     * BE-52: a PAID product order (docs/04-api.md §Products) has no place and no ticket, so it is clean, and the sale
     * beside it too; a ticket on it is a TICKETS_PLACES row.
     */
    @Test
    void aPaidProductOrderIsCleanAndATicketOnItIsABreach() throws Exception {
        Sale s = sale();
        String product = JsonPath.read(body(call(s.room().admin(), post("/org/products"), """
                {"kind": "MEMBERSHIP", "name": "Cotisation", "price_mad": 15000, "valid_until": "2099-07-01T00:00:00Z",
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""").andExpect(status().isCreated())), "$.id");
        String order = JsonPath.read(body(mvc.perform(post("/fan/product-orders")
                .header("Authorization", "Bearer " + s.fan().token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Nadia Berrada\"}"
                        .formatted(product))).andExpect(status().isCreated())), "$.id");
        callback(PaymentTest.approve(pay(s.fan(), order), 15_000)).andExpect(status().isSeeOther());
        assertThat(jdbc.sql("select status::text from orders where id = ?::uuid").param(order).query(String.class)
                .single()).isEqualTo("PAID");
        assertThat(foundFor(order)).isEmpty();
        assertThat(found(s)).isEmpty();

        sql("""
                insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, status)
                select organizer_id, ?::uuid, event_id, zone_id, price_category_id, 'VALID' from tickets
                where order_id = ?::uuid limit 1""", order, s.paid());
        assertThat(foundFor(order)).containsExactly("TICKETS_PLACES");
    }

    private String productOrder(Fan f, String product) throws Exception {
        return JsonPath.read(body(mvc.perform(post("/fan/product-orders")
                .header("Authorization", "Bearer " + f.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\": \"%s\", \"channel\": \"APP\", \"holder_name\": \"Nadia Berrada\"}"
                        .formatted(product))).andExpect(status().isCreated())), "$.id");
    }

    /**
     * BE-53: a pass product's sold = its passes that aren't CANCELLED. A paid, a pending and an expired purchase are
     * clean, and the sale beside them too; sold drifted from them is a PRODUCT_SOLD row.
     */
    @Test
    void aPassProductsSoldIsItsLivePasses() throws Exception {
        Sale s = sale();
        Caller a = s.room().admin();
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        int zone = zone(a, venue(a, "Stade"), "EST");
        String product = JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement", "price_mad": 150000, "quota": 5, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""".formatted(series, zone))
                .andExpect(status().isCreated())), "$.id");
        String paid = productOrder(s.fan(), product);
        callback(PaymentTest.approve(pay(s.fan(), paid), 150_000)).andExpect(status().isSeeOther());
        String pending = productOrder(fan(), product);
        String expired = productOrder(fan(), product);
        sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid", expired);
        orderService.expireDue();
        assertThat(jdbc.sql("select p.status::text from passes p join orders o on o.pass_id = p.id where o.id in "
                        + "(?::uuid, ?::uuid, ?::uuid) order by array_position(array[?, ?, ?], o.id::text)")
                .params(paid, pending, expired, paid, pending, expired).query(String.class).list())
                .containsExactly("ACTIVE", "PENDING", "CANCELLED");
        for (String ref : List.of(product, paid, pending, expired)) {
            assertThat(foundFor(ref)).isEmpty();
        }
        assertThat(found(s)).isEmpty();

        sql("update products set sold = sold + 1 where id = ?::uuid", product);
        assertThat(foundFor(product)).containsExactly("PRODUCT_SOLD");
    }

    /**
     * BE-55: the pass job issues without the per-account limit (04 §Passes Tickets), so a holder over it through a pass
     * ticket is no PER_ACCOUNT_LIMIT row, and the pass ticket order is clean; places bought over it still are.
     */
    @Test
    void aPassTicketOverTheLimitIsNoBreach() throws Exception {
        Sale s = sale(); // the fan holds 3 places: A (2, PAID) and B (1, RESERVED)
        Caller a = s.room().admin();
        int event = s.room().event();
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        call(a, patch("/org/events/" + event), "{\"series_id\": \"%s\"}".formatted(series))
                .andExpect(status().isOk());
        int zone = jdbc.sql("select zone_id from price_categories where id = ?::uuid").param(s.room().price())
                .query(Integer.class).single();
        call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": 1, "audience": "PASS"}"""
                .formatted(zone)).andExpect(status().isCreated());
        String product = JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "Abonnement", "price_mad": 150000, "quota": 5, "series_id": "%s",
                 "zone_id": %d, "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true}""".formatted(series, zone))
                .andExpect(status().isCreated())), "$.id");
        String purchase = productOrder(s.fan(), product);
        callback(PaymentTest.approve(pay(s.fan(), purchase), 150_000)).andExpect(status().isSeeOther());
        sql("update events set per_account_limit = 3 where id = ?", event);

        passTickets.runAt(Instant.now());
        String ticketOrder = jdbc.sql("select id::text from orders where event_id = ? and pass_id is not null")
                .param(event).query(String.class).single();
        assertThat(found(s)).isEmpty(); // 4 places held at a limit of 3, one of them the pass ticket
        assertThat(foundFor(ticketOrder)).isEmpty();

        sql("update events set per_account_limit = 2 where id = ?", event);
        assertThat(found(s)).containsExactly("PER_ACCOUNT_LIMIT");
    }

    @Test
    void eachCheckFiresOnItsOwnBreach() throws Exception {
        // Oversell: both tickets of A transferred, and the old ones left VALID: 4 VALID tickets on a 3-place category.
        Sale s = sale();
        List<UUID> tickets = jdbc.sql("select id from tickets where order_id = ?::uuid").param(s.paid())
                .query(UUID.class).list();
        for (UUID t : tickets) {
            mvc.perform(post("/fan/tickets/" + t + "/transfer").header("Authorization", "Bearer " + s.fan().token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"to_phone\": \"%s\", \"name\": \"Nadia Berrada\"}".formatted(randomPhone())))
                    .andExpect(status().isCreated());
            sql("update tickets set status = 'VALID' where id = ?", t);
        }
        assertThat(found(s)).containsExactly("OVERSELL_TICKETS");

        // sold drifted from held + paid.
        s = sale();
        sql("update price_categories set sold = sold - 1 where id = ?::uuid", s.room().price());
        assertThat(found(s)).containsExactly("SOLD_MISMATCH");

        // A REFUNDED ticket of a PAID order frees its place: sold not lowered is a mismatch, lowered is clean.
        s = sale();
        sql("update tickets set status = 'REFUNDED' where id = (select min(id::text)::uuid from tickets "
                + "where order_id = ?::uuid)", s.paid());
        assertThat(found(s)).containsExactly("SOLD_MISMATCH");
        sql("update price_categories set sold = sold - 1 where id = ?::uuid", s.room().price());
        assertThat(found(s)).isEmpty();

        // Double charge: A's second card attempt succeeded too, and no payment.refund_required names it.
        s = sale();
        sql("update payments set status = 'SUCCEEDED' where order_id = ?::uuid and status = 'PENDING'", s.paid());
        assertThat(found(s)).containsExactly("DOUBLE_CHARGE");

        // Tickets ≠ places on a PAID order: one place moved from A to B (held + paid unchanged).
        s = sale();
        sql("update order_items set qty = 1, holders = jsonb_build_array(holders -> 0) where order_id = ?::uuid",
                s.paid());
        sql("update order_items set qty = 2, holders = holders || holders where order_id = ?::uuid", s.held());
        assertThat(found(s)).containsExactly("TICKETS_PLACES");

        // The account holds 3 places, the limit drops to 2.
        s = sale();
        sql("update events set per_account_limit = 2 where id = ?", s.room().event());
        assertThat(found(s)).containsExactly("PER_ACCOUNT_LIMIT");

        // A refunded order whose tickets stay VALID (sold follows the order, so only this check fires).
        s = sale();
        sql("update orders set status = 'REFUNDED' where id = ?::uuid", s.paid());
        sql("update price_categories set sold = 1 where id = ?::uuid", s.room().price());
        assertThat(found(s)).containsExactly("TICKET_NOT_PAID");

        // Charged without an order: B's card attempt succeeded, B is still RESERVED.
        s = sale();
        sql("update payments set status = 'SUCCEEDED' where order_id = ?::uuid", s.held());
        assertThat(found(s)).containsExactly("CHARGED_NO_ORDER");

        // Admission out of rank order: an entry admitted without a rank, then ranked past admitted_through (0), then
        // inside it (clean).
        s = sale();
        sql("update queue_entries set admitted_at = now() where room_id = ?", s.room().id());
        assertThat(found(s)).containsExactly("ADMISSION_ORDER");
        sql("update waiting_rooms set ranked_count = 1 where id = ?", s.room().id());
        sql("update queue_entries set rank = 1 where room_id = ?", s.room().id());
        assertThat(found(s)).containsExactly("ADMISSION_ORDER");
        sql("update waiting_rooms set admitted_through = 1 where id = ?", s.room().id());
        assertThat(found(s)).isEmpty();
    }
}
