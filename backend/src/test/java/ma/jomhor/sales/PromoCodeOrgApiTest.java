package ma.jomhor.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BE-64: docs/04-api.md §Promo codes and §Organizer GET, POST /org/events/{id}/promo-codes, PATCH
 * /org/events/{id}/promo-codes/{promo_id}; EVENT_NOT_EDITABLE for a promo-code create; the Lot 5 step 4 role line.
 */
class PromoCodeOrgApiTest extends OrgApiTest {

    private static final String[] ROW_KEYS = {"id", "code", "kind", "percent", "amount_mad", "price_category",
        "max_uses", "per_account_max", "active", "created_at", "uses", "discount_mad"};

    /** A DRAFT event: "Plein tarif" (ALL) in EST, "Abonnés" (PASS) and "Presse" (INVITE). */
    private record Setup(Caller admin, String venue, int event, int est, String plein, String pass, String invite) {}

    private Setup setup() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int est = zone(a, venue, "EST");
        int event = event(a, venue);
        String plein = price(a, event, est, "Plein tarif", 10);
        String pass = category(a, event, est, "Abonnés", "PASS");
        String invite = category(a, event, est, "Presse", "INVITE");
        return new Setup(a, venue, event, est, plein, pass, invite);
    }

    private String category(Caller c, int event, int zone, String name, String audience) throws Exception {
        return JsonPath.read(body(call(c, post("/org/events/" + event + "/prices"), """
                {"zone_id": %d, "name": "%s", "price_mad": 0, "quota": 5, "audience": "%s"}"""
                .formatted(zone, name, audience)).andExpect(status().isCreated())), "$.id");
    }

    private ResultActions create(Caller c, int event, String body) throws Exception {
        return call(c, post("/org/events/" + event + "/promo-codes"), body);
    }

    private ResultActions update(Caller c, int event, String id, String body) throws Exception {
        return call(c, patch("/org/events/" + event + "/promo-codes/" + id), body);
    }

    private ResultActions refused(ResultActions r, String field) throws Exception {
        return r.andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields." + field).exists());
    }

    @Test
    void createsPercentAndAmountCodesNormalized() throws Exception {
        Setup s = setup();
        String json = body(create(s.admin(), s.event(), """
                {"code": "  abonnes20 ", "kind": "PERCENT", "percent": 20, "price_category_id": "%s",
                 "max_uses": 100}""".formatted(s.plein())).andExpect(status().isCreated()));
        assertKeys(json, "$", ROW_KEYS);
        assertKeys(json, "$.price_category", "id", "name", "zone");
        assertKeys(json, "$.price_category.zone", "code", "name");
        assertKeys(json, "$.uses", "held", "paid");
        assertThat(JsonPath.<String>read(json, "$.code")).isEqualTo("ABONNES20");
        assertThat(JsonPath.<String>read(json, "$.kind")).isEqualTo("PERCENT");
        assertThat(JsonPath.<Integer>read(json, "$.percent")).isEqualTo(20);
        assertThat(JsonPath.<Object>read(json, "$.amount_mad")).isNull();
        assertThat(JsonPath.<String>read(json, "$.price_category.id")).isEqualTo(s.plein());
        assertThat(JsonPath.<String>read(json, "$.price_category.name")).isEqualTo("Plein tarif");
        assertThat(JsonPath.<String>read(json, "$.price_category.zone.code")).isEqualTo("EST");
        assertThat(JsonPath.<Integer>read(json, "$.max_uses")).isEqualTo(100);
        assertThat(JsonPath.<Integer>read(json, "$.per_account_max")).isEqualTo(1);
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isTrue();
        assertThat(JsonPath.<Integer>read(json, "$.uses.held")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$.uses.paid")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$.discount_mad")).isZero();

        json = body(create(s.admin(), s.event(), """
                {"code": "DERBY50", "kind": "AMOUNT", "amount_mad": 5000, "per_account_max": 3, "active": false}""")
                .andExpect(status().isCreated()));
        assertKeys(json, "$", ROW_KEYS);
        assertThat(JsonPath.<Object>read(json, "$.price_category")).isNull();
        assertThat(JsonPath.<Object>read(json, "$.percent")).isNull();
        assertThat(JsonPath.<Integer>read(json, "$.amount_mad")).isEqualTo(5000);
        assertThat(JsonPath.<Object>read(json, "$.max_uses")).isNull();
        assertThat(JsonPath.<Integer>read(json, "$.per_account_max")).isEqualTo(3);
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isFalse();

        // ON_SALE is editable too.
        call(s.admin(), post("/org/events/" + s.event() + "/publish")).andExpect(status().isOk());
        create(s.admin(), s.event(), """
                {"code": "VIRAGE10", "kind": "PERCENT", "percent": 10}""").andExpect(status().isCreated());

        String audit = jdbc.sql("select payload::text from audit_log where action = 'promo.create' and target = ?")
                .param(JsonPath.<String>read(json, "$.id")).query(String.class).single();
        assertKeys(audit, "$", "code", "kind", "percent", "amount_mad", "price_category_id", "max_uses",
                "per_account_max", "active");
        assertThat(JsonPath.<String>read(audit, "$.code")).isEqualTo("DERBY50");
    }

    @Test
    void refusesBadFieldsTakenCodesAndClosedEvents() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        int e = s.event();
        for (String code : new String[] {"ab1", "ab-12", "ABCDEFGHIJKLMNOPQRSTU", "", "   "}) {
            refused(create(a, e, """
                    {"code": "%s", "kind": "PERCENT", "percent": 20}""".formatted(code)), "code");
        }
        refused(create(a, e, "{\"kind\": \"PERCENT\", \"percent\": 20}"), "code");
        refused(create(a, e, "{\"code\": \"ABCD\", \"percent\": 20}"), "kind");

        create(a, e, "{\"code\": \"abcd\", \"kind\": \"PERCENT\", \"percent\": 20}").andExpect(status().isCreated());
        refused(create(a, e, "{\"code\": \"ABCD\", \"kind\": \"AMOUNT\", \"amount_mad\": 100}"), "code")
                .andExpect(jsonPath("$.details.reason").value("TAKEN"));
        int other = event(a, s.venue());
        create(a, other, "{\"code\": \"ABCD\", \"kind\": \"AMOUNT\", \"amount_mad\": 100}")
                .andExpect(status().isCreated());

        for (int percent : new int[] {0, 100, -5}) {
            refused(create(a, e, """
                    {"code": "PCT%d", "kind": "PERCENT", "percent": %d}""".formatted(Math.abs(percent), percent)),
                    "percent");
        }
        refused(create(a, e, "{\"code\": \"BOTH1\", \"kind\": \"PERCENT\", \"percent\": 20, \"amount_mad\": 100}"),
                "amount_mad");
        refused(create(a, e, "{\"code\": \"BOTH2\", \"kind\": \"AMOUNT\", \"percent\": 20, \"amount_mad\": 100}"),
                "percent");
        refused(create(a, e, "{\"code\": \"NONE1\", \"kind\": \"PERCENT\"}"), "percent");
        refused(create(a, e, "{\"code\": \"NONE2\", \"kind\": \"AMOUNT\"}"), "amount_mad");
        refused(create(a, e, "{\"code\": \"ZERO1\", \"kind\": \"AMOUNT\", \"amount_mad\": 0}"), "amount_mad");

        int otherZone = zone(a, s.venue(), "NORD");
        String foreign = price(a, other, otherZone, "Plein tarif", 5);
        for (String category : new String[] {foreign, s.pass(), s.invite(), UUID.randomUUID().toString()}) {
            refused(create(a, e, """
                    {"code": "CAT1", "kind": "PERCENT", "percent": 20, "price_category_id": "%s"}"""
                    .formatted(category)), "price_category_id");
        }
        refused(create(a, e, "{\"code\": \"CAPS1\", \"kind\": \"PERCENT\", \"percent\": 5, \"max_uses\": 0}"),
                "max_uses");
        for (int perAccount : new int[] {0, 101}) {
            refused(create(a, e, """
                    {"code": "CAPS2", "kind": "PERCENT", "percent": 5, "per_account_max": %d}""".formatted(perAccount)),
                    "per_account_max");
        }

        for (String status : new String[] {"GATES_OPEN", "CLOSED"}) {
            setStatus(other, status);
            create(a, other, "{\"code\": \"LATE1\", \"kind\": \"PERCENT\", \"percent\": 5}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
        }
        assertThat(jdbc.sql("select count(*) from promo_codes where event_id in (?, ?)").params(e, other)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void patchesCapsAndActiveButNotFixedFields() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        int e = s.event();
        String id = JsonPath.read(body(create(a, e, """
                {"code": "ABONNES20", "kind": "PERCENT", "percent": 20, "price_category_id": "%s", "max_uses": 100}"""
                .formatted(s.plein())).andExpect(status().isCreated())), "$.id");

        String json = body(update(a, e, id, "{\"active\": false}").andExpect(status().isOk()));
        assertKeys(json, "$", ROW_KEYS);
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isFalse();
        assertThat(JsonPath.<Integer>read(json, "$.max_uses")).isEqualTo(100);
        assertThat(JsonPath.<String>read(json, "$.price_category.id")).isEqualTo(s.plein());

        json = body(update(a, e, id, "{\"max_uses\": 5, \"per_account_max\": 2}")
                .andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.max_uses")).isEqualTo(5);
        assertThat(JsonPath.<Integer>read(json, "$.per_account_max")).isEqualTo(2);
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isFalse();
        // max_uses can't be cleared: null = unchanged.
        json = body(update(a, e, id, "{\"max_uses\": null, \"active\": true}").andExpect(status().isOk()));
        assertThat(JsonPath.<Integer>read(json, "$.max_uses")).isEqualTo(5);
        assertThat(JsonPath.<Boolean>read(json, "$.active")).isTrue();
        // The fixed fields sent unchanged are accepted: no changeable field, so no write and no audit. A changeable
        // field is written as sent, even with its current value (audited).
        update(a, e, id, """
                {"code": "abonnes20", "kind": "PERCENT", "percent": 20, "price_category_id": "%s"}"""
                .formatted(s.plein())).andExpect(status().isOk());
        update(a, e, id, "{\"active\": true, \"max_uses\": 5}").andExpect(status().isOk());

        refused(update(a, e, id, "{\"code\": \"OTHER20\"}"), "code");
        refused(update(a, e, id, "{\"kind\": \"AMOUNT\"}"), "kind");
        refused(update(a, e, id, "{\"percent\": 30}"), "percent");
        refused(update(a, e, id, "{\"amount_mad\": 100}"), "amount_mad");
        refused(update(a, e, id, "{\"price_category_id\": \"%s\"}".formatted(s.pass())), "price_category_id");
        refused(update(a, e, id, "{\"max_uses\": 0}"), "max_uses");
        refused(update(a, e, id, "{\"per_account_max\": 101}"), "per_account_max");
        assertThat(jdbc.sql("select max_uses || ':' || per_account_max || ':' || active from promo_codes where id = ?::uuid")
                .param(id).query(String.class).single()).isEqualTo("5:2:true");

        update(a, e, UUID.randomUUID().toString(), "{\"active\": false}").andExpect(status().isNotFound());
        int other = event(a, s.venue());
        update(a, other, id, "{\"active\": false}").andExpect(status().isNotFound());

        List<String> audits = jdbc.sql("select payload::text from audit_log where action = 'promo.update' and target = ?"
                + " order by id").param(id).query(String.class).list();
        assertThat(audits).hasSize(4);
        assertKeys(audits.get(0), "$", "max_uses", "per_account_max", "active");
    }

    /** Each PATCH writes only its own field: two at once on different fields both survive. */
    @Test
    void concurrentPatchesOfDifferentFieldsBothSurvive() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        Caller ops = caller(a.org(), "OPS");
        int e = s.event();
        String id = JsonPath.read(body(create(a, e, "{\"code\": \"RACE1\", \"kind\": \"PERCENT\", \"percent\": 5}")
                .andExpect(status().isCreated())), "$.id");
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int round = 1; round <= 10; round++) {
                boolean active = round % 2 == 0;
                int maxUses = 10 + round;
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> done = new ArrayList<>();
                done.add(pool.submit(() -> {
                    start.await();
                    return update(a, e, id, "{\"active\": %s}".formatted(active)).andReturn().getResponse().getStatus();
                }));
                done.add(pool.submit(() -> {
                    start.await();
                    return update(ops, e, id, "{\"max_uses\": %d}".formatted(maxUses)).andReturn().getResponse()
                            .getStatus();
                }));
                start.countDown();
                for (Future<Integer> f : done) {
                    assertThat(f.get()).isEqualTo(200);
                }
                assertThat(jdbc.sql("select active || ':' || max_uses from promo_codes where id = ?::uuid").param(id)
                        .query(String.class).single()).as("round " + round).isEqualTo(active + ":" + maxUses);
            }
        }
    }

    /** 04 prices DELETE: a category a promo code targets is in use, even unsold. */
    @Test
    void aCategoryWithACodeCantBeDeleted() throws Exception {
        Setup s = setup();
        create(s.admin(), s.event(), """
                {"code": "TARGET1", "kind": "PERCENT", "percent": 5, "price_category_id": "%s"}"""
                .formatted(s.plein())).andExpect(status().isCreated());
        call(s.admin(), delete("/org/events/" + s.event() + "/prices/" + s.plein()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRICE_IN_USE"));
        call(s.admin(), delete("/org/events/" + s.event() + "/prices/" + s.pass())).andExpect(status().isNoContent());
    }

    @Test
    void listsByCodeWithUsesAndDiscountFromOrders() throws Exception {
        Setup s = setup();
        Caller a = s.admin();
        int e = s.event();
        String zulu = JsonPath.read(body(create(a, e, "{\"code\": \"ZULU1\", \"kind\": \"AMOUNT\", \"amount_mad\": 900}")
                .andExpect(status().isCreated())), "$.id");
        String alpha = JsonPath.read(body(create(a, e, """
                {"code": "ALPHA2", "kind": "PERCENT", "percent": 20, "price_category_id": "%s"}"""
                .formatted(s.plein())).andExpect(status().isCreated())), "$.id");
        create(a, e, "{\"code\": \"B0B0\", \"kind\": \"PERCENT\", \"percent\": 10}").andExpect(status().isCreated());

        // ALPHA2: 2 held (RESERVED, AWAITING_CASH: by status, as the cap), an EXPIRED (not counted), 2 PAID.
        UUID user = jdbc.sql("insert into users (phone_e164) values (?) returning id").param(randomPhone())
                .query(UUID.class).single();
        seed(a.org(), e, user, alpha, "RESERVED", "now() + interval '8 minutes'", 1000);
        seed(a.org(), e, user, alpha, "AWAITING_CASH", "now() + interval '1 day'", 1000);
        seed(a.org(), e, user, alpha, "EXPIRED", "now() - interval '1 minute'", 1000);
        seed(a.org(), e, user, alpha, "PAID", "now()", 6000);
        seed(a.org(), e, user, alpha, "PAID", "now()", 3000);
        seed(a.org(), e, user, zulu, "PAID", "now()", 900);

        String json = body(call(a, get("/org/events/" + e + "/promo-codes")).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(json, "$[*].code")).containsExactly("ALPHA2", "B0B0", "ZULU1");
        assertKeys(json, "$[0]", ROW_KEYS);
        assertThat(JsonPath.<Integer>read(json, "$[0].uses.held")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$[0].uses.paid")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(json, "$[0].discount_mad")).isEqualTo(9000);
        assertThat(JsonPath.<String>read(json, "$[0].price_category.name")).isEqualTo("Plein tarif");
        assertThat(JsonPath.<Integer>read(json, "$[1].uses.paid")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$[1].discount_mad")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$[2].uses.held")).isZero();
        assertThat(JsonPath.<Integer>read(json, "$[2].uses.paid")).isOne();
        assertThat(JsonPath.<Integer>read(json, "$[2].discount_mad")).isEqualTo(900);

        // A PATCH answers the list row with its figures.
        update(a, e, alpha, "{\"active\": false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.uses.held").value(2)).andExpect(jsonPath("$.discount_mad").value(9000));

        // Another event of the organizer has none.
        call(a, get("/org/events/" + event(a, s.venue()) + "/promo-codes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * An order of the code with no items: only its status and discount matter to the figures. EXPIRED, PAID and holds in
     * the future are never touched by the expiry job.
     */
    private void seed(UUID org, int event, UUID user, String promo, String status, String until, long discount) {
        jdbc.sql("""
                insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, reserved_until, paid_at,
                                    promo_code_id, discount_mad)
                values (?, ?, ?, 'APP', ?::order_status, 100, %s, case when ? = 'PAID' then now() end, ?::uuid, ?)"""
                .formatted(until)).params(org, event, user, status, status, promo, discount).update();
    }

    @Test
    void rolesAndTenancy() throws Exception {
        Setup s = setup();
        int e = s.event();
        String id = JsonPath.read(body(create(s.admin(), e, "{\"code\": \"ROLE1\", \"kind\": \"PERCENT\", \"percent\": 5}")
                .andExpect(status().isCreated())), "$.id");
        String body = "{\"code\": \"ROLE2\", \"kind\": \"PERCENT\", \"percent\": 5}";

        UUID org = s.admin().org();
        Caller ops = caller(org, "OPS");
        create(ops, e, body).andExpect(status().isCreated());
        update(ops, e, id, "{\"active\": false}").andExpect(status().isOk());

        Caller finance = caller(org, "FINANCE");
        call(finance, get("/org/events/" + e + "/promo-codes")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        create(finance, e, "{\"code\": \"ROLE3\", \"kind\": \"PERCENT\", \"percent\": 5}")
                .andExpect(status().isForbidden());
        update(finance, e, id, "{\"active\": true}").andExpect(status().isForbidden());
        for (String role : new String[] {"CASHIER", "GATE", "SUPERVISOR"}) {
            Caller c = caller(org, role);
            call(c, get("/org/events/" + e + "/promo-codes")).andExpect(status().isForbidden());
            create(c, e, "{\"code\": \"ROLE4\", \"kind\": \"PERCENT\", \"percent\": 5}").andExpect(status().isForbidden());
            update(c, e, id, "{\"active\": true}").andExpect(status().isForbidden());
        }

        Caller other = admin();
        call(other, get("/org/events/" + e + "/promo-codes")).andExpect(status().isNotFound());
        create(other, e, "{\"code\": \"ROLE5\", \"kind\": \"PERCENT\", \"percent\": 5}")
                .andExpect(status().isNotFound());
        update(other, e, id, "{\"active\": true}").andExpect(status().isNotFound());
        call(s.admin(), get("/org/events/999999999/promo-codes")).andExpect(status().isNotFound());
        assertThat(jdbc.sql("select code || ':' || active from promo_codes where event_id = ? order by code")
                .param(e).query(String.class).list()).containsExactly("ROLE1:false", "ROLE2:true");
    }
}
