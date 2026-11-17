package ma.jomhor.tickets.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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

    /**
     * 04 §Passes Effective status (plan lot-5 Q198), over {@code t}: a VALID ticket of a pass ticket order, still held
     * by the pass's holder, while that pass is SUSPENDED, for an event whose refund_cutoff_at is after suspended_at
     * (02's freeze: nothing changes inside a match's last hour). It then reads BLOCKED; the row is never written.
     */
    static final String PASS_BLOCKED = """
            (t.status = 'VALID' and exists (
              select 1 from orders po
              join passes pp on pp.id = po.pass_id and pp.organizer_id = po.organizer_id
              join events pe on pe.id = po.event_id and pe.organizer_id = po.organizer_id
              where po.id = t.order_id and po.organizer_id = t.organizer_id and pp.status = 'SUSPENDED'
                and pp.user_id = t.holder_user_id and pe.refund_cutoff_at > pp.suspended_at)) as pass_blocked""";

    /** The effective status of a row selecting {@code status} and {@link #PASS_BLOCKED}. */
    private static Ticket.Status effective(ResultSet rs) throws SQLException {
        return rs.getBoolean("pass_blocked") ? Ticket.Status.BLOCKED : Ticket.Status.valueOf(rs.getString("status"));
    }

    private static final RowMapper<Ticket> TICKET = (rs, n) -> {
        OffsetDateTime transferredAt = rs.getObject("transferred_at", OffsetDateTime.class);
        return new Ticket(rs.getObject("id", UUID.class),
                rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"), rs.getInt("zone_id"),
                effective(rs), rs.getString("holder_name"), rs.getString("seat_label"),
                rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                transferredAt == null ? null
                        : new Ticket.Transferred(rs.getString("to_name"), transferredAt.toInstant()),
                rs.getObject("pass_id", UUID.class), rs.getBoolean("pass_blocked"));
    };

    // A holder's ticket, its order's pass while the ticket is held by that pass's holder (04 GET /fan/tickets pass_id:
    // null once transferred) and, when this holder transferred it away (not a re-issue: to = from), the new ticket's
    // holder.
    private static final String HELD = """
            select t.id, t.organizer_id, t.event_id, t.zone_id, t.status, t.holder_name, t.seat_label, t.issued_at,
                   case when tt.to_user_id <> tt.from_user_id then tt.transferred_at end as transferred_at,
                   nt.holder_name as to_name, case when p.user_id = t.holder_user_id then p.id end as pass_id,
            """ + PASS_BLOCKED + """

            from tickets t
            join orders o on o.id = t.order_id and o.organizer_id = t.organizer_id
            left join passes p on p.id = o.pass_id and p.organizer_id = o.organizer_id
            left join ticket_transfers tt on tt.ticket_id = t.id and tt.organizer_id = t.organizer_id
            left join tickets nt on nt.id = tt.new_ticket_id and nt.organizer_id = tt.organizer_id
            where t.holder_user_id = :holder
            """;

    // Only the ticket row is locked: the pass and its event are read, not held (a suspension racing a transfer is as if
    // it came just after it).
    private static final String LOCKED = """
            select t.id, t.organizer_id, t.event_id, t.zone_id, t.order_id, t.price_category_id, t.status, t.holder_name,
            """ + PASS_BLOCKED + """

            from tickets t
            """;

    private static final RowMapper<Ticket.Locked> LOCK = (rs, n) -> new Ticket.Locked(rs.getObject("id", UUID.class),
            rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"), rs.getInt("zone_id"),
            rs.getObject("order_id", UUID.class), rs.getObject("price_category_id", UUID.class),
            Ticket.Status.valueOf(rs.getString("status")), rs.getString("holder_name"), rs.getBoolean("pass_blocked"));

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
        return jdbc.sql(HELD + " and coalesce(tt.to_user_id <> tt.from_user_id, true) order by t.event_id, t.id")
                .param("holder", holderUserId).query(TICKET).list();
    }

    @Override
    public List<Ticket> heldOfEvent(UUID holderUserId, int eventId) {
        return jdbc.sql(HELD + " and t.event_id = :event and coalesce(tt.to_user_id <> tt.from_user_id, true) order by t.id")
                .param("holder", holderUserId).param("event", eventId).query(TICKET).list();
    }

    @Override
    public Optional<Ticket> heldBy(UUID holderUserId, UUID ticketId) {
        return jdbc.sql(HELD + " and t.id = :id")
                .param("id", ticketId).param("holder", holderUserId).query(TICKET).optional();
    }

    @Override
    public Optional<UUID> holder(UUID org, UUID ticketId) {
        return jdbc.sql("select holder_user_id from tickets where organizer_id = :org and id = :id and holder_user_id is not null")
                .param("org", org).param("id", ticketId).query(UUID.class).optional();
    }

    @Override
    public Optional<Ticket.Locked> lock(UUID org, UUID ticketId) {
        return jdbc.sql(LOCKED + " where t.organizer_id = :org and t.id = :id for no key update of t")
                .param("org", org).param("id", ticketId).query(LOCK).optional();
    }

    @Override
    public Optional<Ticket.Locked> lockHeld(UUID holderUserId, UUID ticketId) {
        return jdbc.sql(LOCKED + " where t.id = :id and t.holder_user_id = :holder for no key update of t")
                .param("id", ticketId).param("holder", holderUserId).query(LOCK).optional();
    }

    @Override
    public Tickets.Transfer insertTransfer(UUID org, UUID ticketId, UUID newTicketId, UUID fromUserId, UUID toUserId) {
        return jdbc.sql("""
                        insert into ticket_transfers (organizer_id, ticket_id, new_ticket_id, from_user_id, to_user_id)
                        values (:org, :ticket, :new, :from, :to)
                        returning id, transferred_at
                        """)
                .param("org", org).param("ticket", ticketId).param("new", newTicketId).param("from", fromUserId)
                .param("to", toUserId)
                .query((rs, n) -> new Tickets.Transfer(rs.getObject("id", UUID.class),
                        rs.getObject("transferred_at", OffsetDateTime.class).toInstant()))
                .single();
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
                   t.order_id, o.channel, o.payment_method, t.issued_at, o.total_mad as order_total_mad,
                   o.discount_mad as order_discount_mad,
                   r.id as refund_id, r.status as refund_status, r.amount_mad as refund_amount_mad,
                   tt_to.new_ticket_id as transferred_to, tt_from.ticket_id as transferred_from,
                   case when p.user_id = t.holder_user_id then p.status::text end as pass_status,
            """ + PASS_BLOCKED + """

            from tickets t
            join zones z on z.id = t.zone_id and z.organizer_id = t.organizer_id
            join orders o on o.id = t.order_id and o.organizer_id = t.organizer_id
            left join passes p on p.id = o.pass_id and p.organizer_id = o.organizer_id
            join order_items i on i.order_id = t.order_id and i.price_category_id = t.price_category_id
                              and i.organizer_id = t.organizer_id
            left join refunds r on r.ticket_id = t.id and r.organizer_id = t.organizer_id
            left join ticket_transfers tt_to on tt_to.ticket_id = t.id and tt_to.organizer_id = t.organizer_id
            left join ticket_transfers tt_from on tt_from.new_ticket_id = t.id and tt_from.organizer_id = t.organizer_id
            where t.organizer_id = :org
            """;

    private static final RowMapper<Tickets.Row> ROW = (rs, n) -> new Tickets.Row(rs.getObject("id", UUID.class),
            effective(rs), rs.getInt("zone_id"), rs.getString("zone_code"),
            rs.getString("zone_name"), rs.getString("holder_name"), rs.getLong("unit_price_mad"),
            rs.getObject("order_id", UUID.class), rs.getString("channel"), rs.getString("payment_method"),
            rs.getObject("issued_at", OffsetDateTime.class).toInstant(), rs.getObject("refund_id", UUID.class),
            rs.getString("refund_status"), rs.getObject("refund_amount_mad", Long.class),
            rs.getObject("transferred_to", UUID.class), rs.getObject("transferred_from", UUID.class),
            rs.getBoolean("pass_blocked"), rs.getString("pass_status"), rs.getLong("order_total_mad"),
            rs.getLong("order_discount_mad"));

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

    // ponytail: PASS_BLOCKED is a primary-key probe on orders per row (~45,000 per build, at most every 5 min); join
    // the event's suspended passes once if a build gets slow.
    @Override
    public List<Ticket.ManifestRow> manifestRows(UUID org, int eventId) {
        return jdbc.sql("select t.id, t.zone_id, t.status, t.holder_initials, " + PASS_BLOCKED + """

                        from tickets t
                        where t.organizer_id = :org and t.event_id = :event order by t.id
                        """)
                .param("org", org).param("event", eventId)
                .query((rs, n) -> new Ticket.ManifestRow(rs.getObject("id", UUID.class), rs.getInt("zone_id"),
                        effective(rs), rs.getString("holder_initials")))
                .list();
    }

    // ponytail: the stored status, so the gate's sold counts a suspended pass's tickets (plan lot-5 Q198, §11.9); add
    // PASS_BLOCKED here if it ever matters.
    @Override
    public Map<Integer, Long> countByZone(UUID org, int eventId, Ticket.Status status) {
        return jdbc.sql("""
                        select zone_id, count(*) as n from tickets
                        where organizer_id = :org and event_id = :event and status = cast(:status as ticket_status)
                        group by zone_id
                        """)
                .param("org", org).param("event", eventId).param("status", status.name())
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
                        select t.id, t.event_id, t.zone_id, t.price_category_id, t.status, t.holder_name,
                               t.holder_user_id
                        from tickets t
                        where t.organizer_id = :org and t.order_id = :order
                          and not exists (select 1 from ticket_transfers tt
                                          where tt.organizer_id = t.organizer_id and tt.new_ticket_id = t.id)
                        order by t.id
                        """)
                .param("org", org).param("order", orderId)
                .query((rs, n) -> new Ticket.OfOrder(rs.getObject("id", UUID.class), rs.getInt("event_id"),
                        rs.getInt("zone_id"), rs.getObject("price_category_id", UUID.class),
                        Ticket.Status.valueOf(rs.getString("status")), rs.getString("holder_name"),
                        rs.getObject("holder_user_id", UUID.class)))
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
