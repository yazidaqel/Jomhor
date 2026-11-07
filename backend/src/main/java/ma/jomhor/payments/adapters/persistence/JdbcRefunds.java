package ma.jomhor.payments.adapters.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.application.Refunds;
import ma.jomhor.payments.domain.Refund;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRefunds implements Refunds {

    private static final String SELECT = """
            select r.id, r.event_id, r.ticket_id, r.order_id, r.amount_mad, p.method, r.reason, r.status,
                   r.requested_by, rq.name as requested_by_name, r.requested_at, r.done_by, dn.name as done_by_name,
                   r.done_at, r.reference
            from refunds r
            left join payments p on p.id = r.payment_id and p.organizer_id = r.organizer_id
            join staff rq on rq.id = r.requested_by and rq.organizer_id = r.organizer_id
            left join staff dn on dn.id = r.done_by and dn.organizer_id = r.organizer_id
            where r.organizer_id = :org
            """;

    private static final RowMapper<Refund> REFUND = (rs, n) -> new Refund(rs.getObject("id", UUID.class),
            rs.getInt("event_id"), rs.getObject("ticket_id", UUID.class), rs.getObject("order_id", UUID.class),
            rs.getLong("amount_mad"), rs.getString("method"), rs.getString("reason"),
            Refund.Status.valueOf(rs.getString("status")), rs.getObject("requested_by", UUID.class),
            rs.getString("requested_by_name"), instant(rs.getObject("requested_at", OffsetDateTime.class)),
            rs.getObject("done_by", UUID.class), rs.getString("done_by_name"),
            instant(rs.getObject("done_at", OffsetDateTime.class)), rs.getString("reference"));

    private final JdbcClient jdbc;

    JdbcRefunds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // The succeeding payment: the first SUCCEEDED one (a second success is a refund_required of its own).
    @Override
    public UUID insert(UUID org, UUID userId, int eventId, UUID ticketId, UUID orderId, long amountMad, String reason) {
        return jdbc.sql("""
                        insert into refunds (organizer_id, event_id, ticket_id, order_id, payment_id, amount_mad, reason,
                                             requested_by)
                        values (:org, :event, :ticket, :order,
                                (select id from payments where organizer_id = :org and order_id = :order
                                 and status = 'SUCCEEDED' order by completed_at, id limit 1),
                                :amount, :reason,
                                (select id from staff where organizer_id = :org and user_id = :user))
                        returning id
                        """)
                .param("org", org).param("event", eventId).param("ticket", ticketId).param("order", orderId)
                .param("amount", amountMad).param("reason", reason).param("user", userId)
                .query(UUID.class).single();
    }

    @Override
    public List<Refund> list(UUID org, Refund.Status status, Integer eventId) {
        String filter = (status == null ? "" : " and r.status = cast(:status as refund_status)")
                + (eventId == null ? "" : " and r.event_id = :event");
        var sql = jdbc.sql(SELECT + filter + " order by r.status = 'REQUIRED' desc, r.requested_at desc, r.id")
                .param("org", org);
        if (status != null) {
            sql = sql.param("status", status.name());
        }
        if (eventId != null) {
            sql = sql.param("event", eventId);
        }
        return sql.query(REFUND).list();
    }

    @Override
    public Optional<Refund> get(UUID org, UUID refundId) {
        return jdbc.sql(SELECT + " and r.id = :id").param("org", org).param("id", refundId).query(REFUND).optional();
    }

    @Override
    public Optional<Refund.Status> lockStatus(UUID org, UUID refundId) {
        return jdbc.sql("select status from refunds where organizer_id = :org and id = :id for update")
                .param("org", org).param("id", refundId)
                .query((rs, n) -> Refund.Status.valueOf(rs.getString(1))).optional();
    }

    @Override
    public void done(UUID org, UUID userId, UUID refundId, String reference) {
        jdbc.sql("""
                        update refunds set status = 'DONE', done_at = now(), reference = :reference,
                               done_by = (select id from staff where organizer_id = :org and user_id = :user)
                        where organizer_id = :org and id = :id
                        """)
                .param("org", org).param("user", userId).param("id", refundId).param("reference", reference).update();
    }

    private static Instant instant(OffsetDateTime t) {
        return t == null ? null : t.toInstant();
    }
}
