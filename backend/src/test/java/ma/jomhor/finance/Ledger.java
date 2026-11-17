package ma.jomhor.finance;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Settlement fixtures written straight into the tables a statement reads: organizers.settings.finance, events,
 * products, orders, order items and SUCCEEDED payments (the statement reads nothing else of them).
 */
public final class Ledger {

    public static final String RIB = "011780000012345678901234";

    private Ledger() {}

    /** settings.finance of the demo club at {@code rateBp}, from {@code startOn}. */
    public static void activate(JdbcClient jdbc, UUID org, int rateBp, String startOn) {
        settings(jdbc, org, """
                {"legal_name": "Atlas FC SA", "address": "Stade de l'Atlas, Casablanca", "ice": "001234567000089",
                 "if": "12345678", "rc": "Casablanca 123456", "rib": "%s", "fee_rate_bp": %d, "start_on": "%s"}
                """.formatted(RIB, rateBp, startOn));
    }

    /** settings.finance set to {@code json} as given (malformed on purpose in tests). */
    public static void settings(JdbcClient jdbc, UUID org, String json) {
        jdbc.sql("update organizers set settings = coalesce(settings, '{}'::jsonb) || jsonb_build_object('finance', ?::jsonb)"
                + " where id = ?").params(json, org).update();
    }

    /** An event (DRAFT is enough: statements read only orders' event_id) at a venue of its own. */
    public static int event(JdbcClient jdbc, UUID org, String title) {
        UUID venue = jdbc.sql("insert into venues (organizer_id, name, capacity) values (?, 'Stade', 1000) returning id")
                .param(org).query(UUID.class).single();
        return jdbc.sql("""
                        insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                        values (?, ?, ?, now(), '\\x00') returning id""")
                .params(org, venue, title).query(Integer.class).single();
    }

    /** A MEMBERSHIP product: a product subject like a pass purchase or an installment. */
    public static UUID product(JdbcClient jdbc, UUID org, String name) {
        return jdbc.sql("""
                        insert into products (organizer_id, kind, name, price_mad, sales_close_at, valid_until)
                        values (?, 'MEMBERSHIP', ?, 10000, now() + interval '1 year', now() + interval '1 year')
                        returning id""")
                .params(org, name).query(UUID.class).single();
    }

    /** An event order of the organizer in {@code status} (PAID, EXPIRED, ...). */
    public static UUID eventOrder(JdbcClient jdbc, UUID org, int eventId, String status) {
        return jdbc.sql("""
                        insert into orders (organizer_id, event_id, channel, status, total_mad, reserved_until)
                        values (?, ?, 'APP', ?::order_status, 0, now()) returning id""")
                .params(org, eventId, status).query(UUID.class).single();
    }

    /** A PAID product order with its one product item. */
    public static UUID productOrder(JdbcClient jdbc, UUID org, UUID productId) {
        UUID order = jdbc.sql("""
                        insert into orders (organizer_id, channel, status, total_mad, reserved_until)
                        values (?, 'APP', 'PAID', 0, now()) returning id""")
                .param(org).query(UUID.class).single();
        jdbc.sql("""
                        insert into order_items (organizer_id, order_id, product_id, qty, unit_price_mad, holders)
                        values (?, ?, ?, 1, 0, '[{"name": "Fan Test"}]')""")
                .params(org, order, productId).update();
        return order;
    }

    /** A SUCCEEDED payment of the order completed at {@code at} (a CASH_AGENCY one gets a fresh code). */
    public static UUID pay(JdbcClient jdbc, UUID org, UUID orderId, String method, String provider, long amountMad,
                           Instant at) {
        String code = method.equals("CASH_AGENCY") ? code() : null;
        return jdbc.sql("""
                        insert into payments (organizer_id, order_id, method, provider, amount_mad, status, pay_code,
                                              completed_at)
                        values (?, ?, ?::payment_method, ?, ?, 'SUCCEEDED', ?, ?) returning id""")
                .params(org, orderId, method, provider, amountMad, code, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(UUID.class).single();
    }

    /** A refund in {@code status} of one ticket of the event order, requested by {@code staffId}. */
    public static void refund(JdbcClient jdbc, UUID org, int eventId, UUID orderId, long amountMad, String status,
                              UUID staffId) {
        UUID venue = jdbc.sql("select venue_id from events where id = ?").param(eventId).query(UUID.class).single();
        short zone = jdbc.sql("""
                        insert into zones (organizer_id, venue_id, code, name, capacity)
                        values (?, ?, ?, 'Nord', 100) returning id""")
                .params(org, venue, "Z" + ThreadLocalRandom.current().nextInt(1_000_000)).query(Short.class).single();
        UUID price = jdbc.sql("""
                        insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota)
                        values (?, ?, ?, 'Plein tarif', ?, 10) returning id""")
                .params(org, eventId, zone, amountMad).query(UUID.class).single();
        UUID ticket = jdbc.sql("""
                        insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, status)
                        values (?, ?, ?, ?, ?, 'REFUNDED') returning id""")
                .params(org, orderId, eventId, zone, price).query(UUID.class).single();
        boolean done = status.equals("DONE");
        jdbc.sql("""
                        insert into refunds (organizer_id, event_id, ticket_id, order_id, amount_mad, reason, status,
                                             requested_by, done_by, done_at, reference)
                        values (?, ?, ?, ?, ?, 'Annulation', ?::refund_status, ?, ?, ?, ?)""")
                .params(org, eventId, ticket, orderId, amountMad, status, staffId, done ? staffId : null,
                        done ? OffsetDateTime.now(ZoneOffset.UTC) : null, done ? "VIR 1" : null)
                .update();
    }

    private static String code() {
        String symbols = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            s.append(symbols.charAt(ThreadLocalRandom.current().nextInt(symbols.length())));
        }
        return s.toString();
    }
}
