package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.Inventory;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.waitlist.application.WaitlistService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * BE-61: docs/04-api.md §Invitations Places and the prices rows INVITE_PRICE / INVITE_SOLD. An INVITE category costs 0
 * and is treated exactly as PASS wherever fans and tills read or buy; Inventory.takeInvite takes INVITE places only.
 */
class InviteCategoryTest extends QueueFixture {

    @Autowired
    WaitlistService waitlist;

    @Autowired
    Inventory inventory;

    @Autowired
    PlatformTransactionManager transactions;

    /** An ON_SALE event (no room) with "Cat 1" (ALL, 50 DH, quota 10) in NORD and "Presse" (INVITE, quota 5) in PRESSE. */
    record Match(Caller admin, int event, int nord, int presse, String cat, String invite) {}

    private Match match() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int nord = zone(a, venue, "NORD");
        int presse = zone(a, venue, "PRESSE");
        int event = event(a, venue);
        String cat = price(a, event, nord, "Cat 1", 10);
        String invite = invitePrice(a, event, presse, 5);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        return new Match(a, event, nord, presse, cat, invite);
    }

    private ResultActions postInvite(Caller a, int event, int zone, long priceMad, int quota) throws Exception {
        return call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Presse", "price_mad": %d, "quota": %d, "audience": "INVITE"}"""
                .formatted(zone, priceMad, quota));
    }

    private String invitePrice(Caller a, int event, int zone, int quota) throws Exception {
        return JsonPath.read(body(postInvite(a, event, zone, 0, quota).andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("INVITE"))), "$.id");
    }

    private static void refused(ResultActions r, String reason, String field) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.reason").value(reason))
                .andExpect(jsonPath("$.details.fields." + field).isString());
    }

    private static void categoryRefused(ResultActions r) throws Exception {
        r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['items[0].price_category_id']").isString());
    }

    private int sold(String category) {
        return jdbc.sql("select sold from price_categories where id = ?::uuid").param(category)
                .query(Integer.class).single();
    }

    @Test
    void anInviteCategoryCostsNothing() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int presse = zone(a, venue, "PRESSE");
        int event = event(a, venue);
        refused(postInvite(a, event, presse, 5000, 5), "INVITE_PRICE", "price_mad");
        String invite = invitePrice(a, event, presse, 5);
        String path = "/org/events/" + event + "/prices/";
        refused(call(a, patch(path + invite), "{\"price_mad\": 100}"), "INVITE_PRICE", "price_mad");
        String cat = price(a, event, presse, "Cat 1", 5);
        refused(call(a, patch(path + cat), "{\"audience\": \"INVITE\"}"), "INVITE_PRICE", "price_mad");
        call(a, patch(path + cat), "{\"audience\": \"INVITE\", \"price_mad\": 0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("INVITE")).andExpect(jsonPath("$.price_mad").value(0));
        call(a, patch(path + cat), "{\"audience\": \"ALL\", \"price_mad\": 4000}").andExpect(status().isOk());
        // The organizer still sees them; they count in the zone's capacity.
        String json = body(call(a, get("/org/events/" + event + "/prices")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[?(@.audience == 'INVITE')].id")).containsExactly(invite);
        call(a, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "Trop", "price_mad": 0, "quota": 91, "audience": "INVITE"}""".formatted(presse))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("QUOTA_EXCEEDS_CAPACITY"));
    }

    /** 04 PATCH prices: a category moves to or from INVITE only while sold = 0 (INVITE_SOLD). */
    @Test
    void audienceMovesToOrFromInviteOnlyUnsold() throws Exception {
        Match m = match();
        String path = "/org/events/" + m.event() + "/prices/";
        setSold(m.cat(), 1);
        refused(call(m.admin(), patch(path + m.cat()), "{\"audience\": \"INVITE\", \"price_mad\": 0}"),
                "INVITE_SOLD", "audience");
        setSold(m.invite(), 1);
        refused(call(m.admin(), patch(path + m.invite()), "{\"audience\": \"ALL\"}"), "INVITE_SOLD", "audience");
        refused(call(m.admin(), patch(path + m.invite()), "{\"audience\": \"MEMBERS\", \"price_mad\": 100}"),
                "INVITE_SOLD", "audience");
        call(m.admin(), patch(path + m.invite()), "{\"audience\": \"INVITE\", \"quota\": 4}").andExpect(status().isOk());
        setSold(m.invite(), 0);
        call(m.admin(), patch(path + m.invite()), "{\"audience\": \"PASS\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.audience").value("PASS"));
        call(m.admin(), patch(path + m.invite()), "{\"audience\": \"ALL\", \"price_mad\": 100}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.audience").value("ALL"));
    }

    @Test
    void fanReadsLeaveInvitePlacesOut() throws Exception {
        Match m = match();
        String json = body(mvc.perform(get("/fan/events/" + m.event())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Integer>>read(json, "$.zones[*].id")).containsExactly(m.nord()); // PRESSE: INVITE only
        assertThat(JsonPath.<List<String>>read(json, "$.zones[*].prices[*].price_category_id"))
                .containsExactly(m.cat());
        assertThat(JsonPath.<Integer>read(json, "$.from_price_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Boolean>read(json, "$.sold_out")).isFalse();

        setSold(m.cat(), 10); // only INVITE places left: sold out for fans
        json = body(mvc.perform(get("/fan/events/" + m.event())).andExpect(status().isOk()));
        assertThat(JsonPath.<Boolean>read(json, "$.sold_out")).isTrue();
        assertThat(JsonPath.<Object>read(json, "$.from_price_mad")).isNull();
        json = body(mvc.perform(get("/fan/events?organizer=" + m.admin().org())).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Boolean>>read(json, "$[?(@.id == %d)].sold_out".formatted(m.event())))
                .containsExactly(true);
        assertThat(JsonPath.<List<Object>>read(json, "$[?(@.id == %d)].from_price_mad".formatted(m.event())))
                .containsOnlyNulls();

        // The waitlist opens on that sold_out, and its R doesn't count the 5 INVITE places.
        Fan f = fan();
        mvc.perform(put("/fan/waitlist/" + m.event()).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isCreated());
        waitlist.notifyAt(Instant.now());
        assertThat(jdbc.sql("select count(*) from waitlist_entries where event_id = ? and notified_at is not null")
                .param(m.event()).query(Long.class).single()).isZero();
    }

    @Test
    void aFanOrderOrAPosSaleNamingAnInviteCategoryIsRefused() throws Exception {
        Match m = match();
        Fan f = fan();
        categoryRefused(mvc.perform(post("/fan/orders").header("Authorization", "Bearer " + f.token())
                .contentType("application/json").content("""
                        {"event_id": %d, "channel": "APP",
                         "items": [{"price_category_id": "%s", "qty": 1, "holders": %s}]}"""
                        .formatted(m.event(), m.invite(), holders(1)))));
        call(m.admin(), post("/org/pos/sessions"), "{\"label\": \"Guichet\"}").andExpect(status().isCreated());
        categoryRefused(call(m.admin(), post("/org/pos/orders"), """
                {"sale_id": "%s", "event_id": %d,
                 "items": [{"price_category_id": "%s", "qty": 1, "holders": [{"name": "Yazid Aqel"}]}]}"""
                .formatted(UUID.randomUUID(), m.event(), m.invite())));
        assertThat(sold(m.invite())).isZero();
    }

    @Test
    void aRoomIsSoldOutWhenOnlyInvitePlacesRemain() throws Exception {
        Room r = room("{}");
        UUID venue = jdbc.sql("select venue_id from events where id = ?").param(r.event()).query(UUID.class).single();
        invitePrice(r.admin(), r.event(), zone(r.admin(), venue.toString(), "PRESSE"), 5);
        tick(OPENS);
        tick(SALE);
        assertThat(roomRow(r.id())).containsEntry("sold_out", false);
        setSold(r.price(), 100);
        tick("2099-04-01T10:00:10Z");
        assertThat(roomRow(r.id())).containsEntry("sold_out", true);
    }

    /** The writes themselves: a sale never takes an INVITE place, and takeInvite takes nothing but INVITE places. */
    @Test
    void takesAreConditionalOnTheAudience() throws Exception {
        Match m = match();
        UUID org = m.admin().org();
        TransactionTemplate tx = new TransactionTemplate(transactions);
        Map<UUID, Integer> invite = Map.of(UUID.fromString(m.invite()), 1);
        assertThat(soldOut(() -> tx.executeWithoutResult(s -> inventory.reserve(org, m.event(), invite)))).isTrue();
        Optional<Map<UUID, Inventory.SalePrice>> late = tx.execute(s -> inventory.tryReserve(org, m.event(), invite));
        assertThat(late).isEmpty();
        assertThat(sold(m.invite())).isZero();

        Map<UUID, Inventory.SalePrice> taken = tx.execute(s -> inventory.takeInvite(org, m.event(),
                Map.of(UUID.fromString(m.invite()), 2)));
        assertThat(taken.get(UUID.fromString(m.invite())).priceMad()).isZero();
        assertThat(sold(m.invite())).isEqualTo(2);
        // An ALL category, or more than the quota: SOLD_OUT, and the whole take rolls back.
        assertThat(soldOut(() -> tx.executeWithoutResult(s -> inventory.takeInvite(org, m.event(),
                Map.of(UUID.fromString(m.cat()), 1))))).isTrue();
        assertThat(soldOut(() -> tx.executeWithoutResult(s -> inventory.takeInvite(org, m.event(),
                Map.of(UUID.fromString(m.invite()), 4))))).isTrue();
        assertThat(sold(m.cat())).isZero();
        assertThat(sold(m.invite())).isEqualTo(2);
    }

    private static boolean soldOut(Runnable take) {
        Throwable t = catchThrowable(take::run);
        return t instanceof ApiException e && e.code().equals("SOLD_OUT");
    }
}
