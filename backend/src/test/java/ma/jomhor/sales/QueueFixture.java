package ma.jomhor.sales;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import ma.jomhor.sales.rooms.application.QueueService;
import ma.jomhor.sales.rooms.application.RoomMemo;
import ma.jomhor.sales.rooms.application.RoomTicks;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Waiting-room fixtures (docs/04-api.md §Waiting room): an ON_SALE event whose sale opens at {@link #SALE} (doors
 * 2099-05-01T19:00Z, so sales close at 18:00Z) with one category, its room, and fans joining it. The tick job never
 * fires in tests: {@link #tick} runs it at a fixed time.
 */
abstract class QueueFixture extends OrgApiTest {

    static final String SALE = "2099-04-01T10:00:00Z";
    static final String OPENS = "2099-04-01T09:30:00Z";
    static final String CUTOFF = "2099-05-01T18:00:00Z";

    record Room(Caller admin, int event, String price, UUID id) {}

    record Fan(UUID id, String token) {}

    @Autowired
    RoomTicks ticks;

    @Autowired
    QueueService queue;

    @Autowired
    RoomMemo memo;

    /** An ON_SALE event with a category of {@code quota} and its room from {@code roomJson} (the PUT body). */
    Room room(String roomJson, int quota) throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "NORD", Math.max(quota, 100));
        int event = event(a, venue);
        call(a, patch("/org/events/" + event), "{\"sale_starts_at\": \"%s\"}".formatted(SALE))
                .andExpect(status().isOk());
        String price = price(a, event, zone, "Cat 1", quota);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        call(a, put("/org/events/" + event + "/room"), roomJson).andExpect(status().isCreated());
        UUID id = jdbc.sql("select id from waiting_rooms where event_id = ?").param(event).query(UUID.class).single();
        return new Room(a, event, price, id);
    }

    Room room(String roomJson) throws Exception {
        return room(roomJson, 100);
    }

    /** One tick of every live room at {@code at}, then this instance's memo reloads on its next read. */
    void tick(String at) {
        ticks.tick(Instant.parse(at));
        memo.invalidate();
    }

    /** A logged-in fan (OTP through the API). */
    Fan fan() throws Exception {
        String phone = randomPhone();
        String token = fanToken(phone);
        return new Fan(jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single(),
                token);
    }

    /** A user row only, for joins through QueueService (no HTTP). */
    UUID user() {
        return jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
    }

    ResultActions join(Fan f, int event, String deviceId, String ip) throws Exception {
        return mvc.perform(post("/fan/queue").header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"event_id\": %d, \"device_id\": \"%s\"}".formatted(event, deviceId))
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                }));
    }

    ResultActions join(Fan f, int event) throws Exception {
        return join(f, event, "device-" + UUID.randomUUID(), "10.0.0." + (1 + (f.id().hashCode() & 0x7f)));
    }

    ResultActions poll(String queueToken) throws Exception {
        return mvc.perform(get("/fan/queue").header("X-Queue-Token", queueToken));
    }

    /** {@code (rank, admitted_at)} of the room's entries, by user. */
    Map<String, Object> entry(UUID roomId, UUID userId) {
        return jdbc.sql("select rank, admitted_at from queue_entries where room_id = ? and user_id = ?")
                .params(roomId, userId).query().singleRow();
    }

    Map<String, Object> roomRow(UUID roomId) {
        return jdbc.sql("""
                        select status::text as status, joined_count, ranked_count, admitted_through,
                               measured_rate_per_min, sold_out, last_tick_at, drawn_at
                        from waiting_rooms where id = ?""").param(roomId).query().singleRow();
    }

    /** The decoded claims of a JWT (payload only; the signature is the server's business). */
    static Map<String, Object> claims(String jwt) {
        return JsonPath.read(new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8),
                "$");
    }
}
