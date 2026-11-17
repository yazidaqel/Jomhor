package ma.jomhor.payments.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.payments.application.CashDeposits;
import ma.jomhor.payments.domain.CashDeposit;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * cash_deposits and pos_sessions.deposit_id. Staff names are joined on the row's organizer (read only).
 *
 * <p>ponytail: no index on pos_sessions.deposit_id, so the deposit → sessions join (reads) and the void's session
 * lookup scan the organizer's sessions; a club has a few hundred a year. Add (organizer_id, deposit_id) if one
 * organizer's pos_sessions ever reach the 100,000s.
 */
@Repository
class JdbcCashDeposits implements CashDeposits {

    // One row per (deposit, session); a voided deposit or one read alone comes back with null session columns.
    private static final String SELECT = """
            select d.id, d.deposited_on, d.amount_mad, d.reference, d.recorded_by, rb.name as recorded_by_name,
                   d.recorded_at, d.voided_at, d.voided_by, vb.name as voided_by_name, d.void_reason,
                   p.id as session_id, p.label, p.staff_id, ps.name as staff_name, p.closed_at, p.declared_mad
            from cash_deposits d
              join staff rb on rb.id = d.recorded_by and rb.organizer_id = d.organizer_id
              left join staff vb on vb.id = d.voided_by and vb.organizer_id = d.organizer_id
              left join pos_sessions p on p.deposit_id = d.id and p.organizer_id = d.organizer_id
              left join staff ps on ps.id = p.staff_id and ps.organizer_id = p.organizer_id
            """;
    private static final String ORDER = " order by d.deposited_on desc, d.recorded_at desc, d.id, p.closed_at, p.id";

    private final JdbcClient jdbc;

    JdbcCashDeposits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Locked> lockSessions(UUID org, List<UUID> ids) {
        return jdbc.sql("""
                        select id, closed_at, deposit_id is not null as deposited from pos_sessions
                        where organizer_id = ? and id = any(?)
                        order by id
                        for update
                        """)
                .params(org, ids.toArray(UUID[]::new))
                .query((rs, n) -> new Locked(rs.getObject("id", UUID.class), instant(rs, "closed_at"),
                        rs.getBoolean("deposited")))
                .list();
    }

    @Override
    public UUID insert(UUID org, UUID userId, LocalDate depositedOn, long amountMad, String reference,
                       List<UUID> sessionIds) {
        UUID id = jdbc.sql("""
                        insert into cash_deposits (organizer_id, deposited_on, amount_mad, reference, recorded_by)
                        select ?, ?, ?, ?, s.id from staff s where s.organizer_id = ? and s.user_id = ?
                        returning id
                        """)
                .params(org, depositedOn, amountMad, reference, org, userId).query(UUID.class).single();
        jdbc.sql("update pos_sessions set deposit_id = ? where organizer_id = ? and id = any(?)")
                .params(id, org, sessionIds.toArray(UUID[]::new)).update();
        return id;
    }

    @Override
    public Optional<Boolean> lockVoided(UUID org, UUID depositId) {
        return jdbc.sql("select voided_at is not null from cash_deposits where organizer_id = ? and id = ? for update")
                .params(org, depositId).query(Boolean.class).optional();
    }

    @Override
    public List<UUID> voidDeposit(UUID org, UUID userId, UUID depositId, String reason) {
        jdbc.sql("""
                        update cash_deposits set voided_at = now(), void_reason = ?,
                               voided_by = (select id from staff where organizer_id = ? and user_id = ?)
                        where organizer_id = ? and id = ? and voided_at is null
                        """)
                .params(reason, org, userId, org, depositId).update();
        // Session rows locked in id order, as lockSessions does, so a void and a create never deadlock.
        List<UUID> freed = jdbc.sql("select id from pos_sessions where organizer_id = ? and deposit_id = ? order by id"
                        + " for update")
                .params(org, depositId).query(UUID.class).list();
        jdbc.sql("update pos_sessions set deposit_id = null where organizer_id = ? and id = any(?)")
                .params(org, freed.toArray(UUID[]::new)).update();
        return freed;
    }

    @Override
    public Optional<CashDeposit> get(UUID org, UUID depositId) {
        return assemble(jdbc.sql(SELECT + " where d.organizer_id = ? and d.id = ?" + ORDER).params(org, depositId))
                .stream().findFirst();
    }

    @Override
    public List<CashDeposit> between(UUID org, LocalDate from, LocalDate to) {
        return assemble(jdbc.sql(SELECT + " where d.organizer_id = ? and d.deposited_on between ? and ?" + ORDER)
                .params(org, from, to));
    }

    @Override
    public List<CashDeposit.Session> undeposited(UUID org, int limit) {
        return jdbc.sql("""
                        select p.id as session_id, p.label, p.staff_id, s.name as staff_name, p.closed_at, p.declared_mad
                        from pos_sessions p join staff s on s.id = p.staff_id and s.organizer_id = p.organizer_id
                        where p.organizer_id = ? and p.closed_at is not null and p.deposit_id is null
                        order by p.closed_at, p.id
                        limit ?
                        """)
                .params(org, limit).query((rs, n) -> session(rs)).list();
    }

    /** Folds the (deposit, session) rows, kept in query order, into deposits. */
    private static List<CashDeposit> assemble(JdbcClient.StatementSpec spec) {
        Map<UUID, CashDeposit> byId = new LinkedHashMap<>();
        spec.query(rs -> {
            UUID id = rs.getObject("id", UUID.class);
            CashDeposit d = byId.computeIfAbsent(id, k -> deposit(rs, k));
            if (rs.getObject("session_id") != null) {
                d.sessions().add(session(rs));
            }
        });
        return byId.values().stream().map(d -> new CashDeposit(d.id(), d.depositedOn(), d.amountMad(), d.reference(),
                d.recordedBy(), d.recordedByName(), d.recordedAt(), d.voidedAt(), d.voidedBy(), d.voidedByName(),
                d.voidReason(), List.copyOf(d.sessions()))).toList();
    }

    private static CashDeposit deposit(ResultSet rs, UUID id) {
        try {
            return new CashDeposit(id, rs.getObject("deposited_on", LocalDate.class), rs.getLong("amount_mad"),
                    rs.getString("reference"), rs.getObject("recorded_by", UUID.class), rs.getString("recorded_by_name"),
                    instant(rs, "recorded_at"), instant(rs, "voided_at"), rs.getObject("voided_by", UUID.class),
                    rs.getString("voided_by_name"), rs.getString("void_reason"), new ArrayList<>());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CashDeposit.Session session(ResultSet rs) throws SQLException {
        return new CashDeposit.Session(rs.getObject("session_id", UUID.class), rs.getString("label"),
                rs.getObject("staff_id", UUID.class), rs.getString("staff_name"), instant(rs, "closed_at"),
                rs.getLong("declared_mad"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
