import 'dart:convert';
import 'dart:isolate';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:drift/drift.dart' show Value;
import 'package:jomhor_scanner/data/scanner_database.dart';

/// A manifest the device refuses: bad signature, bad payload, or an envelope that disagrees with the signed
/// header. Nothing has been written when this is thrown ("last good kept", docs/02 §Manifest step 5).
final class ManifestRejected implements Exception {
  const new(this.reason);

  final String reason;

  @override
  String toString() => 'ManifestRejected($reason)';
}

/// A verified, parsed docs/04 §Manifest payload, as rows ready for Drift.
final class Manifest {
  const new({
    required this.eventId,
    required this.version,
    required this.since,
    required this.rows,
    required this.reentry,
    required this.zones,
    required this.gates,
    required this.tickets,
  });

  final int eventId;
  final int version;

  /// 0 for a full manifest, else the version this delta applies to.
  final int since;

  /// Ticket rows of the full manifest at [version] (a delta carries fewer `T` lines).
  final int rows;
  final String reentry;
  final List<ZonesCompanion> zones;
  final List<GatesCompanion> gates;
  final List<ManifestTicketsCompanion> tickets;

  bool get full => since == 0;
}

/// base64url with or without padding (docs/04 encodes without).
Uint8List base64UrlBytes(String s) => base64Url.decode(base64Url.normalize(s));

/// Ed25519 over the exact UTF-8 bytes of [payload] with the key pinned at enrolment (base64url, 32 bytes).
/// Pure Dart (`cryptography`'s DartEd25519), so it also runs in a background isolate.
Future<bool> verifyManifestSignature({
  required String publicKey,
  required String payload,
  required String signature,
}) async {
  final Uint8List key;
  final Uint8List sig;
  try {
    key = base64UrlBytes(publicKey);
    sig = base64UrlBytes(signature);
  } on FormatException {
    return false;
  }
  if (key.length != 32 || sig.length != 64) return false;
  return await Ed25519().verify(
    utf8.encode(payload),
    signature: Signature(sig, publicKey: SimplePublicKey(key, type: KeyPairType.ed25519)),
  );
}

/// Verifies, then parses. Throws [ManifestRejected]; parses nothing unless the signature holds.
Future<Manifest> openManifest({required String publicKey, required String payload, required String signature}) async {
  if (!await verifyManifestSignature(publicKey: publicKey, payload: payload, signature: signature)) {
    throw const ManifestRejected('signature');
  }
  return parseManifest(payload);
}

/// The `GET /gate/events/{id}/manifest` body decoder, in ONE background isolate: JSON decode (about 2.6 MB),
/// signature check with the pinned key, parse. A manifest body comes back without `payload` and `signature`, with
/// `manifest` (a [Manifest]) or `rejected` (the reason); any other body (a 04 error) as decoded.
Future<Object?> decodeManifestBody(Uint8List body, String publicKey) => Isolate.run(() async {
  final json = jsonDecode(utf8.decode(body));
  if (json case {'payload': final String payload, 'signature': final String signature}) {
    final envelope = Map<String, Object?>.of(json.cast<String, Object?>())
      ..remove('payload')
      ..remove('signature');
    try {
      envelope['manifest'] = await openManifest(publicKey: publicKey, payload: payload, signature: signature);
    } on ManifestRejected catch (e) {
      envelope['rejected'] = e.reason;
    }
    return envelope;
  }
  return json;
});

/// docs/04 §Manifest payload: LF lines, TAB fields, the 7 header lines in order, then Z, G and T lines.
Manifest parseManifest(String payload) {
  try {
    final lines = payload.split('\n');
    if (lines.last.isEmpty) lines.removeLast();
    if (lines.length < 7 || lines[0] != 'JMH-MANIFEST\t1') throw const FormatException('magic');
    String header(int i, String name) {
      final f = lines[i].split('\t');
      if (f.length != 2 || f[0] != name) throw FormatException('header $name');
      return f[1];
    }

    final eventId = int.parse(header(1, 'event'));
    final version = int.parse(header(2, 'version'));
    final since = int.parse(header(3, 'since'));
    header(4, 'built_at');
    final reentry = header(5, 'reentry');
    final rows = int.parse(header(6, 'rows'));
    final zones = <ZonesCompanion>[];
    final gates = <GatesCompanion>[];
    final tickets = <ManifestTicketsCompanion>[];
    for (final line in lines.skip(7)) {
      final f = line.split('\t');
      switch (f) {
        case ['Z', final id, final code, final name]:
          zones.add(ZonesCompanion.insert(zoneId: Value(int.parse(id)), code: code, name: name));
        case ['G', final id, final code, final name, final zoneIds]:
          gates.add(
            GatesCompanion.insert(
              gateId: Value(id),
              code: code,
              name: name,
              zoneIds: jsonEncode([
                for (final z in zoneIds.split(','))
                  if (z.isNotEmpty) int.parse(z),
              ]),
            ),
          );
        case ['T', final id, final zone, final status, final initials] when id.length == 36:
          tickets.add(
            ManifestTicketsCompanion.insert(
              ticketId: Value(id),
              ticketNo: id.substring(28).toUpperCase(),
              zoneId: int.parse(zone),
              status: status,
              holderInitials: Value(initials.isEmpty ? null : initials),
            ),
          );
        default:
          throw FormatException('line ${f.first}');
      }
    }
    if (since == 0 && tickets.length != rows) throw const FormatException('rows');
    return Manifest(
      eventId: eventId,
      version: version,
      since: since,
      rows: rows,
      reentry: reentry,
      zones: zones,
      gates: gates,
      tickets: tickets,
    );
  } on FormatException catch (e) {
    throw ManifestRejected('payload: ${e.message}');
  }
}
