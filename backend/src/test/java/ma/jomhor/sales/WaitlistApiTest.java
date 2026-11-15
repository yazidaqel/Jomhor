package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract (BE-49): docs/04-api.md §Fan GET, PUT and DELETE /fan/waitlist/{event_id}, §Waitlist Join, error
 * WAITLIST_CLOSED; plan lot-5 Q176-Q179.
 */
class WaitlistApiTest extends QueueFixture {

    private ResultActions as(Fan f, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + f.token()));
    }

    private static String path(int event) {
        return "/fan/waitlist/" + event;
    }

    private static void refused(ResultActions r, String reason) throws Exception {
        String json = body(r.andExpect(status().isConflict()));
        assertThat((String) JsonPath.read(json, "$.code")).isEqualTo("WAITLIST_CLOSED");
        assertThat((Map<String, Object>) JsonPath.read(json, "$.details")).containsExactly(Map.entry("reason", reason));
    }

    @Test
    void getIsNullThenOneEntryPerAccountAndLeaveIsIdempotent() throws Exception {
        Room s = soldOut(3);
        Fan f = fan();
        String empty = body(as(f, get(path(s.event()))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertKeys(empty, "$", "entry");
        assertThat((Object) JsonPath.read(empty, "$.entry")).isNull();

        String joined = body(as(f, put(path(s.event()))).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")));
        assertKeys(joined, "$", "entry");
        assertKeys(joined, "$.entry", "joined_at", "notified_at");
        assertThat((Object) JsonPath.read(joined, "$.entry.notified_at")).isNull();
        String joinedAt = JsonPath.read(joined, "$.entry.joined_at");
        assertThat(Instant.parse(joinedAt)).isBefore(Instant.now().plusSeconds(5));

        String again = body(as(f, put(path(s.event()))).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(again, "$.entry.joined_at")).isEqualTo(joinedAt);
        String read = body(as(f, get(path(s.event()))).andExpect(status().isOk()));
        assertThat((String) JsonPath.read(read, "$.entry.joined_at")).isEqualTo(joinedAt);
        assertThat(jdbc.sql("select organizer_id from waitlist_entries where event_id = ? and user_id = ?")
                .params(s.event(), f.id()).query(UUID.class).single()).isEqualTo(s.admin().org());

        Fan other = fan(); // another user's entry is never visible
        assertThat((Object) JsonPath.read(body(as(other, get(path(s.event()))).andExpect(status().isOk())),
                "$.entry")).isNull();
        as(other, delete(path(s.event()))).andExpect(status().isNoContent());
        assertThat(jdbc.sql("select count(*) from waitlist_entries where event_id = ?").param(s.event())
                .query(Integer.class).single()).isOne();

        as(f, delete(path(s.event()))).andExpect(status().isNoContent());
        as(f, delete(path(s.event()))).andExpect(status().isNoContent());
        assertThat((Object) JsonPath.read(body(as(f, get(path(s.event())))), "$.entry")).isNull();
        assertThat(jdbc.sql("select count(*) from waitlist_entries where event_id = ?").param(s.event())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void placesLeftAreNotSoldOut() throws Exception {
        Room s = soldOut(3);
        setSold(s.price(), 2);
        refused(as(fan(), put(path(s.event()))), "NOT_SOLD_OUT");
    }

    @Test
    void aPhaseNotEndedIsTheRoomThenTheWaitlistOpens() throws Exception {
        Room r = room("{}", 3);
        jdbc.sql("update events set sale_starts_at = now() - interval '1 hour' where id = ?").param(r.event()).update();
        setSold(r.price(), 2); // places left too: ROOM comes first
        Fan f = fan();
        refused(as(f, put(path(r.event()))), "ROOM");
        setSold(r.price(), 3);
        refused(as(f, put(path(r.event()))), "ROOM");
        jdbc.sql("update waiting_rooms set status = 'ENDED' where id = ?").param(r.id()).update();
        as(f, put(path(r.event()))).andExpect(status().isCreated());
    }

    @Test
    void outsideTheSaleWindowIsClosed() throws Exception {
        Room r = room("{}", 3); // a room not ENDED and places left: CLOSED comes first
        Fan f = fan();
        refused(as(f, put(path(r.event()))), "CLOSED"); // sale not started
        jdbc.sql("update waiting_rooms set status = 'ENDED' where id = ?").param(r.id()).update();

        Room s = soldOut(3);
        jdbc.sql("update events set refund_cutoff_at = now() - interval '1 minute' where id = ?").param(s.event())
                .update();
        refused(as(f, put(path(s.event()))), "CLOSED");
        Room g = soldOut(3);
        setStatus(g.event(), "GATES_OPEN");
        refused(as(f, put(path(g.event()))), "CLOSED");
    }

    @Test
    void draftOrUnknownIs404AndNoTokenIs401() throws Exception {
        Caller a = admin();
        int draft = event(a, venue(a, "Stade"));
        Fan f = fan();
        for (int event : new int[] {draft, 999_999_999}) {
            as(f, get(path(event))).andExpect(status().isNotFound());
            as(f, put(path(event))).andExpect(status().isNotFound());
            as(f, delete(path(event))).andExpect(status().isNotFound());
        }
        Room s = soldOut(3);
        mvc.perform(get(path(s.event()))).andExpect(status().isUnauthorized());
        mvc.perform(put(path(s.event()))).andExpect(status().isUnauthorized());
        mvc.perform(delete(path(s.event()))).andExpect(status().isUnauthorized());
    }
}
