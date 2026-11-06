package ma.jomhor.notifications.adapters.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ma.jomhor.notifications.application.TicketDeliveries;
import ma.jomhor.notifications.domain.Delivery;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcTicketDeliveries implements TicketDeliveries {

    private final JdbcClient jdbc;

    JdbcTicketDeliveries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueue(UUID org, List<UUID> ticketIds) {
        jdbc.sql("insert into ticket_deliveries (organizer_id, ticket_id) select :org, unnest(cast(:tickets as uuid[]))")
                .param("org", org).param("tickets", ticketIds.toArray(UUID[]::new)).update();
    }

    /** Claims across organizers (the job serves all of them); each row's organizer_id scopes what is read for it. */
    @Override
    public List<Delivery> claimDue(int limit) {
        return jdbc.sql("""
                        select id, organizer_id, ticket_id, attempts from ticket_deliveries
                        where status = 'PENDING' and next_attempt_at <= now()
                        order by next_attempt_at
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", limit)
                .query((rs, n) -> new Delivery(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                        rs.getObject("ticket_id", UUID.class), rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markSent(UUID org, UUID id) {
        jdbc.sql("""
                        update ticket_deliveries set status = 'SENT', attempts = attempts + 1, sent_at = clock_timestamp(),
                                                     last_error = null
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", id).update();
    }

    @Override
    public void markFailed(UUID org, UUID id, String error, Duration retryIn) {
        jdbc.sql("""
                        update ticket_deliveries
                        set attempts = attempts + 1, last_error = :error,
                            status = case when :retry::bigint is null then 'FAILED' else 'PENDING' end::delivery_status,
                            next_attempt_at = case when :retry::bigint is null then next_attempt_at
                                                   else clock_timestamp() + make_interval(secs => :retry::bigint) end
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", id).param("error", error)
                .param("retry", retryIn == null ? null : retryIn.toSeconds()).update();
    }

    @Override
    public void lockTicket(UUID org, UUID ticketId) {
        jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "ticket_deliveries:" + org + ":" + ticketId).query((rs, n) -> 0).single();
    }

    @Override
    public int resendsWithin(UUID org, UUID ticketId, Instant issuedAt, Duration window) {
        return jdbc.sql("""
                        select count(*) from ticket_deliveries
                        where organizer_id = :org and ticket_id = :ticket
                          and created_at > now() - make_interval(secs => :window)
                          and created_at <> :issuedAt
                        """)
                .param("org", org).param("ticket", ticketId).param("window", window.toSeconds())
                .param("issuedAt", OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC))
                .query(Integer.class).single();
    }
}
