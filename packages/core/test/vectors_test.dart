import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:jomhor_core/jomhor_core.dart';
import 'package:test/test.dart';

/// UUID (with or without dashes) or plain hex -> bytes.
Uint8List hex(String s) {
  final h = s.replaceAll('-', '');
  return Uint8List.fromList([for (var i = 0; i < h.length; i += 2) int.parse(h.substring(i, i + 2), radix: 16)]);
}

Future<void> main() async {
  // Resolved from the package (lib/ -> repo root), not the working directory,
  // so an IDE or another cwd reads the same committed file.
  final lib = await Isolate.resolvePackageUri(Uri.parse('package:jomhor_core/jomhor_core.dart'));
  final goldenFile = File.fromUri(lib!.resolve('../../../docs/token-vectors.json'));

  // Golden test against the backend-generated file (docs/04-api.md §Test vectors):
  // [{k_e, event_id, ticket_id, zone_id, slot?, expected_token}]: k_e 64 lowercase
  // hex, ticket_id canonical lowercase UUID, ints as JSON ints, slot absent for v1.
  test('golden vectors from docs/token-vectors.json', () {
    if (!goldenFile.existsSync()) {
      fail('docs/token-vectors.json missing: run the backend TokenVectorsTest and commit the file');
    }
    final vectors = (jsonDecode(goldenFile.readAsStringSync()) as List<dynamic>).cast<Map<String, dynamic>>();
    expect(vectors.where((v) => !v.containsKey('slot')), hasLength(10), reason: 'static vectors');
    expect(vectors.where((v) => v.containsKey('slot')), hasLength(10), reason: 'rotating vectors');

    for (final v in vectors) {
      final kEHex = v['k_e'] as String;
      final ticketIdStr = v['ticket_id'] as String;
      expect(kEHex, matches(r'^[0-9a-f]{64}$'), reason: 'k_e in $v');
      expect(ticketIdStr, matches(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'), reason: '$v');
      final kE = hex(kEHex);
      final ticketId = hex(ticketIdStr);
      final eventId = v['event_id'] as int;
      final zoneId = v['zone_id'] as int;
      final slot = v['slot'] as int?;
      final expected = v['expected_token'] as String;

      final token = slot == null
          ? encodeStatic(kE, eventId, ticketId, zoneId)
          : encodeRotating(deriveTicketKey(kE, ticketId), eventId, ticketId, zoneId, slot);
      expect(token, expected, reason: 'encode $v');

      final parsed = decode(expected);
      expect(parsed.version, slot == null ? versionStatic : versionRotating, reason: 'version $v');
      expect(parsed.eventId, eventId, reason: 'event_id $v');
      expect(parsed.ticketId, ticketId, reason: 'ticket_id $v');
      expect(parsed.zoneId, zoneId, reason: 'zone_id $v');
      expect(parsed.slot, slot, reason: 'slot $v');
      expect(
        verify(parsed, eventId: eventId, eventKey: kE, nowSlot: slot ?? 0),
        isNull,
        reason: 'verify $v',
      );
    }
  });
}
