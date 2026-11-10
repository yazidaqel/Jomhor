package ma.jomhor.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The gate event lock: a transaction-scoped advisory lock on one event, single-bigint key (class << 32) | event_id
 * with class 3 (1 and 2 are JdbcOtpChallenges' phone and IP). Exclusive: device assignment PUTs, scan ingestion and
 * event close, so they run one at a time per event. Shared: manifest downloads. In common because catalog (close)
 * and gate (the rest) both take it and must agree on the key.
 *
 * <p>Advisory keys in use. Single-bigint (class << 32) | 32-bit value: 1 OTP phone, 2 OTP IP (JdbcOtpChallenges),
 * 3 this lock, 4 the manifest build (JdbcManifests). Two-int, a key space Postgres keeps apart from the bigint one:
 * (4, hashtext(organizer id)) the organizer's membership lock (JdbcMemberships, PUT/DELETE /fan/me/membership), and
 * (hashtext(user id), event id) the per-account order lock (JdbcOrders), which can meet a membership key only when
 * a user hash is 4: a short wait, never a wrong result.
 *
 * <p>Scan ingestion's used_since cursor relies on it never skipping a row: seq values are drawn and committed one
 * batch at a time. That holds only while scans.seq's identity sequence has CACHE 1 (the Postgres default, as V11
 * creates it); a per-session cache would hand out seq blocks out of commit order.
 */
@Component
public class EventLock {

    private static final long CLASS = 3;

    private final JdbcClient jdbc;

    EventLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Until the caller's transaction ends. */
    public void exclusive(int eventId) {
        jdbc.sql("select 1 from pg_advisory_xact_lock(?)").param(key(eventId)).query(Integer.class).single();
    }

    /** Until the caller's transaction ends; runs beside other shared holders, never beside an exclusive one. */
    public void shared(int eventId) {
        jdbc.sql("select 1 from pg_advisory_xact_lock_shared(?)").param(key(eventId)).query(Integer.class).single();
    }

    private static long key(int eventId) {
        return (CLASS << 32) | (eventId & 0xFFFFFFFFL);
    }
}
