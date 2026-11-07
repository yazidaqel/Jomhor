import 'dart:convert';

import 'package:drift/drift.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';

/// A test K_e (never a real key): bytes 0..31.
final testKe = Uint8List.fromList(List.generate(32, (i) => i));
const eventId = 12;
const otherEventId = 13;

/// 2026-11-07 20:00:00 UTC, on a 30 s slot boundary + 10 s.
const nowMs = 1794081610000;

/// Zones and gates of the test venue: N1 serves zone 1 (Tribune Nord); V1 and V2 serve zone 2 (Virage).
const zoneNord = 1;
const zoneVirage = 2;
const gateNord = 'a0000000-0000-4000-8000-0000000000e1';
const gates = [
  GateRule(gateId: gateNord, code: 'N1', zoneIds: {zoneNord}),
  GateRule(gateId: 'a0000000-0000-4000-8000-0000000000e2', code: 'V1', zoneIds: {zoneVirage}),
  GateRule(gateId: 'a0000000-0000-4000-8000-0000000000e3', code: 'V2', zoneIds: {zoneVirage}),
];
const staffId = 'b0000000-0000-4000-8000-000000000001';

/// A ticket id whose 16 bytes all equal [n] except the last, so ids are distinct and easy to read.
Uint8List ticketBytes(int n, [int last = 0]) => Uint8List.fromList([...List.filled(15, n), last]);

String ticketId(Uint8List bytes) => uuidString(bytes);

String staticToken(Uint8List ticket, {int zone = zoneNord, int event = eventId, Uint8List? key}) =>
    encodeStatic(key ?? testKe, event, ticket, zone);

String rotatingToken(Uint8List ticket, {required int slot, int zone = zoneNord, int event = eventId, Uint8List? key}) =>
    encodeRotating(deriveTicketKey(key ?? testKe, ticket), event, ticket, zone, slot);

int slotOf(int ms) => slotAt(DateTime.fromMillisecondsSinceEpoch(ms));

/// The token with one tag bit flipped.
String tampered(String token) {
  final bytes = base64Url.decode(base64Url.normalize(token));
  bytes[bytes.length - 1] ^= 0x01;
  return base64Url.encode(bytes).replaceAll('=', '');
}

/// Puts the database in event mode: event 12, gate N1, staff unlocked, the venue's zones and gates, and
/// [tickets] (id -> (zone, status, initials)) in the manifest.
Future<void> seedEventMode(ScannerDatabase db, Map<String, (int, String, String?)> tickets) async {
  await db
      .update(db.config)
      .write(const ConfigCompanion(eventId: Value(eventId), gateId: Value(gateNord), staffId: Value(staffId)));
  await db.batch((b) {
    b
      ..insertAll(db.zones, [
        ZonesCompanion.insert(zoneId: const Value(zoneNord), code: 'N', name: 'Tribune Nord'),
        ZonesCompanion.insert(zoneId: const Value(zoneVirage), code: 'V', name: 'Virage'),
      ])
      ..insertAll(db.gates, [
        for (final g in gates)
          GatesCompanion.insert(
            gateId: Value(g.gateId),
            code: g.code,
            name: g.code == 'N1' ? 'Nord' : 'Virage',
            zoneIds: jsonEncode(g.zoneIds.toList()),
          ),
      ])
      ..insertAll(db.manifestTickets, [
        for (final MapEntry(key: id, value: (zone, status, initials)) in tickets.entries)
          ManifestTicketsCompanion.insert(
            ticketId: Value(id),
            ticketNo: id.substring(28).toUpperCase(),
            zoneId: zone,
            status: status,
            holderInitials: Value(initials),
          ),
      ]);
  });
}
