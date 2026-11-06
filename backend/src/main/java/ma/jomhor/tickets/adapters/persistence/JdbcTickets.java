package ma.jomhor.tickets.adapters.persistence;

import java.util.List;
import java.util.UUID;
import ma.jomhor.tickets.application.Tickets;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTickets implements Tickets {

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
    public List<UUID> heldInOrder(UUID org, UUID orderId, UUID holderUserId) {
        return jdbc.sql("""
                        select id from tickets where organizer_id = :org and order_id = :order and holder_user_id = :holder
                        order by id
                        """)
                .param("org", org).param("order", orderId).param("holder", holderUserId).query(UUID.class).list();
    }
}
