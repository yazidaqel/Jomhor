package ma.jomhor.sales.rooms.application;

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
        return memo.get(eventId).map(r -> new PublicRoom(r.status().name(), r.opensAt(), !r.config().rule().any()
                ? null : new Eligibility(r.verifiedBefore(), r.config().rule().memberJoinedBefore(),
                        r.config().rule().minEventsAttended())));
    }

    @Override
    public void checkSaleStartMovable(UUID org, int eventId) {
        Optional<WaitingRoom.Status> status = rooms.share(org, eventId);
        if (status.isPresent() && status.get().live()) {
            throw new ApiException("EVENT_NOT_EDITABLE", HttpStatus.CONFLICT, "The event's waiting room has started",
                    Map.of("reason", "ROOM_STARTED"));
        }
        if (status.isPresent() && TransactionSynchronizationManager.isSynchronizationActive()) {
            // The room follows (opens_at is derived): this instance's memo reloads once the PATCH commits.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    memo.invalidate();
                }
            });
        }
    }
}
