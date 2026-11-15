package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.PushSender;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BE-49: docs/04-api.md §Waitlist Notify and Retention (the job at a fixed clock), §Notifications WAITLIST_OPEN (copy,
 * push path, WhatsApp parameters, the stale rule); plan lot-5 Q176-Q179. The job serves every organizer, so
 * assertions are scoped to each test's event.
 */
class WaitlistJobTest extends QueueFixture {

    @Autowired
    WaitlistService waitlist;

    @Autowired
    FanMessageService messages;

    @Autowired
    OrderService orderService;

    /** Rows other test classes left due are sent first (to the mocks), so each test sees only its own. */
    @BeforeEach
    void drain() {
        messages.deliverDue(Integer.MAX_VALUE);
        clearInvocations(pushSender, ticketMessenger);
    }

    private ResultActions joinWaitlist(Fan f, int event) throws Exception {
        return mvc.perform(put("/fan/waitlist/" + event).header("Authorization", "Bearer " + f.token()));
    }

    /** The event's entries notified, oldest first. */
    private List<UUID> notified(int event) {
        return jdbc.sql("""
                        select user_id from waitlist_entries where event_id = ? and notified_at is not null
                        order by joined_at, id""").param(event).query(UUID.class).list();
    }

    private List<Map<String, Object>> rows(int event) {
        return jdbc.sql("""
                        select organizer_id, user_id, channel::text as channel, kind::text as kind, order_id,
                               ticket_id, campaign_id, status::text as status, last_error
                        from fan_messages where event_id = ? order by user_id, channel""")
                .param(event).query().listOfRows();
    }

    private String phone(UUID user) {
        return jdbc.sql("select phone_e164 from users where id = ?").param(user).query(String.class).single();
    }

    private List<PushSender.Push> pushes() {
        var captor = ArgumentCaptor.forClass(PushSender.Push.class);
        verify(pushSender, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    private List<TicketMessenger.Template> templates() {
        var captor = ArgumentCaptor.forClass(TicketMessenger.Template.class);
        verify(ticketMessenger, atLeast(0)).send(captor.capture());
        return captor.getAllValues();
    }

    // ---------- notify ----------

    @Test
    void releasedPlacesNotifyTheOldestOncePerCooldown() throws Exception {
        Room s = soldOut(10);
        Fan first = fan();
        List<UUID> users = new ArrayList<>(List.of(first.id()));
        for (int i = 1; i < 10; i++) {
            users.add(user());
        }
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        for (int i = 0; i < 10; i++) {
            assertThat(waitlist.join(users.get(i), s.event()).created()).isTrue();
            jdbc.sql("update waitlist_entries set joined_at = ? where event_id = ? and user_id = ?")
                    .params(Timestamp.from(base.plusSeconds(i)), s.event(), users.get(i)).update();
        }
        // One 3-place order of the 10 sold expires: 3 places come back.
        jdbc.sql("""
                        with o as (
                            insert into orders (organizer_id, event_id, user_id, channel, status, total_mad,
                                                reserved_until)
                            values (?, ?, ?, 'APP', 'RESERVED', 15000, now() - interval '1 minute')
                            returning id, organizer_id)
                        insert into order_items (organizer_id, order_id, price_category_id, qty, unit_price_mad, holders)
                        select organizer_id, id, ?::uuid, 3, 5000,
                               '[{"name": "Fan Test"}, {"name": "Fan Test"}, {"name": "Fan Test"}]' from o""")
                .params(s.admin().org(), s.event(), user(), s.price()).update();
        orderService.expireDue(10);

        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        waitlist.notifyAt(t);
        assertThat(notified(s.event())).containsExactlyElementsOf(users.subList(0, 3));
        assertThat(jdbc.sql("""
                        select distinct notified_at from waitlist_entries
                        where event_id = ? and notified_at is not null""")
                .param(s.event()).query(Timestamp.class).single().toInstant()).isEqualTo(t);
        List<Map<String, Object>> rows = rows(s.event());
        assertThat(rows).hasSize(6).allSatisfy(r -> {
            assertThat(r).containsEntry("kind", "WAITLIST_OPEN").containsEntry("status", "PENDING")
                    .containsEntry("organizer_id", s.admin().org()).containsEntry("order_id", null)
                    .containsEntry("ticket_id", null).containsEntry("campaign_id", null);
            assertThat(users.subList(0, 3)).contains((UUID) r.get("user_id"));
        });
        assertThat(rows).extracting(r -> r.get("channel"))
                .containsExactlyInAnyOrder("PUSH", "PUSH", "PUSH", "WHATSAPP", "WHATSAPP", "WHATSAPP");

        setSold(s.price(), 6); // 4 places, but inside the 10 min cooldown: nothing
        waitlist.notifyAt(t.plus(Duration.ofMinutes(5)));
        assertThat(notified(s.event())).hasSize(3);
        assertThat(rows(s.event())).hasSize(6);

        setSold(s.price(), 9); // after 10 min, 1 place: the 4th
        waitlist.notifyAt(t.plus(Duration.ofMinutes(10)));
        assertThat(notified(s.event())).containsExactlyElementsOf(users.subList(0, 4));
        assertThat(rows(s.event())).hasSize(8);

        setSold(s.price(), 10); // sold out again: a notified fan re-joins and goes to the back
        String back = body(joinWaitlist(first, s.event()).andExpect(status().isCreated()));
        assertThat((Object) JsonPath.read(back, "$.entry.notified_at")).isNull();
        setSold(s.price(), 4); // 6 places: the 6 still waiting before the first fan
        waitlist.notifyAt(t.plus(Duration.ofMinutes(20)));
        assertThat(notified(s.event())).containsExactlyInAnyOrderElementsOf(users.subList(1, 10));
        assertThat(jdbc.sql("select notified_at is null from waitlist_entries where event_id = ? and user_id = ?")
                .params(s.event(), first.id()).query(Boolean.class).single()).isTrue();
    }

    @Test
    void aLivePhaseOrAClosedSaleNotifiesNothing() throws Exception {
        Room r = room("{}", 2);
        jdbc.sql("update events set sale_starts_at = now() - interval '1 hour' where id = ?").param(r.event()).update();
        setSold(r.price(), 2);
        jdbc.sql("update waiting_rooms set status = 'ENDED' where id = ?").param(r.id()).update();
        UUID a = user();
        UUID b = user();
        waitlist.join(a, r.event());
        waitlist.join(b, r.event());
        setSold(r.price(), 0);
        Instant t = Instant.now();

        jdbc.sql("update waiting_rooms set status = 'OPEN' where id = ?").param(r.id()).update();
        waitlist.notifyAt(t);
        assertThat(notified(r.event())).isEmpty();
        jdbc.sql("update waiting_rooms set status = 'ENDED' where id = ?").param(r.id()).update();

        setStatus(r.event(), "GATES_OPEN");
        waitlist.notifyAt(t);
        assertThat(notified(r.event())).isEmpty();
        setStatus(r.event(), "ON_SALE");
        waitlist.notifyAt(Instant.parse(CUTOFF)); // refund_cutoff_at: sales closed
        assertThat(notified(r.event())).isEmpty();
        assertThat(rows(r.event())).isEmpty();

        waitlist.notifyAt(t);
        assertThat(notified(r.event())).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void entriesArePurged30DaysAfterTheEventStarts() throws Exception {
        Room s = soldOut(1); // starts 2099-05-01T19:00Z
        waitlist.join(user(), s.event());
        waitlist.purge(Instant.parse("2099-05-31T18:59:59Z"));
        assertThat(jdbc.sql("select count(*) from waitlist_entries where event_id = ?").param(s.event())
                .query(Integer.class).single()).isOne();
        waitlist.purge(Instant.parse("2099-05-31T19:00:00Z"));
        assertThat(jdbc.sql("select count(*) from waitlist_entries where event_id = ?").param(s.event())
                .query(Integer.class).single()).isZero();
    }

    // ---------- send ----------

    @Test
    void theNoticeOpensTheEventInTheFansLanguageWithATitleAndALinkOnly() throws Exception {
        Room s = soldOut(3);
        Map<String, Fan> fans = Map.of("fr", fan(), "ar", fan(), "en", fan());
        Map<String, String> tokens = new HashMap<>();
        for (var e : fans.entrySet()) {
            Fan f = e.getValue();
            mvc.perform(patch("/fan/me").header("Authorization", "Bearer " + f.token())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"locale\": \"%s\"}".formatted(e.getKey())))
                    .andExpect(status().isOk());
            String token = "fcm-" + UUID.randomUUID();
            mvc.perform(put("/fan/me/push-token").header("Authorization", "Bearer " + f.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"token\": \"%s\", \"platform\": \"android\"}".formatted(token)))
                    .andExpect(status().isNoContent());
            tokens.put(e.getKey(), token);
            joinWaitlist(f, s.event()).andExpect(status().isCreated());
        }
        setSold(s.price(), 0);
        waitlist.notifyAt(Instant.now());
        messages.deliverDue(Integer.MAX_VALUE);

        assertThat(rows(s.event())).hasSize(6).allSatisfy(r -> assertThat(r).containsEntry("status", "SENT"));
        Map<String, List<String>> copy = Map.of("fr", List.of("Des places se sont libérées", "Match"),
                "ar", List.of("تحررو شي بلايص", "\u2068Match\u2069"), "en", List.of("Places have opened up", "Match"));
        String path = "/events/" + s.event();
        String link = "https://shop.test/events/" + s.event();
        StringBuilder all = new StringBuilder();
        for (var e : fans.entrySet()) {
            String phone = phone(e.getValue().id());
            assertThat(pushes().stream().filter(p -> p.token().equals(tokens.get(e.getKey()))).toList())
                    .singleElement().satisfies(p -> {
                        assertThat(p.title()).isEqualTo(copy.get(e.getKey()).get(0));
                        assertThat(p.body()).isEqualTo(copy.get(e.getKey()).get(1));
                        assertThat(p.path()).isEqualTo(path);
                        all.append(p.title()).append('|').append(p.body()).append('|').append(p.path()).append('|');
                    });
            assertThat(templates().stream().filter(t -> t.phoneE164().equals(phone)).toList())
                    .singleElement().satisfies(t -> {
                        assertThat(t.name()).isEqualTo("waitlist_open");
                        assertThat(t.locale()).isEqualTo(e.getKey());
                        assertThat(t.params()).containsExactly("Match", link); // no isolates in WhatsApp parameters
                        all.append(t.params()).append('|');
                    });
        }
        for (Fan f : fans.values()) { // no phone in any payload
            assertThat(all.toString()).doesNotContain(phone(f.id())).doesNotContain(phone(f.id()).substring(1));
        }
    }

    @Test
    void soldOutAgainSalesClosedOrLeftIsStale() throws Exception {
        Room s = soldOut(2);
        Room g = soldOut(1);
        Room h = soldOut(1);
        UUID a = user();
        UUID b = user();
        UUID c = user();
        waitlist.join(a, s.event());
        waitlist.join(b, g.event());
        waitlist.join(c, h.event());
        setSold(s.price(), 1);
        setSold(g.price(), 0);
        setSold(h.price(), 0);
        waitlist.notifyAt(Instant.now());
        for (Room r : List.of(s, g, h)) {
            assertThat(rows(r.event())).hasSize(2);
        }

        setSold(s.price(), 2); // sold out again before the send
        setStatus(g.event(), "GATES_OPEN"); // no longer on sale
        waitlist.leave(c, h.event()); // left the list before the send
        messages.deliverDue(Integer.MAX_VALUE);

        for (Room r : List.of(s, g, h)) {
            assertThat(rows(r.event())).allSatisfy(row -> assertThat(row).containsEntry("status", "FAILED")
                    .containsEntry("last_error", "STALE"));
        }
        assertThat(templates()).noneMatch(t -> List.of(a, b, c).contains(t.userId()));
    }

    /** The cooldown is read from the outbox: the only notified fan leaving doesn't reopen the event's notices. */
    @Test
    void aLeaveDoesNotResetTheCooldown() throws Exception {
        Room s = soldOut(1);
        UUID a = user();
        UUID b = user();
        waitlist.join(a, s.event());
        waitlist.join(b, s.event());
        jdbc.sql("update waitlist_entries set joined_at = joined_at - interval '1 minute' where event_id = ? "
                + "and user_id = ?").params(s.event(), a).update();
        setSold(s.price(), 0);
        Instant t = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        waitlist.notifyAt(t);
        assertThat(notified(s.event())).containsExactly(a);

        waitlist.leave(a, s.event()); // no notified entry is left for the event
        waitlist.notifyAt(t.plus(Duration.ofMinutes(2))); // the place is still free 2 min later: inside the cooldown
        waitlist.notifyAt(t.plus(Duration.ofMinutes(10)).minusSeconds(1));
        assertThat(notified(s.event())).isEmpty();
        assertThat(rows(s.event())).hasSize(2);

        waitlist.notifyAt(t.plus(Duration.ofMinutes(10)));
        assertThat(notified(s.event())).containsExactly(b);
        assertThat(rows(s.event())).hasSize(4);
    }
}
