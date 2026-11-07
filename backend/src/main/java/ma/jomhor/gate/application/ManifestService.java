package ma.jomhor.gate.application;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.EventService;
import ma.jomhor.catalog.application.VenueService;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.catalog.domain.Venue;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.domain.ManifestPayload;
import ma.jomhor.tickets.application.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Gate manifests (docs/04-api.md §Manifest payload, §Gate GET /gate/events/{id}/manifest; plan lot-2 Q43): versions
 * are built from T0 - manifest-lead until T0 + 30 min, at most every 5 min and only when the content changed; a
 * device downloads the full latest version (with K_e) or a delta from the version it holds.
 */
@Service
public class ManifestService {

    private static final Logger log = LoggerFactory.getLogger(ManifestService.class);
    /** Last build: T0 + 30 min (02 §Capacity & runbook). */
    static final Duration AFTER_START = Duration.ofMinutes(30);
    /** A new version waits until the latest is this old (Q43: checked every 5 min). */
    static final Duration REBUILD_AFTER = Duration.ofMinutes(5);
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * One download. {@code eventKey} (base64url K_e) only on a full manifest, else null; never printed by
     * {@link #toString()}.
     */
    public record Delivery(int eventId, int version, int since, int rows, String payload, byte[] signature,
                           String eventKey) {
        @Override
        public String toString() {
            return "Delivery[eventId=" + eventId + ", version=" + version + ", since=" + since + ", rows=" + rows + "]";
        }
    }

    private final Manifests manifests;
    private final Assignments assignments;
    private final EventService events;
    private final VenueService venues;
    private final TicketService tickets;
    private final ManifestSigner signer;
    private final Duration lead;
    private final TransactionTemplate tx;

    public ManifestService(Manifests manifests, Assignments assignments, EventService events, VenueService venues, TicketService tickets,
                           ManifestSigner signer, @Value("${jomhor.gate.manifest-lead}") Duration lead,
                           PlatformTransactionManager transactions) {
        this.manifests = manifests;
        this.assignments = assignments;
        this.events = events;
        this.venues = venues;
        this.tickets = tickets;
        this.signer = signer;
        this.lead = lead;
        this.tx = new TransactionTemplate(transactions);
    }

    // ---------- build ----------

    /** The job: every ON_SALE/GATES_OPEN event inside [T0 - lead, T0 + 30 min], one transaction each. */
    public int buildDue() {
        int built = 0;
        for (Event.Ref e : events.inGateWindow(lead, AFTER_START)) {
            try {
                if (build(e.organizerId(), e.id()).isPresent()) {
                    built++;
                }
            } catch (RuntimeException ex) { // one event never stops the others
                log.warn("Manifest build failed for event {}", e.id(), ex);
            }
        }
        return built;
    }

    /**
     * Builds the next version of the event if its content changed and the latest is at least 5 min old (none yet:
     * v1). Under a per-event try-lock, so a second instance skips the event instead of waiting; the unique
     * (event_id, version) catches anything else. Returns the version built.
     */
    public Optional<Integer> build(UUID org, int eventId) {
        return tx.execute(s -> buildLocked(org, eventId));
    }

    private Optional<Integer> buildLocked(UUID org, int eventId) {
        if (!manifests.tryLockBuild(org, eventId)) {
            return Optional.empty();
        }
        Event e = events.get(org, eventId);
        if (e.status() != Event.Status.ON_SALE && e.status() != Event.Status.GATES_OPEN) {
            return Optional.empty();
        }
        Instant now = ManifestPayload.builtAt(manifests.now());
        Optional<Manifests.Latest> latest = manifests.latest(org, eventId);
        if (latest.isPresent() && latest.get().builtAt().isAfter(now.minus(REBUILD_AFTER))) {
            return Optional.empty();
        }
        int version = latest.map(l -> l.version() + 1).orElse(1);
        Venue.Detail venue = venues.get(org, e.venueId());
        List<ManifestPayload.Row> rows = tickets.manifestRows(org, eventId).stream()
                .map(t -> new ManifestPayload.Row(t.id(), t.zoneId(), t.status().name(), t.holderInitials()))
                .toList();
        String payload = ManifestPayload.full(eventId, version, now, e.reentryPolicy().name(),
                venue.zones().stream().map(z -> new ManifestPayload.Zone(z.id(), z.code(), z.name())).toList(),
                venue.gates().stream().map(g -> new ManifestPayload.Gate(g.id(), g.code(), g.name(), g.zoneIds()))
                        .toList(),
                rows);
        if (latest.isPresent() && ManifestPayload.sameContent(payload,
                manifests.find(org, eventId, latest.get().version()).orElseThrow().payload())) {
            return Optional.empty();
        }
        byte[] signature = signer.sign(payload.getBytes(StandardCharsets.UTF_8));
        return manifests.insert(org, eventId, version, now, payload, signature, rows.size())
                ? Optional.of(version) : Optional.empty();
    }

    // ---------- download ----------

    /** What the download transaction read: the stored versions (still gzip) and, for a full one, K_e. */
    private record Read(Manifests.Stored full, Optional<Manifests.Stored> base, String eventKey) {
        @Override
        public String toString() {
            return "Read[version=" + full.version() + "]";
        }
    }

    /**
     * 04 order: assigned (else 404, which covers another organizer's event) → CLOSED → 409 EVENT_CLOSED → before
     * starts_at - lead or nothing built → 409 MANIFEST_NOT_READY. {@code since} absent, unknown or above the latest →
     * the full latest version with K_e; otherwise the delta from {@code since}, signed now, without K_e. Either way
     * the device's manifest_version becomes the latest.
     *
     * <p>The transaction holds the event lock shared, so it runs either before an assignment PUT or close (which
     * then sees the recorded manifest_version: DEVICE_IN_USE) or after it (404 / EVENT_CLOSED). It only reads and
     * records; inflating, diffing and signing happen after the commit.
     */
    public Delivery download(GateDevice device, int eventId, Integer since) {
        UUID org = device.organizerId();
        Read r = tx.execute(s -> {
            assignments.lockEventShared(org, eventId);
            if (!manifests.assigned(org, device.id(), eventId)) {
                throw ApiException.notFound("Event not found");
            }
            Event e = events.get(org, eventId);
            if (e.status() == Event.Status.CLOSED) {
                throw new ApiException("EVENT_CLOSED", HttpStatus.CONFLICT, "Event is closed");
            }
            Instant availableAt = e.startsAt().minus(lead);
            Optional<Manifests.Latest> latest = manifests.latest(org, eventId);
            if (manifests.now().isBefore(availableAt) || latest.isEmpty()) {
                throw new ApiException("MANIFEST_NOT_READY", HttpStatus.CONFLICT, "Manifest not available yet",
                        Map.of("available_at", availableAt));
            }
            int version = latest.get().version();
            Manifests.Stored full = manifests.find(org, eventId, version).orElseThrow();
            Optional<Manifests.Stored> base = (since == null || since < 1 || since > version)
                    ? Optional.empty() : manifests.find(org, eventId, since);
            manifests.recordDownload(org, device.id(), eventId, version);
            // K_e leaves withEventKey only as base64url, inside this response (plan lot-2 §6)
            String key = base.isPresent() ? null : events.withEventKey(org, eventId, B64URL::encodeToString);
            return new Read(full, base, key);
        });
        Manifests.Stored full = r.full();
        if (r.base().isPresent()) {
            // ponytail: a delta inflates and diffs two stored versions per request (~2.6 MB each at 45,000 rows);
            // cache by (event, since, version) if many devices poll at once
            String delta = ManifestPayload.delta(full.payload(), r.base().get().payload(), since);
            return new Delivery(eventId, full.version(), since, full.rowCount(), delta,
                    signer.sign(delta.getBytes(StandardCharsets.UTF_8)), null);
        }
        return new Delivery(eventId, full.version(), 0, full.rowCount(), full.payload(), full.signature(), r.eventKey());
    }
}
