package ma.jomhor.sales.rooms.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import ma.jomhor.catalog.application.EventRooms;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Catalog's view of the rooms. Kept apart from WaitingRoomService, which depends on catalog's EventService, so the
 * two modules' beans don't form a cycle.
 */
@Component
class CatalogRooms implements EventRooms {

    private final RoomMemo memo;
    private final WaitingRooms rooms;

    CatalogRooms(RoomMemo memo, WaitingRooms rooms) {
        this.memo = memo;
        this.rooms = rooms;
    }

    @Override
    public Optional<PublicRoom> publicRoom(int eventId) {
        Instant now = Instant.now();
        List<WaitingRoom> phases = memo.rooms(eventId);
        return WaitingRoom.current(phases, now).map(r -> new PublicRoom(r.phase(), r.label(),
                r.statusAt(now).name(), r.opensAt(), r.saleStartsAt(), !r.config().rule().any() ? null
                        : new Eligibility(r.verifiedBefore(), r.config().rule().memberJoinedBefore(),
                                r.config().rule().minEventsAttended()),
                WaitingRoom.next(phases, r.phase())
                        .map(n -> new NextPhase(n.phase(), n.label(), n.opensAt(), n.saleStartsAt())).orElse(null)));
    }

    @Override
    public void checkSaleStartMovable(UUID org, int eventId, Instant saleStartsAt) {
        Optional<WaitingRoom.Status> status = rooms.share(org, eventId, 1);
        if (status.isEmpty()) {
            return;
        }
        if (status.get().live()) {
            throw new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT, "The event's waiting room has started",
                    Map.of("reason", "ROOM_STARTED"));
        }
        if (status.get() == WaitingRoom.Status.SCHEDULED) { // read under the event lock every room PUT takes
            WaitingRoom.next(rooms.list(org, eventId), 1).filter(r -> !r.opensAt().isAfter(saleStartsAt))
                    .ifPresent(r -> {
                        Map<String, Object> details = new LinkedHashMap<>();
                        details.put("reason", "PHASE_ORDER");
                        details.put("fields", Map.of("sale_starts_at", "must be before phase " + r.phase() + " opens"));
                        throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT,
                                "Invalid request body", details);
                    });
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // Phase 1 follows (opens_at is derived): this instance's memo reloads once the PATCH commits.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    memo.invalidate();
                }
            });
        }
    }
}
