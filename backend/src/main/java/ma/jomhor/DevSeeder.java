package ma.jomhor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.EventService.EventInput;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.payments.application.PosService;
import ma.jomhor.sales.application.OrderService.HolderRequest;
import ma.jomhor.sales.application.OrderService.ItemRequest;
import ma.jomhor.tenancy.domain.StaffRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Dev demo data that Flyway can't write (K_e is encrypted in Java) or that must follow the clock (docs/TESTING.md).
 * Runs at every dev startup, through the modules' services, after db/dev/R__dev_seed.sql (organizers, venues, staff,
 * demo fans), for each demo club in turn. Idempotent by event title per club: a missing demo event is created,
 * priced, published, and the demo fan gets a ticket for it (a till sale by the dev admin on that club's till, with
 * two paper tickets); then the club's ON_SALE demo events get their times moved relative to now, so the stack is
 * always testable. Past events are closed once and left alone; the demo fan was admitted at most of the archive ones.
 */
@Component
@Profile("dev")
class DevSeeder implements ApplicationRunner {

    static final UUID ORG = UUID.fromString("a7a5fc00-0000-4000-8000-000000000001");
    static final UUID TENSIFT = UUID.fromString("a7a5fc00-0000-4000-8000-000000000002");
    static final UUID ADMIN = UUID.fromString("a7a5fc00-0000-4000-8000-000000000101");
    static final UUID VENUE = UUID.fromString("a7a5fc00-0000-4000-8000-000000000301");
    static final UUID TENSIFT_VENUE = UUID.fromString("a7a5fc00-0000-4000-8000-000000000302");
    static final String FAN_PHONE = "+212600000010";
    static final String FAN_NAME = "Yassine Benali";

    /** Kickoff = now + {@code kickoff}; doors open {@code doors} before it. */
    record Demo(String title, String opponent, int matchday, Duration kickoff, Duration doors) {}

    /** On sale, cash codes possible (sales close at T0 - 60 min, cash 2 h before that). */
    static final Demo UPCOMING = new Demo("ATLAS FC vs Rapid Oued", "Rapid Oued", 12, Duration.ofDays(10),
            Duration.ofHours(2));
    /**
     * Matchday: doors open, manifest available (T0 - 3 h), transfers closed (T0 - 90 min), sales still open for
     * 15 min (T0 - 60).
     */
    static final Demo MATCHDAY = new Demo("ATLAS FC vs Sporting Anfa", "Sporting Anfa", 11, Duration.ofMinutes(75),
            Duration.ofHours(2));
    static final Demo PAST = new Demo("ATLAS FC vs Union Rif", "Union Rif", 10, Duration.ofDays(-7), Duration.ofHours(2));
    /**
     * Plan lot-4 Q98: ten earlier home matches, a week apart before PAST, so the Club tab shows 11 past dots. The oldest
     * is last season's final matchday.
     */
    static final List<Demo> ARCHIVE = List.of(
            past("Étoile Souss", 9, 2), past("Chabab Draa", 8, 3), past("Ittihad Saïss", 7, 4),
            past("Moghreb Côtier", 6, 5), past("Racing Tadla", 5, 6), past("Hilal Oriental", 4, 7),
            past("Najm Ziz", 3, 8), past("Amal Loukkos", 2, 9), past("Fath Bouregreg", 1, 10),
            past("Nahda Dakhla", 30, 11));
    /** Archive matches the demo fan missed: admitted at the other seven. */
    static final Set<Demo> MISSED = Set.of(ARCHIVE.get(2), ARCHIVE.get(5), ARCHIVE.get(8));
    /** Retired scanner, P1 (Honneur) and the PIN gate agent of R__dev_seed.sql: where the archive admissions happened. */
    static final UUID ARCHIVE_DEVICE = UUID.fromString("a7a5fc00-0000-4000-8000-000000000501");
    static final UUID GATE_P1 = UUID.fromString("a7a5fc00-0000-4000-8000-000000000401");
    static final UUID GATE_AGENT = UUID.fromString("a7a5fc00-0000-4000-8000-000000000205");

    private static Demo past(String opponent, int matchday, int weeksAgo) {
        return new Demo("ATLAS FC vs " + opponent, opponent, matchday, Duration.ofDays(-7L * weeksAgo),
                Duration.ofHours(2));
    }

    /** The second demo club's home event (plan multi-club Q76): on sale, the demo fan holds a ticket. */
    static final Demo TENSIFT_HOME = new Demo("Olympique Tensift vs Union Rif", "Union Rif", 12, Duration.ofDays(6),
            Duration.ofHours(2));

    /** A demo club: R__dev_seed.sql's organizer and venue (the dev admin is ADMIN there), and its demo events. */
    record Club(UUID org, UUID venue, List<Demo> demos) {}

    static final List<Club> CLUBS = List.of(
            new Club(ORG, VENUE, Stream.concat(Stream.of(UPCOMING, MATCHDAY, PAST), ARCHIVE.stream()).toList()),
            new Club(TENSIFT, TENSIFT_VENUE, List.of(TENSIFT_HOME)));

    /** Centimes and quota per zone code of the dev venues (a code a venue lacks is skipped). */
    static final Map<String, long[]> PRICES = Map.of("NORD", new long[] {5_000, 2_000}, "SUD", new long[] {5_000, 2_000},
            "EST", new long[] {8_000, 3_000}, "HONNEUR", new long[] {25_000, 500});

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    private final EventService events;
    private final VenueService venues;
    private final PosService pos;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    DevSeeder(EventService events, VenueService venues, PosService pos, JdbcClient jdbc,
              PlatformTransactionManager transactions) {
        this.events = events;
        this.venues = venues;
        this.pos = pos;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> seed());
    }

    void seed() {
        CLUBS.forEach(this::seed);
    }

    private void seed(Club club) {
        Map<String, Event.Summary> existing = new HashMap<>();
        events.list(club.org(), null, null).forEach(e -> existing.putIfAbsent(e.title(), e));
        List<Demo> missing = club.demos().stream().filter(d -> !existing.containsKey(d.title())).toList();
        if (!missing.isEmpty()) {
            var till = pos.open(club.org(), ADMIN, "Guichet démo");
            long cash = 0;
            for (Demo d : missing) {
                cash += create(club, d);
            }
            if (till.created()) {
                pos.close(club.org(), ADMIN, StaffRole.ADMIN, till.session().id(), cash);
            }
            log.info("[dev seed] created {}", missing.stream().map(Demo::title).toList());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (Demo d : club.demos()) {
            Event.Summary e = existing.get(d.title());
            if (e != null && e.status() == Event.Status.ON_SALE) {
                move(club, e.id(), d, now);
            }
        }
    }

    /** Creates, prices and publishes the event, sells the demo fan's ticket; returns the cash taken. */
    private long create(Club club, Demo d) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var metadata = JsonNodeFactory.instance.objectNode()
                .put("opponent", d.opponent()).put("competition", "Championnat").put("matchday", d.matchday());
        Instant onSale = now.plus(UPCOMING.kickoff()); // sold first, moved after
        int id = events.create(club.org(), new EventInput(club.venue(), Event.Type.FOOTBALL_MATCH, d.title(), onSale,
                onSale.minus(d.doors()), null, null, null, metadata)).id();
        Map<String, UUID> priceOf = new HashMap<>();
        for (var zone : venues.get(club.org(), club.venue()).zones()) {
            long[] p = PRICES.get(zone.code());
            if (p != null) {
                priceOf.put(zone.code(),
                        events.createPrice(club.org(), id, zone.id(), "Plein tarif", p[0], (int) p[1]).id());
            }
        }
        events.publish(club.org(), ADMIN, id);
        List<ItemRequest> items = new ArrayList<>();
        items.add(new ItemRequest(priceOf.get("HONNEUR"), 1, List.of(new HolderRequest(FAN_NAME, FAN_PHONE))));
        items.add(new ItemRequest(priceOf.get("NORD"), 2,
                List.of(new HolderRequest("Karim Alaoui", null), new HolderRequest("Nadia Berrada", null))));
        long cash = pos.sale(club.org(), ADMIN, UUID.randomUUID(), id, items).view().order().totalMad();
        move(club, id, d, now);
        if (d.kickoff().isNegative()) {
            events.close(club.org(), ADMIN, id);
        }
        if (ARCHIVE.contains(d) && !MISSED.contains(d)) {
            admit(club, id);
        }
        return cash;
    }

    /**
     * One OK scan of the demo fan's ticket, 40 min before kickoff. Written as a row: ingestion takes only signed device
     * batches, and this is dev history, not a gate.
     */
    private void admit(Club club, int eventId) {
        jdbc.sql("""
                        insert into scans (scan_id, organizer_id, event_id, device_id, ticket_id, gate_id, staff_id,
                                           direction, result, token_version, device_time)
                        select uuidv7(), t.organizer_id, t.event_id, ?, t.id, ?, ?, 'IN', 'OK', 1,
                               e.starts_at - interval '40 minutes'
                        from tickets t join events e on e.id = t.event_id and e.organizer_id = t.organizer_id
                        join users u on u.id = t.holder_user_id
                        where t.organizer_id = ? and t.event_id = ? and u.phone_e164 = ?
                        """)
                .params(ARCHIVE_DEVICE, GATE_P1, GATE_AGENT, club.org(), eventId, FAN_PHONE).update();
    }

    private void move(Club club, int id, Demo d, Instant now) {
        Instant kickoff = now.plus(d.kickoff());
        events.update(club.org(), id, new EventInput(null, null, null, kickoff, kickoff.minus(d.doors()), null, null, null,
                null));
    }
}
