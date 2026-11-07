package ma.jomhor.gate.application;

import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import ma.jomhor.common.AuditLog;
import ma.jomhor.common.web.ApiException;
import ma.jomhor.gate.domain.Device;
import ma.jomhor.gate.domain.DeviceSignature;
import ma.jomhor.tenancy.application.AuthService;
import ma.jomhor.tenancy.application.AuthService.EnrolCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scanner devices: request authentication (docs/04-api.md §Gate auth), enrolment (§Gate) and the organizer's
 * device list, approve and revoke (§Organizer). {@code org} is always OrgContext.organizerId(); another organizer's
 * device is 404.
 */
@Service
public class DeviceService {

    static final int LABEL_MAX = 40;
    private static final Pattern TIME = Pattern.compile("0|[1-9][0-9]{0,14}");

    private final Devices devices;
    private final AuthService auth;
    private final AuditLog audit;
    private final ManifestSigner manifest;

    public DeviceService(Devices devices, AuthService auth, AuditLog audit, ManifestSigner manifest) {
        this.devices = devices;
        this.auth = auth;
        this.audit = audit;
        this.manifest = manifest;
    }

    /** The signed request, headers as received (null when absent). {@code path} is the raw path + query. */
    public record SignedRequest(String deviceId, String time, String signature, String method, String path, byte[] body) {}

    public record Enrolment(Device device, boolean created) {}

    // ---------- authentication ----------

    /**
     * 04 server order: device known → signature → clock window → status ACTIVE → last_seen_at. The status is only
     * looked at once the signature holds, so an unsigned caller learns nothing about a device.
     */
    public GateDevice authenticate(SignedRequest r) {
        UUID id = uuid(r.deviceId());
        Device device = (id == null ? null : devices.findForAuth(id).orElse(null));
        if (device == null) {
            throw unauthorized();
        }
        PublicKey key = DeviceSignature.p256(device.publicKey()).orElseThrow(DeviceService::unauthorized);
        checkSignatureAndTime(key, r);
        if (device.status() != Device.Status.ACTIVE) {
            throw new ApiException("DEVICE_NOT_ACTIVE", HttpStatus.FORBIDDEN, "Device not active",
                    Map.of("status", device.status().name()));
        }
        devices.touch(device.id());
        return new GateDevice(device.id(), device.organizerId());
    }

    /** POST /gate/devices/enrol: the key comes from the body (SPKI DER) and signs the request; no status check. */
    public void authenticateEnrolment(byte[] publicKey, SignedRequest r) {
        PublicKey key = (publicKey == null ? null : DeviceSignature.p256(publicKey).orElse(null));
        if (key == null) {
            throw unauthorized();
        }
        checkSignatureAndTime(key, r);
    }

    private static void checkSignatureAndTime(PublicKey key, SignedRequest r) {
        long time;
        byte[] signature;
        try {
            // 1-15 digits without a leading zero: the number's decimal form is then exactly the header as sent, so
            // "+1…" or "01…" can't reuse the signature of "1…"
            if (r.time() == null || !TIME.matcher(r.time()).matches()) {
                throw unauthorized();
            }
            time = Long.parseLong(r.time());
            signature = Base64.getUrlDecoder().decode(r.signature());
        } catch (RuntimeException e) { // absent or malformed header
            throw unauthorized();
        }
        String canonical = DeviceSignature.canonical(r.method(), r.path(), time, r.body());
        if (!DeviceSignature.verify(key, canonical, signature)) {
            throw unauthorized();
        }
        long now = System.currentTimeMillis();
        if (!DeviceSignature.withinWindow(time, now)) {
            throw new ApiException("DEVICE_CLOCK_SKEW", HttpStatus.UNAUTHORIZED, "Device clock more than 5 min off",
                    Map.of("server_time", Instant.ofEpochMilli(now)));
        }
    }

    // ---------- enrolment ----------

    /**
     * {@code publicKey} is the key the request was verified with. A valid enrol code is needed every time; a known key
     * returns its existing device (of whichever organizer enrolled it), so a retried enrolment is harmless.
     */
    @Transactional
    public Enrolment enrol(byte[] publicKey, String label, String enrolCode) {
        UUID org = auth.enrolOrganizer(enrolCode).orElseThrow(DeviceService::unauthorized);
        String l = label == null ? "" : label.strip();
        int length = l.codePointCount(0, l.length());
        boolean control = label != null && label.codePoints().anyMatch(c -> Character.getType(c) == Character.CONTROL);
        if (control || length < 1 || length > LABEL_MAX) {
            throw new ApiException("VALIDATION_FAILED", HttpStatus.UNPROCESSABLE_CONTENT, "Invalid request body",
                    Map.of("fields", Map.of("label", "1 to 40 characters, no control characters")));
        }
        var existing = devices.findByPublicKey(publicKey);
        if (existing.isPresent()) {
            return new Enrolment(existing.get(), false);
        }
        return devices.insert(org, l, publicKey).map(d -> new Enrolment(d, true))
                // a concurrent enrolment with the same key won the insert
                .orElseGet(() -> new Enrolment(devices.findByPublicKey(publicKey).orElseThrow(), false));
    }

    /** Raw 32-byte Ed25519 key the device pins. */
    public byte[] manifestPublicKey() {
        return manifest.publicKey();
    }

    // ---------- organizer ----------

    public EnrolCode enrolCode(UUID org) {
        return auth.enrolCode(org);
    }

    public List<Device> list(UUID org) {
        return devices.list(org);
    }

    /** PENDING → ACTIVE (audited); ACTIVE unchanged; REVOKED → 409 DEVICE_REVOKED. */
    @Transactional
    public Device approve(UUID org, UUID userId, UUID id) {
        if (devices.approve(org, id)) {
            audit.staff(org, userId, "device.approve", id.toString());
        }
        Device d = devices.find(org, id).orElseThrow(DeviceService::notFound);
        if (d.status() == Device.Status.REVOKED) {
            throw new ApiException("DEVICE_REVOKED", HttpStatus.CONFLICT, "Device is revoked");
        }
        return d;
    }

    /** Any status → REVOKED, idempotent; audited when it changes. */
    @Transactional
    public Device revoke(UUID org, UUID userId, UUID id) {
        if (devices.revoke(org, id)) {
            audit.staff(org, userId, "device.revoke", id.toString());
        }
        return devices.find(org, id).orElseThrow(DeviceService::notFound);
    }

    private static UUID uuid(String value) {
        try {
            return value != null && value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ApiException unauthorized() {
        return new ApiException("UNAUTHORIZED", HttpStatus.UNAUTHORIZED, "Unauthorized");
    }

    private static ApiException notFound() {
        return ApiException.notFound("Device not found");
    }
}
