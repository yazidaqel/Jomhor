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
        assertThat(jdbc.sql("""
                select name || ' ' || kind || ' ' || (settings -> 'theme' ->> 'accent') || ' ' || (settings -> 'theme' ->> 'on_accent')
                from organizers""").query(String.class).list())
                .containsExactly("ATLAS FC SPORTS_CLUB #F5B700 #0B0F1A");
        assertThat(jdbc.sql("""
                select s.role::text from staff s join users u on u.id = s.user_id
                where u.phone_e164 = '+212600000001' and s.active""").query(String.class).list())
                .containsExactly("ADMIN");
        assertThat(jdbc.sql("select name || ' ' || timezone from venues").query(String.class).list())
                .containsExactly("Grand Stade Africa/Casablanca");
        assertThat(jdbc.sql("select code from zones order by code").query(String.class).list())
                .containsExactly("EST", "HONNEUR", "NORD", "SUD");
        assertThat(jdbc.sql("select sum(z.capacity) <= 45000 and sum(z.capacity) <= max(v.capacity) from zones z join venues v on v.id = z.venue_id")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("select code from gates order by code").query(String.class).list())
                .isEqualTo(List.of("P1", "P2", "P3", "P4", "P5", "P6", "P7"));
        assertThat(jdbc.sql("select count(*) from gate_zones").query(Integer.class).single()).isEqualTo(9);
        assertThat(jdbc.sql("select count(*) from gates g where not exists (select 1 from gate_zones gz where gz.gate_id = g.id)")
                .query(Integer.class).single()).isZero();
    }
}
