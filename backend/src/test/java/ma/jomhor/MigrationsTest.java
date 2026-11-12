package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationsTest extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void v1ToV20Apply() {
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                        "17", "18", "19", "20");
    }

    /** V20: an attempt returns to APP or WEB only (a CHECK runs before the foreign keys, so no rows are needed). */
    @Test
    void paymentReturnChannelRefusesPos() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into payments (organizer_id, order_id, method, provider, amount_mad, return_channel)
                values (gen_random_uuid(), gen_random_uuid(), 'CARD', 'FAKE', 1, 'POS')"""))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("payments_return_channel_check");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where tablename = 'orders' and indexdef like '%(group_order_id) WHERE (group_order_id IS NOT NULL)'""",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void membershipNumbersArePositiveAndUniquePerOrganizer() {
        UUID org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club') returning id", UUID.class);
        UUID other = jdbcTemplate.queryForObject("insert into organizers (name) values ('Club 2') returning id", UUID.class);
        String user = "insert into users (phone_e164) values (?) returning id";
        UUID u1 = jdbcTemplate.queryForObject(user, UUID.class, randomPhone());
        UUID u2 = jdbcTemplate.queryForObject(user, UUID.class, randomPhone());
        String insert = "insert into memberships (organizer_id, user_id, number) values (?, ?, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, u1, 0))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("memberships_number_check");
        jdbcTemplate.update(insert, org, u1, 1);
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, u2, 1)).isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("memberships_organizer_id_number_key");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, u1, 2)).isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("memberships_organizer_id_user_id_key");
        jdbcTemplate.update(insert, other, u2, 1); // numbers are per organizer
    }

    @Test
    void gateLocationIsBothOrNeitherAndInRange() {
        UUID org = jdbcTemplate.queryForObject("insert into organizers (name) values ('Loc') returning id", UUID.class);
        UUID venue = jdbcTemplate.queryForObject(
                "insert into venues (organizer_id, name, capacity) values (?, 'V', 10) returning id", UUID.class, org);
        String insert = "insert into gates (organizer_id, venue_id, code, name, latitude, longitude) values (?, ?, ?, 'G', ?, ?)";
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, venue, "A", 33.5, null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("gates_location");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, venue, "B", null, -7.6))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("gates_location");
        assertThatThrownBy(() -> jdbcTemplate.update(insert, org, venue, "C", new BigDecimal("91"),
                new BigDecimal("-7.6")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("gates_latitude_check");
        jdbcTemplate.update(insert, org, venue, "D", new BigDecimal("-90"), new BigDecimal("180"));
        jdbcTemplate.update(insert, org, venue, "E", null, null);
    }

    @Test
    void organizerSlugCheckUniqueAndNullable() {
        for (String bad : new String[] {"Atlas", "atlas--fc", "-atlas", "atlas-", "a", "a".repeat(41), "orders", "mine",
                "ar", "all", "events", "atlas_fc", "atlas fc"}) {
            assertThatThrownBy(() -> jdbcTemplate.update("insert into organizers (name, slug) values ('X', ?)", bad))
                    .as(bad)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("organizers_slug");
        }
        String ok = "t-" + UUID.randomUUID().toString().substring(24) + "-" + "9".repeat(20); // 2..40, digits ok
        jdbcTemplate.update("insert into organizers (name, slug) values ('X', ?)", ok);
        assertThatThrownBy(() -> jdbcTemplate.update("insert into organizers (name, slug) values ('Y', ?)", ok))
                .isInstanceOf(DuplicateKeyException.class);
        jdbcTemplate.update("insert into organizers (name) values ('No slug 1'), ('No slug 2')"); // nulls coexist
    }

    @Test
    void ticketTransfersAreUniqueOnBothTicketsAndCarryOrganizerId() {
        assertThat(jdbcTemplate.queryForList("""
                select a.attname from pg_index i
                join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)
                where i.indrelid = 'ticket_transfers'::regclass and i.indisunique and not i.indisprimary
                """, String.class)).containsExactlyInAnyOrder("ticket_id", "new_ticket_id");
        assertThat(jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_name = 'ticket_transfers' and column_name = 'organizer_id'
                """, String.class)).isEqualTo("NO");
    }

    @Test
    void scansRefuseUpdateDeleteAndTruncateEvenOnNoRow() {
        for (String sql : new String[] {"update scans set result = result where false", "delete from scans where false",
                "truncate scans"}) {
            assertThatThrownBy(() -> jdbcTemplate.execute(sql)).as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("scans is append-only");
        }
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
                .containsExactlyInAnyOrder("organizers", "users", "otp_challenges", "fan_sessions", "flyway_schema_history");
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
