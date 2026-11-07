package ma.jomhor.gate.adapters.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import ma.jomhor.gate.application.GateDevice;
import ma.jomhor.gate.application.ManifestService;
import ma.jomhor.gate.domain.ManifestPayload;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /gate/events/{id}/manifest[?since=] (docs/04-api.md §Gate): the JSON body is always sent gzip-encoded,
 * {@code no-store}. {@code event_key} is present on a full manifest only and the body is never logged.
 */
@RestController
class GateManifestController {

    record ManifestDto(int eventId, int version, int since, int rows, String payload, String signature,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String eventKey, Instant serverTime) {
        @Override
        public String toString() {
            return "ManifestDto[eventId=" + eventId + ", version=" + version + ", since=" + since + "]";
        }
    }

    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final ManifestService manifests;
    private final JsonMapper json;

    GateManifestController(ManifestService manifests, JsonMapper json) {
        this.manifests = manifests;
        this.json = json;
    }

    @GetMapping("/gate/events/{id}/manifest")
    ResponseEntity<byte[]> manifest(@RequestAttribute(GateDevice.ATTRIBUTE) GateDevice device, @PathVariable int id,
                                    @RequestParam(required = false) Integer since) {
        var d = manifests.download(device, id, since);
        var dto = new ManifestDto(d.eventId(), d.version(), d.since(), d.rows(), d.payload(),
                B64URL.encodeToString(d.signature()), d.eventKey(), Instant.now().truncatedTo(ChronoUnit.MILLIS));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_ENCODING, "gzip").body(ManifestPayload.gzip(json.writeValueAsBytes(dto)));
    }
}
