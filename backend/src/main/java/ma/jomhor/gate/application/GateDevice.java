package ma.jomhor.gate.application;

import java.util.UUID;

/**
 * The ACTIVE device behind a {@code /gate/**} request, set by the device-signature filter once every 04 §Gate auth
 * check has passed. Controllers read it with {@code @RequestAttribute(GateDevice.ATTRIBUTE)}; gate queries take the
 * organizer from here, never from the body.
 */
public record GateDevice(UUID id, UUID organizerId) {

    public static final String ATTRIBUTE = "ma.jomhor.gateDevice";
}
