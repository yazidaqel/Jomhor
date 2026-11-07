package ma.jomhor;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Dev demo data that Flyway can't write (K_e is encrypted in Java) or that must follow the clock (docs/TESTING.md).
 * Runs at every dev startup, through the modules' services, after db/dev/R__dev_seed.sql (organizer, venue, staff,
 * demo fans). Idempotent by event title: a missing demo event is created, priced, published, and the demo fan gets
 * a ticket for it (a till sale by the dev admin, with two paper tickets); then the ON_SALE demo events get their
 * times moved relative to now, so the stack is always testable. The past event is closed once and left alone.
 */
@Component
@Profile("dev")
class DevSeeder implements ApplicationRunner {

    static final UUID ORG = UUID.fromString("a7a5fc00-0000-4000-8000-000000000001");
    static final UUID ADMIN = UUID.fromString("a7a5fc00-0000-4000-8000-000000000101");
    static final UUID VENUE = UUID.fromString("a7a5fc00-0000-4000-8000-000000000301");
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
    static final List<Demo> ALL = List.of(UPCOMING, MATCHDAY, PAST);

    /** Centimes and quota per zone code of the dev venue. */
    static final Map<String, long[]> PRICES = Map.of("NORD", new long[] {5_000, 2_000}, "SUD", new long[] {5_000, 2_000},
            "EST", new long[] {8_000, 3_000}, "HONNEUR", new long[] {25_000, 500});

    private static final Logger log = LoggerFactory.getLogger(DevSeeder.class);

    private final EventService events;
    private final VenueService venues;
    private final PosService pos;
    private final TransactionTemplate tx;

    DevSeeder(EventService events, VenueService venues, PosService pos, PlatformTransactionManager transactions) {
        this.events = events;
        this.venues = venues;
        this.pos = pos;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> seed());
    }

    void seed() {
        Map<String, Event.Summary> existing = new HashMap<>();
        events.list(ORG, null, null).forEach(e -> existing.putIfAbsent(e.title(), e));
        List<Demo> missing = ALL.stream().filter(d -> !existing.containsKey(d.title())).toList();
        if (!missing.isEmpty()) {
            var till = pos.open(ORG, ADMIN, "Guichet démo");
            long cash = 0;
            for (Demo d : missing) {
                cash += create(d);
            }
            if (till.created()) {
                pos.close(ORG, ADMIN, StaffRole.ADMIN, till.session().id(), cash);
            }
            log.info("[dev seed] created {}", missing.stream().map(Demo::title).toList());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (Demo d : List.of(UPCOMING, MATCHDAY)) {
            Event.Summary e = existing.get(d.title());
            if (e != null && e.status() == Event.Status.ON_SALE) {
                move(e.id(), d, now);
            }
        }
    }

    /** Creates, prices and publishes the event, sells the demo fan's ticket; returns the cash taken. */
    private long create(Demo d) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        var metadata = JsonNodeFactory.instance.objectNode()
                .put("opponent", d.opponent()).put("competition", "Championnat").put("matchday", d.matchday());
        Instant onSale = now.plus(UPCOMING.kickoff()); // sold first, moved after
        int id = events.create(ORG, new EventInput(VENUE, Event.Type.FOOTBALL_MATCH, d.title(), onSale,
                onSale.minus(d.doors()), null, null, null, metadata)).id();
        Map<String, UUID> priceOf = new HashMap<>();
        for (var zone : venues.get(ORG, VENUE).zones()) {
            long[] p = PRICES.get(zone.code());
            if (p != null) {
                priceOf.put(zone.code(), events.createPrice(ORG, id, zone.id(), "Plein tarif", p[0], (int) p[1]).id());
            }
        }
        events.publish(ORG, ADMIN, id);
        List<ItemRequest> items = new ArrayList<>();
        items.add(new ItemRequest(priceOf.get("HONNEUR"), 1, List.of(new HolderRequest(FAN_NAME, FAN_PHONE))));
        items.add(new ItemRequest(priceOf.get("NORD"), 2,
                List.of(new HolderRequest("Karim Alaoui", null), new HolderRequest("Nadia Berrada", null))));
        long cash = pos.sale(ORG, ADMIN, UUID.randomUUID(), id, items).view().order().totalMad();
        move(id, d, now);
        if (d == PAST) {
            events.close(ORG, ADMIN, id);
        }
        return cash;
    }

    private void move(int id, Demo d, Instant now) {
        Instant kickoff = now.plus(d.kickoff());
        events.update(ORG, id, new EventInput(null, null, null, kickoff, kickoff.minus(d.doors()), null, null, null,
                null));
    }
}
