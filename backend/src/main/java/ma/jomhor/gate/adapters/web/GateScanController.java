package ma.jomhor.gate.adapters.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import ma.jomhor.catalog.domain.Event;
import ma.jomhor.gate.application.GateDevice;
import ma.jomhor.gate.application.ScanService;
import ma.jomhor.gate.domain.Scan;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * POST /gate/events/{id}/scans and /closed-ack (docs/04-api.md §Gate). Rows bind as raw JSON so that one bad row is
 * refused on its own field instead of failing the whole batch. {@code acked} and {@code errors[].scan_id} echo the
 * row's scan_id string as sent, even a non-UUID one; only a missing or non-string scan_id is left out of
 * {@code acked} (its error has {@code scan_id: null}).
 */
@RestController
class GateScanController {

    record ScanBatch(Long cursor, List<JsonNode> scans) {}

    record ErrorDto(String scanId, String field) {}

    record UsedDto(UUID ticketId, Scan.Direction direction, Instant firstAt, String gateCode) {}

    record SyncDto(List<String> acked, List<ErrorDto> errors, List<UsedDto> usedSince, long cursor, boolean more,
                   Integer manifestVersion, Event.Status eventStatus, Instant serverTime) {}

    private final ScanService scans;

    GateScanController(ScanService scans) {
        this.scans = scans;
    }

    @PostMapping("/gate/events/{id}/scans")
    SyncDto sync(@RequestAttribute(GateDevice.ATTRIBUTE) GateDevice device, @PathVariable int id,
                 @RequestBody ScanBatch body) {
        var s = scans.sync(device, id, body.cursor(), body.scans());
        return new SyncDto(s.acked(), s.errors().stream().map(e -> new ErrorDto(e.scanId(), e.field())).toList(),
                s.usedSince().stream().map(u -> new UsedDto(u.ticketId(), u.direction(), u.firstAt(), u.gateCode()))
                        .toList(),
                s.cursor(), s.more(), s.manifestVersion(), s.eventStatus(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @PostMapping("/gate/events/{id}/closed-ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void closedAck(@RequestAttribute(GateDevice.ATTRIBUTE) GateDevice device, @PathVariable int id) {
        scans.closedAck(device, id);
    }
}
