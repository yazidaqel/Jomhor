package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import ma.jomhor.payments.PaymentTest;
import ma.jomhor.sales.application.OrderService;
import ma.jomhor.sales.rooms.application.RoomTicks;
import ma.jomhor.sales.rooms.application.RoomTokens;
import ma.jomhor.tenancy.application.AuthService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Plan lot-5 §5.5, CI column (BE-45): the on-sale on two instances of the app on one Postgres (BE-43's fixture: this
 * class's context is A, {@link #b} a second one, each driven through its own MockMvc). 300 fans, 3 zones with a
 * 120-place hot category, admission 600/min on a stepped fixed clock (5 s a step), 30 % of holds abandoned and 5 % of
 * cards refused. Within a step the tick on both instances, the expiry of the previous step's holds on both, and the
 * fans' turns (poll on one instance, order and pay on the other) all run at once; B is closed in the middle of step 2
 * with calls in flight (one order surely so: held on a row lock of the test's, on a fourth category no turn buys), and
 * a turn whose call to B fails goes on from there on A in the next step. Then check.sql,
 * every 201 an order and every order PAID or EXPIRED, sold = paid places = tickets, one draw, and a trigger that logs
 * any move back of last_tick_at, admitted_through or ranked_count. Also the per-IP OTP and join counts shared by both,
 * and the production tick path (RoomTicks.run: the poll sweep and the clock_timestamp() read) on both at once on the
 * real clock. B is closed by the sale, so the methods run in order; the class has 2 min in all.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class OnSaleTwoInstanceTest extends QueueFixture {

    private static final Duration BUDGET = Duration.ofMinutes(2);

    static long startedAt;
    static ConfigurableApplicationContext b;
    static MockMvc mvcB;

    @Autowired
    JwtEncoder jwt;

    @Autowired
    OrderService orderService;

    @Autowired
    RoomTokens tokens;

    @Autowired
    DataSource dataSource;

    @BeforeAll
    static void startB() {
        startedAt = System.nanoTime();
        b = instance("--otp.per-ip.fan.sends-10m=5"); // A's per-IP OTP limit is off (test config): B counts A's sends
        mvcB = MockMvcBuilders.webAppContextSetup((WebApplicationContext) b)
                .addFilters(b.getBean("springSecurityFilterChain", Filter.class)).build();
    }

    @AfterAll
    static void stopB() {
        b.close();
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThanOrEqualTo(BUDGET);
    }

    record Res(int status, String body) {
        <T> T read(String path) {
            return JsonPath.read(body, path);
        }
    }

    private static Res send(MockMvc m, MockHttpServletRequestBuilder req) throws Exception {
        var r = m.perform(req).andReturn().getResponse();
        return new Res(r.getStatus(), r.getContentAsString());
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder req, String ip) {
        return req.with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }

    /** A fan with a live session and its fan JWT (as AuthService signs it; one secret for both instances). */
    private Fan mintFan() {
        UUID user = user();
        UUID sid = jdbc.sql("insert into fan_sessions (user_id, expires_at) values (?, now() + interval '1 day') "
                + "returning id").param(user).query(UUID.class).single();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(user.toString()).audience(List.of(AuthService.FAN)).issuedAt(now)
                .expiresAt(now.plus(Duration.ofDays(1))).claim(AuthService.SID, sid.toString()).build();
        return new Fan(user, jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue());
    }

    private static Res join(MockMvc m, Fan f, int event, String ip) throws Exception {
        return send(m, from(post("/fan/queue").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\": %d, \"device_id\": \"device-%s\"}".formatted(event, f.id())), ip));
    }

    /** An ON_SALE event selling from {@code sale}, its room (600/min, pre-queue 30 min); the room id. */
    private UUID roomFor(Caller a, int event, String sale) throws Exception {
        call(a, patch("/org/events/" + event), "{\"sale_starts_at\": \"%s\"}".formatted(sale))
                .andExpect(status().isOk());
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        call(a, put("/org/events/" + event + "/rooms/1"), "{\"admission_rate_per_min\": 600}")
                .andExpect(status().isCreated());
        return jdbc.sql("select id from waiting_rooms where event_id = ? and phase = 1").param(event).query(UUID.class).single();
    }

    private int draws(int event) {
        return jdbc.sql("select count(*) from audit_log where action = 'room.draw' and target = ?")
                .param(String.valueOf(event)).query(Integer.class).single();
    }

    /** From here on, every update of the room that moves last_tick_at, admitted_through or ranked_count back. */
    private void guard(UUID room) {
        jdbc.sql("create table be45_moved_back (old_row jsonb, new_row jsonb)").update();
        jdbc.sql("""
                create function be45_moved_back() returns trigger language plpgsql as $$
                begin insert into be45_moved_back values (to_jsonb(old), to_jsonb(new)); return new; end $$""")
                .update();
        jdbc.sql("""
                create trigger be45_moved_back after update on waiting_rooms for each row
                when (new.id = '%s' and (new.last_tick_at < old.last_tick_at
                      or new.admitted_through < old.admitted_through or new.ranked_count < old.ranked_count))
                execute function be45_moved_back()""".formatted(room)).update();
    }

    private List<Map<String, Object>> movedBack() {
        return jdbc.sql("select * from be45_moved_back").query().listOfRows();
    }

    private void unguard() {
        jdbc.sql("drop trigger if exists be45_moved_back on waiting_rooms").update();
        jdbc.sql("drop function if exists be45_moved_back()").update();
        jdbc.sql("drop table if exists be45_moved_back").update();
    }

    @Test
    @Order(1)
    void theProductionTickOnBothNeverMovesTheRoomBack() throws Exception {
        // run() ticks every live room on the real clock: earlier classes' rooms are ended out of the way.
        jdbc.sql("update waiting_rooms set status = 'ENDED' where status <> 'ENDED'").update();
        Caller a = admin();
        String venue = venue(a, "Stade");
        int event = event(a, venue);
        price(a, event, zone(a, venue, "NORD"), "Cat 1", 100);
        // Its pre-queue opened 30 min ago; the draw is 5 s away.
        UUID room = roomFor(a, event, Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS).toString());
        try {
            guard(room);
            for (int i = 0; i < 20; i++) {
                Fan f = mintFan();
                assertThat(join(i % 2 == 0 ? mvc : mvcB, f, event, "198.19.0." + (i + 1)).status()).isEqualTo(201);
            }
            // Both run the job back to back (no 1 s pause between runs) through the draw until all 20 are admitted.
            AtomicBoolean stop = new AtomicBoolean();
            RoomTicks onB = b.getBean(RoomTicks.class);
            try (var pool = Executors.newFixedThreadPool(2)) {
                List<Future<Integer>> runs = new ArrayList<>();
                for (RoomTicks t : List.of(ticks, onB)) {
                    runs.add(pool.submit(() -> {
                        int n = 0;
                        while (!stop.get()) {
                            t.run();
                            n++;
                        }
                        return n;
                    }));
                }
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                while (!Integer.valueOf(20).equals(roomRow(room).get("admitted_through"))
                        && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                stop.set(true);
                for (Future<Integer> run : runs) {
                    assertThat(run.get(30, TimeUnit.SECONDS)).isPositive();
                }
            }
            assertThat(roomRow(room)).containsEntry("status", "OPEN").containsEntry("ranked_count", 20)
                    .containsEntry("admitted_through", 20);
            assertThat(draws(event)).isOne();
            assertThat(movedBack()).isEmpty();
        } finally {
            unguard();
            call(a, post("/org/events/" + event + "/rooms/1/end")).andExpect(status().isOk());
        }
    }

    @Test
    @Order(2)
    void perIpOtpSendsAreCountedForBothInstances() throws Exception {
        String ip = "198.51.100.45";
        for (MockMvc m : List.of(mvc, mvcB, mvc, mvcB, mvc)) {
            Res r = send(m, from(post("/fan/auth/otp").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\": \"%s\"}".formatted(randomPhone())), ip));
            assertThat(r.status()).isEqualTo(202);
        }
        // B sent 2 of this IP's 5 codes; its limit is 5.
        assertThat(send(mvcB, from(post("/fan/auth/otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\": \"%s\"}".formatted(randomPhone())), ip)).status()).isEqualTo(429);
    }

    // ---------- the sale ----------

    /** Fan i's turn: 1-4 places, half in the hot category; i % 20 = 13..18 abandon the hold, 19's card is refused. */
    private record Plan(String price, int qty, boolean abandon, boolean refused) {}

    /** One fan's progress: a turn that failed on B goes on from the step it reached. */
    private static final class Turn {
        final Fan fan;
        final Plan plan;
        final int index;
        String queueToken;
        String admission;
        UUID order;
        Map<String, String> callback;
        volatile boolean done;

        Turn(Fan fan, Plan plan, int index, String queueToken) {
            this.fan = fan;
            this.plan = plan;
            this.index = index;
            this.queueToken = queueToken;
        }
    }

    /** A call to B that failed once B was closing (it threw or answered 5xx): the turn goes on in the next step. */
    private static final class BDown extends RuntimeException {}

    private final Map<UUID, Integer> placed = new ConcurrentHashMap<>(); // order id (a 201) -> places
    private final Set<UUID> released = ConcurrentHashMap.newKeySet(); // abandoned and refused orders
    private final Set<UUID> toExpire = ConcurrentHashMap.newKeySet(); // this step's holds that will run out
    private final AtomicInteger bFailures = new AtomicInteger();
    private volatile boolean bUp = true;
    private volatile boolean bClosing;

    private MockMvc on(int k) {
        return bUp && k % 2 == 1 ? mvcB : mvc;
    }

    /** {@link #send}, with a failure on B once it is closing turned into {@link BDown}. */
    private Res sendOn(MockMvc m, MockHttpServletRequestBuilder req) throws Exception {
        if (m != mvcB) {
            return send(m, req);
        }
        try {
            Res r = send(m, req);
            if (r.status() < 500 || !bClosing) {
                return r;
            }
        } catch (Exception | AssertionError e) {
            if (!bClosing) {
                throw e;
            }
        }
        bFailures.incrementAndGet();
        throw new BDown();
    }

    /** Runs {@code job} on B while it is up; once B is closing, its failure is the close's, not the job's. */
    private void onB(Callable<?> job) throws Exception {
        if (!bUp) {
            return;
        }
        try {
            job.call();
        } catch (Exception e) {
            if (!bClosing) {
                throw e;
            }
        }
    }

    /** One tick at {@code at} on A and B at once (A alone once B is down). */
    private void tickBoth(RoomTicks ticksB, Instant at) throws Exception {
        both(() -> {
            ticks.tick(at);
            return null;
        }, () -> {
            onB(() -> {
                ticksB.tick(at);
                return null;
            });
            return null;
        });
    }

    /** Polls on one instance; at the turn, orders on the other, then pays (or abandons, or the card is refused). */
    private void turn(Turn t, int event, int k) throws Exception {
        Plan p = t.plan;
        if (t.admission == null) {
            Res poll = sendOn(on(k), get("/fan/queue").header("X-Queue-Token", t.queueToken));
            assertThat(poll.status()).isEqualTo(200);
            t.queueToken = poll.read("$.queue_token");
            if (!"ADMITTED".equals(poll.read("$.status"))) {
                assertThat((String) poll.read("$.status")).isEqualTo("WAITING");
                return;
            }
            t.admission = poll.read("$.admission.token");
        }
        MockMvc m = on(k + 1);
        if (t.order == null) {
            Res order = sendOn(m, post("/fan/orders").header("Authorization", "Bearer " + t.fan.token())
                    .header("X-Admission-Token", t.admission).contentType(MediaType.APPLICATION_JSON).content("""
                            {"event_id": %d, "channel": "APP",
                             "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}"""
                            .formatted(event, p.price(), p.qty(), holders(p.qty()))));
            if (order.status() == 409) {
                assertThat((String) order.read("$.code")).isIn("SOLD_OUT", "PER_ACCOUNT_LIMIT");
                t.done = true;
                return;
            }
            assertThat(order.status()).isEqualTo(201);
            t.order = UUID.fromString(order.read("$.id"));
            assertThat(placed.put(t.order, p.qty())).isNull();
            if (p.abandon()) {
                released.add(t.order);
                toExpire.add(t.order);
                t.done = true;
                return;
            }
        }
        if (t.callback == null) {
            Res pay = sendOn(m, post("/fan/orders/" + t.order + "/pay").header("Authorization", "Bearer "
                    + t.fan.token()).contentType(MediaType.APPLICATION_JSON).content("{\"method\": \"CARD\"}"));
            assertThat(pay.status()).isEqualTo(200);
            t.callback = PaymentTest.signed(pay.read("$.payment_id"), p.refused() ? "DECLINED" : "APPROVED",
                    5000L * p.qty(), "FAKE-" + UUID.randomUUID());
        }
        var cb = post("/payments/fake/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED);
        t.callback.forEach(cb::param);
        assertThat(sendOn(on(k), cb).status()).isEqualTo(303);
        if (p.refused()) {
            released.add(t.order);
            toExpire.add(t.order);
        }
        t.done = true;
    }

    @Test
    @Order(3)
    void threeHundredFansOnTwoInstancesWithOneClosedMidSale() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int event = event(a, venue);
        String hot = price(a, event, zone(a, venue, "NORD", 120), "Virage", 120);
        List<String> prices = List.of(hot, hot, price(a, event, zone(a, venue, "SUD", 60), "Sud", 60),
                price(a, event, zone(a, venue, "EST", 60), "Est", 60));
        String blocker = price(a, event, zone(a, venue, "OUEST", 10), "Ouest", 10); // the cut-off order's only
        UUID room = roomFor(a, event, SALE);
        RoomTicks ticksB = b.getBean(RoomTicks.class);
        OrderService ordersB = b.getBean(OrderService.class);
        try {
            guard(room);
            sale(event, room, prices, blocker, ticksB, ordersB);
            assertThat(movedBack()).isEmpty();
        } finally {
            unguard();
            call(a, post("/org/events/" + event + "/rooms/1/end")).andExpect(status().isOk());
        }
    }

    /**
     * Closes B while one of its orders waits on a row lock the test holds (the {@code blocker} category's, which no
     * turn buys), so at least one call to B is cut off by the close whatever the timing; then lets the row go. The
     * order never commits: its connection was aborted by the close.
     */
    private void closeBWithAnOrderWaiting(int event, UUID room, String blocker) throws Exception {
        Fan f = mintFan();
        String admission = tokens.admission(f.id(), event, room, UUID.randomUUID(), Instant.now().plusSeconds(600));
        try (var waiter = Executors.newSingleThreadExecutor(); Connection lock = dataSource.getConnection()) { // lock closes first, so waiter.close() never waits on a held row
            lock.setAutoCommit(false);
            int pid;
            try (var st = lock.createStatement(); var rs = st.executeQuery("select pg_backend_pid()")) {
                rs.next();
                pid = rs.getInt(1);
            }
            try (var ps = lock.prepareStatement("select 1 from price_categories where id = ?::uuid for update")) {
                ps.setString(1, blocker);
                ps.executeQuery().close();
            }
            Future<Res> order = waiter.submit(() -> sendOn(mvcB, post("/fan/orders")
                    .header("Authorization", "Bearer " + f.token()).header("X-Admission-Token", admission)
                    .contentType(MediaType.APPLICATION_JSON).content("""
                            {"event_id": %d, "channel": "APP",
                             "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                            .formatted(event, blocker, holders(1)))));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.sql("select count(*) from pg_stat_activity where ? = any(pg_blocking_pids(pid))").param(pid)
                    .query(Integer.class).single() == 0) {
                assertThat(order.isDone()).as("B's order ended before it waited on the row").isFalse();
                assertThat(System.nanoTime()).as("B's order waits on the row").isLessThan(deadline);
                Thread.sleep(5);
            }
            bClosing = true;
            b.close();
            bUp = false;
            lock.rollback();
            assertThatThrownBy(order::get).hasCauseInstanceOf(BDown.class);
        }
    }

    private void sale(int event, UUID room, List<String> prices, String blocker, RoomTicks ticksB,
                      OrderService ordersB) throws Exception {
        // ---------- pre-queue: 300 fans join over both instances ----------
        tickBoth(ticksB, Instant.parse(OPENS));
        List<Fan> fans = IntStream.range(0, 300).mapToObj(i -> mintFan()).toList();
        Map<Integer, String> joined = new ConcurrentHashMap<>();
        // Fans 0-2 share one IP, on B, A, B: the room's 3 per IP per 5 min (test config) is then reached for both.
        String shared = "198.51.100.7";
        for (int i = 0; i < 3; i++) {
            Res r = join(i == 1 ? mvc : mvcB, fans.get(i), event, shared);
            assertThat(r.status()).isEqualTo(201);
            joined.put(i, r.read("$.queue_token"));
        }
        Fan extra = mintFan();
        for (MockMvc m : List.of(mvc, mvcB)) {
            Res r = join(m, extra, event, shared);
            assertThat(r.status()).isEqualTo(429);
            assertThat((String) r.read("$.details.reason")).isEqualTo("IP");
        }
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Void>> joins = new ArrayList<>();
            for (int i = 3; i < 300; i++) {
                int k = i;
                joins.add(() -> {
                    Res r = join(on(k), fans.get(k), event, "198.18.%d.%d".formatted(k / 250, k % 250 + 1));
                    assertThat(r.status()).isEqualTo(201);
                    joined.put(k, r.read("$.queue_token"));
                    return null;
                });
            }
            for (Future<Void> j : pool.invokeAll(joins)) {
                j.get();
            }
        }
        List<Turn> turns = IntStream.range(0, 300).mapToObj(k -> new Turn(fans.get(k), new Plan(prices.get(k % 4),
                1 + k / 4 % 4, k % 20 >= 13 && k % 20 <= 18, k % 20 == 19), k, joined.get(k))).toList();

        // ---------- the draw at T0, on both at once: once ----------
        Instant[] at = {Instant.parse(SALE)};
        tickBoth(ticksB, at[0]);
        assertThat(draws(event)).isOne();
        assertThat(jdbc.sql("select rank from queue_entries where room_id = ? order by rank").param(room)
                .query(Integer.class).list()).isEqualTo(IntStream.rangeClosed(1, 300).boxed().toList());
        // Orders check the sale window on the real clock; the room keeps its 2099 draw (Lot5OnSaleFlowTest).
        jdbc.sql("update events set sale_starts_at = now() - interval '1 minute' where id = ?").param(event).update();

        // ---------- the sale: 5 s a step (50 turns at 600/min); tick, expiry and turns of a step run at once ----------
        int admittedAtClose = -1;
        int steps = 0;
        while (turns.stream().anyMatch(t -> !t.done) && soldPlaces(event) < 240) {
            assertThat(++steps).as("steps").isLessThanOrEqualTo(40);
            long stepStart = System.nanoTime();
            at[0] = at[0].plusSeconds(5);
            // The previous step's abandoned and refused holds run out now (8 min of the hold): these ids only.
            List<UUID> due = List.copyOf(toExpire);
            toExpire.removeAll(due);
            for (UUID id : due) {
                jdbc.sql("update orders set reserved_until = now() - interval '1 minute' where id = ?").param(id)
                        .update();
            }
            List<Turn> open = turns.stream().filter(t -> !t.done).toList();
            AtomicInteger finished = new AtomicInteger();
            int[] closeAt = {-1};
            List<Callable<Void>> work = new ArrayList<>();
            if (steps == 2) { // B shuts down once half of this step's turns are through, the others in flight
                work.add(() -> {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (finished.get() < open.size() / 2 && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    closeAt[0] = (Integer) roomRow(room).get("admitted_through");
                    closeBWithAnOrderWaiting(event, room, blocker);
                    return null;
                });
            }
            Instant now = at[0];
            work.add(() -> {
                tickBoth(ticksB, now);
                return null;
            });
            work.add(() -> {
                both(() -> orderService.expireDue(10), () -> {
                    onB(() -> ordersB.expireDue(10));
                    return null;
                });
                return null;
            });
            int s = steps;
            for (Turn t : open) {
                work.add(() -> {
                    try {
                        turn(t, event, t.index + s);
                    } catch (BDown e) {
                        // goes on from where it was, on A, next step
                    } finally {
                        finished.incrementAndGet();
                    }
                    return null;
                });
            }
            try (var pool = Executors.newFixedThreadPool(8)) {
                for (Future<Void> f : pool.invokeAll(work)) {
                    f.get();
                }
            }
            if (closeAt[0] >= 0) {
                admittedAtClose = closeAt[0];
            }
            for (UUID id : due) { // released exactly once, by one instance or the other
                assertThat(orderStatus(id)).isEqualTo("EXPIRED");
            }
            // The memos reload once per rooms.memo-ttl (1 s): a step lasts at least that, so polls see the tick.
            long left = 1_100 - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stepStart);
            if (left > 0) {
                Thread.sleep(left);
            }
        }

        // ---------- checks ----------
        assertThat(steps).isGreaterThan(2);
        assertThat(bUp).isFalse();
        assertThat(bFailures).as("calls to B cut off by the close").hasPositiveValue();
        assertThat((Integer) roomRow(room).get("admitted_through")).isGreaterThan(admittedAtClose); // A alone after B
        // Holds still open (the last step's, and any order a failed call to B created unseen) run out.
        jdbc.sql("update orders set reserved_until = now() - interval '1 minute' where event_id = ? and status = "
                + "'RESERVED'").param(event).update();
        orderService.expireDue(10);
        assertThat(breaches(event)).isEmpty();
        assertThat(draws(event)).isOne();
        // No order lost: every 201 is a row; every order is PAID or EXPIRED, the abandoned and refused ones EXPIRED.
        placed.keySet().forEach(id -> assertThat(orderStatus(id)).isIn("PAID", "EXPIRED"));
        assertThat(jdbc.sql("select count(*) from orders where event_id = ? and status not in ('PAID', 'EXPIRED')")
                .param(event).query(Integer.class).single()).isZero();
        assertThat(released).isNotEmpty().allMatch(id -> "EXPIRED".equals(orderStatus(id)));
        long paidPlaces = jdbc.sql("""
                        select coalesce(sum(i.qty), 0) from order_items i join orders o on o.id = i.order_id
                        where o.event_id = ? and o.status = 'PAID'""").param(event).query(Long.class).single();
        assertThat(paidPlaces).isPositive();
        assertThat(soldPlaces(event)).isEqualTo(paidPlaces);
        assertThat(jdbc.sql("select count(*) from tickets where order_id in (select id from orders where event_id = ?)")
                .param(event).query(Long.class).single()).isEqualTo(paidPlaces);
    }

    private long soldPlaces(int event) {
        return jdbc.sql("select sum(sold) from price_categories where event_id = ?").param(event).query(Long.class)
                .single();
    }

    private String orderStatus(UUID id) {
        return jdbc.sql("select status::text from orders where id = ?").param(id).query(String.class).single();
    }
}
