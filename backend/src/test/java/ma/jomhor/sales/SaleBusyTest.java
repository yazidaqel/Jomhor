package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/** docs/04-api.md §On-sale Busy: the basic queue with sales.max-active-holds-per-event = 2. */
@TestPropertySource(properties = "sales.max-active-holds-per-event=2")
class SaleBusyTest extends OrgApiTest {

    record Sale(int eventId, String priceId) {}

    private Sale onSale() throws Exception {
        Caller a = admin();
        String venueId = venue(a, "Stade");
        int eventId = event(a, venueId);
        String priceId = price(a, eventId, zone(a, venueId, "NORD"), "Plein tarif", 100);
        call(a, post("/org/events/" + eventId + "/publish")).andExpect(status().isOk());
        return new Sale(eventId, priceId);
    }

    private ResultActions order(String token, Sale s, int qty, String holders) throws Exception {
        return mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"event_id": %d, "channel": "WEB", "items": [{"price_category_id": "%s", "qty": %d, "holders": %s}]}
                        """.formatted(s.eventId(), s.priceId(), qty, holders)));
    }

    private ResultActions order(String token, Sale s, int qty) throws Exception {
        return order(token, s, qty, holders(qty));
    }

    private String reserve(Sale s) throws Exception {
        return JsonPath.read(body(order(fanToken(randomPhone()), s, 1).andExpect(status().isCreated())), "$.id");
    }

    private int busyRetryAfter(ResultActions r) throws Exception {
        String json = body(r.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("SALE_BUSY")));
        Map<String, Object> details = JsonPath.read(json, "$.details");
        assertThat(details).containsOnlyKeys("retry_after_s");
        assertThat(details.get("retry_after_s")).isInstanceOf(Integer.class);
        return (Integer) details.get("retry_after_s");
    }

    private int sold(Sale s) {
        return jdbc.sql("select sold from price_categories where id = ?::uuid").param(s.priceId()).query(Integer.class).single();
    }

    @Test
    void theThirdHoldIsBusyWithAJitteredRetry() throws Exception {
        Sale s = onSale();
        String first = reserve(s);
        reserve(s);
        String fan = fanToken(randomPhone());
        Set<Integer> retries = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            int retry = busyRetryAfter(order(fan, s, 1));
            assertThat(retry).isBetween(5, 30);
            retries.add(retry);
        }
        assertThat(retries).hasSizeGreaterThan(1);
        assertThat(sold(s)).isEqualTo(2);
        assertThat(jdbc.sql("select count(*) from orders where event_id = ?").param(s.eventId()).query(Integer.class)
                .single()).isEqualTo(2);

        // Another event is not affected.
        reserve(onSale());

        // A hold past reserved_until no longer counts, even before the expiry job sweeps it.
        jdbc.sql("update orders set reserved_until = now() - interval '1 second' where id = ?::uuid").param(first).update();
        order(fan, s, 1).andExpect(status().isCreated());
        busyRetryAfter(order(fanToken(randomPhone()), s, 1));
    }

    /** Checks in order: sale window and holders before the queue; the queue before the limit and the quota. */
    @Test
    void theQueueComesAfterTheHoldersAndBeforeTheLimit() throws Exception {
        Sale s = onSale();
        reserve(s);
        reserve(s);
        String fan = fanToken(randomPhone());
        order(fan, s, 2, holders(1)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.details.fields['items[0].holders']").isString());
        order(fan, s, 1, "[{\"name\": \"X\"}]").andExpect(status().isUnprocessableContent());
        busyRetryAfter(order(fan, s, 5)); // over the per-account limit (4), but busy first
        jdbc.sql("update events set status = 'CLOSED' where id = ?").param(s.eventId()).update();
        order(fan, s, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));
    }
}
