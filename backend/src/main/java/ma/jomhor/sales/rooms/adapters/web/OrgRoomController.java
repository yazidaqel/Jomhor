package ma.jomhor.sales.rooms.adapters.web;

import java.time.Instant;
import java.util.List;
import ma.jomhor.sales.rooms.application.WaitingRoomService;
import ma.jomhor.sales.rooms.domain.WaitingRoom;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer /org/events/{id}/rooms*. Roles come from OrgContextInterceptor: ADMIN and OPS read and
 * write, FINANCE reads (its PUT/POST are 403 there), CASHIER, GATE and SUPERVISOR get 403. GET answers
 * {@code {rooms: [room]}} by phase; PUT and the transitions answer that phase's bare room, built field by field.
 */
@RestController
class OrgRoomController {

    record EligibilityBody(Integer minAccountAgeDays, Instant memberJoinedBefore, Integer minEventsAttended) {}

    record PutBody(Integer preQueueMin, Integer admissionRatePerMin, EligibilityBody eligibility, String label,
                   Instant startsAt) {
        WaitingRoomService.Input input() {
            return new WaitingRoomService.Input(label, startsAt, preQueueMin, admissionRatePerMin, eligibility == null
                    ? null : new WaitingRoom.Rule(eligibility.minAccountAgeDays(), eligibility.memberJoinedBefore(),
                            eligibility.minEventsAttended()));
        }
    }

    record EligibilityDto(Integer minAccountAgeDays, Instant memberJoinedBefore, Integer minEventsAttended) {}

    record FiguresDto(long joined, long waiting, long admitted, long admittedLastMin, long activeHolds,
                      long maxActiveHolds, long paid, long quota, long remaining, boolean soldOut,
                      Long estimatedDrainS) {}

    record RoomDto(int eventId, int phase, String label, Instant startsAt, WaitingRoom.Status status, int preQueueMin,
                   Instant opensAt, Instant saleStartsAt, int admissionRatePerMin, EligibilityDto eligibility,
                   Instant drawnAt, FiguresDto figures) {
        static RoomDto of(WaitingRoomService.View v) {
            WaitingRoom r = v.room();
            WaitingRoom.Rule rule = r.config().rule();
            WaitingRoomService.Figures f = v.figures();
            return new RoomDto(r.eventId(), r.phase(), r.label(), r.startsAt(), r.status(), r.config().preQueueMin(),
                    r.opensAt(), r.saleStartsAt(), r.config().admissionRatePerMin(),
                    new EligibilityDto(rule.minAccountAgeDays(), rule.memberJoinedBefore(), rule.minEventsAttended()),
                    r.drawnAt(), new FiguresDto(f.joined(), f.waiting(), f.admitted(), f.admittedLastMin(),
                            f.activeHolds(), f.maxActiveHolds(), f.paid(), f.quota(), f.remaining(), f.soldOut(),
                            f.estimatedDrainS()));
        }
    }

    record Rooms(List<RoomDto> rooms) {}

    private final WaitingRoomService rooms;

    OrgRoomController(WaitingRoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/org/events/{id}/rooms")
    Rooms list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return new Rooms(rooms.list(org.organizerId(), id).stream().map(RoomDto::of).toList());
    }

    @PutMapping("/org/events/{id}/rooms/{phase}")
    ResponseEntity<RoomDto> put(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                                @PathVariable int phase, @RequestBody PutBody body) {
        WaitingRoomService.Put put = rooms.put(org.organizerId(), org.userId(), id, phase, body.input());
        return ResponseEntity.status(put.created() ? HttpStatus.CREATED : HttpStatus.OK).body(RoomDto.of(put.view()));
    }

    @PostMapping("/org/events/{id}/rooms/{phase}/pause")
    RoomDto pause(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                  @PathVariable int phase) {
        return RoomDto.of(rooms.pause(org.organizerId(), org.userId(), id, phase));
    }

    @PostMapping("/org/events/{id}/rooms/{phase}/resume")
    RoomDto resume(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                   @PathVariable int phase) {
        return RoomDto.of(rooms.resume(org.organizerId(), org.userId(), id, phase));
    }

    @PostMapping("/org/events/{id}/rooms/{phase}/end")
    RoomDto end(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                @PathVariable int phase) {
        return RoomDto.of(rooms.end(org.organizerId(), org.userId(), id, phase));
    }
}
