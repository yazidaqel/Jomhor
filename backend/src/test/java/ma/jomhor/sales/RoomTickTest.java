package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * docs/04-api.md §Waiting room Tick, Lifecycle, Fairness, Memo, Admission (plan lot-5 Q141, Q142, Q143, Q154, Q155):
 * the tick at fixed times, then what polls see. Joins go through QueueService (CSPRNG draw keys), each from its own
 * device and IP.
 */
class RoomTickTest extends QueueFixture {

    /** {@code n} fans joined in this order. */
    private List<UUID> joinAll(Room r, int n) {
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID u = user();
            queue.join(u, r.event(), "device-" + UUID.randomUUID(), "10.20.%d.%d".formatted(i / 250, i % 250));
            users.add(u);
        }
        return users;
    }

    private Integer rank(Room r, UUID user) {
        return (Integer) entry(r.id(), user).get("rank");
    }

    private int admitted(Room r) {
        return (Integer) roomRow(r.id()).get("admitted_through");
    }

    /** Spearman's rho of join order against rank (no ties: ranks are dense and unique). */
    private static double spearman(List<Integer> ranks) {
        int n = ranks.size();
        double d2 = IntStream.range(0, n).mapToDouble(i -> Math.pow(i + 1 - ranks.get(i), 2)).sum();
        return 1 - 6 * d2 / (n * ((double) n * n - 1));
    }

    // ---------- draw and ranking ----------

    @Test
    void drawRanksThePreQueueOnceAtRandomThenArrivalOrder() throws Exception {
        double rho = 0;
        for (int run = 0; run < 5; run++) {
            Room r = room("{}");
            tick(OPENS);
            assertThat(roomRow(r.id()).get("status")).isEqualTo("PRE_QUEUE");
            List<UUID> users = joinAll(r, 200);
            assertThat(users.stream().map(u -> rank(r, u))).containsOnlyNulls();
            tick(SALE);
            List<Integer> ranks = users.stream().map(u -> rank(r, u)).toList();
            assertThat(ranks).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 200).boxed().toList());
            rho += Math.abs(spearman(ranks));
            if (run > 0) {
                continue;
            }
            assertThat(roomRow(r.id())).containsEntry("status", "OPEN").containsEntry("joined_count", 200)
                    .containsEntry("ranked_count", 200).containsEntry("admitted_through", 0);
            assertThat(jdbc.sql("select drawn_at from waiting_rooms where id = ?").param(r.id())
                    .query(OffsetDateTime.class).single().toInstant()).isEqualTo(Instant.parse(SALE));

            tick(SALE); // a second tick changes nothing
            assertThat(users.stream().map(u -> rank(r, u)).toList()).isEqualTo(ranks);
            assertThat(jdbc.sql("select actor_type::text || ' ' || payload::text from audit_log "
                            + "where action = 'room.draw' and target = ?").param(String.valueOf(r.event()))
                    .query(String.class).list()).containsExactly("SYSTEM {\"count\": 200, \"phase\": 1}");

            UUID late = joinAll(r, 1).getFirst();
            UUID later = joinAll(r, 1).getFirst();
            tick("2099-04-01T10:00:01Z");
            assertThat(rank(r, late)).isEqualTo(201);
            assertThat(rank(r, later)).isEqualTo(202);
        }
        // ponytail: the mean over 5 rooms (each |rho| has sd ~0.07 at n = 200, so a per-room bound of 0.2 would flake).
        assertThat(rho / 5).isLessThan(0.2);
    }

    // ---------- admission ----------

    @Test
    void admissionFollowsTheRateCappedAtTheRanked() throws Exception {
        Room r = room("{\"admission_rate_per_min\": 600}");
        tick(OPENS);
        joinAll(r, 150);
        tick(SALE);
        tick("2099-04-01T10:00:10Z");
        assertThat(admitted(r)).isEqualTo(100);
        tick("2099-04-01T10:00:30Z");
        assertThat(admitted(r)).isEqualTo(150);
    }

    @Test
    void noAdmissionWhileHoldsNearTheCapSoldOutOrPausedAndTheMeasuredRate() throws Exception {
        Room r = room("{\"admission_rate_per_min\": 60}");
        tick(OPENS);
        joinAll(r, 50);
        tick(SALE);
        UUID buyer = user();
        jdbc.sql("""
                        insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, reserved_until)
                        select ?, ?, ?, 'APP', 'RESERVED', 5000, now() + interval '8 minutes'
                        from generate_series(1, 1800)""").params(r.admin().org(), r.event(), buyer).update();
        tick("2099-04-01T10:00:10Z"); // 1,800 holds = 90 % of 2,000
        assertThat(admitted(r)).isZero();
        jdbc.sql("delete from orders where user_id = ?").param(buyer).update();
        tick("2099-04-01T10:00:20Z");
        assertThat(admitted(r)).isEqualTo(10);

        setSold(r.price(), 100);
        tick("2099-04-01T10:00:30Z");
        assertThat(roomRow(r.id())).containsEntry("sold_out", true).containsEntry("admitted_through", 10);
        setSold(r.price(), 0);
        tick("2099-04-01T10:00:40Z");
        assertThat(roomRow(r.id())).containsEntry("sold_out", false).containsEntry("admitted_through", 20);

        jdbc.sql("update waiting_rooms set status = 'PAUSED' where id = ?").param(r.id()).update();
        tick("2099-04-01T10:00:50Z");
        assertThat(admitted(r)).isEqualTo(20);
        jdbc.sql("update waiting_rooms set status = 'OPEN' where id = ?").param(r.id()).update();
        tick("2099-04-01T10:01:00Z");
        // admitted over the minute since the draw
        assertThat(roomRow(r.id())).containsEntry("admitted_through", 30).containsEntry("measured_rate_per_min", 30);
    }

    // ---------- polls ----------

    @Test
    void pollReadsTheEntryUntilRankedThenNoSqlThenTheTurnOnceThenExpiredAndRejoin() throws Exception {
        Room r = room("{\"admission_rate_per_min\": 6000}");
        tick(OPENS);
        Fan f = fan();
        String unranked = JsonPath.read(body(join(f, r.event()).andExpect(status().isCreated())), "$.queue_token");
        tick(SALE);

        long before = statements.count();
        String json = body(poll(unranked).andExpect(status().isOk()));
        assertThat(statements.count()).isGreaterThan(before);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("WAITING");
        assertThat((Integer) JsonPath.read(json, "$.position")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(json, "$.in_room")).isEqualTo(1);
        // The draw seeded the measured rate with the configured one (Q180): ⌈1 × 60 ÷ 6000⌉ s, near the front (Q181).
        assertThat((Integer) JsonPath.read(json, "$.estimated_wait_s")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(json, "$.poll_after_s")).isEqualTo(2);
        String ranked = JsonPath.read(json, "$.queue_token");

        memo.rooms(r.event()); // warm this instance's memo right before counting (it reloads once per memo-ttl)
        before = statements.count();
        json = body(poll(ranked).andExpect(status().isOk()));
        assertThat(statements.count()).isEqualTo(before);
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("WAITING");
        assertThat((Integer) JsonPath.read(json, "$.position")).isEqualTo(1);

        tick("2099-04-01T10:00:10Z");
        json = body(poll(ranked).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("ADMITTED");
        assertThat((Object) JsonPath.read(json, "$.position")).isNull();
        assertThat((Integer) JsonPath.read(json, "$.poll_after_s")).isZero();
        Instant turnEnd = jdbc.sql("select date_trunc('second', admitted_at + interval '10 minutes') "
                        + "from queue_entries where room_id = ? and user_id = ?").params(r.id(), f.id())
                .query(OffsetDateTime.class).single().toInstant();
        assertThat(Instant.parse(JsonPath.read(json, "$.admission.expires_at"))).isEqualTo(turnEnd);
        Object admittedAt = entry(r.id(), f.id()).get("admitted_at");
        json = body(poll(ranked).andExpect(status().isOk()));
        assertThat(Instant.parse(JsonPath.read(json, "$.admission.expires_at"))).isEqualTo(turnEnd);
        assertThat(entry(r.id(), f.id()).get("admitted_at")).isEqualTo(admittedAt); // set once

        jdbc.sql("update queue_entries set admitted_at = admitted_at - interval '11 minutes' where user_id = ?")
                .param(f.id()).update();
        json = body(poll(ranked).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("EXPIRED");
        assertThat((Object) JsonPath.read(json, "$.admission")).isNull();
        assertThat((Integer) JsonPath.read(json, "$.poll_after_s")).isZero();

        json = body(join(f, r.event()).andExpect(status().isCreated())); // back of the queue
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("WAITING");
        assertThat((Object) JsonPath.read(json, "$.position")).isNull();
        assertThat((Integer) JsonPath.read(json, "$.poll_after_s")).isEqualTo(2);
        assertThat(entry(r.id(), f.id())).containsEntry("rank", null).containsEntry("admitted_at", null);
        join(f, r.event()).andExpect(status().isOk());
        tick("2099-04-01T10:00:11Z");
        assertThat(rank(r, f.id())).isEqualTo(2);

        tick(CUTOFF);
        assertThat(roomRow(r.id()).get("status")).isEqualTo("ENDED");
        json = body(poll(ranked).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(json, "$.status")).isEqualTo("ENDED");
        assertThat((String) JsonPath.read(json, "$.sale_starts_at")).isEqualTo(SALE);
        assertThat((Integer) JsonPath.read(json, "$.poll_after_s")).isZero();
    }

    // ---------- retention, tenants ----------

    @Test
    void purgeDeletesEntriesThirtyDaysAfterTheEventStarts() throws Exception {
        Room r = room("{}");
        tick(OPENS);
        joinAll(r, 3);
        ticks.purge(Instant.parse("2099-05-31T18:59:59Z"));
        assertThat(jdbc.sql("select count(*) from queue_entries where room_id = ?").param(r.id()).query(Long.class)
                .single()).isEqualTo(3);
        ticks.purge(Instant.parse("2099-05-31T19:00:00Z"));
        assertThat(jdbc.sql("select count(*) from queue_entries where room_id = ?").param(r.id()).query(Long.class)
                .single()).isZero();
        assertThat(roomRow(r.id())).isNotEmpty();
    }

    @Test
    void anOrganizerSeesAndRanksOnlyItsOwnEntries() throws Exception {
        Room a = room("{}");
        Room b = room("{}");
        tick(OPENS);
        joinAll(a, 3);
        joinAll(b, 5);
        tick(SALE);
        assertThat(roomRow(a.id())).containsEntry("joined_count", 3).containsEntry("ranked_count", 3);
        assertThat(roomRow(b.id())).containsEntry("joined_count", 5).containsEntry("ranked_count", 5);
        call(a.admin(), get("/org/events/" + a.event() + "/rooms")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.rooms[0].figures.joined").value(3));
        call(a.admin(), get("/org/events/" + b.event() + "/rooms")).andExpect(status().isNotFound());
    }

    // ---------- phases (BE-48) ----------

    private long nextPhaseEnds(Room r) {
        return jdbc.sql("""
                        select count(*) from audit_log where action = 'room.end' and target = ? and actor_type = 'SYSTEM'
                          and payload = '{"reason": "NEXT_PHASE", "phase": 1}'::jsonb""")
                .param(String.valueOf(r.event())).query(Long.class).single();
    }

    /**
     * Q167, Q180: phase 2's opens_at ends phase 1 once (one audit room.end NEXT_PHASE) and starts phase 2's own
     * pre-queue; phase 2 draws at its own starts_at, ranks 1..n in its room. The measured rate is the configured one
     * from the draw until the first 60 s re-mark.
     */
    @Test
    void theNextPhaseOpeningEndsThePreviousOnceAndDrawsInItsOwnRoom() throws Exception {
        Room r = room("{\"admission_rate_per_min\": 600}");
        UUID two = phase(r, 2, """
                {"pre_queue_min": 30, "admission_rate_per_min": 120, "starts_at": "2099-04-02T10:00:00Z"}""");
        tick(OPENS);
        List<UUID> presale = joinAll(r, 2);
        tick(SALE);
        assertThat(roomRow(r.id())).containsEntry("status", "OPEN").containsEntry("measured_rate_per_min", 600);
        assertThat(roomRow(two)).containsEntry("status", "SCHEDULED");

        tick("2099-04-02T09:29:59Z");
        assertThat(roomRow(r.id()).get("status")).isEqualTo("OPEN");
        assertThat(nextPhaseEnds(r)).isZero();
        tick("2099-04-02T09:30:00Z");
        assertThat(roomRow(r.id()).get("status")).isEqualTo("ENDED");
        assertThat(roomRow(two).get("status")).isEqualTo("PRE_QUEUE");
        tick("2099-04-02T09:30:01Z");
        assertThat(nextPhaseEnds(r)).isEqualTo(1);
        assertThat(presale.stream().map(u -> rank(r, u))).containsExactlyInAnyOrder(1, 2); // phase 1's stay

        List<UUID> general = joinAll(r, 3); // phase 1 has ENDED: the joins land in phase 2
        assertThat(general.stream().map(u -> entry(two, u).get("rank"))).containsOnlyNulls();
        tick("2099-04-02T10:00:00Z");
        assertThat(general.stream().map(u -> (Integer) entry(two, u).get("rank")))
                .containsExactlyInAnyOrder(1, 2, 3);
        assertThat(roomRow(two)).containsEntry("status", "OPEN").containsEntry("ranked_count", 3)
                .containsEntry("measured_rate_per_min", 120);
        tick("2099-04-02T10:01:00Z"); // 120/min would admit 120, but 3 are in: the real rate is 3
        assertThat(roomRow(two)).containsEntry("admitted_through", 3).containsEntry("measured_rate_per_min", 3);
        assertThat(roomRow(r.id()).get("status")).isEqualTo("ENDED");
    }
}
