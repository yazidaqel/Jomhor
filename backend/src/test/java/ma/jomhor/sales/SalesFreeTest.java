package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;

/**
 * Contract (BE-68): GET /org/events/{id}/sales Lot 5 step 5 (docs/04-api.md §Organizer; plan lot-5 Q237). Channels
 * count sales only; pass tickets and invitations are {@code free}; paid = Σ channels + free. Orders written as rows.
 */
class SalesFreeTest extends OrgApiTest {

    /** A PAID order of {@code qty} places of the price category, with the given order columns. */
    private void order(UUID org, int event, String price, int qty, long unitMad, String channel, String method,
                       UUID user, UUID pass, UUID invitedBy) {
        UUID order = jdbc.sql("""
                        insert into orders (organizer_id, event_id, user_id, pass_id, invited_by, channel, status, total_mad,
                                            payment_method, reserved_until, paid_at)
                        values (?, ?, ?, ?, ?, ?::sales_channel, 'PAID', ?, ?::payment_method, now(), now()) returning id""")
                .params(org, event, user, pass, invitedBy, channel, qty * unitMad, method).query(UUID.class).single();
        jdbc.sql("""
                        insert into order_items (organizer_id, order_id, price_category_id, qty, unit_price_mad, holders)
                        select ?, ?, ?::uuid, ?, ?, jsonb_agg('{}'::jsonb) from generate_series(1, ?)""")
                .params(org, order, price, qty, unitMad, qty).update();
    }

    private UUID user() {
        return jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
    }

    @Test
    void twoPassTicketsThreeInvitationsFourAppAndTwoPosPlaces() throws Exception {
        Caller a = admin();
        UUID org = a.org();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "NORD");
        int event = event(a, venue);
        String plein = price(a, event, zone, "Plein tarif", 50);
        String abonnes = JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"),
                "{\"zone_id\": %d, \"name\": \"Abonnés\", \"price_mad\": 0, \"quota\": 10, \"audience\": \"PASS\"}"
                        .formatted(zone)).andExpect(status().isCreated())), "$.id");
        String presse = JsonPath.read(body(call(a, post("/org/events/" + event + "/prices"),
                "{\"zone_id\": %d, \"name\": \"Presse\", \"price_mad\": 0, \"quota\": 10, \"audience\": \"INVITE\"}"
                        .formatted(zone)).andExpect(status().isCreated())), "$.id");
        UUID series = jdbc.sql("insert into event_series (organizer_id, name, starts_on, ends_on) "
                + "values (?, 'Saison', '2026-01-01', '2099-12-31') returning id").param(org).query(UUID.class).single();
        UUID product = jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, series_id, zone_id)
                        values (?, 'PASS', 'Abonnement', 150000, now() + interval '30 days', 10, ?, ?) returning id""")
                .params(org, series, zone).query(UUID.class).single();
        for (int i = 0; i < 2; i++) {
            UUID holder = user();
            UUID pass = jdbc.sql("insert into passes (organizer_id, product_id, series_id, user_id, holder_name, status)"
                    + " values (?, ?, ?, ?, 'Abonné', 'ACTIVE') returning id").params(org, product, series, holder)
                    .query(UUID.class).single();
            order(org, event, abonnes, 1, 0, "APP", "FREE", holder, pass, null);
        }
        UUID staff = jdbc.sql("select id from staff where organizer_id = ? and role = 'ADMIN'").param(org)
                .query(UUID.class).single();
        order(org, event, presse, 2, 0, "POS", "FREE", null, null, staff);
        order(org, event, presse, 1, 0, "POS", "FREE", null, null, staff);
        order(org, event, plein, 4, 5000, "APP", "CARD", user(), null, null);
        order(org, event, plein, 2, 5000, "POS", "POS_CASH", null, null, null);

        String json = body(call(a, get("/org/events/" + event + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Map<String, Object>>>read(json, "$.channels")).containsExactly(
                Map.of("channel", "APP", "paid", 4, "revenue_mad", 20000),
                Map.of("channel", "WEB", "paid", 0, "revenue_mad", 0),
                Map.of("channel", "POS", "paid", 2, "revenue_mad", 10000));
        assertKeys(json, "$.free", "passes", "invitations");
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.free")).isEqualTo(Map.of("passes", 2, "invitations", 3));
        int paid = JsonPath.read(json, "$.paid");
        assertThat(paid).isEqualTo(11);
        int channels = JsonPath.<List<Integer>>read(json, "$.channels[*].paid").stream().mapToInt(Integer::intValue).sum();
        assertThat(paid).isEqualTo(channels + 2 + 3);
        assertThat(JsonPath.<List<Integer>>read(json, "$.methods[?(@.method == 'FREE')].paid")).containsExactly(5);
        assertThat(JsonPath.<Integer>read(json, "$.revenue_mad")).isEqualTo(30000); // unchanged: free places cost 0
    }

    @Test
    void anEventWithoutFreePlacesHasZeros() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int event = event(a, venue);
        price(a, event, zone(a, venue, "NORD"), "Plein tarif", 5);
        String json = body(call(a, get("/org/events/" + event + "/sales")).andExpect(status().isOk()));
        assertThat(JsonPath.<Map<String, Object>>read(json, "$.free")).isEqualTo(Map.of("passes", 0, "invitations", 0));
    }
}
