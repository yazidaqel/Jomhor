package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BE-51: docs/04-api.md §Passes Pass places. A PASS category costs 0 (PASS_PRICE) and is never sold to fans or at a
 * till: the fan event reads leave it out of the rows, from_price_mad and sold_out; a room's sold_out and the waitlist's
 * R ignore it; a fan order or a POS sale naming it is a 422 on its price_category_id.
 */
class PassCategoryTest extends QueueFixture {

    @Autowired
    WaitlistService waitlist;

    @Autowired
    Inventory inventory;

    @Autowired
    PlatformTransactionManager transactions;

    /** An ON_SALE event (no room) with "Cat 1" (ALL, 50 DH, quota 10) in NORD and "Abonnés" (PASS, quota 5) in EST. */
    record Match(Caller admin, int event, int nord, int est, String cat, String pass) {}

    private Match match() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int nord = zone(a, venue, "NORD");
        int est = zone(a, venue, "EST");
        int event = event(a, venue);
        String cat = price(a, event, nord, "Cat 1", 10);
        String pass = passPrice(a, event, est, 5);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        return new Match(a, event, nord, est, cat, pass);
    }

    private ResultActions postPass(Caller a, int event, int zone, long priceMad, int quota) throws Exception {
        return call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Abonnés", "price_mad": %d, "quota": %d, "audience": "PASS"}"""
                .formatted(zone, priceMad, quota));
    }

    private String passPrice(Caller a, int event, int zone, int quota) throws Exception {
        return JsonPath.read(body(postPass(a, event, zone, 0, quota).andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("PASS"))), "$.id");
    }

    private static void passPriceRefused(ResultActions r) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("PASS_PRICE"))
                .andExpect(jsonPath("$.details.fields.price_mad").isString());
    }

    private static void passSold(ResultActions r) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value("PASS_SOLD"))
                .andExpect(jsonPath("$.details.fields.audience").isString());
    }

    private static void categoryRefused(ResultActions r) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['items[0].price_category_id']").isString());
    }

    @Test
    void aPassCategoryCostsNothing() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int est = zone(a, venue, "EST");
        int event = event(a, venue);
        passPriceRefused(postPass(a, event, est, 5000, 5));
        String pass = passPrice(a, event, est, 5);
        String path = "/org/events/" + event + "/prices/";
        passPriceRefused(call(a, patch(path + pass), "{\"price_mad\": 100}"));
        String cat = price(a, event, est, "Cat 1", 5);
        passPriceRefused(call(a, patch(path + cat), "{\"audience\": \"PASS\"}"));
        call(a, patch(path + cat), "{\"audience\": \"PASS\", \"price_mad\": 0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("PASS")).andExpect(jsonPath("$.price_mad").value(0));
        call(a, patch(path + cat), "{\"audience\": \"ALL\", \"price_mad\": 4000}").andExpect(status().isOk());
        // The organizer still sees and manages them; they count in the zone's capacity.
        String json = body(call(a, get("/org/events/" + event + "/prices")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.audience == 'PASS')].id")).containsExactly(pass);
        call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Trop", "price_mad": 0, "quota": 91, "audience": "PASS"}""".formatted(est))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDS_CAPACITY"));
    }

    @Test
    void fanReadsLeavePassPlacesOut() throws Exception {
        Match m = match();
        String json = body(mvc.perform(get("/fan/events/" + m.event())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(json, "$.zones[*].id")).containsExactly(m.nord()); // EST has only PASS
        assertThat(JsonPath.<List<String>>read(json, "$.zones[*].prices[*].price_category_id"))
                .containsExactly(m.cat());
        assertThat(JsonPath.<Integer>read(json, "$.from_price_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Boolean>read(json, "$.sold_out")).isFalse();

        setSold(m.cat(), 10); // only PASS places left: sold out for fans
        json = body(mvc.perform(get("/fan/events/" + m.event())).andExpect(status().isOk()));
        assertThat(JsonPath.<Boolean>read(json, "$.sold_out")).isTrue();
        assertThat(JsonPath.<Object>read(json, "$.from_price_mad")).isNull();
        json = body(mvc.perform(get("/fan/events?organizer=" + m.admin().org())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Boolean>>read(json, "$[?(@.id == %d)].sold_out".formatted(m.event())))
                .containsExactly(true);
        assertThat(JsonPath.<List<Object>>read(json, "$[?(@.id == %d)].from_price_mad".formatted(m.event())))
                .containsOnlyNulls();

        // The waitlist opens on that sold_out, and its R doesn't count the 5 PASS places.
        Fan f = fan();
        mvc.perform(put("/fan/waitlist/" + m.event()).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isCreated());
        waitlist.notifyAt(Instant.now());
        assertThat(jdbc.sql("select count(*) from waitlist_entries where event_id = ? and notified_at is not null")
                .param(m.event()).query(Long.class).single()).isZero();
        setSold(m.cat(), 9); // one place fans can buy: one notice
        waitlist.notifyAt(Instant.now());
        assertThat(jdbc.sql("select user_id from waitlist_entries where event_id = ? and notified_at is not null")
                .param(m.event()).query(UUID.class).list()).containsExactly(f.id());
    }

    @Test
    void aFanOrderOrAPosSaleNamingAPassCategoryIsRefused() throws Exception {
        Match m = match();
        Fan f = fan();
        categoryRefused(mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType("application/json").content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                        .formatted(m.event(), m.pass(), holders(1)))));

        call(m.admin(), post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        categoryRefused(call(m.admin(), post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d,
                 "items": [{"price_category_id": "%s", "qty": 1, "holders": [{"name": "Yazid Aqel"}]}]}"""
                .formatted(UUID.randomUUID(), m.event(), m.pass())));
        assertThat(jdbc.sql("select sold from price_categories where id = ?::uuid").param(m.pass())
                .query(Integer.class).single()).isZero();
        // The ALL category still sells at the till.
        call(m.admin(), post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d,
                 "items": [{"price_category_id": "%s", "qty": 1, "holders": [{"name": "Yazid Aqel"}]}]}"""
                .formatted(UUID.randomUUID(), m.event(), m.cat())).andExpect(status().isCreated());
    }

    @Test
    void aRoomIsSoldOutWhenOnlyPassPlacesRemain() throws Exception {
        Room r = room("{}");
        UUID venue = jdbc.sql("select venue_id from events where id = ?").param(r.event()).query(UUID.class).single();
        passPrice(r.admin(), r.event(), zone(r.admin(), venue.toString(), "EST"), 5);
        tick(OPENS);
        tick(SALE);
        assertThat(roomRow(r.id())).containsEntry("sold_out", false);
        setSold(r.price(), 100);
        tick("2099-04-01T10:00:10Z");
        assertThat(roomRow(r.id())).containsEntry("sold_out", true);
        call(r.admin(), get("/org/events/" + r.event() + "/rooms")).andExpect(status().isOk())
                .andExpect(jsonPath("$.rooms[0].figures.quota").value(100))
                .andExpect(jsonPath("$.rooms[0].figures.remaining").value(0))
                .andExpect(jsonPath("$.rooms[0].figures.sold_out").value(true));
    }

    /** 04 PATCH prices: a category moves to or from PASS only while sold = 0 (PASS_SOLD); other moves stay free. */
    @Test
    void audienceMovesToOrFromPassOnlyUnsold() throws Exception {
        Match m = match();
        String path = "/org/events/" + m.event() + "/prices/";
        setSold(m.cat(), 1);
        passSold(call(m.admin(), patch(path + m.cat()), "{\"audience\": \"PASS\", \"price_mad\": 0}"));
        call(m.admin(), patch(path + m.cat()), "{\"audience\": \"MEMBERS\"}").andExpect(status().isOk());
        call(m.admin(), patch(path + m.cat()), "{\"audience\": \"ALL\"}").andExpect(status().isOk());
        setSold(m.pass(), 1);
        passSold(call(m.admin(), patch(path + m.pass()), "{\"audience\": \"ALL\"}"));
        passSold(call(m.admin(), patch(path + m.pass()), "{\"audience\": \"MEMBERS\", \"price_mad\": 100}"));
        call(m.admin(), patch(path + m.pass()), "{\"audience\": \"PASS\", \"quota\": 4}").andExpect(status().isOk());
        setSold(m.pass(), 0);
        call(m.admin(), patch(path + m.pass()), "{\"audience\": \"ALL\", \"price_mad\": 100}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("ALL"));
    }

    /** The write itself never hands a fan or a till a PASS place, whatever the read before it saw. */
    @Test
    void aSaleNeverTakesAPassPlace() throws Exception {
        Match m = match();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Throwable refused = org.assertj.core.api.Assertions.catchThrowable(() -> tx.executeWithoutResult(s ->
                inventory.reserve(m.admin().org(), m.event(), Map.of(UUID.fromString(m.pass()), 1))));
        assertThat(refused).isInstanceOf(ApiException.class);
        assertThat(((ApiException) refused).code()).isEqualTo("SOLD_OUT");
        java.util.Optional<Map<UUID, Inventory.SalePrice>> late = tx.execute(s -> inventory.tryReserve(m.admin().org(),
                m.event(), Map.of(UUID.fromString(m.pass()), 1)));
        assertThat(late).isEmpty();
        assertThat(jdbc.sql("select sold from price_categories where id = ?::uuid").param(m.pass())
                .query(Integer.class).single()).isZero();
    }

    /**
     * A holder whose category became PASS (its zone has only PASS categories) still gets the wallet, the PDF and the
     * till slip: tickets read every zone, only GET /fan/events/{id} leaves PASS-only zones out.
     */
    @Test
    void ticketsInAPassOnlyZoneStillRead() throws Exception {
        Match m = match();
        String category = price(m.admin(), m.event(), m.est(), "Est", 5);
        String phone = randomPhone();
        call(m.admin(), post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        String order = JsonPath.read(body(call(m.admin(), post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d,
                 "items": [{"price_category_id": "%s", "qty": 1, "holders": [{"name": "Yazid Aqel", "phone": "%s"}]}]}"""
                .formatted(UUID.randomUUID(), m.event(), category, phone)).andExpect(status().isCreated())), "$.id");
        // As the pass job's places will be (BE-55): the zone's only categories are PASS.
        jdbc.sql("update price_categories set audience = 'PASS', price_mad = 0 where id = ?::uuid").param(category)
                .update();
        String fanEvent = body(mvc.perform(get("/fan/events/" + m.event())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(fanEvent, "$.zones[*].id")).doesNotContain(m.est());

        String token = fanToken(phone);
        String json = body(mvc.perform(get("/fan/tickets").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].zone.code")).containsExactly("EST");
        String ticket = JsonPath.<List<String>>read(json, "$.tickets[*].id").getFirst();
        mvc.perform(get("/fan/tickets/" + ticket + "/pdf").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        json = body(call(m.admin(), get("/org/pos/orders/" + order + "/tickets")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$.tickets[*].zone.code")).containsExactly("EST");
    }
}
