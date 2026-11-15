package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import ma.jomhor.crm.application.CampaignService;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.notifications.application.FanMessageService;
import ma.jomhor.notifications.application.TicketMessenger;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.products.application.PassTicketService;
import ma.jomhor.sales.rooms.application.RoomTicks;
import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Plan lot-5 BE-43: every job is safe with N instances. This class's context is instance A, {@link #b} a second one
 * on the same Postgres; each job runs on both at once. Jobs serve every organizer, so leftovers of earlier test
 * classes in the shared database are pushed out of the way where they would change a count.
 */
class MultiInstanceTest extends QueueFixture {

    static ConfigurableApplicationContext b;

    @Autowired
    OrderService orderService;

    @Autowired
    FanMessageService messages;

    @Autowired
    CampaignService campaigns;

    @Autowired
    ManifestService manifests;

    @Autowired
    WaitlistService waitlist;

    @Autowired
    PassTicketService passTickets;

    @BeforeAll
    static void startB() {
        b = instance();
    }

    @AfterAll
    static void stopB() {
        b.close();
    }

    private void tickBoth(String at) throws Exception {
        RoomTicks onB = b.getBean(RoomTicks.class);
        both(() -> {
            ticks.tick(Instant.parse(at));
            return 0;
        }, () -> {
            onB.tick(Instant.parse(at));
            return 0;
        });
        memo.invalidate();
    }

    @Test
    void twoExpiryPassesReleaseEachOrderOnce() throws Exception {
        Room r = room("{}", 300);
        UUID buyer = user();
        jdbc.sql("""
                        with o as (
                            insert into orders (organizer_id, event_id, user_id, channel, status, total_mad,
                                                reserved_until)
                            select ?, ?, ?, 'APP', 'RESERVED', 5000, now() - interval '1 minute'
                            from generate_series(1, 200)
                            returning id, organizer_id)
                        insert into order_items (organizer_id, order_id, price_category_id, qty, unit_price_mad, holders)
                        select organizer_id, id, ?::uuid, 1, 5000, '[{"name": "Fan Test"}]' from o""")
                .params(r.admin().org(), r.event(), buyer, r.price()).update();
        setSold(r.price(), 200);

        OrderService onB = b.getBean(OrderService.class);
        both(() -> orderService.expireDue(10), () -> onB.expireDue(10));

        assertThat(jdbc.sql("select count(*) from orders where event_id = ? and status = 'EXPIRED'")
                .param(r.event()).query(Integer.class).single()).isEqualTo(200);
        assertThat(jdbc.sql("select sold from price_categories where id = ?::uuid").param(r.price())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void theRoomTickOnBothDrawsOnceAndAdmitsOncePerInterval() throws Exception {
        Room r = room("{\"admission_rate_per_min\": 600}");
        tickBoth(OPENS);
        List<UUID> users = IntStream.range(0, 150).mapToObj(i -> {
            UUID u = user();
            queue.join(u, r.event(), "device-" + UUID.randomUUID(), "10.30.0." + (i % 250));
            return u;
        }).toList();

        tickBoth(SALE);
        assertThat(jdbc.sql("select count(*) from audit_log where action = 'room.draw' and target = ?")
                .param(String.valueOf(r.event())).query(Integer.class).single()).isOne();
        assertThat(users.stream().map(u -> (Integer) entry(r.id(), u).get("rank")))
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 150).boxed().toList());

        tickBoth("2099-04-01T10:00:10Z"); // 600/min × 10 s
        assertThat(roomRow(r.id())).containsEntry("admitted_through", 100);
        ticks.tick(Instant.parse("2099-04-01T10:00:10Z")); // one after the other, same second: nothing more
        b.getBean(RoomTicks.class).tick(Instant.parse("2099-04-01T10:00:10Z"));
        assertThat(roomRow(r.id())).containsEntry("admitted_through", 100);
        tickBoth("2099-04-01T10:00:15Z");
        assertThat(roomRow(r.id())).containsEntry("admitted_through", 150);
    }

    /** BE-48: both instances tick at phase 2's opens_at: phase 1 ends once, with one audit, and phase 2 opens. */
    @Test
    void theNextPhaseEndsThePreviousOnceOnBoth() throws Exception {
        Room r = room("{}");
        UUID two = phase(r, 2, "{\"pre_queue_min\": 0, \"starts_at\": \"2099-04-02T10:00:00Z\"}");
        tickBoth(OPENS);
        tickBoth(SALE);
        tickBoth("2099-04-02T10:00:00Z");
        assertThat(roomRow(r.id()).get("status")).isEqualTo("ENDED");
        assertThat(roomRow(two).get("status")).isEqualTo("OPEN");
        tickBoth("2099-04-02T10:00:01Z");
        assertThat(jdbc.sql("""
                        select count(*) from audit_log where action = 'room.end' and target = ?
                          and payload ->> 'reason' = 'NEXT_PHASE'""")
                .param(String.valueOf(r.event())).query(Integer.class).single()).isOne();
    }

    /** BE-49: both instances run the waitlist job at once, twice: the R oldest are notified once, with 2 rows each. */
    @Test
    void theWaitlistNoticeGoesOutOncePerWait() throws Exception {
        Room s = soldOut(5);
        List<UUID> users = IntStream.range(0, 8).mapToObj(i -> user()).toList();
        for (int i = 0; i < users.size(); i++) {
            waitlist.join(users.get(i), s.event());
            jdbc.sql("update waitlist_entries set joined_at = '2026-01-01'::timestamptz + ? * interval '1 second' "
                    + "where event_id = ? and user_id = ?").params(i, s.event(), users.get(i)).update();
        }
        setSold(s.price(), 2); // R = 3
        Instant t = Instant.now();
        WaitlistService onB = b.getBean(WaitlistService.class);
        for (Instant at : List.of(t, t.plusSeconds(60))) { // the second run is inside the cooldown
            both(() -> {
                waitlist.notifyAt(at);
                return 0;
            }, () -> {
                onB.notifyAt(at);
                return 0;
            });
        }
        assertThat(jdbc.sql("""
                        select user_id from waitlist_entries where event_id = ? and notified_at is not null
                        order by joined_at""").param(s.event()).query(UUID.class).list())
                .containsExactlyElementsOf(users.subList(0, 3));
        assertThat(jdbc.sql("select count(*) from fan_messages where event_id = ? and kind = 'WAITLIST_OPEN'")
                .param(s.event()).query(Integer.class).single()).isEqualTo(6);
    }

    @Test
    void fanMessagesOnBothSendAtMostOneRunsCapPerPeriod() throws Exception {
        UUID org = organizer("Multi Messages FC");
        staff(org, randomPhone(), "ADMIN", true);
        UUID campaign = jdbc.sql("""
                        insert into campaigns (organizer_id, channel, audience, title, body, status, created_by)
                        select organizer_id, 'WHATSAPP', 'MEMBERS', 'Titre', 'Corps', 'SENDING', id from staff
                        where organizer_id = ? returning id""").param(org).query(UUID.class).single();
        for (int i = 0; i < 300; i++) {
            UUID u = user();
            jdbc.sql("""
                            insert into memberships (organizer_id, user_id, number, marketing_whatsapp)
                            values (?, ?, ?, true)""").params(org, u, i + 1).update();
            jdbc.sql("""
                            insert into fan_messages (organizer_id, user_id, channel, kind, campaign_id)
                            values (?, ?, 'WHATSAPP', 'CAMPAIGN', ?)""").params(org, u, campaign).update();
        }
        jdbc.sql("""
                        update fan_messages set next_attempt_at = '9999-01-01'
                        where status = 'PENDING' and campaign_id is distinct from ?""").param(campaign).update();

        // Rate 20/s, period 5 s: one run sends at most 100. A's run is held at its first send, the claim lock taken.
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            sending.countDown();
            release.await();
            return null;
        }).when(ticketMessenger).send(any(TicketMessenger.Template.class));
        FanMessageService onB = b.getBean(FanMessageService.class);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> onA = pool.submit(() -> messages.deliverDue());
            assertThat(sending.await(30, TimeUnit.SECONDS)).isTrue();
            int sentByB = onB.deliverDue();
            release.countDown();
            assertThat(onA.get(60, TimeUnit.SECONDS)).isEqualTo(100);
            assertThat(sentByB).isZero();
        }
        verify(ticketMessenger, times(100)).send(any(TicketMessenger.Template.class));
        verifyNoInteractions(b.getBean(TicketMessenger.class));
        assertThat(statusCount(campaign, "SENT")).isEqualTo(100);

        assertThat(onB.deliverDue()).isEqualTo(100); // the next period: whichever instance runs it
        assertThat(statusCount(campaign, "SENT")).isEqualTo(200);
    }

    private int statusCount(UUID campaign, String status) {
        return jdbc.sql("select count(*) from fan_messages where campaign_id = ? and status = ?::delivery_status")
                .params(campaign, status).query(Integer.class).single();
    }

    @Test
    void oneCampaignIsSnapshottedOnce() throws Exception {
        UUID org = organizer("Multi Campaign FC");
        staff(org, randomPhone(), "ADMIN", true);
        for (int i = 0; i < 3; i++) {
            jdbc.sql("""
                            insert into memberships (organizer_id, user_id, number, marketing_whatsapp)
                            values (?, ?, ?, true)""").params(org, user(), i + 1).update();
        }
        UUID campaign = jdbc.sql("""
                        insert into campaigns (organizer_id, channel, audience, title, body, status, scheduled_at,
                                               created_by)
                        select organizer_id, 'WHATSAPP', 'MEMBERS', 'Titre', 'Corps', 'SCHEDULED',
                               now() - interval '1 minute', id
                        from staff where organizer_id = ? returning id""").param(org).query(UUID.class).single();
        jdbc.sql("update campaigns set scheduled_at = '9999-01-01' where status = 'SCHEDULED' and id <> ?")
                .param(campaign).update();

        CampaignService onB = b.getBean(CampaignService.class);
        List<Integer> started = both(() -> campaigns.runDue(), () -> onB.runDue());

        assertThat(started.get(0) + started.get(1)).isOne();
        assertThat(jdbc.sql("select count(*) from fan_messages where campaign_id = ?").param(campaign)
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("select status::text from campaigns where id = ?").param(campaign)
                .query(String.class).single()).isEqualTo("SENDING");
    }

    @Test
    void aDueManifestVersionIsBuiltOnce() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "NORD");
        int event = event(a, venue, Instant.now().plusSeconds(3600).toString());
        price(a, event, zone, "Cat 1", 100);
        setStatus(event, "ON_SALE");

        ManifestService onB = b.getBean(ManifestService.class);
        both(() -> manifests.buildDue(), () -> onB.buildDue());

        assertThat(jdbc.sql("select count(*) from manifests where event_id = ?").param(event)
                .query(Integer.class).single()).isOne();
    }

    /**
     * BE-55: both instances run the pass tickets job at once, twice, over 600 ACTIVE passes (two batches of 500): each
     * pass gets exactly one ticket order and one ticket for the event, and the PASS category's sold is 600.
     */
    @Test
    void eachPassTicketIsIssuedOnce() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "EST", 600);
        String series = JsonPath.read(body(call(a, post("/org/series"),
                "{\"name\": \"Saison\", \"starts_on\": \"2026-08-01\", \"ends_on\": \"2099-06-30\"}")
                .andExpect(status().isCreated())), "$.id");
        int event = JsonPath.read(body(call(a, post("/org/events"), """
                {"venue_id": "%s", "event_type": "FOOTBALL_MATCH", "title": "Match", "starts_at": "2099-05-01T19:00:00Z",
                 "series_id": "%s"}""".formatted(venue, series)).andExpect(status().isCreated())), "$.id");
        String pass = JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": 0, "quota": 600, "audience": "PASS"}""".formatted(zone))
                .andExpect(status().isCreated())), "$.id");
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        UUID product = jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, sold, series_id,
                                              zone_id)
                        values (?, 'PASS', 'Abonnement', 150000, '2099-06-30T22:00:00Z', 600, 600, ?::uuid, ?)
                        returning id""").params(a.org(), series, zone).query(UUID.class).single();
        for (int i = 0; i < 600; i++) {
            jdbc.sql("insert into passes (organizer_id, product_id, series_id, user_id, holder_name, status)"
                    + " values (?, ?, ?::uuid, ?, 'Fan Test', 'ACTIVE')").params(a.org(), product, series, user())
                    .update();
        }

        PassTicketService onB = b.getBean(PassTicketService.class);
        Instant now = Instant.parse("2099-01-01T00:00:00Z");
        for (int run = 0; run < 2; run++) {
            both(() -> {
                passTickets.runAt(now);
                return 0;
            }, () -> {
                onB.runAt(now);
                return 0;
            });
        }
        assertThat(jdbc.sql("""
                        select count(distinct o.pass_id) || '/' || count(o.id) || '/' || count(t.id)
                        from orders o left join tickets t on t.order_id = o.id
                        where o.event_id = ? and o.pass_id is not null""")
                .param(event).query(String.class).single()).isEqualTo("600/600/600");
        assertThat(sold(pass)).isEqualTo(600);
    }

    private int sold(String priceId) {
        return jdbc.sql("select sold from price_categories where id = ?::uuid").param(priceId).query(Integer.class)
                .single();
    }
}
