package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.notifications.domain.MessageCopy;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.products.application.PassTicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * BE-59 (plan lot-5 Q199; docs/04-api.md §Passes Reminder, §Notifications INSTALLMENT_DUE), on a fixed clock: the pass
 * job reminds each unpaid installment after the first once, passes.reminder-before (3 d) before its due_at, with one
 * PUSH and one WHATSAPP row carrying pass_id; the push opens /passes/{id} in the holder's language with the amount and
 * the date; WhatsApp installment_due gets [product, amount, date, {SHOP_ORIGIN}/me/tickets]; no phone, no name. Paid
 * before the send or a CANCELLED pass → STALE; a CANCELLED pass and a due date already past are never reminded; two
 * installments due at once go out one per run, each with its own message pair.
 */
class InstallmentReminderTest extends PaymentTest {

    private static final long PRICE = 90_000; // 3 × 30,000
    private static final long PART = 30_000;
    private static final Duration BEFORE = Duration.ofDays(3);
    private static final String PRODUCT = "Abonnement Est";
    private static final String TZ = "Africa/Casablanca"; // the venues' default zone (V5)

    @Autowired
    PassTicketService job;

    @Autowired
    FanMessageService messages;

    /** Rows other test classes left due are sent first (to the mocks), so each test sees only its own. */
    @BeforeEach
    void drain() {
        messages.deliverDue(Integer.MAX_VALUE);
        clearInvocations(pushSender, ticketMessenger);
    }

    private record Fan(String token, String phone, String pass, String device) {}

    private String product() throws Exception {
        Caller a = admin();
        int est = zone(a, venue(a, "Stade"), "EST");
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        return JsonPath.read(body(call(a, post("/org/products"), """
                {"kind": "PASS", "name": "%s", "price_mad": %d, "quota": 10, "series_id": "%s", "zone_id": %d,
                 "sales_close_at": "2099-06-30T22:00:00Z", "on_sale": true, "max_installments": 3}"""
                .formatted(PRODUCT, PRICE, series, est)).andExpect(status().isCreated())), "$.id");
    }

    /** A fan in {@code locale} with a push device, holding a pass bought in 3 installments (seq 1 paid by card). */
    private Fan holder(String product, String locale, String name) throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        fan(token, patch("/fan/me"), "{\"locale\": \"%s\"}".formatted(locale)).andExpect(status().isOk());
        String device = "fcm-" + UUID.randomUUID();
        fan(token, put("/fan/me/push-token"), "{\"token\": \"%s\", \"platform\": \"android\"}".formatted(device))
                .andExpect(status().isNoContent());
        String json = body(fan(token, post("/fan/product-orders"), """
                {"product_id": "%s", "channel": "APP", "holder_name": "%s", "installments": 3}"""
                .formatted(product, name)).andExpect(status().isCreated()));
        callback(approve(payment(token, JsonPath.read(json, "$.id")), PART)).andExpect(status().isSeeOther());
        return new Fan(token, phone, JsonPath.read(json, "$.pass_id"), device);
    }

    private Instant due(Fan f, int seq) {
        return jdbc.sql("select due_at from pass_installments where pass_id = ?::uuid and seq = ?").params(f.pass(), seq)
                .query(OffsetDateTime.class).single().toInstant();
    }

    /** seq → reminded_at (null when not reminded), by seq. */
    private List<Instant> reminded(Fan f) {
        return jdbc.sql("select reminded_at from pass_installments where pass_id = ?::uuid order by seq")
                .param(f.pass()).query((rs, n) -> {
                    OffsetDateTime t = rs.getObject(1, OffsetDateTime.class);
                    return t == null ? null : t.toInstant();
                }).list();
    }

    private List<Map<String, Object>> rows(Fan f) {
        return jdbc.sql("""
                        select m.channel::text as channel, m.status::text as status, m.last_error, m.created_at,
                               m.organizer_id = p.organizer_id and m.user_id = p.user_id as holder
                        from fan_messages m join passes p on p.id = m.pass_id
                        where m.pass_id = ?::uuid and m.kind = 'INSTALLMENT_DUE' order by m.channel""")
                .param(f.pass()).query().listOfRows();
    }

    private List<PushSender.Push> pushesTo(Fan f) {
        var captor = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(p -> p.token().equals(f.device())).toList();
    }

    private List<TicketMessenger.Template> templatesTo(Fan f) {
        var captor = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues().stream().filter(t -> t.phoneE164().equals(f.phone())).toList();
    }

    @Test
    void eachInstallmentAfterTheFirstIsRemindedOnceThreeDaysBeforeItsDueDateInTheHoldersLanguage() throws Exception {
        String product = product();
        Map<String, String> names = Map.of("fr", "Nadia Berrada", "ar", "يزيد عقل", "en", "Karim El Idrissi");
        Map<String, Fan> fans = Map.of("fr", holder(product, "fr", names.get("fr")),
                "ar", holder(product, "ar", names.get("ar")), "en", holder(product, "en", names.get("en")));
        Instant at = fans.values().stream().map(f -> due(f, 2).minus(BEFORE)).max(Instant::compareTo).orElseThrow();

        // 1 s too early for every fan: nothing.
        Instant early = fans.values().stream().map(f -> due(f, 2).minus(BEFORE)).min(Instant::compareTo).orElseThrow()
                .minusSeconds(1);
        job.runAt(early);
        fans.values().forEach(f -> assertThat(rows(f)).isEmpty());

        // At due − 3 d: seq 2 reminded, 2 rows (PUSH, WHATSAPP) to the holder with pass_id, queued at reminded_at;
        // seq 1 (paid) and seq 3 (later) are not. A second run, on this instance, adds nothing.
        job.runAt(at);
        job.runAt(at.plusSeconds(60));
        Instant micros = at.truncatedTo(ChronoUnit.MICROS);
        for (Fan f : fans.values()) {
            assertThat(reminded(f)).containsExactly(null, micros, null);
            assertThat(rows(f)).hasSize(2).allSatisfy(r -> assertThat(r).containsEntry("status", "PENDING")
                    .containsEntry("holder", true)).extracting(r -> r.get("channel")).containsExactly("PUSH", "WHATSAPP");
            assertThat(rows(f)).allSatisfy(r -> assertThat(((Timestamp) r.get("created_at")).toInstant())
                    .isEqualTo(micros));
        }

        messages.deliverDue(Integer.MAX_VALUE);
        String amount = MessageCopy.amount(PART);
        assertThat(amount).isEqualTo("300,00 DH");
        StringBuilder all = new StringBuilder();
        for (var e : fans.entrySet()) {
            Fan f = e.getValue();
            String date = MessageCopy.date(due(f, 2), TZ, e.getKey());
            Map<String, List<String>> copy = Map.of(
                    "fr", List.of("Échéance de ton abonnement", amount + " avant le " + date),
                    "ar", List.of("قرب موعد خلاص الأبونمون ديالك", "\u2068" + amount + "\u2069 قبل \u2068" + date + "\u2069"),
                    "en", List.of("Your season pass installment is due", amount + " before " + date));
            assertThat(rows(f)).allSatisfy(r -> assertThat(r).containsEntry("status", "SENT"));
            assertThat(pushesTo(f)).singleElement().satisfies(p -> {
                assertThat(p.title()).isEqualTo(copy.get(e.getKey()).get(0));
                assertThat(p.body()).isEqualTo(copy.get(e.getKey()).get(1));
                assertThat(p.path()).isEqualTo("/passes/" + f.pass());
                all.append(p.title()).append('|').append(p.body()).append('|');
            });
            assertThat(templatesTo(f)).singleElement().satisfies(t -> {
                assertThat(t.name()).isEqualTo("installment_due");
                assertThat(t.locale()).isEqualTo(e.getKey());
                assertThat(t.params()).containsExactly(PRODUCT, amount, date, "https://shop.test/me/tickets");
                all.append(t.params()).append('|');
            });
        }
        for (var e : fans.entrySet()) { // no phone, no name in any payload
            String phone = e.getValue().phone();
            assertThat(all.toString()).doesNotContain(phone).doesNotContain(phone.substring(1))
                    .doesNotContain(names.get(e.getKey()).split(" ")[0]);
        }

        // Seq 3, 3 days before its due date: one more reminder each.
        Fan fr = fans.get("fr");
        job.runAt(due(fr, 3).minus(BEFORE));
        assertThat(reminded(fr)).containsExactly(null, micros, due(fr, 3).minus(BEFORE).truncatedTo(ChronoUnit.MICROS));
        assertThat(rows(fr)).hasSize(4);
    }

    @Test
    void twoInstallmentsDueAtOnceGetOneMessagePairEachOverTwoRuns() throws Exception {
        Fan f = holder(product(), "fr", "Nadia Berrada");
        jdbc.sql("update pass_installments set due_at = due_at - interval '31 days' where pass_id = ?::uuid and seq = 3")
                .param(f.pass()).update(); // seq 3 due 1 day before seq 2: both inside the 3 days, seq 2 goes first
        Instant first = due(f, 2).minus(BEFORE);
        Instant second = first.plusSeconds(60);

        job.runAt(first);
        assertThat(reminded(f)).containsExactly(null, first.truncatedTo(ChronoUnit.MICROS), null);
        assertThat(rows(f)).hasSize(2);
        job.runAt(second);
        assertThat(reminded(f)).containsExactly(null, first.truncatedTo(ChronoUnit.MICROS),
                second.truncatedTo(ChronoUnit.MICROS));
        assertThat(rows(f)).hasSize(4);
        job.runAt(second.plusSeconds(60));
        assertThat(rows(f)).hasSize(4);

        messages.deliverDue(Integer.MAX_VALUE);
        String amount = MessageCopy.amount(PART);
        assertThat(pushesTo(f)).extracting(PushSender.Push::body).containsExactlyInAnyOrder(
                amount + " avant le " + MessageCopy.date(due(f, 2), TZ, "fr"),
                amount + " avant le " + MessageCopy.date(due(f, 3), TZ, "fr"));
        assertThat(templatesTo(f)).extracting(t -> t.params().get(2)).containsExactlyInAnyOrder(
                MessageCopy.date(due(f, 2), TZ, "fr"), MessageCopy.date(due(f, 3), TZ, "fr"));
    }

    @Test
    void paidBeforeTheSendOrACancelledPassIsStaleAndACancelledPassIsNeverReminded() throws Exception {
        String product = product();
        Fan paid = holder(product, "fr", "Nadia Berrada");
        Fan cancelled = holder(product, "fr", "Karim El Idrissi");
        Fan never = holder(product, "fr", "Samir Benali");
        Fan unpaidFirst = holder(product, "fr", "Salma Tazi");
        Fan late = holder(product, "fr", "Hamza Chraibi");
        // Seq 2 already past and never reminded: no late reminder, the suspension handles it.
        jdbc.sql("update pass_installments set due_at = now() - interval '1 day' where pass_id = ?::uuid and seq = 2")
                .param(late.pass()).update();
        jdbc.sql("update passes set status = 'CANCELLED' where id = ?::uuid").param(never.pass()).update();
        // Seq 1 unpaid (never in the flow, since it is the purchase): still never reminded; the pass gets suspended.
        jdbc.sql("update pass_installments set order_id = null, paid_at = null where pass_id = ?::uuid and seq = 1")
                .param(unpaidFirst.pass()).update();

        Instant at = List.of(paid, cancelled, never, unpaidFirst).stream().map(f -> due(f, 2).minus(BEFORE))
                .max(Instant::compareTo).orElseThrow();
        job.runAt(at);
        assertThat(rows(never)).isEmpty();
        assertThat(reminded(never)).containsOnlyNulls();
        assertThat(rows(late)).isEmpty();
        assertThat(reminded(late)).containsOnlyNulls();
        assertThat(jdbc.sql("select status::text from passes where id = ?::uuid").param(late.pass())
                .query(String.class).single()).isEqualTo("SUSPENDED");
        assertThat(reminded(unpaidFirst).getFirst()).isNull();
        assertThat(rows(unpaidFirst)).hasSize(2);
        assertThat(rows(paid)).hasSize(2);
        assertThat(rows(cancelled)).hasSize(2);

        // Before the send: seq 2 paid; the other pass CANCELLED.
        String order = JsonPath.read(body(fan(paid.token(), post("/fan/passes/" + paid.pass() + "/installments"))
                .andExpect(status().isCreated())), "$.id");
        callback(approve(payment(paid.token(), order), PART)).andExpect(status().isSeeOther());
        jdbc.sql("update passes set status = 'CANCELLED', suspended_at = null where id = ?::uuid")
                .param(cancelled.pass()).update();
        messages.deliverDue(Integer.MAX_VALUE);

        for (Fan f : List.of(paid, cancelled)) {
            assertThat(rows(f)).allSatisfy(r -> assertThat(r).containsEntry("status", "FAILED")
                    .containsEntry("last_error", "STALE"));
            assertThat(pushesTo(f)).isEmpty();
            assertThat(templatesTo(f)).isEmpty();
        }
        assertThat(rows(unpaidFirst)).allSatisfy(r -> assertThat(r).containsEntry("status", "SENT"));
    }
}
