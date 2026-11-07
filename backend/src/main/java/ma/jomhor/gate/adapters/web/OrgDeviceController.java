package ma.jomhor.gate.adapters.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import ma.jomhor.gate.application.DeviceService;
import ma.jomhor.gate.domain.Device;
import ma.jomhor.tenancy.domain.OrgContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * docs/04-api.md §Organizer: /org/devices, /org/devices/enrol-codes, /org/devices/{id}/approve|revoke. ADMIN and OPS
 * only (FINANCE too gets 403, GET included). Responses are the DTO records below: never the public key.
 */
@RestController
class OrgDeviceController {

    record DeviceDto(UUID id, String label, Device.Status status, String fingerprint, Instant enrolledAt,
                     Instant approvedAt, Instant lastSeenAt) {
        static DeviceDto of(Device d) {
            return new DeviceDto(d.id(), d.label(), d.status(), d.fingerprint(), d.enrolledAt(), d.approvedAt(),
                    d.lastSeenAt());
        }
    }

    record EnrolCodeDto(String enrolCode, Instant expiresAt) {}

    private final DeviceService devices;

    OrgDeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/org/devices/enrol-codes")
    @ResponseStatus(HttpStatus.CREATED)
    EnrolCodeDto enrolCode(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        var code = devices.enrolCode(org.adminOrOps().organizerId());
        return new EnrolCodeDto(code.code(), code.expiresAt());
    }

    @GetMapping("/org/devices")
    List<DeviceDto> list(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org) {
        return devices.list(org.adminOrOps().organizerId()).stream().map(DeviceDto::of).toList();
    }

    @PostMapping("/org/devices/{id}/approve")
    DeviceDto approve(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        org.adminOrOps();
        return DeviceDto.of(devices.approve(org.organizerId(), org.userId(), id));
    }

    @PostMapping("/org/devices/{id}/revoke")
    DeviceDto revoke(@RequestAttribute(OrgContext.ATTRIBUTE) OrgContext org, @PathVariable UUID id) {
        org.adminOrOps();
        return DeviceDto.of(devices.revoke(org.organizerId(), org.userId(), id));
    }
}
