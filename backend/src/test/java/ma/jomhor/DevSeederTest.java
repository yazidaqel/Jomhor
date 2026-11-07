package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import ma.jomhor.tenancy.domain.Pins;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The dev profile end to end, on its own Postgres (the dev seed must not reach the shared test container): the seed
 * and DevSeeder give three demo events and the demo fan's three tickets, a second run (a restart) adds nothing, the
 * scanner staff PINs match, and the fixed OTP logs in a fan and a cashier (a wrong code still fails).
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
                ctx.getBean(DevSeeder.class).run(null); // a restart
                var jdbc = ctx.getBean(JdbcClient.class);

                assertThat(jdbc.sql("""
                                select status::text || ' ' || (starts_at > now() + interval '9 days')::text
                                    || ' ' || (starts_at between now() + interval '1 hour' and now() + interval '2 hours')::text
                                from events where organizer_id = ? order by starts_at desc""")
                        .param(DevSeeder.ORG).query(String.class).list())
                        .containsExactly("ON_SALE true false", "ON_SALE false true", "CLOSED false false");
                assertThat(jdbc.sql("""
                                select count(*) from tickets t join users u on u.id = t.holder_user_id
                                where u.phone_e164 = ? and t.status = 'VALID'""")
                        .param(DevSeeder.FAN_PHONE).query(Integer.class).single()).isEqualTo(3);
                List<String> pins = jdbc.sql("select pin_hash from staff where role in ('GATE', 'SUPERVISOR') order by role")
                        .query(String.class).list();
                assertThat(Pins.matches("1234", pins.get(0))).isTrue();
                assertThat(Pins.matches("5678", pins.get(1))).isTrue();

                String api = "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port") + "/v1";
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
