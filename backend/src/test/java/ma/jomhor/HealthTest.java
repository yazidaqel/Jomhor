package ma.jomhor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import ma.jomhor.catalog.OrgApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;

/**
 * Plan lot-5 BE-43 (P107): /v1/actuator/health is public and says UP with no details; once shutdown starts readiness
 * is OUT_OF_SERVICE, and a POST /fan/orders already in flight still completes. On an instance of its own (it gets
 * closed); the fixtures come through this class's context, on the same database.
 */
class HealthTest extends OrgApiTest {

    private static HttpResponse<String> get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 20 s");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void healthIsPublicWithoutDetailsAndShutdownDrainsAnInFlightOrder() throws Exception {
        Caller a = admin();
        String venue = venue(a, "Stade");
        int zone = zone(a, venue, "NORD");
        int event = event(a, venue);
        String price = price(a, event, zone, "Cat 1", 100);
        call(a, post("/org/events/" + event + "/publish")).andExpect(status().isOk());
        String phone = randomPhone();
        String token = fanToken(phone);
        UUID user = jdbc.sql("select id from users where phone_e164 = ?").param(phone).query(UUID.class).single();

        var app = instance();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
             var lock = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                     POSTGRES.getPassword())) {
            String base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port") + "/v1";
            // Status only: no components, no details (the root names the probe groups, nothing about the system).
            assertThat(get(http, base + "/actuator/health").body())
                    .isEqualTo("{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"UP\"}");
            for (String probe : new String[] {"liveness", "readiness"}) {
                HttpResponse<String> health = get(http, base + "/actuator/health/" + probe);
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(health.body()).isEqualTo("{\"status\":\"UP\"}");
            }
            assertThat(get(http, base + "/actuator/info").statusCode()).isEqualTo(401); // the rest stays closed
            ApplicationAvailability availability = app.getBean(ApplicationAvailability.class);
            HealthEndpoint health = app.getBean(HealthEndpoint.class);

            // The order waits on its buyer lock (JdbcOrders.lockBuyer), held here, while the instance shuts down.
            try (var hold = lock.prepareStatement("select pg_advisory_lock(hashtext(?), ?)")) {
                hold.setString(1, user.toString());
                hold.setInt(2, event);
                hold.execute();
            }
            var order = http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/fan/orders"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"event_id": %d, "channel": "APP", "items": [{"price_category_id": "%s", "qty": 1,
                             "holders": %s}]}""".formatted(event, price, holders(1)))).build(),
                    HttpResponse.BodyHandlers.ofString());
            await(() -> jdbc.sql("""
                            select count(*) from pg_locks
                            where locktype = 'advisory' and not granted and classid = cast(hashtext(?) as oid)
                              and objid = ? and objsubid = 2""") // two-int key (hashtext(user), event)
                    .params(user.toString(), event).query(Integer.class).single() > 0);

            Thread closing = Thread.ofPlatform().start(app::close);
            await(() -> availability.getReadinessState() == ReadinessState.REFUSING_TRAFFIC);
            assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
            assertThat(order).isNotDone(); // graceful: the server is waiting for it

            try (var unlock = lock.createStatement()) {
                unlock.execute("select pg_advisory_unlock_all()");
            }
            HttpResponse<String> placed = order.get(20, TimeUnit.SECONDS);
            assertThat(placed.statusCode()).isEqualTo(201);
            closing.join(Duration.ofSeconds(30));
            assertThat(app.isActive()).isFalse();
        } finally {
            app.close();
        }
    }
}
