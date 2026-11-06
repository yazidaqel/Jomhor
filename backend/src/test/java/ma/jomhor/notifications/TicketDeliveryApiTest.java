package ma.jomhor.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.payments.PaymentTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Contract: POST /fan/tickets/{id}/whatsapp (docs/04-api.md §Fan, §Errors; plan BE-14). Holder only (404), VALID
 * only (409 TICKET_NOT_VALID), 202 with a PENDING delivery queued; more than 3 re-sends of one ticket in 24 h →
 * 429 DELIVERY_RATE_LIMITED (the automatic delivery on payment is not a re-send); fan token only (401).
 */
class TicketDeliveryApiTest extends PaymentTest {

    /** A paid order of two places, the 2nd held by {@code friendPhone}; returns the order id. */
    private String paid(String token, Sale s, String friendPhone) throws Exception {
        String orderId = JsonPath.read(body(fan(token, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 2, "holders":
                  [{"name": "Yazid Aqel"}, {"name": "Samir Benali", "phone": "%s"}]}]}
                """.formatted(s.eventId(), s.priceId(), friendPhone)).andExpect(status().isCreated())), "$.id");
        callback(approve(payment(token, orderId), total(orderId))).andExpect(status().isSeeOther());
        return orderId;
    }

    private UUID ticketHeldBy(String orderId, String phone) {
        return jdbc.sql("""
                        select t.id from tickets t join users u on u.id = t.holder_user_id
                        where t.order_id = ?::uuid and u.phone_e164 = ?
                        """).params(orderId, phone).query(UUID.class).single();
    }

    private int deliveries(UUID ticketId, String status) {
        return jdbc.sql("select count(*) from ticket_deliveries where ticket_id = ? and status = ?::delivery_status")
                .params(ticketId, status).query(Integer.class).single();
    }

    private ResultActions resend(String token, Object ticketId) throws Exception {
        return fan(token, post("/fan/tickets/" + ticketId + "/whatsapp"));
    }

    @Test
    void theHolderGetsThreeReSendsIn24HoursThenA429() throws Exception {
        Sale s = onSale(10);
        String buyerPhone = randomPhone();
        String buyer = fanToken(buyerPhone);
        UUID ticket = ticketHeldBy(paid(buyer, s, randomPhone()), buyerPhone);
        assertThat(deliveries(ticket, "PENDING")).isEqualTo(1); // the automatic one

        for (int i = 0; i < 3; i++) {
            resend(buyer, ticket).andExpect(status().isAccepted()).andExpect(content().string(""));
        }
        assertThat(deliveries(ticket, "PENDING")).isEqualTo(4);
        resend(buyer, ticket).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DELIVERY_RATE_LIMITED"));
        assertThat(deliveries(ticket, "PENDING")).isEqualTo(4);

        // The window is 24 h: re-sends older than that no longer count.
        jdbc.sql("""
                update ticket_deliveries set created_at = now() - interval '25 hours'
                where ticket_id = ? and created_at <> (select issued_at from tickets where id = ?)
                """).params(ticket, ticket).update();
        resend(buyer, ticket).andExpect(status().isAccepted());
    }

    @Test
    void parallelReSendsOfOneTicketStopAtThree() throws Exception {
        Sale s = onSale(10);
        String buyerPhone = randomPhone();
        String buyer = fanToken(buyerPhone);
        UUID ticket = ticketHeldBy(paid(buyer, s, randomPhone()), buyerPhone);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> call = () -> {
            start.await();
            return resend(buyer, ticket).andReturn().getResponse().getStatus();
        };
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(pool.submit(call));
            }
            start.countDown();
            for (Future<Integer> f : calls) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses).filteredOn(st -> st == 202).hasSize(3);
        assertThat(statuses).filteredOn(st -> st == 429).hasSize(5);
        assertThat(deliveries(ticket, "PENDING")).isEqualTo(4);
    }

    /** A Lot 0 ticket has no automatic row (issued before BE-14): still exactly 3 re-sends. */
    @Test
    void aTicketWithoutAnAutomaticDeliveryGetsThreeReSendsToo() throws Exception {
        Sale s = onSale(10);
        String buyerPhone = randomPhone();
        String buyer = fanToken(buyerPhone);
        UUID ticket = ticketHeldBy(paid(buyer, s, randomPhone()), buyerPhone);
        jdbc.sql("delete from ticket_deliveries where ticket_id = ?").param(ticket).update();

        for (int i = 0; i < 3; i++) {
            resend(buyer, ticket).andExpect(status().isAccepted());
        }
        resend(buyer, ticket).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("DELIVERY_RATE_LIMITED"));
        assertThat(deliveries(ticket, "PENDING")).isEqualTo(3);
    }

    @Test
    void onlyTheHolderCanAskAndOnlyForAValidTicket() throws Exception {
        Sale s = onSale(10);
        String buyerPhone = randomPhone();
        String friendPhone = randomPhone();
        String buyer = fanToken(buyerPhone);
        String orderId = paid(buyer, s, friendPhone);
        UUID friendTicket = ticketHeldBy(orderId, friendPhone);

        // The buyer of a friend's ticket gets the same 404 as for an unknown ticket.
        for (UUID id : List.of(friendTicket, UUID.randomUUID())) {
            resend(buyer, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
        assertThat(deliveries(friendTicket, "PENDING")).isEqualTo(1);
        String friend = fanToken(friendPhone);
        resend(friend, friendTicket).andExpect(status().isAccepted());
        assertThat(deliveries(friendTicket, "PENDING")).isEqualTo(2);

        jdbc.sql("update tickets set status = 'BLOCKED' where id = ?").param(friendTicket).update();
        resend(friend, friendTicket).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_VALID"));
        assertThat(deliveries(friendTicket, "PENDING")).isEqualTo(2);

        resend(friend, "not-a-uuid").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/fan/tickets/" + friendTicket + "/whatsapp")).andExpect(status().isUnauthorized());
        mvc.perform(post("/fan/tickets/" + friendTicket + "/whatsapp").header("Authorization", "Bearer " + s.org().token()))
                .andExpect(status().isUnauthorized());
    }
}
