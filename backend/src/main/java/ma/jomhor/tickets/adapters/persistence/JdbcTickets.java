package ma.jomhor.tickets.adapters.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ma.jomhor.tickets.application.Tickets;
import ma.jomhor.tickets.domain.Ticket;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTickets implements Tickets {

    private static final RowMapper<Ticket> TICKET = (rs, n) -> new Ticket(rs.getObject("id", UUID.class),
            rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"), rs.getInt("zone_id"),
            Ticket.Status.valueOf(rs.getString("status")), rs.getString("holder_name"), rs.getString("seat_label"),
            rs.getObject("issued_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    JdbcTickets(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(UUID org, UUID orderId, int eventId, int zoneId, UUID priceCategoryId, UUID holderUserId,
                       String holderName, String holderInitials) {
        return jdbc.sql("""
                        insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, holder_user_id,
                                             holder_name, holder_initials)
                        values (:org, :order, :event, :zone, :category, :holder, :name, :initials)
                        returning id
                        """)
                .param("org", org).param("order", orderId).param("event", eventId).param("zone", zoneId)
                .param("category", priceCategoryId).param("holder", holderUserId).param("name", holderName)
                .param("initials", holderInitials)
                .query(UUID.class).single();
    }

    @Override
    public List<Ticket> heldBy(UUID holderUserId) {
        return jdbc.sql("""
                        select id, organizer_id, event_id, zone_id, status, holder_name, seat_label, issued_at from tickets
                        where holder_user_id = :holder order by event_id, id
                        """)
                .param("holder", holderUserId).query(TICKET).list();
    }

    @Override
    public Optional<Ticket> heldBy(UUID holderUserId, UUID ticketId) {
        return jdbc.sql("""
                        select id, organizer_id, event_id, zone_id, status, holder_name, seat_label, issued_at from tickets
                        where id = :id and holder_user_id = :holder
                        """)
                .param("id", ticketId).param("holder", holderUserId).query(TICKET).optional();
    }

    @Override
    public Optional<UUID> holder(UUID org, UUID ticketId) {
        return jdbc.sql("select holder_user_id from tickets where organizer_id = :org and id = :id and holder_user_id is not null")
                .param("org", org).param("id", ticketId).query(UUID.class).optional();
    }

    @Override
    public Optional<Ticket.Locked> lock(UUID org, UUID ticketId) {
        return jdbc.sql("""
                        select id, event_id, order_id, price_category_id, status from tickets
                        where organizer_id = :org and id = :id for no key update
                        """)
                .param("org", org).param("id", ticketId)
                .query((rs, n) -> new Ticket.Locked(rs.getObject("id", UUID.class), rs.getInt("event_id"),
                        rs.getObject("order_id", UUID.class), rs.getObject("price_category_id", UUID.class),
                        Ticket.Status.valueOf(rs.getString("status"))))
                .optional();
    }

    @Override
    public void setStatus(UUID org, UUID ticketId, Ticket.Status status) {
        jdbc.sql("update tickets set status = cast(:status as ticket_status) where organizer_id = :org and id = :id")
                .param("status", status.name()).param("org", org).param("id", ticketId).update();
    }

    // A staff read joining the order item (price snapshot), the order (channel, method) and the refund, each on
    // organizer_id as well, so a lookup is one query.
    private static final String ROWS = """
            select t.id, t.status, t.zone_id, z.code as zone_code, z.name as zone_name, t.holder_name, i.unit_price_mad,
                   t.order_id, o.channel, o.payment_method, t.issued_at,
                   r.id as refund_id, r.status as refund_status, r.amount_mad as refund_amount_mad
            from tickets t
            join zones z on z.id = t.zone_id and z.organizer_id = t.organizer_id
            join orders o on o.id = t.order_id and o.organizer_id = t.organizer_id
            join order_items i on i.order_id = t.order_id and i.price_category_id = t.price_category_id
                              and i.organizer_id = t.organizer_id
            left join refunds r on r.ticket_id = t.id and r.organizer_id = t.organizer_id
            where t.organizer_id = :org
            """;

    private static final RowMapper<Tickets.Row> ROW = (rs, n) -> new Tickets.Row(rs.getObject("id", UUID.class),
            Ticket.Status.valueOf(rs.getString("status")), rs.getInt("zone_id"), rs.getString("zone_code"),
            rs.getString("zone_name"), rs.getString("holder_name"), rs.getLong("unit_price_mad"),
            rs.getObject("order_id", UUID.class), rs.getString("channel"), rs.getString("payment_method"),
            rs.getObject("issued_at", OffsetDateTime.class).toInstant(), rs.getObject("refund_id", UUID.class),
            rs.getString("refund_status"), rs.getObject("refund_amount_mad", Long.class));

    @Override
    public List<Tickets.Row> search(UUID org, int eventId, Match match, String value, int limit) {
        String filter = switch (match) {
            case TICKET_NO -> "right(t.id::text, 8) = :value";
            case PHONE -> "t.holder_user_id = (select u.id from users u where u.phone_e164 = :value)";
            case CASH_CODE ->
                    "t.order_id = (select p.order_id from payments p where p.organizer_id = :org and p.pay_code = :value)";
        };
        // ponytail: a ticket-number match scans the event's tickets (no index on the id's tail); fine at 45,000 rows.
        // ticket_no order = the last 8 hex chars (lowercase and uppercase hex sort alike)
        return jdbc.sql(ROWS + " and t.event_id = :event and " + filter
                        + " order by right(t.id::text, 8), t.id limit :limit")
                .param("org", org).param("event", eventId).param("value", value).param("limit", limit)
                .query(ROW).list();
    }

    @Override
    public Optional<Tickets.Row> row(UUID org, UUID ticketId) {
        return jdbc.sql(ROWS + " and t.id = :id").param("org", org).param("id", ticketId).query(ROW).optional();
    }

    @Override
    public Instant now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single().toInstant();
    }

    @Override
    public List<Ticket.ManifestRow> manifestRows(UUID org, int eventId) {
        return jdbc.sql("""
                        select id, zone_id, status, holder_initials from tickets
                        where organizer_id = :org and event_id = :event order by id
                        """)
                .param("org", org).param("event", eventId)
                .query((rs, n) -> new Ticket.ManifestRow(rs.getObject("id", UUID.class), rs.getInt("zone_id"),
                        Ticket.Status.valueOf(rs.getString("status")), rs.getString("holder_initials")))
                .list();
    }

    @Override
    public Map<Integer, Long> validByZone(UUID org, int eventId) {
        return jdbc.sql("""
                        select zone_id, count(*) as n from tickets
                        where organizer_id = :org and event_id = :event and status = 'VALID' group by zone_id
                        """)
                .param("org", org).param("event", eventId)
                .query((rs, n) -> Map.entry(rs.getInt("zone_id"), rs.getLong("n"))).stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Set<UUID> ofEvent(UUID org, int eventId, Collection<UUID> ticketIds) {
        return new HashSet<>(jdbc.sql("select id from tickets where organizer_id = :org and event_id = :event and id = any(:ids)")
                .param("org", org).param("event", eventId).param("ids", ticketIds.toArray(UUID[]::new))
                .query(UUID.class).list());
    }

    @Override
    public List<Ticket.OfOrder> inOrder(UUID org, UUID orderId) {
        return jdbc.sql("""
                        select id, event_id, zone_id, price_category_id, status, holder_name from tickets
                        where organizer_id = :org and order_id = :order order by id
                        """)
                .param("org", org).param("order", orderId)
                .query((rs, n) -> new Ticket.OfOrder(rs.getObject("id", UUID.class), rs.getInt("event_id"),
                        rs.getInt("zone_id"), rs.getObject("price_category_id", UUID.class),
                        Ticket.Status.valueOf(rs.getString("status")), rs.getString("holder_name")))
                .list();
    }

    @Override
    public List<UUID> heldInOrder(UUID org, UUID orderId, UUID holderUserId) {
        return jdbc.sql("""
                        select id from tickets where organizer_id = :org and order_id = :order and holder_user_id = :holder
                        order by id
                        """)
                .param("org", org).param("order", orderId).param("holder", holderUserId).query(UUID.class).list();
    }
}
