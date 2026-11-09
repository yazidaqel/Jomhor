package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import ma.jomhor.tenancy.domain.Pins;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The dev profile end to end, on its own Postgres (the dev seed must not reach the shared test container): the seed
 * and DevSeeder give two clubs, ATLAS FC's three demo events plus ten archive ones (plan lot-4 Q98: the demo fan
 * admitted at seven) and Olympique Tensift's one, the demo fan's tickets of
 * both clubs, a second run (a restart) adds nothing and moves the on-sale events with the clock, GET /fan/events lists
 * both clubs, the scanner staff PINs match, and the fixed OTP logs in a fan and a cashier (a wrong code still fails).
 */
class DevSeederTest {

    @Test
    void devSeedAndFixedOtp() throws Exception {
        try (var pg = new PostgreSQLContainer("postgres:18")) {
            pg.start();
            try (ConfigurableApplicationContext ctx = new SpringApplication(JomhorApplication.class).run(
                    "--spring.profiles.active=dev",
                    "--server.port=0",
                    "--spring.datasource.url=" + pg.getJdbcUrl(),
                    "--spring.datasource.username=" + pg.getUsername(),
                    "--spring.datasource.password=" + pg.getPassword(),
                    "--jomhor.dev.fixed-otp=123456")) {
                var jdbc = ctx.getBean(JdbcClient.class);
                // the clock moved on: the restart puts the Tensift event back at now + 6 days
                jdbc.sql("update events set starts_at = starts_at + interval '30 days' where organizer_id = ?")
                        .param(DevSeeder.TENSIFT).update();
                ctx.getBean(DevSeeder.class).run(null); // a restart

                assertThat(jdbc.sql("""
                                select status::text || ' ' || (starts_at > now() + interval '9 days')::text
                                    || ' ' || (starts_at between now() + interval '1 hour' and now() + interval '2 hours')::text
                                from events where organizer_id = ? order by starts_at desc""")
                        .param(DevSeeder.ORG).query(String.class).list())
                        .containsExactly(Stream.concat(Stream.of("ON_SALE true false", "ON_SALE false true"),
                                Collections.nCopies(11, "CLOSED false false").stream()).toArray(String[]::new));
                assertThat(jdbc.sql("""
                                select count(distinct s.event_id) from scans s join tickets t on t.id = s.ticket_id
                                join users u on u.id = t.holder_user_id
                                where s.organizer_id = ? and u.phone_e164 = ? and s.result = 'OK'""")
                        .params(DevSeeder.ORG, DevSeeder.FAN_PHONE).query(Integer.class).single()).isEqualTo(7);
                assertThat(jdbc.sql("""
                                select title || ' ' || status::text || ' '
                                    || (starts_at between now() + interval '5 days 23 hours' and now() + interval '6 days')::text
                                from events where organizer_id = ?""")
                        .param(DevSeeder.TENSIFT).query(String.class).list())
                        .containsExactly("Olympique Tensift vs Union Rif ON_SALE true");
                assertThat(jdbc.sql("""
                                select o.slug || ' ' || count(*) from tickets t join users u on u.id = t.holder_user_id
                                join organizers o on o.id = t.organizer_id
                                where u.phone_e164 = ? and t.status = 'VALID' group by o.slug order by o.slug""")
                        .param(DevSeeder.FAN_PHONE).query(String.class).list())
                        .containsExactly("atlas-fc 13", "olympique-tensift 1");
                List<String> pins = jdbc.sql("select pin_hash from staff where role in ('GATE', 'SUPERVISOR') order by role")
                        .query(String.class).list();
                assertThat(Pins.matches("1234", pins.get(0))).isTrue();
                assertThat(Pins.matches("5678", pins.get(1))).isTrue();

                String api = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + "/v1";
                String tout;
                try (var http = HttpClient.newHttpClient()) {
                    tout = http.send(HttpRequest.newBuilder(URI.create(api + "/fan/events")).build(),
                            HttpResponse.BodyHandlers.ofString()).body();
                }
                assertThat(tout).contains("\"slug\":\"atlas-fc\"").contains("\"slug\":\"olympique-tensift\"")
                        .contains("ATLAS FC vs Rapid Oued").contains("Olympique Tensift vs Union Rif");
                assertThat(post(api + "/fan/auth/otp", "{\"phone\": \"+212600000011\"}").statusCode()).isEqualTo(202);
                assertThat(post(api + "/fan/auth/verify", "{\"phone\": \"+212600000011\", \"code\": \"123456\"}")
                        .body()).contains("\"token\"");
                assertThat(post(api + "/fan/auth/verify", "{\"phone\": \"+212600000011\", \"code\": \"654321\"}")
                        .statusCode()).isEqualTo(401);
                assertThat(post(api + "/org/auth/verify", "{\"phone\": \"+212600000002\", \"code\": \"123456\"}")
                        .body()).contains("CASHIER");
            }
        }
    }

    private static HttpResponse<String> post(String url, String json) throws Exception {
        try (var http = HttpClient.newHttpClient()) {
            return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
