package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The loadtest-profile seeder (plan lot-5 §5 BE-44), on its own Postgres (the shared one holds real-looking accounts,
 * which the seeder refuses): N fans with +999… phones verified 30 days back, a live session each whose JWT (in the seed
 * file, by phone) logs in; the on-sale event with its room and the hot-row event; a restart writes nothing more; a
 * seeded database without its seed file, or with any user outside +999…, is refused. Also refused under prod, without
 * the profile, and with any card provider but the simulated one; absent from a context without the profile.
 */
class LoadSeederTest extends IntegrationTest {

    @TempDir
    Path dir;

    @Autowired
    ApplicationContext context;

    @Test
    void seedsFansSessionsEventsAndTheSeedFile() throws Exception {
        Path file = dir.resolve("load/seed.json");
        try (var pg = new PostgreSQLContainer("postgres:18")) {
            pg.start();
            try (var app = instance(pg, "--spring.profiles.active=loadtest", "--jomhor.loadtest.seed-file=" + file,
                    "--jomhor.loadtest.fans=25", "--jomhor.loadtest.sale-in=10m")) {
                var db = app.getBean(JdbcClient.class);
                String api = "http://localhost:" + app.getEnvironment().getProperty("local.server.port") + "/v1";
                String seed = Files.readString(file);
                Map<String, Object> keys = JsonPath.read(seed, "$");
                assertThat(keys).containsOnlyKeys("event_id", "prices", "sale_starts_at", "hot_event_id", "hot_price",
                        "tokens");
                List<String> tokens = JsonPath.read(seed, "$.tokens");
                assertThat(tokens).hasSize(25);

                assertThat(db.sql("""
                                select count(*) from users where phone_e164 like '+999%'
                                and verified_at between now() - interval '30 days 1 minute' and now() - interval '29 days'""")
                        .query(Long.class).single()).isEqualTo(25);
                assertThat(db.sql("""
                                select count(*) from fan_sessions s join users u on u.id = s.user_id
                                where u.phone_e164 like '+999%' and s.revoked_at is null
                                and s.expires_at > now() + interval '29 days'""")
                        .query(Long.class).single()).isEqualTo(25);
                assertThat((String) JsonPath.read(get(api + "/fan/me", tokens.getFirst()), "$.phone"))
                        .isEqualTo("+99900000001");
                assertThat((String) JsonPath.read(get(api + "/fan/me", tokens.getLast()), "$.phone"))
                        .isEqualTo("+99900000025");

                String detail = get(api + "/fan/events/" + JsonPath.read(seed, "$.event_id"), null);
                assertThat((Integer) JsonPath.read(detail, "$.per_account_limit")).isEqualTo(4);
                assertThat((String) JsonPath.read(detail, "$.room.eligibility.verified_before")).isNotNull();
                assertThat(JsonPath.<List<Integer>>read(detail, "$.zones[*].prices[*].remaining"))
                        .containsExactlyInAnyOrder(1_000, 500, 500);
                assertThat(JsonPath.<List<String>>read(detail, "$.zones[*].prices[*].price_category_id"))
                        .contains(JsonPath.<String>read(seed, "$.prices[0]"));
                String hot = get(api + "/fan/events/" + JsonPath.read(seed, "$.hot_event_id"), null);
                assertThat((Boolean) JsonPath.read(hot, "$.on_sale")).isTrue();
                assertThat((Object) JsonPath.read(hot, "$.room")).isNull();
                assertThat(JsonPath.<List<Integer>>read(hot, "$.zones[*].prices[*].remaining"))
                        .containsExactly(200_000);

                LoadSeeder seeder = app.getBean(LoadSeeder.class);
                seeder.run(null); // a restart: already seeded
                assertThat(db.sql("select count(*) from fan_sessions").query(Long.class).single()).isEqualTo(25);

                Files.delete(file); // seeded, but the file is gone (a lost volume): never a half-usable state
                assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("is missing");

                db.sql("insert into users (phone_e164) values ('+212600000099')").update(); // a real account
                assertThatThrownBy(() -> seeder.run(null)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("users outside +999");
            }
        }
    }

    @Test
    void refusedUnderProdWithoutTheProfileOrWithARealCardProvider() {
        LoadSeeder.refuseUnsafe(env("fake", "loadtest"));
        assertThatThrownBy(() -> LoadSeeder.refuseUnsafe(env("fake", "loadtest", "prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
        assertThatThrownBy(() -> LoadSeeder.refuseUnsafe(env("fake", "staging")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("loadtest profile");
        assertThatThrownBy(() -> LoadSeeder.refuseUnsafe(env("payzone", "loadtest")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("payments.card.provider=fake");
        assertThatThrownBy(() -> LoadSeeder.refuseUnsafe(env(null, "loadtest")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("payments.card.provider=fake");
        assertThat(context.getBeanProvider(LoadSeeder.class).getIfAvailable()).isNull(); // test profile only
    }

    private static MockEnvironment env(String cardProvider, String... profiles) {
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        if (cardProvider != null) {
            env.setProperty("payments.card.provider", cardProvider);
        }
        return env;
    }

    /** GET, 200 expected; {@code token}: a fan bearer, or null. */
    private static String get(String url, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try (var http = HttpClient.newHttpClient()) {
            HttpResponse<String> res = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as(url).isEqualTo(200);
            return res.body();
        }
    }
}
