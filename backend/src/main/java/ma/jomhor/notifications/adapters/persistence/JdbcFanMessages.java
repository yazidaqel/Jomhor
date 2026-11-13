package ma.jomhor.notifications.adapters.persistence;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import ma.jomhor.notifications.application.FanMessages;
import ma.jomhor.notifications.domain.FanMessage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcFanMessages implements FanMessages {

    private final JdbcClient jdbc;

    JdbcFanMessages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void enqueueTicket(UUID org, UUID ticketId) {
        jdbc.sql("""
                        insert into fan_messages (organizer_id, user_id, channel, kind, ticket_id)
                        select organizer_id, holder_user_id, 'PUSH', 'TICKET_RECEIVED', id from tickets
                        where organizer_id = :org and id = :ticket and holder_user_id is not null
                        on conflict do nothing
                        """)
                .param("org", org).param("ticket", ticketId).update();
    }

    /** Untargeted on conflict: the per-order unique index (order_id, kind, channel) keeps the first row. */
    @Override
    public void enqueueOrders(UUID org, Collection<UUID> orderIds, FanMessage.Kind kind,
                              List<FanMessage.Channel> channels) {
        if (orderIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        insert into fan_messages (organizer_id, user_id, channel, kind, order_id)
                        select o.organizer_id, o.user_id, cast(c.channel as message_channel),
                               cast(:kind as message_kind), o.id
                        from orders o cross join unnest(cast(:channels as text[])) as c(channel)
                        where o.organizer_id = :org and o.id = any(cast(:orders as uuid[])) and o.user_id is not null
                        order by o.id, c.channel
                        on conflict do nothing
                        """)
                .param("org", org).param("orders", orderIds.toArray(UUID[]::new)).param("kind", kind.name())
                .param("channels", channels.stream().map(Enum::name).toArray(String[]::new)).update();
    }

    /**
     * Claims across organizers (the job serves all of them); each row's organizer_id scopes what is read for it.
     * Transactional kinds go first, so a campaign never delays an invite or a pay code (04 §Campaigns Send).
     */
    @Override
    public List<FanMessage> claimDue(int limit) {
        return jdbc.sql("""
                        select id, organizer_id, user_id, channel::text, kind::text, order_id, ticket_id, attempts
                        from fan_messages
                        where status = 'PENDING' and next_attempt_at <= now()
                        order by (kind = 'CAMPAIGN'), next_attempt_at
                        limit :limit
                        for update skip locked
                        """)
                .param("limit", limit)
                .query((rs, n) -> new FanMessage(rs.getObject("id", UUID.class),
                        rs.getObject("organizer_id", UUID.class), rs.getObject("user_id", UUID.class), FanMessage.Channel.valueOf(rs.getString("channel")),
                        FanMessage.Kind.valueOf(rs.getString("kind")), rs.getObject("order_id", UUID.class),
                        rs.getObject("ticket_id", UUID.class), rs.getInt("attempts")))
                .list();
    }

    @Override
    public void markSent(UUID org, UUID id) {
        jdbc.sql("""
                        update fan_messages set status = 'SENT', attempts = attempts + 1, sent_at = clock_timestamp(),
                                                last_error = null
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", id).update();
    }

    @Override
    public void markFailed(UUID org, UUID id, String error, Duration retryIn) {
        jdbc.sql("""
                        update fan_messages
                        set attempts = attempts + 1, last_error = :error,
                            status = case when :retry::bigint is null then 'FAILED' else 'PENDING' end::delivery_status,
                            next_attempt_at = case when :retry::bigint is null then next_attempt_at
                                                   else clock_timestamp() + make_interval(secs => :retry::bigint) end
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("id", id).param("error", error)
                .param("retry", retryIn == null ? null : retryIn.toSeconds()).update();
    }
}
