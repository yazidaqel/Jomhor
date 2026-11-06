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
    public UUID insert(UUID org, UUID orderId, int eventId, int zoneId, UUID priceCategoryId, UUID holderUserId) {
        return jdbc.sql("""
                        insert into tickets (organizer_id, order_id, event_id, zone_id, price_category_id, holder_user_id)
                        values (:org, :order, :event, :zone, :category, :holder)
                        returning id
                        """)
                .param("org", org).param("order", orderId).param("event", eventId).param("zone", zoneId)
                .param("category", priceCategoryId).param("holder", holderUserId)
                .query(UUID.class).single();
    }

    @Override
    public List<UUID> ofOrder(UUID org, UUID orderId) {
        return jdbc.sql("select id from tickets where organizer_id = :org and order_id = :order order by id")
                .param("org", org).param("order", orderId).query(UUID.class).list();
    }
}
