package ma.jomhor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.EventService.EventInput;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.sales.rooms.application.WaitingRoomService;
import ma.jomhor.sales.rooms.domain.WaitingRoom.Rule;
import ma.jomhor.tenancy.application.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Load-test data (plan lot-5 §5 BE-44, Q158): fans and the on-sale set-up the k6 scripts in backend/loadtest/k6 drive.
 * Runs once at startup when {@code jomhor.loadtest.seed-file} is set (api-1 of the smoke compose; api-2 leaves it
 * unset): {@code fans} users with phones +999… (unassigned range: no message can reach anyone), verified 30 days back,
 * each with a live 30-day fan session; a listed organizer; the on-sale event (3 zones, 2,000 places, the hot zone 1,000
 * in one category, per_account_limit 4, sale at now + {@code sale-in}) with its room (pre-queue 30 min, {@code rate}
 * fans/min, "Fans vérifiés" = account ≥ 7 days); and the hot-row event (Q151: no room, one 200,000-place category,
 * per_account_limit 10,000, on sale now). The seed file (JSON) holds the ids and one fan JWT per user, in phone
 * order. One transaction, the file included; idempotent: an existing load organizer and seed file mean the database is
 * seeded, and nothing is written. Refused under prod, without the loadtest profile, with any card provider but the
 * simulated one, and on a database with any user outside +999….
 */
@Component
@Profile("loadtest")
class LoadSeeder implements ApplicationRunner {

    static final UUID ORG = UUID.fromString("10ad7e57-0000-4000-8000-000000000001");
    /** The audit actor of the seeder's catalog writes (no staff row: nobody logs in as it). */
    static final UUID SEEDER = UUID.fromString("10ad7e57-0000-4000-8000-000000000002");
    static final String PHONE_PREFIX = "+999";

    /** What the k6 scripts read (snake_case on disk). {@code prices}: hot zone first. */
    record Seed(int eventId, List<UUID> prices, Instant saleStartsAt, int hotEventId, UUID hotPrice,
                List<String> tokens) {}

    private static final Logger log = LoggerFactory.getLogger(LoadSeeder.class);

    private final EventService events;
    private final VenueService venues;
    private final WaitingRoomService rooms;
    private final JwtEncoder jwt;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate tx;
    private final String seedFile;
    private final int fans;
    private final Duration saleIn;
    private final int rate;

    LoadSeeder(EventService events, VenueService venues, WaitingRoomService rooms, JwtEncoder jwt, JdbcClient jdbc,
               JsonMapper json, PlatformTransactionManager transactions, Environment env,
               @Value("${jomhor.loadtest.seed-file:}") String seedFile,
               @Value("${jomhor.loadtest.fans:7000}") int fans,
               @Value("${jomhor.loadtest.sale-in:5m}") Duration saleIn,
               @Value("${jomhor.loadtest.rate:1000}") int rate) {
        refuseUnsafe(env);
        this.events = events;
        this.venues = venues;
        this.rooms = rooms;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
        this.seedFile = seedFile;
        this.fans = fans;
        this.saleIn = saleIn;
        this.rate = rate;
    }

    /** Startup fails rather than seed a database that could be a real one or charge a real card. */
    static void refuseUnsafe(Environment env) {
        if (env.matchesProfiles("prod")) {
            throw new IllegalStateException("The load seeder is refused under the prod profile");
        }
        if (!env.matchesProfiles("loadtest")) {
            throw new IllegalStateException("The load seeder runs only under the loadtest profile");
        }
        if (!"fake".equals(env.getProperty("payments.card.provider"))) {
            throw new IllegalStateException("The load seeder needs payments.card.provider=fake");
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (seedFile.isBlank()) {
            return;
        }
        refuseRealAccounts();
        Path file = Path.of(seedFile);
        if (jdbc.sql("select exists (select 1 from organizers where id = ?)").param(ORG).query(Boolean.class).single()) {
            if (!Files.exists(file)) {
                throw new IllegalStateException("The database is load-seeded but " + seedFile
                        + " is missing: start from an empty database (docker compose -p jomhor-smoke down -v)");
            }
            log.info("[load seed] already seeded, {} left as it is", seedFile);
            return;
        }
        // The file is written inside the transaction: a failed write rolls the seed back, and a failed commit leaves
        // a file the next start overwrites.
        tx.executeWithoutResult(s -> write(file, seed()));
        log.info("[load seed] {} fans, seed file {}", fans, seedFile);
    }

    /** Never next to real accounts: any user whose phone isn't +999… means this isn't a throwaway database. */
    void refuseRealAccounts() {
        if (jdbc.sql("select exists (select 1 from users where phone_e164 not like ? || '%')").param(PHONE_PREFIX)
                .query(Boolean.class).single()) {
            throw new IllegalStateException("The load seeder is refused: the database has users outside " + PHONE_PREFIX
                    + "…");
        }
    }

    Seed seed() {
        jdbc.sql("insert into organizers (id, name, kind, slug) values (?, 'Load Test FC', 'SPORTS_CLUB', 'load-test-fc')")
                .param(ORG).update();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant kickoff = now.plus(Duration.ofDays(7));

        UUID venue = venues.create(ORG, "Stade Charge", "Casablanca", 200_000, null).id();
        int nord = venues.createZone(ORG, venue, "NORD", "Tribune Nord", 1_000, false).id();
        int sud = venues.createZone(ORG, venue, "SUD", "Tribune Sud", 500, false).id();
        int est = venues.createZone(ORG, venue, "EST", "Tribune Est", 500, false).id();
        Instant sale = now.plus(saleIn);
        int event = events.create(ORG, new EventInput(venue, Event.Type.OTHER, "Derby de charge", kickoff,
                kickoff.minus(Duration.ofHours(2)), sale, null, 4, null)).id();
        List<UUID> prices = List.of(
                events.createPrice(ORG, event, nord, "Virage", 5_000, 1_000).id(),
                events.createPrice(ORG, event, sud, "Plein tarif", 8_000, 500).id(),
                events.createPrice(ORG, event, est, "Plein tarif", 12_000, 500).id());
        events.publish(ORG, SEEDER, event);
        rooms.put(ORG, SEEDER, event, new WaitingRoomService.Input(30, rate, new Rule(7, null, null)));

        UUID hotVenue = venues.create(ORG, "Stade Rangée Chaude", "Casablanca", 200_000, null).id();
        int hotZone = venues.createZone(ORG, hotVenue, "HOT", "Zone chaude", 200_000, false).id();
        int hot = events.create(ORG, new EventInput(hotVenue, Event.Type.OTHER, "Rangée chaude", kickoff,
                kickoff.minus(Duration.ofHours(2)), null, null, 10_000, null)).id();
        UUID hotPrice = events.createPrice(ORG, hot, hotZone, "Plein tarif", 5_000, 200_000).id();
        events.publish(ORG, SEEDER, hot);

        return new Seed(event, prices, sale, hot, hotPrice, fans(now));
    }

    /** {@code fans} users verified 30 days back, a session each; their fan JWTs (as AuthService signs them) by phone. */
    private List<String> fans(Instant now) {
        jdbc.sql("""
                        insert into users (phone_e164, verified_at)
                        select ? || lpad(g::text, 8, '0'), now() - interval '30 days' from generate_series(1, ?) g
                        on conflict (phone_e164) do update set verified_at = excluded.verified_at""")
                .params(PHONE_PREFIX, fans).update();
        Instant exp = now.plus(Duration.ofDays(30));
        return jdbc.sql("""
                        with s as (
                          insert into fan_sessions (user_id, expires_at)
                          select id, ? from users where phone_e164 like ? || '%' and length(phone_e164) = 12
                          returning id, user_id)
                        select s.id as sid, s.user_id from s join users u on u.id = s.user_id order by u.phone_e164""")
                .params(Timestamp.from(exp), PHONE_PREFIX)
                .query((rs, n) -> token(rs.getObject("user_id", UUID.class), rs.getObject("sid", UUID.class), now, exp))
                .list();
    }

    private String token(UUID userId, UUID sessionId, Instant now, Instant exp) {
        var claims = JwtClaimsSet.builder().subject(userId.toString()).audience(List.of(AuthService.FAN))
                .issuedAt(now).expiresAt(exp).claim(AuthService.SID, sessionId.toString()).build();
        return jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    /**
     * Created with the process's default permissions (k6 reads it as another user: in the smoke image, root's umask
     * leaves it world-readable). Written before the instance reports ready, so the scripts, which start after
     * readiness, never see half a file.
     */
    private void write(Path file, Seed seed) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, json.writeValueAsString(seed));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
