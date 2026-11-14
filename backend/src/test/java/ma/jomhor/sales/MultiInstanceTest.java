package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import ma.jomhor.sales.rooms.application.RoomTicks;
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

    @BeforeAll
    static void startB() {
        b = instance();
    }

    @AfterAll
    static void stopB() {
        b.close();
    }

    /** {@code onA} and {@code onB} started together; their results in that order. */
    private static <T> List<T> both(Callable<T> onA, Callable<T> onB) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<T>> runs = List.of(pool.submit(() -> {
                start.await();
                return onA.call();
            }), pool.submit(() -> {
                start.await();
                return onB.call();
            }));
            start.countDown();
            return List.of(runs.get(0).get(60, TimeUnit.SECONDS), runs.get(1).get(60, TimeUnit.SECONDS));
        }
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
}
