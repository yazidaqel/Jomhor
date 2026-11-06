package ma.jomhor.common;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Appends to audit_log inside the caller's transaction. */
@Component
public class AuditLog {

    private final JdbcClient jdbc;

    AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A staff action: actor_type STAFF, actor_id = the staff member's user id. */
    public void staff(UUID organizerId, UUID userId, String action, String target) {
        jdbc.sql("insert into audit_log (organizer_id, actor_type, actor_id, action, target) values (?, 'STAFF', ?, ?, ?)")
                .params(organizerId, userId, action, target).update();
    }
}
