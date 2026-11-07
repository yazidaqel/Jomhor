package ma.jomhor.common;

import java.util.List;
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

    /** A staff action with a payload: {@code payloadJson} is a JSON object and must never hold a secret. */
    public void staff(UUID organizerId, UUID userId, String action, String target, String payloadJson) {
        jdbc.sql("insert into audit_log (organizer_id, actor_type, actor_id, action, target, payload) "
                        + "values (?, 'STAFF', ?, ?, ?, ?::jsonb)")
                .params(organizerId, userId, action, target, payloadJson).update();
    }

    /** One audit row: {@code payloadJson} is a JSON object and must never hold a secret. */
    public record Entry(String target, String payloadJson) {}

    /** A gate device's actions, one statement: actor_type DEVICE, actor_id = the device id. */
    public void device(UUID organizerId, UUID deviceId, String action, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        insert into audit_log (organizer_id, actor_type, actor_id, action, target, payload)
                        select ?, 'DEVICE', ?, ?, e.target, e.payload::jsonb
                        from unnest(?::text[], ?::text[]) with ordinality as e(target, payload, n) order by e.n
                        """)
                .params(organizerId, deviceId, action, entries.stream().map(Entry::target).toArray(String[]::new),
                        entries.stream().map(Entry::payloadJson).toArray(String[]::new))
                .update();
    }

    /** A system action (no actor); {@code payloadJson} is a JSON object and must never hold a secret. */
    public void system(UUID organizerId, String action, String target, String payloadJson) {
        jdbc.sql("insert into audit_log (organizer_id, actor_type, action, target, payload) values (?, 'SYSTEM', ?, ?, ?::jsonb)")
                .params(organizerId, action, target, payloadJson).update();
    }
}
