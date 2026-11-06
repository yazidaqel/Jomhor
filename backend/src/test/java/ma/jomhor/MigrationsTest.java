package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void v1ToV7Apply() {
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    void everyTableCarriesOrganizerIdExceptPlatformIdentityTables() {
        var withoutOrganizerId = jdbcTemplate.queryForList("""
                select t.table_name from information_schema.tables t
                where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                  and not exists (select 1 from information_schema.columns c
                                  where c.table_schema = t.table_schema and c.table_name = t.table_name
                                    and c.column_name = 'organizer_id')
                """, String.class);
        assertThat(withoutOrganizerId)
                .containsExactlyInAnyOrder("organizers", "users", "otp_challenges", "flyway_schema_history");
    }

    @Test
    void organizerIdIsNotNullExceptOnAuditLog() {
        var nullable = jdbcTemplate.queryForList("""
                select table_name from information_schema.columns
                where table_schema = 'public' and column_name = 'organizer_id' and is_nullable = 'YES'
                """, String.class);
        assertThat(nullable).containsExactly("audit_log");
    }

    @Test
    void soldAboveQuotaIsRejected() {
        var org = jdbcTemplate.queryForObject("insert into organizers (name) values ('ATLAS FC') returning id", UUID.class);
        var venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'Stade', 1000) returning id",
                UUID.class, org);
        var zone = jdbcTemplate.queryForObject(
                "insert into zones (organizer_id, venue_id, code, name, capacity) values (?, ?, 'NORD', 'Nord', 100) returning id",
                Short.class, org, venue);
        var event = jdbcTemplate.queryForObject("""
                insert into events (organizer_id, venue_id, title, starts_at, event_key_enc)
                values (?, ?, 'Match', now() + interval '7 days', ?) returning id
                """, Integer.class, org, venue, new byte[32]);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into price_categories (organizer_id, event_id, zone_id, name, price_mad, quota, sold)
                values (?, ?, ?, 'Plein tarif', 5000, 1, 2)
                """, org, event, zone))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("violates check constraint \"price_categories_check\"");
    }
}
