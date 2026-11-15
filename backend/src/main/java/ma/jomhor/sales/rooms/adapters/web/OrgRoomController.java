package ma.jomhor.sales.rooms.adapters.web;

import java.time.Instant;
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
 * docs/04-api.md §Organizer /org/events/{id}/room*. Roles come from OrgContextInterceptor: ADMIN and OPS read and
 * write, FINANCE reads (its PUT/POST are 403 there), CASHIER, GATE and SUPERVISOR get 403. Every answer is the GET
 * shape {@code {room}}, built field by field.
 */
@RestController
class OrgRoomController {

    record EligibilityBody(Integer minAccountAgeDays, Instant memberJoinedBefore, Integer minEventsAttended) {}

    record PutBody(Integer preQueueMin, Integer admissionRatePerMin, EligibilityBody eligibility) {
        WaitingRoomService.Input input() {
            return new WaitingRoomService.Input(preQueueMin, admissionRatePerMin, eligibility == null ? null
                    : new WaitingRoom.Rule(eligibility.minAccountAgeDays(), eligibility.memberJoinedBefore(),
                            eligibility.minEventsAttended()));
        }
    }

    record EligibilityDto(Integer minAccountAgeDays, Instant memberJoinedBefore, Integer minEventsAttended) {}

    record FiguresDto(long joined, long waiting, long admitted, long admittedLastMin, long activeHolds,
                      long maxActiveHolds, long paid, long quota, long remaining, boolean soldOut,
                      Long estimatedDrainS) {}

    record RoomDto(int eventId, WaitingRoom.Status status, int preQueueMin, Instant opensAt, Instant saleStartsAt,
                   int admissionRatePerMin, EligibilityDto eligibility, Instant drawnAt, FiguresDto figures) {
        static RoomDto of(WaitingRoomService.View v) {
            WaitingRoom r = v.room();
            WaitingRoom.Rule rule = r.config().rule();
            WaitingRoomService.Figures f = v.figures();
            return new RoomDto(r.eventId(), r.status(), r.config().preQueueMin(), r.opensAt(), r.saleStartsAt(),
                    r.config().admissionRatePerMin(),
                    new EligibilityDto(rule.minAccountAgeDays(), rule.memberJoinedBefore(), rule.minEventsAttended()),
                    r.drawnAt(), new FiguresDto(f.joined(), f.waiting(), f.admitted(), f.admittedLastMin(),
                            f.activeHolds(), f.maxActiveHolds(), f.paid(), f.quota(), f.remaining(), f.soldOut(),
                            f.estimatedDrainS()));
        }
    }

    record Body(RoomDto room) {
        static Body of(WaitingRoomService.View v) {
            return new Body(v == null ? null : RoomDto.of(v));
        }
    }

    private final WaitingRoomService rooms;

    OrgRoomController(WaitingRoomService rooms) {
        this.rooms = rooms;
    }

    @GetMapping("/org/events/{id}/room")
    Body get(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return Body.of(rooms.get(org.organizerId(), id).orElse(null));
    }

    @PutMapping("/org/events/{id}/room")
    ResponseEntity<Body> put(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id,
                             @RequestBody PutBody body) {
        WaitingRoomService.Put put = rooms.put(org.organizerId(), org.userId(), id, body.input());
        return ResponseEntity.status(put.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Body.of(put.view()));
    }

    @PostMapping("/org/events/{id}/room/pause")
    Body pause(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return Body.of(rooms.pause(org.organizerId(), org.userId(), id));
    }

    @PostMapping("/org/events/{id}/room/resume")
    Body resume(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return Body.of(rooms.resume(org.organizerId(), org.userId(), id));
    }

    @PostMapping("/org/events/{id}/room/end")
    Body end(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable int id) {
        return Body.of(rooms.end(org.organizerId(), org.userId(), id));
    }
}
