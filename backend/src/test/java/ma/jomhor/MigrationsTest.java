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
    void v1ToV37Apply() {
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32",
                        "33", "34", "35", "36", "37");
    }

    /**
     * V37: a statement's status agrees with its payout and payout fields (settlements_status); a line has exactly one
     * subject (settlement_lines_subject); an invoice's TTC is HT + VAT (invoices_ttc), its number and (year, seq)
     * unique; invoices refuse UPDATE, DELETE and TRUNCATE, even on no row.
     */
    @Test
    void settlementsAndImmutableInvoices() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        var staff = jdbcTemplate.queryForObject("insert into staff (organizer_id, user_id, name, role) "
                + "values (?, ?, 'Salma', 'FINANCE') returning id", UUID.class, org, user);
        String statement = "insert into settlements (organizer_id, day, collected_mad, till_mad, fee_rate_bp, "
                + "fee_ht_mad, fee_vat_mad, carried_in_mad, payout_mad, due_on, status, paid_at, paid_by, "
                + "payout_reference) values (?, current_date - ?, 0, 0, 0, 0, 0, 0, ?, current_date, "
                + "?::settlement_status, ?, ?, ?) returning id";
        Timestamp now = Timestamp.from(Instant.now());
        Object[][] bad = {{1, 0L, "DUE", null, null, null}, {2, 100L, "NONE", null, null, null},
            {3, 100L, "PAID", null, null, null}, {4, 100L, "DUE", now, staff, "VIR"},
            {5, 100L, "PAID", now, null, "VIR"}, {6, 100L, "PAID", now, staff, null}};
        for (Object[] b : bad) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject(statement, UUID.class, org, b[0], b[1], b[2], b[3],
                    b[4], b[5])).as(Arrays.toString(b)).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("settlements_status");
        }
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(statement, UUID.class, org, 7, 0L, "NONE", null, null,
                "")).isInstanceOf(DataIntegrityViolationException.class);
        var paid = jdbcTemplate.queryForObject(statement, UUID.class, org, 8, 100L, "PAID", now, staff, "VIR 0042");
        var none = jdbcTemplate.queryForObject(statement, UUID.class, org, 9, 0L, "NONE", null, null, null);
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(statement, UUID.class, org, 9, 0L, "NONE", null, null,
                null)).isInstanceOf(DuplicateKeyException.class); // one statement per organizer and day

        var product = jdbcTemplate.queryForObject("insert into products (organizer_id, kind, name, price_mad, "
                + "sales_close_at, valid_until) values (?, 'MEMBERSHIP', 'Adhésion', 100, now(), now()) returning id",
                UUID.class, org);
        String line = "insert into settlement_lines (organizer_id, settlement_id, event_id, product_id, method, "
                + "collector, payments, amount_mad) values (?, ?, null, ?, 'CARD', 'FAKE', ?, 100)";
        assertThatThrownBy(() -> jdbcTemplate.update(line, org, paid, null, 1))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("settlement_lines_subject");
        assertThatThrownBy(() -> jdbcTemplate.update(line, org, paid, product, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update(line, org, paid, product, 1);

        String invoice = "insert into invoices (organizer_id, settlement_id, year, seq, number, issued_on, issuer, "
                + "customer, amount_ht_mad, vat_rate_bp, vat_mad, amount_ttc_mad) values (?, ?, 1999, ?, ?, "
                + "current_date, '{}', '{}', 100, 2000, 20, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(invoice, org, paid, 1, "JMH-1999-000001", 119L))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("invoices_ttc");
        jdbcTemplate.update(invoice, org, paid, 1, "JMH-1999-000001", 120L);
        assertThatThrownBy(() -> jdbcTemplate.update(invoice, org, none, 1, "JMH-1999-000001-b", 120L))
                .isInstanceOf(DuplicateKeyException.class); // (year, seq)
        assertThatThrownBy(() -> jdbcTemplate.update(invoice, org, paid, 2, "JMH-1999-000002", 120L))
                .isInstanceOf(DuplicateKeyException.class); // one invoice per statement
        for (String sql : new String[] {"update invoices set vat_mad = vat_mad", "update invoices set seq = seq where false",
                "delete from invoices where false", "delete from invoices where year = 1999", "truncate invoices"}) {
            assertThatThrownBy(() -> jdbcTemplate.execute(sql)).as(sql).isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("invoices are immutable");
        }
    }

    /**
     * V36: a deposit takes only closed sessions (pos_sessions_deposit); a void sets its time, staff and reason together
     * (cash_deposits_void); amounts are >= 0 and references 1-100 characters.
     */
    @Test
    void cashDepositsTakeClosedSessionsOnly() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());
        var staff = jdbcTemplate.queryForObject("insert into staff (organizer_id, user_id, name, role) "
                + "values (?, ?, 'Salma', 'FINANCE') returning id", UUID.class, org, user);
        String deposit = "insert into cash_deposits (organizer_id, deposited_on, amount_mad, reference, recorded_by) "
                + "values (?, current_date, ?, ?, ?) returning id";
        for (Object[] b : new Object[][] {{-1L, "R"}, {0L, ""}, {0L, "x".repeat(101)}}) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject(deposit, UUID.class, org, b[0], b[1], staff))
                    .as(Arrays.toString(b)).isInstanceOf(DataIntegrityViolationException.class);
        }
        var id = jdbcTemplate.queryForObject(deposit, UUID.class, org, 0L, "x".repeat(100), staff);
        assertThatThrownBy(() -> jdbcTemplate.update("update cash_deposits set voided_at = now() where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("cash_deposits_void");
        assertThatThrownBy(() -> jdbcTemplate.update("update cash_deposits set voided_at = now(), voided_by = ?, "
                + "void_reason = '' where id = ?", staff, id)).isInstanceOf(DataIntegrityViolationException.class);

        var open = jdbcTemplate.queryForObject("insert into pos_sessions (organizer_id, staff_id, label) "
                + "values (?, ?, 'Nord') returning id", UUID.class, org, staff);
        assertThatThrownBy(() -> jdbcTemplate.update("update pos_sessions set deposit_id = ? where id = ?", id, open))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("pos_sessions_deposit");
        jdbcTemplate.update("update pos_sessions set closed_at = now(), expected_mad = 0, declared_mad = 0, "
                + "deposit_id = ? where id = ?", id, open);
        assertThatThrownBy(() -> jdbcTemplate.update("update pos_sessions set closed_at = null, expected_mad = null, "
                + "declared_mad = null where id = ?", open)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("pos_sessions_deposit");
        jdbcTemplate.update("update cash_deposits set voided_at = now(), voided_by = ?, void_reason = 'erreur' "
                + "where id = ?", staff, id);
    }

    /**
     * V29 + V30: the new checks were validated against every row there was (none is NOT VALID); an event order still
     * takes its event, a product order (event_id null) is APP or WEB and never a split member; an item is a place or a
     * product, never both or neither; a PASS category costs 0 (price_categories_free since V34); products need their
     * kind's fields; one live pass per account per series; memberships.paid_until starts null.
     */
    @Test
    void productsPassesAndProductOrders() {
        assertThat(jdbcTemplate.queryForList("""
                select conname from pg_constraint
                where conname in ('orders_product', 'order_items_subject', 'price_categories_free', 'products_kind')
                  and convalidated""", String.class))
                .containsExactlyInAnyOrder("orders_product", "order_items_subject", "price_categories_free",
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
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("price_categories_free");
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
     * V33 + V34: price_categories_free replaces price_categories_pass (PASS and INVITE cost 0); orders_invited keeps
     * invited_by to POS orders of an event with no buyer and no pass; the V34 delete (the statement of the migration
     * file, re-run on this test's rows only) drops REQUIRED refunds of 0 and keeps DONE ones and every non-zero one.
     */
    @Test
    void invitationsAndZeroAmountRefunds() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V34__invitations.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("delete from refunds");
        String delete = sql.substring(start, sql.indexOf(';', start));
        assertThat(jdbcTemplate.queryForList("""
                select conname from pg_constraint
                where conname in ('price_categories_free', 'price_categories_pass', 'orders_invited') and convalidated
                """, String.class)).containsExactlyInAnyOrder("price_categories_free", "orders_invited");

        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        var zone = jdbcTemplate.queryForObject("insert into zones (organizer_id, venue_id, code, name, capacity) "
                + "values (?, ?, 'PRESSE', 'Presse', 100) returning id", Short.class, org, venue);
        Integer event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""",
                Integer.class, org, venue, new byte[32]);
        var staff = jdbcTemplate.queryForObject(
                "insert into staff (organizer_id, name, role) values (?, 'Agent', 'OPS') returning id", UUID.class, org);
        var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());

        String category = "insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota, "
                + "audience) values (?, ?, ?, ?, ?, 5, ?::price_audience) returning id";
        for (String audience : new String[] {"INVITE", "PASS"}) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject(category, UUID.class, org, event, zone, audience, 100,
                    audience)).as(audience).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("price_categories_free");
        }
        var invite = jdbcTemplate.queryForObject(category, UUID.class, org, event, zone, "Presse", 0, "INVITE");

        String order = "insert into orders (organizer_id, event_id, user_id, channel, status, payment_method, "
                + "total_mad, reserved_until, paid_at, invited_by) values (?, ?, ?, ?::sales_channel, 'PAID', 'FREE', 0, "
                + "now(), now(), ?) returning id";
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order, UUID.class, org, event, user, "APP", staff))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_invited");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order, UUID.class, org, event, user, "POS", staff))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_invited");
        var invitation = jdbcTemplate.queryForObject(order, UUID.class, org, event, null, "POS", staff);

        // One ticket per refund (amount, status).
        Object[][] refunds = {{0, "REQUIRED"}, {0, "DONE"}, {5000, "REQUIRED"}, {5000, "DONE"}};
        for (Object[] r : refunds) {
            var ticket = jdbcTemplate.queryForObject("insert into tickets (organizer_id, order_id, event_id, zone_id, "
                    + "price_category_id, holder_name, holder_initials) values (?, ?, ?, ?, ?, 'Yazid Aqel', 'Y. A.') "
                    + "returning id", UUID.class, org, invitation, event, zone, invite);
            boolean done = r[1].equals("DONE");
            jdbcTemplate.update("insert into refunds (organizer_id, event_id, ticket_id, order_id, amount_mad, reason, "
                    + "status, requested_by, done_by, done_at, reference) values (?, ?, ?, ?, ?, 'x', "
                    + "?::refund_status, ?, ?, ?, ?)", org, event, ticket, invitation, r[0], r[1], staff,
                    done ? staff : null, done ? Timestamp.from(Instant.now()) : null, done ? "ref" : null);
        }
        jdbcTemplate.update(delete + " and organizer_id = ?", org);
        assertThat(jdbcTemplate.queryForList("select amount_mad || ':' || status from refunds where organizer_id = ? "
                + "order by amount_mad, status", String.class, org))
                .containsExactly("0:DONE", "5000:REQUIRED", "5000:DONE");
    }

    /**
     * V35: promo_codes checks (format, kind fields, ranges, one code per event); orders.discount_mad defaults to 0 for
     * rows that predate it and orders_promo keeps a discount to orders with a code.
     */
    @Test
    void promoCodesAndOrderDiscounts() {
        assertThat(jdbcTemplate.queryForList("select conname from pg_constraint where conname in ('orders_promo', "
                + "'promo_codes_kind') and convalidated", String.class))
                .containsExactlyInAnyOrder("orders_promo", "promo_codes_kind");
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        Integer event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '1 day', ?) returning id""",
                Integer.class, org, venue, new byte[32]);
        var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                randomPhone());

        String promo = "insert into promo_codes (organizer_id, event_id, code, kind, percent, amount_mad, max_uses, "
                + "per_account_max) values (?, ?, ?, ?::promo_kind, ?, ?, ?, ?) returning id";
        Object[][] bad = {{"abcd", "PERCENT", 20, null, null, 1}, {"AB1", "PERCENT", 20, null, null, 1},
            {"AB-12", "PERCENT", 20, null, null, 1}, {"PCT1", "PERCENT", 0, null, null, 1},
            {"PCT2", "PERCENT", 100, null, null, 1}, {"BOTH", "PERCENT", 20, 100L, null, 1},
            {"NONE", "AMOUNT", null, null, null, 1}, {"ZERO", "AMOUNT", null, 0L, null, 1},
            {"CAP1", "AMOUNT", null, 100L, 0, 1}, {"CAP2", "AMOUNT", null, 100L, null, 0},
            {"CAP3", "AMOUNT", null, 100L, null, 101}};
        for (Object[] b : bad) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject(promo, UUID.class, org, event, b[0], b[1], b[2], b[3],
                    b[4], b[5])).as(Arrays.toString(b)).isInstanceOf(DataIntegrityViolationException.class);
        }
        var code = jdbcTemplate.queryForObject(promo, UUID.class, org, event, "DERBY50", "AMOUNT", null, 5000L, 2, 1);
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(promo, UUID.class, org, event, "DERBY50", "PERCENT", 10,
                null, null, 1)).isInstanceOf(DuplicateKeyException.class);

        // EXPIRED: the expiry job, run by other test classes on this shared database, must never pick these up.
        String order = "insert into orders (organizer_id, event_id, user_id, channel, status, total_mad, reserved_until"
                + "%s) values (?, ?, ?, 'APP', 'EXPIRED', 100, now()%s) returning id";
        var plain = jdbcTemplate.queryForObject(order.formatted("", ""), UUID.class, org, event, user);
        assertThat(jdbcTemplate.queryForObject("select discount_mad from orders where id = ?", Long.class, plain))
                .isZero();
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order.formatted(", discount_mad", ", 100"), UUID.class,
                org, event, user)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("orders_promo");
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(order.formatted(", promo_code_id, discount_mad",
                ", ?, -1"), UUID.class, org, event, user, code)).isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.queryForObject(order.formatted(", promo_code_id, discount_mad", ", ?, 100"), UUID.class, org,
                event, user, code);
        jdbcTemplate.queryForObject(order.formatted(", promo_code_id", ", ?"), UUID.class, org, event, user, code);
    }

    /**
     * V31 + V32: the backfill (the statement of the migration file, re-run on this test's passes only) gives each step-3a
     * pass, CANCELLED ones included (a late success can revive them), a seq 1 from its purchase order, paid when that
     * order is PAID; INSTALLMENT_DUE needs its
     * pass and only it has one; max_installments 1-10 and 1 for a MEMBERSHIP; suspended_at exactly when SUSPENDED.
     */
    @Test
    void passInstallmentsBackfillAndChecks() throws Exception {
        String sql;
        try (var in = getClass().getResourceAsStream("/db/migration/V32__pass_installments.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = sql.indexOf("insert into pass_installments");
        String backfill = sql.substring(start, sql.indexOf(';', start));

        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 100) returning id", UUID.class, org);
        var zone = jdbcTemplate.queryForObject("insert into zones (organizer_id, venue_id, code, name, capacity) "
                + "values (?, ?, 'EST', 'Est', 100) returning id", Short.class, org, venue);
        var series = jdbcTemplate.queryForObject("""
                insert into event_series (organizer_id, name, starts_on, ends_on)
                values (?, 'Saison', '2026-08-01', '2027-06-30') returning id""", UUID.class, org);
        String product = "insert into products (organizer_id, kind, name, price_mad, sales_close_at, quota, series_id, "
                + "zone_id, valid_until, max_installments) values (?, ?::product_kind, 'P', 150000, "
                + "now() + interval '1 day', ?, ?, ?, ?, ?) returning id";
        var passProduct = jdbcTemplate.queryForObject(product, UUID.class, org, "PASS", 3, series, zone, null, 1);
        for (int bad : new int[] {0, 11}) {
            assertThatThrownBy(() -> jdbcTemplate.queryForObject(product, UUID.class, org, "PASS", 3, series, zone,
                    null, bad)).isInstanceOf(DataIntegrityViolationException.class);
        }
        var validUntil = Timestamp.from(Instant.now().plus(Duration.ofDays(30)));
        assertThatThrownBy(() -> jdbcTemplate.queryForObject(product, UUID.class, org, "MEMBERSHIP", null, null, null,
                validUntil, 2)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("products_installments");

        // Three step-3a passes, each with its purchase order: PAID, RESERVED, and CANCELLED (EXPIRED).
        UUID[] passes = new UUID[3];
        UUID[] orders = new UUID[3];
        String[][] states = {{"ACTIVE", "PAID"}, {"PENDING", "RESERVED"}, {"CANCELLED", "EXPIRED"}};
        for (int n = 0; n < 3; n++) {
            var user = jdbcTemplate.queryForObject("insert into users (phone_e164) values (?) returning id", UUID.class,
                    randomPhone());
            passes[n] = jdbcTemplate.queryForObject("insert into passes (organizer_id, product_id, series_id, user_id, "
                    + "holder_name, status) values (?, ?, ?, ?, 'Yazid Aqel', ?::pass_status) returning id", UUID.class,
                    org, passProduct, series, user, states[n][0]);
            // reserved_until in the future: the expiry job of other classes must leave the RESERVED one alone.
            orders[n] = jdbcTemplate.queryForObject("insert into orders (organizer_id, pass_id, user_id, channel, "
                    + "status, total_mad, reserved_until, paid_at) values (?, ?, ?, 'APP', ?::order_status, 150000, "
                    + "now() + interval '1 day', case when ? = 'PAID' then now() end) returning id", UUID.class,
                    org, passes[n], user, states[n][1], states[n][1]);
        }
        jdbcTemplate.update(backfill + " where p.id = any(?)", (Object) passes);
        assertThat(jdbcTemplate.queryForList("""
                select p.status || ':' || i.seq || ':' || i.amount_mad || ':' || (i.due_at = p.created_at) || ':'
                       || coalesce(i.order_id::text, '-') || ':' || (i.paid_at is not null)
                from pass_installments i join passes p on p.id = i.pass_id
                where i.pass_id = any(?) order by p.status""", String.class, (Object) passes))
                .containsExactly("PENDING:1:150000:true:-:false", "ACTIVE:1:150000:true:" + orders[0] + ":true",
                        "CANCELLED:1:150000:true:-:false");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "update passes set status = 'SUSPENDED' where id = ?", passes[0]))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("passes_suspended");
        jdbcTemplate.update("update passes set status = 'SUSPENDED', suspended_at = now() where id = ?", passes[0]);

        String message = "insert into fan_messages (organizer_id, user_id, channel, kind, pass_id) "
                + "values (?, gen_random_uuid(), 'PUSH', ?::message_kind, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(message, org, "INSTALLMENT_DUE", null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_subject");
        assertThatThrownBy(() -> jdbcTemplate.update(message, org, "WAITLIST_OPEN", passes[0]))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fan_messages_subject");
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
