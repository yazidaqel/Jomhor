package ma.jomhor.tickets.adapters.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.tickets.application.Tickets;
import ma.jomhor.tickets.domain.Ticket;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTickets implements Tickets {

    private static final RowMapper<Ticket> TICKET = (rs, n) -> new Ticket(rs.getObject("id", UUID.class),
            rs.getObject("organizer_id", UUID.class), rs.getInt("event_id"), rs.getInt("zone_id"),
            Ticket.Status.valueOf(rs.getString("status")), rs.getString("holder_name"), rs.getString("seat_label"));

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
                        select id, organizer_id, event_id, zone_id, status, holder_name, seat_label from tickets
                        where holder_user_id = :holder order by event_id, id
                        """)
                .param("holder", holderUserId).query(TICKET).list();
    }

    @Override
    public Optional<Ticket> heldBy(UUID holderUserId, UUID ticketId) {
        return jdbc.sql("""
                        select id, organizer_id, event_id, zone_id, status, holder_name, seat_label from tickets
                        where id = :id and holder_user_id = :holder
                        """)
                .param("id", ticketId).param("holder", holderUserId).query(TICKET).optional();
    }

    @Override
    public Instant now() {
        return jdbc.sql("select now()").query(OffsetDateTime.class).single().toInstant();
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
