import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';

import 'scan_fixtures.dart';

final Uint8List valid = ticketBytes(0x11);
final Uint8List cancelled = ticketBytes(0x22);
final Uint8List refunded = ticketBytes(0x33);
final Uint8List blocked = ticketBytes(0x44);
final Uint8List virage = ticketBytes(0x55);
final Uint8List used = ticketBytes(0x66);
final Uint8List absent = ticketBytes(0x77);
final Uint8List strange = ticketBytes(0x88);

final manifest = <String, ManifestEntry>{
  ticketId(valid): const ManifestEntry(zoneId: zoneNord, status: 'VALID', initials: 'Y. A.'),
  ticketId(cancelled): const ManifestEntry(zoneId: zoneNord, status: 'CANCELLED'),
  ticketId(refunded): const ManifestEntry(zoneId: zoneNord, status: 'REFUNDED'),
  ticketId(blocked): const ManifestEntry(zoneId: zoneNord, status: 'BLOCKED'),
  ticketId(virage): const ManifestEntry(zoneId: zoneVirage, status: 'VALID', initials: 'K. B.'),
  ticketId(used): const ManifestEntry(zoneId: zoneNord, status: 'VALID'),
  ticketId(strange): const ManifestEntry(zoneId: zoneNord, status: 'SOMETHING_NEW'),
};
final Map<String, UsedEntry> usedSet = {ticketId(used): const UsedEntry(firstAt: nowMs - 60000, gateCode: 'V2')};

Future<Verdict> check(String raw, {int at = nowMs, String gate = gateNord, Uint8List? key}) => validate(
  raw,
  eventId: eventId,
  eventKey: key ?? testKe,
  nowMs: at,
  gateId: gate,
  gates: gates,
  manifest: (id) async => manifest[id],
  used: (id) async => usedSet[id],
);

void main() {
  group('one case per result code (docs/02 steps 1-7, docs/06)', () {
    test('OK, static v1 and rotating v2: zone, initials and ticket no for the screen', () async {
      for (final token in [staticToken(valid), rotatingToken(valid, slot: slotOf(nowMs))]) {
        final v = await check(token);
        expect(v.result, ScanResult.OK);
        expect(v.ticketId, ticketId(valid));
        expect(v.ticketNo, '11111100');
        expect(v.zoneId, zoneNord);
        expect(v.initials, 'Y. A.');
      }
      expect((await check(staticToken(valid))).tokenVersion, versionStatic);
      expect((await check(rotatingToken(valid, slot: slotOf(nowMs)))).tokenVersion, versionRotating);
    });

    test('BAD_FORMAT: not base64url, padded, unknown version, wrong length, empty; no ticket data', () async {
      final good = staticToken(valid);
      for (final raw in ['', 'hello world', '$good=', good.substring(1), 'B${good.substring(1)}', '!!!']) {
        final v = await check(raw);
        expect(v.result, ScanResult.BAD_FORMAT, reason: raw);
        expect(v.ticketId, isNull);
        expect(v.tokenVersion, isNull);
      }
    });

    test('surrounding whitespace from a keyboard wedge is not a format error', () async {
      expect((await check('  ${staticToken(valid)}\n')).result, ScanResult.OK);
    });

    test('WRONG_EVENT, with no ticket_id (not authenticated)', () async {
      final v = await check(staticToken(valid, event: otherEventId));
      expect(v.result, ScanResult.WRONG_EVENT);
      expect(v.ticketId, isNull);
      expect(v.ticketNo, '11111100');
    });

    test('BAD_SIG: flipped tag, or signed with another K_e (v1 and v2)', () async {
      final otherKe = Uint8List.fromList(List.filled(32, 9));
      for (final token in [
        tampered(staticToken(valid)),
        tampered(rotatingToken(valid, slot: slotOf(nowMs))),
        staticToken(valid, key: otherKe),
        rotatingToken(valid, slot: slotOf(nowMs), key: otherKe),
      ]) {
        final v = await check(token);
        expect(v.result, ScanResult.BAD_SIG);
        expect(v.ticketId, isNull);
      }
    });

    test('UNKNOWN: authentic but absent from the manifest; ticket_id is set (after authentication)', () async {
      final v = await check(staticToken(absent));
      expect(v.result, ScanResult.UNKNOWN);
      expect(v.ticketId, ticketId(absent));
      expect(v.zoneId, isNull);
    });

    test('CANCELLED for CANCELLED and REFUNDED; BLOCKED for BLOCKED and an unknown status (fail closed)', () async {
      expect((await check(staticToken(cancelled))).result, ScanResult.CANCELLED);
      expect((await check(staticToken(refunded))).result, ScanResult.CANCELLED);
      expect((await check(staticToken(blocked))).result, ScanResult.BLOCKED);
      expect((await check(staticToken(strange))).result, ScanResult.BLOCKED);
    });

    test('WRONG_ZONE names every gate of the ticket zone; a gate missing from the manifest admits nothing', () async {
      final v = await check(staticToken(virage, zone: zoneVirage));
      expect(v.result, ScanResult.WRONG_ZONE);
      expect(v.correctGates, ['V1', 'V2']);
      expect(v.initials, 'K. B.');
      expect((await check(staticToken(virage, zone: zoneVirage), gate: gates[1].gateId)).result, ScanResult.OK);
      expect((await check(staticToken(valid), gate: 'not-in-manifest')).result, ScanResult.WRONG_ZONE);
    });

    test('DUPLICATE shows the first scan time and gate (re-entry NONE)', () async {
      final v = await check(staticToken(used));
      expect(v.result, ScanResult.DUPLICATE);
      expect(v.firstAt, nowMs - 60000);
      expect(v.firstGateCode, 'V2');
      expect(v.ticketId, ticketId(used));
    });

    test('the zone comes from the manifest, not from the token', () async {
      // The token claims zone 1, the manifest says zone 2: the gate checks zone 2.
      final v = await check(staticToken(virage));
      expect(v.result, ScanResult.WRONG_ZONE);
      expect(v.zoneId, zoneVirage);
    });
  });

  group('rotating slot window on the corrected clock (docs/04 §Token)', () {
    test('slot -1, 0, +1 accepted; -2 and +2 refused as EXPIRED_SLOT', () async {
      final now = slotOf(nowMs);
      for (final d in [-1, 0, 1]) {
        expect((await check(rotatingToken(valid, slot: now + d))).result, ScanResult.OK, reason: '$d');
      }
      for (final d in [-2, 2, -100]) {
        final v = await check(rotatingToken(valid, slot: now + d));
        expect(v.result, ScanResult.EXPIRED_SLOT, reason: '$d');
        expect(v.ticketId, isNull);
      }
    });

    test('a static token has no slot: accepted at any time', () async {
      expect((await check(staticToken(valid), at: nowMs + 86400000)).result, ScanResult.OK);
    });
  });

  group('order: the first failing step wins (docs/02:78-85)', () {
    test('a wrong-event token with a bad tag is WRONG_EVENT', () async {
      expect((await check(tampered(staticToken(valid, event: otherEventId)))).result, ScanResult.WRONG_EVENT);
    });

    test('a bad tag on an unknown ticket is BAD_SIG (the manifest is not read for an unauthentic token)', () async {
      var reads = 0;
      final v = await validate(
        tampered(staticToken(absent)),
        eventId: eventId,
        eventKey: testKe,
        nowMs: nowMs,
        gateId: gateNord,
        gates: gates,
        manifest: (id) async {
          reads++;
          return null;
        },
        used: (id) async => null,
      );
      expect(v.result, ScanResult.BAD_SIG);
      expect(reads, 0);
    });

    test('an expired slot with a bad tag is EXPIRED_SLOT; on another event it is WRONG_EVENT', () async {
      final old = slotOf(nowMs) - 5;
      expect((await check(tampered(rotatingToken(valid, slot: old)))).result, ScanResult.EXPIRED_SLOT);
      expect((await check(rotatingToken(valid, slot: old, event: otherEventId))).result, ScanResult.WRONG_EVENT);
    });

    test('a cancelled ticket at the wrong gate is CANCELLED; a used ticket at the wrong gate is WRONG_ZONE', () async {
      expect((await check(staticToken(cancelled), gate: gates[1].gateId)).result, ScanResult.CANCELLED);
      expect((await check(staticToken(used), gate: gates[1].gateId)).result, ScanResult.WRONG_ZONE);
    });
  });
}
