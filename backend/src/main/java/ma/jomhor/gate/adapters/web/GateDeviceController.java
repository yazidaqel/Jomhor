package ma.jomhor.gate.adapters.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import ma.jomhor.gate.application.DeviceService;
import ma.jomhor.gate.domain.Device;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /gate/devices/enrol (docs/04-api.md §Gate). The request was signed with the new key; {@link DeviceAuthFilter}
 * verified it and passes that key, so {@code public_key} is not read again from the body here.
 */
@RestController
class GateDeviceController {

    record EnrolRequest(String publicKey, String label, String enrolCode) {}

    record EnrolDto(UUID deviceId, Device.Status status, String fingerprint, String manifestPublicKey,
                    Instant serverTime) {}

    private final DeviceService devices;

    GateDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    /** 201 for a new device (PENDING); 200 with the existing device for a known key. */
    @PostMapping(DeviceAuthFilter.ENROL_PATH)
    ResponseEntity<EnrolDto> enrol(@RequestAttribute(DeviceAuthFilter.ENROL_KEY) byte[] publicKey,
                                   @RequestBody EnrolRequest body) {
        var e = devices.enrol(publicKey, body.label(), body.enrolCode());
        Device d = e.device();
        return ResponseEntity.status(e.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new EnrolDto(d.id(), d.status(), d.fingerprint(),
                        Base64.getUrlEncoder().withoutPadding().encodeToString(devices.manifestPublicKey()),
                        Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    }
}
