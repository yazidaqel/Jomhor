package ma.jomhor.tickets.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.common.HolderNames;
import ma.jomhor.tickets.domain.Ticket;
import ma.jomhor.tickets.domain.TicketToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues tickets for a paid order (one per place, status VALID) inside the caller's transaction. The holder is set
 * at insert and never changes (tickets are immutable after issue). Lists a fan's tickets with their K_t.
 */
@Service
public class TicketService {

    /**
     * One place: its zone and category, and who holds it. {@code holderName} is a normalized name (docs/04-api.md
     * §Holder names), or null for a Lot 0 order (no name, no initials).
     */
    public record Seat(int zoneId, UUID priceCategoryId, UUID holderUserId, String holderName) {}

    /**
     * A ticket held by the caller with its event and zone. {@code ticketKey} is K_t base64url, or null when not
     * asked for or not allowed (see {@link #heldBy}). Not for logs.
     */
    public record Held(Ticket ticket, Event.Listing event, Event.ZoneListing zone, String ticketKey) {
        @Override
        public String toString() {
            return "Held[" + ticket.id() + "]";
        }
    }

    /** An event that started longer ago than this is listed with the past ones. */
    static final Duration PAST_AFTER = Duration.ofHours(12);

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final Tickets tickets;
    private final EventService events;

    public TicketService(Tickets tickets, EventService events) {
        this.tickets = tickets;
        this.events = events;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> issue(UUID org, UUID orderId, int eventId, List<Seat> seats) {
        return seats.stream().map(s -> tickets.insert(org, orderId, eventId, s.zoneId(), s.priceCategoryId(),
                s.holderUserId(), s.holderName(), s.holderName() == null ? null : HolderNames.initials(s.holderName())))
                .toList();
    }

    /** The order's tickets this user holds (04: a friend's ticket is never listed to the buyer). */
    public List<UUID> heldInOrder(UUID org, UUID orderId, UUID holderUserId) {
        return tickets.heldInOrder(org, orderId, holderUserId);
    }

    /**
     * 04 GET /fan/tickets: the tickets {@code holderUserId} holds; upcoming events first (by starts_at), then past
     * ones (latest first). Past = CLOSED, or started more than 12 h ago (database clock). With {@code withKeys}, each VALID ticket of an event not CLOSED gets
     * K_t = HMAC(K_e, ticket_id): K_e is opened once per event, only inside withEventKey, and only the derived
     * K_t leaves it, as base64url (the raw K_t bytes are zeroed once encoded).
     */
    public List<Held> heldBy(UUID holderUserId, boolean withKeys) {
        Map<Integer, List<Ticket>> byEvent = tickets.heldBy(holderUserId).stream()
                .collect(Collectors.groupingBy(Ticket::eventId, LinkedHashMap::new, Collectors.toList()));
        List<List<Held>> groups = new ArrayList<>();
        Instant pastBefore = tickets.now().minus(PAST_AFTER);
        // ponytail: ~4 queries per event group (listing, prices, zones, key), growing with every event the fan has
        // attended; batch listings and zones by the set of event ids, or bound the past events, when it matters.
        byEvent.forEach((eventId, held) -> {
            UUID org = held.getFirst().organizerId(); // events.id is global: one organizer per event
            EventService.PublicEvent e = events.publicEvent(eventId);
            Map<Integer, Event.ZoneListing> zones = e.zones().stream()
                    .collect(Collectors.toMap(Event.ZoneListing::id, z -> z));
            List<UUID> keyed = withKeys && e.event().status() != Event.Status.CLOSED
                    ? held.stream().filter(t -> t.status() == Ticket.Status.VALID).map(Ticket::id).toList()
                    : List.of();
            Map<UUID, String> keys = keyed.isEmpty() ? Map.of()
                    : events.withEventKey(org, eventId, eventKey -> ticketKeys(eventKey, keyed));
            groups.add(held.stream()
                    .map(t -> new Held(t, e.event(), zones.get(t.zoneId()), keys.get(t.id()))).toList());
        });
        Predicate<List<Held>> past = g -> g.getFirst().event().status() == Event.Status.CLOSED
                || g.getFirst().event().startsAt().isBefore(pastBefore);
        groups.sort(Comparator.comparing(past::test)
                .thenComparingLong(g -> past.test(g) ? -startsAt(g) : startsAt(g))
                .thenComparingInt(g -> g.getFirst().event().id()));
        return groups.stream().flatMap(List::stream).toList();
    }

    private static long startsAt(List<Held> group) {
        return group.getFirst().event().startsAt().toEpochMilli();
    }

    /** Runs inside withEventKey: {@code eventKey} is not kept, and each raw K_t is zeroed once encoded. */
    private static Map<UUID, String> ticketKeys(byte[] eventKey, List<UUID> ticketIds) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : ticketIds) {
            byte[] kt = TicketToken.deriveTicketKey(eventKey, id);
            try {
                out.put(id, B64URL.encodeToString(kt));
            } finally {
                Arrays.fill(kt, (byte) 0);
            }
        }
        return out;
    }
}
