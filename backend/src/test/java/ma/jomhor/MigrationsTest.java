package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void v1ToV30Apply() {
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30");
    }

    /**
     * V29 + V30: the new checks were validated against every row there was (none is NOT VALID); an event order still
     * takes its event, a product order (event_id null) is APP or WEB and never a split member; an item is a place or a
     * product, never both or neither; a PASS category costs 0; products need their kind's fields; one live pass per
     * account per series; memberships.paid_until starts null.
     */
    @Test
    void productsPassesAndProductOrders() {
        assertThat(jdbcTemplate.queryForList("""
                select conname from pg_constraint
                where conname in ('orders_product', 'order_items_subject', 'price_categories_pass', 'products_kind')
                  and convalidated""", String.class))
                .containsExactlyInAnyOrder("orders_product", "order_items_subject", "price_categories_pass",
                        "products_kind");
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        var zone = jdbcTemplate.queryForObject("insert into zones (organizer_id, venue_id, code, name, capacity) "
                + "values (?, ?, 'EST', 'Est', 100) returning id", Short.class, org, venue);
        Integer event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""",
                Integer.class, org, venue, new byte[32]);
        var series = jdbcTemplate.queryForObject("""
                insert into event_series (organizer_id, name, starts_on, ends_on)
                values (?, 'Saison', '2026-08-01', '2027-06-30') returning id""", UUID.class, org);
        var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());

        String category = "insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota, "
                + "audience) values (?, ?, ?, ?, ?, 5, ?::price_audience) returning id";
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(category, UUID.class, org, event, zone, "P", 100, "PASS"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("price_categories_pass");
        var pass = jdbcTemplate.queryForObject(category, UUID.class, org, event, zone, "Abonnés", 0, "PASS");

        String product = "insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, series_id, "
                + "zone_id, valid_until) values (?, ?::product_kind, 'P', ?, now() + interval '1 day', ?, ?, ?, ?) "
                + "returning id";
        var validUntil = Timestamp.from(Instant.now().plus(Duration.ofDays(30)));
        for (Object[] bad : new Object[][] {{"PASS", 100, 3, series, null, null}, {"PASS", 100, null, series, zone, null},
                {"PASS", 100, 3, series, zone, validUntil}, {"MEMBERSHIP", 100, null, null, null, null}}) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject(product, UUID.class, org, bad[0], bad[1], bad[2],
                    bad[3], bad[4], bad[5])).as(Arrays.toString(bad))
                    .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("products_kind");
        }
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(product, UUID.class, org, "MEMBERSHIP", 0, null, null,
                null, validUntil)).isInstanceOf(DataIntegrityViolationException.class);
        var passProduct = jdbcTemplate.queryForObject(product, UUID.class, org, "PASS", 150_000, 3, series, zone, null);
        var membership = jdbcTemplate.queryForObject(product, UUID.class, org, "MEMBERSHIP", 20_000, null, null, null,
                validUntil);
        assertThatThrownBy(() -> jdbcTemplate.update("update products set sold = 4 where id = ?", passProduct))
                .isInstanceOf(DataIntegrityViolationException.class);

        String newPass = "insert into passes (organizer_id, product_id, series_id, user_id, holder_name, status) "
                + "values (?, ?, ?, ?, 'Yazid Aqel', ?::pass_status) returning id";
        jdbcTemplate.queryForObject(newPass, UUID.class, org, passProduct, series, user, "CANCELLED");
        var live = jdbcTemplate.queryForObject(newPass, UUID.class, org, passProduct, series, user, "PENDING");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(newPass, UUID.class, org, passProduct, series, user,
                "ACTIVE")).isInstanceOf(DuplicateKeyException.class);

        // EXPIRED: the expiry job, run by other test classes on this shared database, must never pick these up.
        String order = "insert into orders (organizer_id, event_id, pass_id, user_id, channel, status, total_mad, "
                + "reserved_until, group_order_id) values (?, ?, ?, ?, ?::sales_channel, 'EXPIRED', 0, now(), ?) "
                + "returning id";
        var eventOrder = jdbcTemplate.queryForObject(order, UUID.class, org, event, null, user, "APP", null);
        var productOrder = jdbcTemplate.queryForObject(order, UUID.class, org, null, live, user, "WEB", null);
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order, UUID.class, org, null, null, null, "POS", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_product");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order, UUID.class, org, null, null, user, "APP",
                eventOrder)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_product");
        jdbcTemplate.queryForObject(order, UUID.class, org, event, live, user, "APP", null); // the pass's ticket order
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order, UUID.class, org, event, live, user, "APP", null))
                .isInstanceOf(DuplicateKeyException.class); // one per pass per event

        String item = "insert into order_items (organizer_id, order_id, price_category_id, product_id, qty, "
                + "unit_price_mad, holders) values (?, ?, ?, ?, 1, 0, '[{\"name\": \"Yazid Aqel\"}]')";
        assertThatThrownBy(() -> jdbcTemplate.update(item, org, productOrder, null, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("order_items_subject");
        assertThatThrownBy(() -> jdbcTemplate.update(item, org, productOrder, pass, membership))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("order_items_subject");
        jdbcTemplate.update(item, org, productOrder, null, passProduct);
        jdbcTemplate.update(item, org, eventOrder, pass, null);

        jdbcTemplate.update("insert into memberships (organizer_id, user_id, number) values (?, ?, 1)", org, user);
        assertThat(jdbcTemplate.queryForObject("select paid_until from memberships where organizer_id = ?",
                Object.class, org)).isNull();
    }

    /**
     * V27 + V28: WAITLIST_OPEN needs its event and only it has one (a CHECK runs before the foreign keys); one waitlist
     * entry per account per event, organizer required.
     */
    @Test
    void waitlistOpenNeedsItsEventAndEntriesAreOnePerAccount() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into fan_messages (organizer_id, user_id, channel, kind)
                values (gen_random_uuid(), gen_random_uuid(), 'PUSH', 'WAITLIST_OPEN')"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_subject");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into fan_messages (organizer_id, user_id, channel, kind, order_id, event_id)
                values (gen_random_uuid(), gen_random_uuid(), 'PUSH', 'ORDER_PAID', gen_random_uuid(), 1)"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_subject");
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('W') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        var event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""", Integer.class, org, venue,
                new byte[32]);
        var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        jdbcTemplate.update("""
                insert into fan_messages (organizer_id, user_id, channel, kind, event_id)
                values (?, ?, 'WHATSAPP', 'WAITLIST_OPEN', ?)""", org, user, event);
        String join = "insert into waitlist_entries (organizer_id, event_id, user_id) values (?, ?, ?)";
        jdbcTemplate.update(join, org, event, user);
        assertThatThrownBy(() -> jdbcTemplate.update(join, org, event, user)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(join, null, event, user))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes where tablename = 'fan_messages'
                  and indexdef like 'CREATE INDEX%(event_id, created_at) WHERE (kind = ''WAITLIST_OPEN''::message_kind)'""",
                Integer.class)).isEqualTo(1);
    }

    /**
     * V23: users.verified_at is backfilled with the user's earliest fan session (the statement of the migration file,
     * re-run on this test's users only); a user who never logged in stays null.
     */
    @Test
    void verifiedAtBackfillIsTheEarliestSession() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V23__waiting_rooms.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("update users u");
        String backfill = sql.substring(start, sql.indexOf(';', start));

        UUID fan = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        UUID holder = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        jdbcTemplate.update("insert into fan_sessions (user_id, created_at, expires_at) values "
                + "(?, '2026-03-01T10:00:00Z', now()), (?, '2026-01-15T08:00:00Z', now()), (?, '2026-06-01T00:00:00Z', now())",
                fan, fan, fan);
        jdbcTemplate.update(backfill + " and u.id in (?, ?)", fan, holder);

        assertThat(jdbcTemplate.queryForObject("select verified_at = '2026-01-15T08:00:00Z' from users where id = ?",
                Boolean.class, fan)).isTrue();
        assertThat(jdbcTemplate.queryForObject("select verified_at from users where id = ?", Object.class, holder))
                .isNull();
    }

    /**
     * V23 + V26: one room per phase of an event (a row written without a phase, as every step-1 room, is phase 1 with
     * starts_at null), phases 1-5, starts_at set exactly for phase 2 and up, label 1-40 characters, the 03 ranges, and
     * admitted_through never above ranked_count.
     */
    @Test
    void waitingRoomsChecks() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        Integer event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""",
                Integer.class, org, venue, new byte[32]);
        jdbcTemplate.update("insert into waiting_rooms (organizer_id, event_id) values (?, ?)", org, event);
        assertThat(jdbcTemplate.queryForMap("select status::text as status, pre_queue_min, admission_rate_per_min, "
                + "phase, label, starts_at from waiting_rooms where event_id = ?", event))
                .containsEntry("status", "SCHEDULED").containsEntry("pre_queue_min", 30)
                .containsEntry("admission_rate_per_min", 500).containsEntry("phase", 1)
                .containsEntry("label", null).containsEntry("starts_at", null);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into waiting_rooms (organizer_id, event_id) values (?, ?)", org, event))
                .isInstanceOf(DuplicateKeyException.class);
        String phase = "insert into waiting_rooms (organizer_id, event_id, phase, label, starts_at) values (?, ?, ?, ?, ?)";
        var at = java.sql.Timestamp.from(java.time.Instant.now().plusSeconds(3600));
        jdbcTemplate.update(phase, org, event, 2, "Vente générale", at);
        assertThatThrownBy(() -> jdbcTemplate.update(phase, org, event, 2, null, at))
                .isInstanceOf(DuplicateKeyException.class);
        for (Object[] bad : new Object[][] {{3, null, null}, {1, null, at}, {6, null, at}, {0, null, at},
                {3, "", at}, {3, "x".repeat(41), at}}) {
            assertThatThrownBy(() -> jdbcTemplate.update(phase, org, event, bad[0], bad[1], bad[2]))
                    .as("phase %s label %s", bad[0], bad[1]).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbcTemplate.update(phase, org, event, 3, "x".repeat(40), at);
        for (String set : new String[] {"pre_queue_min = 1441", "admission_rate_per_min = 0",
                "admission_rate_per_min = 6001", "min_account_age_days = 0", "min_events_attended = 51",
                "admitted_through = 1"}) {
            assertThatThrownBy(() -> jdbcTemplate.update("update waiting_rooms set " + set
                    + " where event_id = ? and phase = 1", event))
                    .as(set).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    /** V25: a category written without an audience (every row before V25) is ALL; holders has its GIN index. */
    @Test
    void priceAudienceAndHoldersIndex() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        var zone = jdbcTemplate.queryForObject("insert into zones (organizer_id, venue_id, code, name, capacity) "
                + "values (?, ?, 'NORD', 'Nord', 100) returning id", Short.class, org, venue);
        Integer event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""",
                Integer.class, org, venue, new byte[32]);
        assertThat(jdbcTemplate.queryForObject("""
                insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota)
                values (?, ?, ?, 'Plein tarif', 5000, 10) returning audience::text""", String.class, org, event, zone))
                .isEqualTo("ALL");
        assertThat(jdbcTemplate.queryForList("select indexdef from pg_indexes where tablename = 'order_items'",
                String.class)).anyMatch(d -> d.contains("USING gin (holders jsonb_path_ops)"));
    }

    /** V24: one entry per account per room, one rank per room, 32-byte hashes, positive ranks, organizer required. */
    @Test
    void queueEntriesChecks() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        Integer event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""",
                Integer.class, org, venue, new byte[32]);
        var room = jdbcTemplate.queryForObject(
                "insert into waiting_rooms (organizer_id, event_id) values (?, ?) returning id", UUID.class, org, event);
        String insert = "insert into queue_entries (organizer_id, room_id, user_id, device_hash, ip_hash, draw_key, rank) "
                + "values (?, ?, ?, ?, ?, 7, ?)";
        var one = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        var two = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        byte[] hash = new byte[32];
        jdbcTemplate.update(insert, org, room, one, hash, hash, 1);
        assertThat(jdbcTemplate.queryForObject("select joined_at is not null and admitted_at is null "
                + "from queue_entries where user_id = ?", Boolean.class, one)).isTrue();
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, room, one, hash, hash, null))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, room, two, hash, hash, 1))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, room, two, new byte[16], hash, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, room, two, hash, new byte[33], null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, room, two, hash, hash, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, null, room, two, hash, hash, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** V22: a CAMPAIGN row needs its campaign and only it has one; EVENT_HOLDERS needs an event; one row per user. */
    @Test
    void campaignRowsAndHolderCampaignsNeedTheirSubject() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into fan_messages (organizer_id, user_id, channel, kind)
                values (gen_random_uuid(), gen_random_uuid(), 'PUSH', 'CAMPAIGN')"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_campaign");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into fan_messages (organizer_id, user_id, channel, kind, order_id, campaign_id)
                values (gen_random_uuid(), gen_random_uuid(), 'PUSH', 'ORDER_PAID', gen_random_uuid(),
                        gen_random_uuid())"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_campaign");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into campaigns (organizer_id, channel, audience, title, body, created_by)
                values (gen_random_uuid(), 'WHATSAPP', 'EVENT_HOLDERS', 'T', 'B', gen_random_uuid())"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("campaigns_event");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes where tablename = 'fan_messages'
                  and indexdef like 'CREATE UNIQUE INDEX%(campaign_id, user_id) WHERE (campaign_id IS NOT NULL)'""",
                Integer.class)).isEqualTo(1);
    }

    /** V21: an order kind needs its order (a CHECK runs before the foreign keys), and is queued once per channel. */
    @Test
    void fanMessagesNeedTheirSubjectAndAreUniquePerOrder() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into fan_messages (organizer_id, user_id, channel, kind)
                values (gen_random_uuid(), gen_random_uuid(), 'WHATSAPP', 'SPLIT_INVITE')"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_subject");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes where tablename = 'fan_messages'
                  and indexdef like 'CREATE UNIQUE INDEX%(order_id, kind, channel) WHERE%'""",
                Integer.class)).isEqualTo(1);
    }

    /** V20: an attempt returns to APP or WEB only (a CHECK runs before the foreign keys, so no rows are needed). */
    @Test
    void paymentReturnChannelRefusesPos() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into payments (organizer_id, order_id, method, provider, amount_mad, return_channel)
                values (gen_random_uuid(), gen_random_uuid(), 'CARD', 'FAKE', 1, 'POS')"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payments_return_channel_check");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'orders' and indexdef like '%(group_order_id) WHERE (group_order_id IS NOT NULL)'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void membershipNumbersArePositiveAndUniquePerOrganizer() {
        UUID org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        UUID other = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club 2') returning id", UUID.class);
        String user = "insert into users (phone_e164) values (?) returning id";
        UUID u1 = jdbcTemplate.queryForObject(user, UUID.class, randomPhone());
        UUID u2 = jdbcTemplate.queryForObject(user, UUID.class, randomPhone());
        String insert = "insert into memberships (organizer_id, user_id, number) values (?, ?, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, u1, 0))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("memberships_number_check");
        jdbcTemplate.update(insert, org, u1, 1);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, u2, 1)).isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("memberships_organizer_id_number_key");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, u1, 2)).isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("memberships_organizer_id_user_id_key");
        jdbcTemplate.update(insert, other, u2, 1); // numbers are per organizer
    }

    @Test
    void gateLocationIsBothOrNeitherAndInRange() {
        UUID org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Loc') returning id", UUID.class);
        UUID venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'V', 10) returning id", UUID.class, org);
        String insert = "insert into gates (organizer_id, venue_id, code, name, latitude, longitude) values (?, ?, ?, 'G', ?, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, venue, "A", 33.5, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("gates_location");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, venue, "B", null, -7.6))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("gates_location");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, venue, "C", new BigDecimal("91"),
                new BigDecimal("-7.6")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("gates_latitude_check");
        jdbcTemplate.update(insert, org, venue, "D", new BigDecimal("-90"), new BigDecimal("180"));
        jdbcTemplate.update(insert, org, venue, "E", null, null);
    }

    @Test
    void organizerSlugCheckUniqueAndNullable() {
        for (String bad : new String[] {"Atlas", "atlas--fc", "-atlas", "atlas-", "a", "a".repeat(41), "orders", "mine",
                "ar", "all", "events", "atlas_fc", "atlas fc"}) {
            assertThatThrownBy(() -> jdbcTemplate.update("insert into organizers (name, slug) values ('X', ?)", bad))
                    .as(bad)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("organizers_slug");
        }
        String ok = "t-" + UUID.randomUUID().toString().substring(24) + "-" + "9".repeat(20); // 2..40, digits ok
        jdbcTemplate.update("insert into organizers (name, slug) values ('X', ?)", ok);
        assertThatThrownBy(() -> jdbcTemplate.update("insert into organizers (name, slug) values ('Y', ?)", ok))
                .isInstanceOf(DuplicateKeyException.class);
        jdbcTemplate.update("insert into organizers (name) values ('No slug 1'), ('No slug 2')"); // nulls coexist
    }

    @Test
    void ticketTransfersAreUniqueOnBothTicketsAndCarryOrganizerId() {
        assertThat(jdbcTemplate.queryForList("""
                select a.attname from pg_index i
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
                where i.indrelid = 'ticket_transfers'::regclass and i.indisunique and not i.indisprimary
                """, String.class)).containsExactlyInAnyOrder("ticket_id", "new_ticket_id");
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'ticket_transfers' and column_name = 'organizer_id'
                """, String.class)).isEqualTo("NO");
    }

    @Test
    void scansRefuseUpdateDeleteAndTruncateEvenOnNoRow() {
        for (String sql : new String[] {"update scans set result = result where false", "delete from scans where false",
                "truncate scans"}) {
            assertThatThrownBy(() -> jdbcTemplate.execute(sql)).as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("scans is append-only");
        }
    }

    @Test
    void everyTableCarriesOrganizerIdExceptPlatformIdentityTables() {
        var withoutOrganizerId = jdbcTemplate.queryForList("""
                select t.table_name from information_schema.tables t
                where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                  and not exists (select 1 from information_schema.columns c
                                  where c.table_schema = t.table_schema and c.table_name = t.table_name
                                    and c.column_name = 'organizer_id')
                """, String.class);
        assertThat(withoutOrganizerId)
                .containsExactlyInAnyOrder("organizers", "users", "otp_challenges", "fan_sessions", "push_tokens",
                        "flyway_schema_history");
    }

    @Test
    void organizerIdIsNotNullExceptOnAuditLog() {
        var nullable = jdbcTemplate.queryForList("""
                select table_name from information_schema.columns
                where table_schema = 'public' and column_name = 'organizer_id' and is_nullable = 'YES'
                """, String.class);
        assertThat(nullable).containsExactly("audit_log");
    }

    @Test
    void soldAboveQuotaIsRejected() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('ATLAS FC') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 1000) returning id",
                UUID.class, org);
        var zone = jdbcTemplate.queryForObject(
                "insert into zones (organizer_id, venue_id, code, name, capacity) values (?, ?, 'NORD', 'Nord', 100) returning id",
                Short.class, org, venue);
        var event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '7 days', ?) returning id
                """, Integer.class, org, venue, new byte[32]);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota, sold)
                values (?, ?, ?, 'Plein tarif', 5000, 1, 2)
                """, org, event, zone))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint \"price_categories_check\"");
    }
}
