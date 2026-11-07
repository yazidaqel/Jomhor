package ma.jomhor.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.zxing.Result;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.notifications.application.TicketDeliveryService;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.domain.Order;
import ma.jomhor.tickets.TicketPdfApiTest;
import ma.jomhor.tickets.domain.TicketToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The WhatsApp ticket outbox (plan BE-14; docs/04-api.md §Fan "one WhatsApp delivery is queued per ticket"): rows
 * are queued in the settle transaction, sent by the job once each to the holder's phone with the ticket PDF,
 * retried 1 m / 5 m / 30 m / 2 h and FAILED after 5 tries; two parallel runs never send one row twice.
 */
class TicketDeliveryTest extends PaymentTest {

    @Autowired
    TicketDeliveryService deliveries;

    @Autowired
    OrderService orders;

    @Autowired
    EventService events;

    @Autowired
    PlatformTransactionManager transactions;

    /** Other test classes leave PENDING rows behind (every paid order queues some): send them to the mock first. */
    @BeforeEach
    void drain() {
        deliveries.deliverDue();
        clearInvocations(ticketMessenger);
    }

    /** Pays an order of {@code holders} (JSON array) and returns its id. */
    private String paid(String token, Sale s, int qty, String holders) throws Exception {
        String orderId = JsonPath.read(body(fan(token, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                """.formatted(s.eventId(), s.priceId(), qty, holders)).andExpect(status().isCreated())), "$.id");
        callback(approve(payment(token, orderId), total(orderId))).andExpect(status().isSeeOther());
        return orderId;
    }

    private static String withFriend(String friendPhone) {
        return "[{\"name\": \"Yazid Aqel\"}, {\"name\": \"Samir Benali\", \"phone\": \"%s\"}]".formatted(friendPhone);
    }

    private List<Map<String, Object>> rows(String orderId) {
        return jdbc.sql("""
                        select d.ticket_id, d.organizer_id, d.status::text as status, d.attempts, d.last_error,
                               d.sent_at, extract(epoch from d.next_attempt_at - now())::bigint as due_in_s
                        from ticket_deliveries d join tickets t on t.id = d.ticket_id
                        where t.order_id = ?::uuid order by d.created_at, d.id
                        """).param(orderId).query().listOfRows();
    }

    private UUID ticketHeldBy(String orderId, String phone) {
        return jdbc.sql("""
                        select t.id from tickets t join users u on u.id = t.holder_user_id
                        where t.order_id = ?::uuid and u.phone_e164 = ?
                        """).params(orderId, phone).query(UUID.class).single();
    }

    private void dueNow(UUID ticketId) {
        jdbc.sql("update ticket_deliveries set next_attempt_at = now() where ticket_id = ?").param(ticketId).update();
    }

    /** The messages the mock got for this order's tickets (other rows may be sent by the same runs). */
    private List<TicketMessenger.Message> sent(String orderId) {
        List<String> ticketNos = jdbc.sql("select id from tickets where order_id = ?::uuid").param(orderId)
                .query(UUID.class).list().stream().map(TicketToken::ticketNo).toList();
        var captor = ArgumentCaptor.forClass(TicketMessenger.Message.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(m -> ticketNos.contains(m.ticketNo())).toList();
    }

    private static int attempts(Map<String, Object> row) {
        return ((Number) row.get("attempts")).intValue();
    }

    @Test
    void paidQueuesOneDeliveryPerTicket() throws Exception {
        Sale s = onSale(10);
        String buyer = randomPhone();
        String friend = randomPhone();
        String orderId = paid(fanToken(buyer), s, 2, withFriend(friend));

        List<Map<String, Object>> rows = rows(orderId);
        assertThat(rows).hasSize(2).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("PENDING");
            assertThat(r.get("organizer_id")).isEqualTo(s.org().org());
            assertThat(attempts(r)).isZero();
        });
        assertThat(rows).extracting(r -> r.get("ticket_id"))
                .containsExactlyInAnyOrder(ticketHeldBy(orderId, buyer), ticketHeldBy(orderId, friend));
    }

    @Test
    void theRowsAreInTheSettleTransactionSoARollbackQueuesNothing() throws Exception {
        Sale s = onSale(10);
        String orderId = order(fanToken(randomPhone()), s, 2);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertThat(orders.settle(UUID.fromString(orderId), Order.PaymentMethod.CARD, "REF-1")).isEqualTo(OrderService.Settlement.PAID);
            assertThat(rows(orderId)).hasSize(2); // queued inside the settle's own transaction
            tx.setRollbackOnly();
        });
        assertThat(jdbc.sql("select count(*) from tickets where order_id = ?::uuid").param(orderId)
                .query(Integer.class).single()).isZero();
        assertThat(rows(orderId)).isEmpty();
    }

    @Test
    void theJobSendsEachTicketOnceToItsHolderWithAPdfWhoseQrDecodes() throws Exception {
        Sale s = onSale(10);
        String buyer = randomPhone();
        String friend = randomPhone();
        String orderId = paid(fanToken(buyer), s, 2, withFriend(friend));

        deliveries.deliverDue();
        deliveries.deliverDue(); // a second run sends nothing more

        List<TicketMessenger.Message> sent = sent(orderId);
        assertThat(sent).hasSize(2);
        for (String phone : List.of(buyer, friend)) {
            UUID ticket = ticketHeldBy(orderId, phone);
            TicketMessenger.Message m = sent.stream()
                    .filter(x -> x.ticketNo().equals(TicketToken.ticketNo(ticket))).findFirst().orElseThrow();
            assertThat(m.phoneE164()).isEqualTo(phone); // the holder's own phone: the friend's never goes to the buyer
            assertThat(m.toString()).doesNotContain(phone);
            Result qr = TicketPdfApiTest.qr(m.pdf());
            TicketToken.Parsed token = TicketToken.decode(qr.getText());
            assertThat(token.version()).isEqualTo(TicketToken.VERSION_STATIC);
            assertThat(token.ticketId()).isEqualTo(ticket);
            boolean verified = events.withEventKey(s.org().org(), s.eventId(),
                    k -> TicketToken.verify(token, s.eventId(), k, 0).isEmpty());
            assertThat(verified).isTrue();
        }
        assertThat(rows(orderId)).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("SENT");
            assertThat(attempts(r)).isEqualTo(1);
            assertThat(r.get("sent_at")).isNotNull();
            assertThat(r.get("last_error")).isNull();
        });
    }

    @Test
    void aProviderErrorBacksOffThenFailsAfterFiveTries() throws Exception {
        Sale s = onSale(10);
        String phone = randomPhone();
        String orderId = paid(fanToken(phone), s, 1, holders(1));
        UUID ticket = ticketHeldBy(orderId, phone);
        doThrow(new TicketMessenger.Failed("131026")).when(ticketMessenger).send(any());

        long[] backoffS = {60, 300, 1800, 7200};
        for (int attempt = 1; attempt <= 5; attempt++) {
            deliveries.deliverDue();
            Map<String, Object> row = rows(orderId).getFirst();
            assertThat(attempts(row)).isEqualTo(attempt);
            assertThat(row.get("last_error")).isEqualTo("131026");
            if (attempt < 5) {
                assertThat(row.get("status")).isEqualTo("PENDING");
                long wait = backoffS[attempt - 1];
                assertThat(((Number) row.get("due_in_s")).longValue()).isBetween(wait - 5, wait);
                deliveries.deliverDue(); // not due yet: no try
                assertThat(attempts(rows(orderId).getFirst())).isEqualTo(attempt);
                dueNow(ticket);
            } else {
                assertThat(row.get("status")).isEqualTo("FAILED");
                assertThat(row.get("sent_at")).isNull();
            }
        }
        dueNow(ticket);
        deliveries.deliverDue(); // FAILED is final
        assertThat(rows(orderId).getFirst()).containsEntry("status", "FAILED");
        assertThat(sent(orderId)).hasSize(5);
    }

    @Test
    void anyOtherErrorKeepsOnlyItsClassNeverItsMessage() throws Exception {
        Sale s = onSale(10);
        String phone = randomPhone();
        String orderId = paid(fanToken(phone), s, 1, holders(1));
        doThrow(new IllegalStateException("provider refused " + phone)).when(ticketMessenger).send(any());
        deliveries.deliverDue();
        assertThat(rows(orderId).getFirst()).containsEntry("status", "PENDING")
                .containsEntry("last_error", "IllegalStateException");
        // Due again in 1 min: settle it so a later test of this class never meets it.
        jdbc.sql("update ticket_deliveries set status = 'FAILED' where ticket_id = ?")
                .param(ticketHeldBy(orderId, phone)).update();
    }

    @Test
    void aTicketNoLongerValidFailsWithoutRetry() throws Exception {
        Sale s = onSale(10);
        String orderId = paid(fanToken(randomPhone()), s, 1, holders(1));
        jdbc.sql("update tickets set status = 'CANCELLED' where order_id = ?::uuid").param(orderId).update();
        deliveries.deliverDue();
        assertThat(rows(orderId).getFirst()).containsEntry("status", "FAILED")
                .containsEntry("last_error", "TICKET_NOT_VALID");
        assertThat(sent(orderId)).isEmpty();
    }

    @Test
    void thePhoneIsReadAtSendTime() throws Exception {
        Sale s = onSale(10);
        String phone = randomPhone();
        String orderId = paid(fanToken(phone), s, 1, holders(1));
        String newPhone = randomPhone();
        jdbc.sql("update users set phone_e164 = ? where phone_e164 = ?").params(newPhone, phone).update();
        deliveries.deliverDue();
        assertThat(sent(orderId)).singleElement().extracting(TicketMessenger.Message::phoneE164).isEqualTo(newPhone);
    }

    @Test
    void twoParallelRunsNeverSendOneRowTwice() throws Exception {
        Sale s = onSale(10);
        String phone = randomPhone();
        String orderId = paid(fanToken(phone), s, 1, holders(1));
        // 120 rows of one ticket (more than two batches of 50), so both runs are busy at the same time.
        jdbc.sql("insert into ticket_deliveries (organizer_id, ticket_id) select ?, ? from generate_series(1, 119)")
                .params(s.org().org(), ticketHeldBy(orderId, phone)).update();
        doAnswer(inv -> {
            Thread.sleep(2);
            return null;
        }).when(ticketMessenger).send(any());

        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> run = () -> {
            start.await();
            return deliveries.deliverDue();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> runs = new ArrayList<>(List.of(pool.submit(run), pool.submit(run)));
            start.countDown();
            for (Future<Integer> f : runs) {
                assertThat(f.get()).isPositive(); // both runs really worked at the same time
            }
        }
        assertThat(sent(orderId)).hasSize(120);
        assertThat(rows(orderId)).hasSize(120).allSatisfy(r -> {
            assertThat(r.get("status")).isEqualTo("SENT");
            assertThat(attempts(r)).isEqualTo(1);
        });
    }
}
