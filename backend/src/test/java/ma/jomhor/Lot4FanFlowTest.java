package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.application.Manifests;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketDeliveryService;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.payments.PaymentTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lot 4 exit proof, backend side (plan lot-4 §1 demo (1)-(8) and §5 BE-35): an Arabic split order of 3 (buyer card,
 * member A card, member B cash code collected at a till), A's ticket transferred to C, B's re-issued, the member push
 * campaign and its inbox row, the EVENT_HOLDERS WhatsApp campaign, the caller's own gate entries, loyalty after the
 * close, /sales and /live unmoved, and no phone, full name or K_t in anything a fan or a log sees. The outbox, the
 * delivery job, the campaign job and the manifest build are called directly; push and WhatsApp are the
 * IntegrationTest mocks. So the log sweep covers the app's own logging only: LogPushSender and LogTicketMessenger are
 * not in this context, and neither has a no-PII test of its own yet (PushSenderProfileTest and
 * TicketMessengerProfileTest only check which bean each profile starts with).
 */
@ExtendWith(OutputCaptureExtension.class)
class Lot4FanFlowTest extends PaymentTest {

    private static final String BUYER_NAME = "يزيد عقل"; // Yazid Aqel
    private static final String A_NAME = "Karim Alaoui";
    private static final String B_NAME = "Sara Bennani";
    private static final String C_NAME = "Omar Idrissi";
    private static final String CREST = "https://assets.jomhor.ma/crests/atlas.png";

    @Autowired
    FanMessageService messages;

    @Autowired
    TicketDeliveryService deliveries;

    @Autowired
    CampaignService campaigns;

    @Autowired
    ManifestService manifestService;

    @Autowired
    Manifests manifests;

    @Test
    void splitTransferReissueCampaignsGateAndLoyalty(CapturedOutput output) throws Exception {
        // What other test classes left due goes to the mocks first, so only this flow's sends are captured.
        campaigns.runDue();
        messages.deliverDue(Integer.MAX_VALUE);
        deliveries.deliverDue();
        clearInvocations(pushSender, ticketMessenger);

        // Organizer A (listed, with a crest and a cash point), event E on sale: 10 places at 50 DH.
        Sale s = onSale(10);
        UUID org = s.org().org();
        int e = s.eventId();
        String slug = listed(org);
        jdbc.sql("update organizers set settings = settings || ?::jsonb where id = ?")
                .params("{\"crest_url\": \"" + CREST + "\", \"cash_points\": [{\"name\": \"Guichet Stade\"}]}", org)
                .update();

        String buyerPhone = randomPhone();
        String aPhone = randomPhone();
        String bPhone = randomPhone();
        String cPhone = randomPhone();
        List<String> phones = List.of(buyerPhone, aPhone, bPhone, cPhone);
        String buyer = fanToken(buyerPhone);
        String a = fanToken(aPhone);
        String aDevice = device(a); // A has the app: the invite push is captured too

        // ---------- (1) Arabic split order: the buyer's own place + A and B with pays ----------
        fan(buyer, patch("/fan/me"), "{\"locale\": \"ar\"}").andExpect(status().isOk());
        String placed = body(fan(buyer, post("/fan/orders"), """
                {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 3, "holders": [
                 {"name": "%s"}, {"name": "%s", "phone": "%s", "pays": true},
                 {"name": "%s", "phone": "%s", "pays": true}]}]}
                """.formatted(e, s.priceId(), BUYER_NAME, A_NAME, aPhone, B_NAME, bPhone))
                .andExpect(status().isCreated()));
        String buyerOrder = JsonPath.read(placed, "$.id");
        assertThat(JsonPath.<Integer>read(placed, "$.total_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Integer>read(placed, "$.items[0].qty")).isEqualTo(1);
        assertThat(JsonPath.<List<String>>read(placed, "$.items[0].holders[*].label")).containsExactly("يزيد ع.");
        assertThat(sold(s.priceId())).isEqualTo(3);
        String orderA = memberOf(buyerOrder, aPhone);
        String orderB = memberOf(buyerOrder, bPhone);

        callback(approve(payment(buyer, buyerOrder), 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(buyerOrder)).isEqualTo("PAID");
        UUID buyerTicket = ticketOf(buyerOrder);
        assertThat(manifestService.build(org, e)).isPresent();
        assertThat(tLines(e, latestVersion(e))).contains(tLine(buyerTicket, s, "VALID", "ي. ع."));

        assertThat(fanMessages("kind = 'SPLIT_INVITE' and order_id in (?::uuid, ?::uuid)", orderA, orderB))
                .containsExactlyInAnyOrder(orderA + " PUSH", orderA + " WHATSAPP", orderB + " PUSH",
                        orderB + " WHATSAPP");
        messages.deliverDue();
        for (String m : List.of(orderA, orderB)) {
            assertThat(templates()).filteredOn(t -> t.name().equals("split_invite") && t.params().getLast()
                    .equals("http://shop.test/orders/" + m)).singleElement()
                    .satisfies(t -> assertThat(t.params().getFirst()).isEqualTo("يزيد ع."));
        }
        assertThat(pushesTo(aDevice)).singleElement()
                .satisfies(p -> assertThat(p.path()).isEqualTo("/orders/" + orderA));

        // ---------- (2) the members pay: A by card, B with a cash code collected at a till ----------
        callback(approve(payment(a, orderA), 5000)).andExpect(status().isSeeOther());
        assertThat(orderStatus(orderA)).isEqualTo("PAID");
        UUID ticketA = ticketOf(orderA);
        assertThat(holderPhone(ticketA)).isEqualTo(aPhone);
        assertThat(body(fan(buyer, get("/fan/tickets")))).doesNotContain(ticketA.toString());

        String b = fanToken(bPhone);
        String code = JsonPath.read(body(fan(b, post("/fan/orders/" + orderB + "/pay"), "{\"method\": \"CASH_AGENCY\"}")
                .andExpect(status().isOk())), "$.pay_code");
        assertThat(fanMessages("kind = 'PAY_CODE' and order_id = ?::uuid", orderB))
                .containsExactly(orderB + " WHATSAPP");
        messages.deliverDue();
        assertThat(templates()).filteredOn(t -> t.name().equals("pay_code")).singleElement().satisfies(t -> {
            assertThat(t.userId()).isEqualTo(user(bPhone));
            assertThat(t.params()).contains("JMH " + code.substring(0, 4) + " " + code.substring(4));
        });
        Caller cashier = caller(org, "CASHIER");
        call(cashier, post("/org/pos/sessions"), "{\"label\": \"Guichet Nord\"}").andExpect(status().isCreated());
        call(cashier, post("/org/pos/codes/" + code + "/collect"), "{\"amount_mad\": 5000}").andExpect(status().isOk());
        assertThat(orderStatus(orderB)).isEqualTo("PAID");
        assertThat(fanMessages("kind = 'ORDER_PAID' and order_id = ?::uuid", orderB)).containsExactly(orderB + " PUSH");
        UUID ticketB = ticketOf(orderB);
        assertThat(holderPhone(ticketB)).isEqualTo(bPhone);
        // The delivery job runs (every 10 s in the app) before A transfers: a PDF still due when its ticket is
        // CANCELLED fails TICKET_NOT_VALID without a retry, by design.
        deliveries.deliverDue();

        String group = body(fan(buyer, get("/fan/orders/" + buyerOrder)).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(group, "$.status")).isEqualTo("PAID");
        assertThat(JsonPath.<List<String>>read(group, "$.group.members[*].status")).containsExactly("PAID", "PAID");
        assertThat(JsonPath.<List<String>>read(group, "$.group.members[*].holder_label"))
                .containsExactlyInAnyOrder("Karim A.", "Sara B.");
        assertThat(JsonPath.<List<String>>read(group, "$.ticket_ids")).containsExactly(buyerTicket.toString());
        long paidBefore = paid(s);
        long liveBefore = liveSold(s);
        assertThat(paidBefore).isEqualTo(3);
        assertThat(liveBefore).isEqualTo(3);

        // ---------- (3) transfer A -> C before transfer_until ----------
        String c = fanToken(cPhone);
        String cDevice = device(c);
        String aTickets = held(a);
        assertThat(Instant.parse(JsonPath.<String>read(aTickets, "$.tickets[0].transfer_until")))
                .isAfter(Instant.now());
        List<String> keys = new ArrayList<>(JsonPath.<List<String>>read(aTickets, "$.tickets[*].k_t"));
        int vBeforeTransfer = latestVersion(e);
        fan(a, post("/fan/tickets/" + ticketA + "/transfer"), "{\"to_phone\": \"%s\", \"name\": \"%s\"}"
                .formatted(cPhone, C_NAME)).andExpect(status().isCreated());
        UUID ticketC = newTicket(ticketA);
        assertThat(ticketStatus(ticketA)).isEqualTo("CANCELLED");
        assertThat(ticketStatus(ticketC)).isEqualTo("VALID");
        assertThat(holderPhone(ticketC)).isEqualTo(cPhone);
        messages.deliverDue();
        assertThat(fanMessages("kind = 'TICKET_RECEIVED' and ticket_id = ?", ticketC)).containsExactly("null PUSH");
        assertThat(pushesTo(cDevice)).singleElement()
                .satisfies(p -> assertThat(p.path()).isEqualTo("/tickets/" + ticketC));
        deliveries.deliverDue();
        assertThat(jdbc.sql("select status::text from ticket_deliveries where ticket_id = ?").param(ticketC)
                .query(String.class).list()).containsExactly("SENT");
        String chain = body(call(s.org(), post("/org/events/" + e + "/tickets/search"),
                "{\"q\": \"%s\"}".formatted(cPhone)).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(chain, "$[*].ticket_no")).containsExactly(ticketNo(ticketC));
        assertThat(JsonPath.<String>read(chain, "$[0].transferred_from")).isEqualTo(ticketNo(ticketA));
        String aAfter = held(a);
        assertThat(JsonPath.<List<String>>read(aAfter, "$.tickets[*].id")).containsExactly(ticketA.toString());
        assertKeys(aAfter, "$.tickets[0].transferred", "to_label", "at");
        assertThat(JsonPath.<String>read(aAfter, "$.tickets[0].transferred.to_label")).isEqualTo("Omar I.");
        assertThat(JsonPath.<Map<String, Object>>read(aAfter, "$.tickets[0]")).doesNotContainKey("k_t");
        int vTransfer = rebuild(e, org);
        assertThat(vTransfer).isEqualTo(vBeforeTransfer + 1);
        assertThat(tLines(e, vTransfer)).contains(tLine(ticketA, s, "CANCELLED", "K. A."),
                tLine(ticketC, s, "VALID", "O. I."));

        // ---------- (4) B re-issues: a new id, the old one unlisted and CANCELLED in the next manifest ----------
        keys.addAll(JsonPath.<List<String>>read(held(b), "$.tickets[*].k_t"));
        UUID reissued = UUID.fromString(JsonPath.read(body(fan(b, post("/fan/tickets/" + ticketB + "/reissue"))
                .andExpect(status().isCreated())), "$.ticket_id"));
        assertThat(reissued).isNotEqualTo(ticketB);
        String bTickets = held(b);
        assertThat(JsonPath.<List<String>>read(bTickets, "$.tickets[*].id")).containsExactly(reissued.toString());
        int vReissue = rebuild(e, org);
        assertThat(tLines(e, vReissue)).contains(tLine(ticketB, s, "CANCELLED", "S. B."),
                tLine(reissued, s, "VALID", "S. B."));

        // (8) /sales paid and /live sold are unmoved by the transfer and the re-issue.
        assertThat(paid(s)).isEqualTo(paidBefore);
        assertThat(liveSold(s)).isEqualTo(liveBefore);
        assertThat(sold(s.priceId())).isEqualTo(3);

        // ---------- (5) campaigns: the buyer joins with push on, C with push off ----------
        keys.addAll(JsonPath.<List<String>>read(bTickets, "$.tickets[*].k_t"));
        keys.addAll(JsonPath.<List<String>>read(held(buyer), "$.tickets[*].k_t"));
        keys.addAll(JsonPath.<List<String>>read(held(c), "$.tickets[*].k_t"));
        String buyerDevice = device(buyer);
        join(buyer, org, true).andExpect(status().isCreated());
        join(c, org, false).andExpect(status().isCreated());
        Caller ops = caller(org, "OPS");
        String push = JsonPath.read(body(call(ops, post("/org/campaigns"), """
                {"channel": "PUSH", "audience": "MEMBERS", "event_id": %d, "title": "Derby samedi",
                 "body": "Réserve ta place"}
                """.formatted(e)).andExpect(status().isCreated())), "$.id");
        call(ops, post("/org/campaigns/" + push + "/send")).andExpect(status().isOk());
        campaigns.runDue();
        messages.deliverDue();
        assertThat(pushes()).filteredOn(p -> p.title().equals("Derby samedi"))
                .containsExactly(new PushSender.Push(buyerDevice, "Derby samedi", "Réserve ta place", "/events/" + e));
        String inbox = inbox(buyer);
        assertThat(JsonPath.<List<String>>read(inbox, "$.messages[*].id")).containsExactly(push);
        assertKeys(inbox, "$.messages[0].organizer", "id", "slug", "name", "crest_url");
        assertThat(JsonPath.<String>read(inbox, "$.messages[0].organizer.id")).isEqualTo(org.toString());
        assertThat(JsonPath.<String>read(inbox, "$.messages[0].organizer.slug")).isEqualTo(slug);
        assertThat(JsonPath.<String>read(inbox, "$.messages[0].organizer.crest_url")).isEqualTo(CREST);
        assertThat(JsonPath.<Integer>read(inbox, "$.messages[0].event_id")).isEqualTo(e);
        assertThat(JsonPath.<List<Object>>read(inbox(c), "$.messages")).isEmpty();

        String info = JsonPath.read(body(call(ops, post("/org/campaigns"), """
                {"channel": "WHATSAPP", "audience": "EVENT_HOLDERS", "event_id": %d, "title": "Portes",
                 "body": "Ouverture des portes à 18 h"}""".formatted(e)).andExpect(status().isCreated())), "$.id");
        call(ops, post("/org/campaigns/" + info + "/send")).andExpect(status().isOk());
        campaigns.runDue();
        assertThat(jdbc.sql("select user_id from fan_messages where campaign_id = ?::uuid").param(info)
                .query(UUID.class).list()).containsExactlyInAnyOrder(user(buyerPhone), user(cPhone), user(bPhone));
        messages.deliverDue();
        assertThat(templates()).filteredOn(t -> t.name().equals("event_info"))
                .extracting(t -> t.phoneE164() + " " + t.locale())
                .containsExactlyInAnyOrder(buyerPhone + " ar", cPhone + " fr", bPhone + " fr");

        // ---------- (6) gate: the buyer and C are scanned; each sees only their own entry ----------
        UUID gate = jdbc.sql("insert into gates (organizer_id, venue_id, code, name) "
                + "select organizer_id, venue_id, 'P4', 'Porte 4' from events where id = ? returning id").param(e)
                .query(UUID.class).single();
        UUID device = jdbc.sql("insert into devices (organizer_id, label, public_key, status) "
                + "values (?, 'PDA', ?, 'ACTIVE') returning id").params(org, UUID.randomUUID().toString().getBytes())
                .query(UUID.class).single();
        UUID staff = jdbc.sql("insert into staff (organizer_id, name, role) values (?, 'Agent', 'GATE') returning id")
                .param(org).query(UUID.class).single();
        Instant entered = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        for (UUID t : List.of(buyerTicket, ticketC)) {
            jdbc.sql("""
                            insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id,
                                               direction, result, token_version, device_time)
                            values (uuidv7(), ?, ?, ?, ?, ?, ?, 'IN', 'OK', 1, ?)""")
                    .params(org, e, device, t, gate, staff, entered.atOffset(ZoneOffset.UTC)).update();
        }
        String buyerLoad = body(load(buyer, e).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(buyerLoad, "$.entries[*].ticket_id"))
                .containsExactly(buyerTicket.toString());
        assertThat(JsonPath.<String>read(buyerLoad, "$.entries[0].gate_code")).isEqualTo("P4");
        assertThat(Instant.parse(JsonPath.read(buyerLoad, "$.entries[0].entered_at"))).isEqualTo(entered);
        String cLoad = body(load(c, e).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(cLoad, "$.entries[*].ticket_id")).containsExactly(ticketC.toString());
        assertThat(JsonPath.<List<Object>>read(body(load(a, e).andExpect(status().isOk())), "$.entries")).isEmpty();
        load(fanToken(randomPhone()), e).andExpect(status().isNotFound());

        // ---------- (7) the event closes: one event attended each, E lit in last_events ----------
        jdbc.sql("update events set starts_at = now() - interval '1 minute' where id = ?").param(e).update();
        call(s.org(), post("/org/events/" + e + "/close")).andExpect(status().isOk());
        for (String fan : List.of(buyer, c)) {
            String loyalty = body(fan(fan, get("/fan/me/membership?organizer=" + org)).andExpect(status().isOk()));
            assertThat(JsonPath.<Integer>read(loyalty, "$.loyalty.events_attended")).isEqualTo(1);
            assertThat(JsonPath.<List<Integer>>read(loyalty, "$.loyalty.last_events[?(@.attended == true)].event_id"))
                    .containsExactly(e);
        }

        String summary = body(call(ops, get("/org/members/summary")).andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(summary, "$.members")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(summary, "$.marketing_push")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(summary, "$.reachable_push")).isEqualTo(1);

        // Every transactional message of the four, exactly; every WhatsApp send, exactly.
        List<UUID> users = phones.stream().map(this::user).toList();
        assertThat(jdbc.sql("""
                        select kind || '/' || channel || ' ' || count(*) from fan_messages
                        where user_id = any(?) and campaign_id is null group by kind, channel""")
                .param(users.toArray(UUID[]::new)).query(String.class).list()).containsExactlyInAnyOrder(
                "SPLIT_INVITE/PUSH 2", "SPLIT_INVITE/WHATSAPP 2", "PAY_CODE/WHATSAPP 1", "ORDER_PAID/PUSH 1",
                "TICKET_RECEIVED/PUSH 1");
        assertThat(templates()).extracting(TicketMessenger.Template::name).containsExactlyInAnyOrder(
                "split_invite", "split_invite", "pay_code", "event_info", "event_info", "event_info");
        // The ticket PDFs: the buyer's, A's and B's on payment, C's on the transfer; none on the re-issue.
        assertThat(ticketPdfs()).extracting(TicketMessenger.Message::ticketNo).containsExactlyInAnyOrder(
                ticketNo(buyerTicket), ticketNo(ticketA), ticketNo(ticketB), ticketNo(ticketC));

        // ---------- (9) no phone, full name or K_t in what fans, staff counts or logs see ----------
        assertThat(keys).hasSize(5).doesNotContainNull().doesNotHaveDuplicates();
        List<String> seen = new ArrayList<>();
        pushes().forEach(p -> seen.add("push " + p.title() + "|" + p.body() + "|" + p.path()));
        templates().forEach(t -> seen.add("template " + t.name() + "|" + String.join("|", t.params())));
        seen.add(inbox(buyer));
        seen.add(inbox(c));
        seen.add(placed);
        seen.add(aAfter);
        for (String fan : List.of(buyer, a, b)) {
            seen.add(body(fan(fan, get("/fan/orders")).andExpect(status().isOk())));
        }
        seen.add(group);
        seen.add(body(fan(buyer, get("/fan/orders/" + buyerOrder)).andExpect(status().isOk())));
        seen.add(body(fan(b, get("/fan/orders/" + orderB)).andExpect(status().isOk())));
        seen.add(summary);
        seen.add(output.getAll());
        assertThat(seen).hasSizeGreaterThan(14);
        List<String> secrets = new ArrayList<>(phones);
        secrets.addAll(phones.stream().map(p -> p.substring(1)).toList()); // without the +
        secrets.addAll(List.of(BUYER_NAME, A_NAME, B_NAME, C_NAME, "عقل", "Alaoui", "Bennani", "Idrissi"));
        secrets.addAll(keys);
        for (String text : seen) {
            for (String secret : secrets) {
                assertThat(text).as(text.length() > 200 ? text.substring(0, 200) + "..." : text).doesNotContain(secret);
            }
        }
    }

    // ---------- helpers ----------

    private UUID user(String phone) {
        return jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();
    }

    /** A fresh push token on the fan's session; returns it. */
    private String device(String token) throws Exception {
        String device = "fcm-" + UUID.randomUUID();
        fan(token, put("/fan/me/push-token"), "{\"token\": \"%s\", \"platform\": \"android\"}".formatted(device))
                .andExpect(status().isNoContent());
        return device;
    }

    private String memberOf(String buyerOrderId, String phone) {
        return jdbc.sql("""
                select o.id::text from orders o join users u on u.id = o.user_id
                where o.group_order_id = ?::uuid and u.phone_e164 = ?""").params(buyerOrderId, phone)
                .query(String.class).single();
    }

    private UUID ticketOf(String orderId) {
        return jdbc.sql("select id from tickets where order_id = ?::uuid and status = 'VALID'").param(orderId)
                .query(UUID.class).single();
    }

    private UUID newTicket(UUID old) {
        return jdbc.sql("select new_ticket_id from ticket_transfers where ticket_id = ?").param(old).query(UUID.class)
                .single();
    }

    private String ticketStatus(UUID id) {
        return jdbc.sql("select status::text from tickets where id = ?").param(id).query(String.class).single();
    }

    private String holderPhone(UUID ticket) {
        return jdbc.sql("select u.phone_e164 from tickets t join users u on u.id = t.holder_user_id where t.id = ?")
                .param(ticket).query(String.class).single();
    }

    /** "order_id channel" per fan_messages row matching {@code where}. */
    private List<String> fanMessages(String where, Object... params) {
        return jdbc.sql("select coalesce(order_id::text, 'null') || ' ' || channel from fan_messages where " + where)
                .params(params).query(String.class).list();
    }

    private ResultActions join(String token, UUID org, boolean push) throws Exception {
        return fan(token, put("/fan/me/membership"), "{\"organizer_id\": \"%s\", \"marketing_push\": %b, "
                .formatted(org, push) + "\"marketing_whatsapp\": false}");
    }

    private String held(String token) throws Exception {
        return body(fan(token, get("/fan/tickets?with_keys=true")).andExpect(status().isOk()));
    }

    private String inbox(String token) throws Exception {
        return body(fan(token, get("/fan/me/inbox")).andExpect(status().isOk()));
    }

    private ResultActions load(String token, int eventId) throws Exception {
        return fan(token, get("/fan/gates/" + eventId + "/load"));
    }

    private long paid(Sale s) throws Exception {
        return JsonPath.<Integer>read(body(call(s.org(), get("/org/events/" + s.eventId() + "/sales"))
                .andExpect(status().isOk())), "$.paid");
    }

    private long liveSold(Sale s) throws Exception {
        return JsonPath.<Integer>read(body(call(s.org(), get("/org/events/" + s.eventId() + "/live"))
                .andExpect(status().isOk())), "$.sold");
    }

    private int latestVersion(int eventId) {
        return jdbc.sql("select max(version) from manifests where event_id = ?").param(eventId).query(Integer.class)
                .single();
    }

    /** Ages the latest version past the 5 min rebuild gap, then builds the next one. */
    private int rebuild(int eventId, UUID org) {
        jdbc.sql("update manifests set built_at = built_at - interval '10 minutes' where event_id = ?").param(eventId)
                .update();
        return manifestService.build(org, eventId).orElseThrow();
    }

    /** A whole manifest T line (ManifestPayload): id, zone, status, initials. */
    private static String tLine(UUID ticket, Sale s, String status, String initials) {
        return String.join("\t", "T", ticket.toString(), Integer.toString(s.zoneId()), status, initials);
    }

    private static String ticketNo(UUID id) {
        String hex = id.toString().substring(28).toUpperCase();
        return hex.substring(0, 4) + " " + hex.substring(4);
    }

    private List<String> tLines(int eventId, int version) {
        UUID org = jdbc.sql("select organizer_id from events where id = ?").param(eventId).query(UUID.class).single();
        return manifests.find(org, eventId, version).orElseThrow().payload().lines().filter(l -> l.startsWith("T\t"))
                .toList();
    }

    private List<PushSender.Push> pushes() {
        var captor = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<PushSender.Push> pushesTo(String token) {
        return pushes().stream().filter(p -> p.token().equals(token)).toList();
    }

    private List<TicketMessenger.Message> ticketPdfs() {
        var captor = ArgumentCaptor.forClass(TicketMessenger.Message.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<TicketMessenger.Template> templates() {
        var captor = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }
}
