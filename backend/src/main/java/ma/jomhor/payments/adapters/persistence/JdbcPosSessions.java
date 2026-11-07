package ma.jomhor.payments.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.application.PosSessions;
import ma.jomhor.payments.domain.PosSession;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * pos_sessions. The cashier comes from staff (read only, joined on the session's organizer); the figures from
 * payments of the session, the tickets of those orders and the {@code pos.print} audit rows (target = session id).
 */
@Repository
class JdbcPosSessions implements PosSessions {

    private static final String PAID = """
            from payments pm where pm.organizer_id = p.organizer_id and pm.pos_session_id = p.id
              and pm.status = 'SUCCEEDED'""";
    private static final String SELECT = """
            select p.id, p.label, p.staff_id, s.name as staff_name, p.opened_at, p.closed_at, p.expected_mad, p.declared_mad,
                   (select count(distinct pm.order_id)\s""" + PAID + """
            ) as orders,
                   (select count(*) from tickets t where t.organizer_id = p.organizer_id
                      and t.order_id in (select pm.order_id\s""" + PAID + """
            )) as tickets,
                   (select count(*) from audit_log a where a.organizer_id = p.organizer_id and a.action = 'pos.print'
                      and a.target = p.id::text) as prints,
                   (select coalesce(sum(pm.amount_mad), 0)\s""" + PAID + """
            ) as collected_mad
            from pos_sessions p join staff s on s.id = p.staff_id and s.organizer_id = p.organizer_id
            """;

    private final JdbcClient jdbc;

    JdbcPosSessions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PosSession> openOf(UUID org, UUID userId) {
        return jdbc.sql(SELECT + " where p.organizer_id = :org and s.user_id = :user and p.closed_at is null")
                .param("org", org).param("user", userId).query(JdbcPosSessions::session).optional();
    }

    @Override
    public Optional<UUID> lockOpenOf(UUID org, UUID userId) {
        return jdbc.sql("""
                        select p.id from pos_sessions p join staff s on s.id = p.staff_id and s.organizer_id = p.organizer_id
                        where p.organizer_id = :org and s.user_id = :user and p.closed_at is null
                        for update of p
                        """)
                .param("org", org).param("user", userId).query(UUID.class).optional();
    }

    // on conflict: a second open (or two at once) of one cashier keeps the open session (04 POST /org/pos/sessions).
    @Override
    public boolean insert(UUID org, UUID userId, String label) {
        return jdbc.sql("""
                        insert into pos_sessions (organizer_id, staff_id, label)
                        select :org, s.id, :label from staff s where s.organizer_id = :org and s.user_id = :user and s.active
                        on conflict (organizer_id, staff_id) where closed_at is null do nothing
                        """)
                .param("org", org).param("user", userId).param("label", label).update() == 1;
    }

    @Override
    public Optional<Owned> lock(UUID org, UUID sessionId) {
        return jdbc.sql("""
                        select p.id, s.user_id, p.closed_at is not null as closed
                        from pos_sessions p join staff s on s.id = p.staff_id and s.organizer_id = p.organizer_id
                        where p.organizer_id = :org and p.id = :id
                        for update of p
                        """)
                .param("org", org).param("id", sessionId)
                .query((rs, n) -> new Owned(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getBoolean("closed")))
                .optional();
    }

    @Override
    public void close(UUID org, UUID sessionId, long declaredMad) {
        jdbc.sql("""
                        update pos_sessions p set closed_at = now(), declared_mad = :declared,
                          expected_mad = (select coalesce(sum(pm.amount_mad), 0)\s""" + PAID + """
                        )
                        where p.organizer_id = :org and p.id = :id and p.closed_at is null
                        """)
                .param("org", org).param("id", sessionId).param("declared", declaredMad).update();
    }

    @Override
    public Optional<PosSession> get(UUID org, UUID sessionId) {
        return jdbc.sql(SELECT + " where p.organizer_id = :org and p.id = :id")
                .param("org", org).param("id", sessionId).query(JdbcPosSessions::session).optional();
    }

    private static PosSession session(ResultSet rs, int n) throws SQLException {
        return new PosSession(rs.getObject("id", UUID.class), rs.getString("label"), rs.getObject("staff_id", UUID.class),
                rs.getString("staff_name"), instant(rs, "opened_at"), instant(rs, "closed_at"), rs.getLong("orders"),
                rs.getLong("tickets"), rs.getLong("prints"), rs.getLong("collected_mad"),
                rs.getObject("expected_mad", Long.class), rs.getObject("declared_mad", Long.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
