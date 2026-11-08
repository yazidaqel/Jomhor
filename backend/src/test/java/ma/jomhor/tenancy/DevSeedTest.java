package ma.jomhor.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Dev profile: R__dev_seed applies on PG18 and is idempotent. Own container (adds a db/dev migration). */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class DevSeedTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void seedAppliesAndIsIdempotent() {
        assertSeed();
        new ResourceDatabasePopulator(new ClassPathResource("db/dev/R__dev_seed.sql")).execute(dataSource);
        assertSeed();
    }

    private void assertSeed() {
        // two listed demo clubs, no crest (plan multi-club Q72, Q76)
        assertThat(jdbc.sql("""
                select name || ' ' || kind || ' ' || slug || ' ' || coalesce(settings ->> 'crest_url', 'no-crest')
                from organizers order by name""").query(String.class).list())
                .containsExactly("ATLAS FC SPORTS_CLUB atlas-fc no-crest",
                        "Olympique Tensift SPORTS_CLUB olympique-tensift no-crest");
        assertThat(jdbc.sql("select p ->> 'name' from organizers, jsonb_array_elements(settings -> 'cash_points') p")
                .query(String.class).list()).containsExactly("Guichet Grand Stade", "Boutique du club"); // cash on in dev
        // the dev admin's memberships, as the organizer switcher lists them (by name)
        assertThat(jdbc.sql("""
                select o.name || ' ' || s.role::text from staff s join users u on u.id = s.user_id
                join organizers o on o.id = s.organizer_id
                where u.phone_e164 = '+212600000001' and s.active order by o.name""").query(String.class).list())
                .containsExactly("ATLAS FC ADMIN", "Olympique Tensift ADMIN");
        assertThat(jdbc.sql("select name || ' ' || city || ' ' || timezone from venues order by name")
                .query(String.class).list())
                .containsExactly("Grand Stade Casablanca Africa/Casablanca", "Stade Tensift Marrakech Africa/Casablanca");
        assertThat(codes("zones", "Grand Stade")).containsExactly("EST", "HONNEUR", "NORD", "SUD");
        assertThat(codes("zones", "Stade Tensift")).containsExactly("HONNEUR", "NORD", "SUD");
        assertThat(jdbc.sql("""
                select bool_and(t.total <= v.capacity) from venues v
                join (select venue_id, sum(capacity) as total from zones group by venue_id) t on t.venue_id = v.id""")
                .query(Boolean.class).single()).isTrue();
        assertThat(codes("gates", "Grand Stade")).isEqualTo(List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7"));
        assertThat(codes("gates", "Stade Tensift")).isEqualTo(List.of("P1", "P2", "P3"));
        assertThat(jdbc.sql("select count(*) from gate_zones").query(Integer.class).single()).isEqualTo(12);
        assertThat(jdbc.sql("""
                select count(*) from gate_zones gz join gates g on g.id = gz.gate_id join zones z on z.id = gz.zone_id
                where g.venue_id <> z.venue_id or gz.organizer_id <> g.organizer_id""").query(Integer.class).single())
                .isZero();
        assertThat(jdbc.sql("select count(*) from gates g where not exists (select 1 from gate_zones gz where gz.gate_id = g.id)")
                .query(Integer.class).single()).isZero();
    }

    /** Codes of the zones or gates of the venue named {@code venue}, ascending. */
    private List<String> codes(String table, String venue) {
        return jdbc.sql("select t.code from " + table + " t join venues v on v.id = t.venue_id where v.name = ? order by t.code")
                .param(venue).query(String.class).list();
    }
}
