import 'dart:typed_data';

import 'package:jomhor_core/jomhor_core.dart';

/// The manifest row the validator reads (docs/05 `manifest_tickets`).
final class ManifestEntry {
  const new({required this.zoneId, required this.status, this.initials});

  final int zoneId;

  /// VALID | CANCELLED | REFUNDED | BLOCKED.
  final String status;

  /// `Y. A.`: the only holder data at the gate.
  final String? initials;
}

/// The used-set row of a ticket (docs/05 `used`), own or remote.
final class UsedEntry {
  const new({required this.firstAt, this.gateCode});

  /// Epoch ms, corrected device time of the first admitted scan.
  final int firstAt;
  final String? gateCode;
}

/// A venue gate from the manifest (docs/05 `gates`).
final class GateRule {
  const new({required this.gateId, required this.code, required this.zoneIds});

  final String gateId;
  final String code;
  final Set<int> zoneIds;
}

/// The validator's answer: the result code plus what the result screen may show (zone and initials only, from
/// the manifest; never a name).
final class Verdict {
  const new(
    this.result, {
    this.tokenVersion,
    this.ticketNo,
    this.ticketId,
    this.zoneId,
    this.initials,
    this.firstAt,
    this.firstGateCode,
    this.correctGates = const [],
  });

  final ScanResult result;

  /// The token's version byte; null for BAD_FORMAT.
  final int? tokenVersion;

  /// Last 8 hex of the token's ticket id, uppercase (docs/05 `ticket_no`), once decoded, so a supervisor can
  /// act on a red screen without a rescan. Display only: not authenticated before step 4 passes.
  final String? ticketNo;

  /// Canonical lowercase UUID, only once the tag verified (docs/04 sync rules: `ticket_id` present iff the
  /// result comes after authentication).
  final String? ticketId;

  /// From the manifest (step 5 on).
  final int? zoneId;
  final String? initials;

  /// DUPLICATE: when and where the ticket first entered.
  final int? firstAt;
  final String? firstGateCode;

  /// WRONG_ZONE: codes of the gates that serve the ticket's zone, in manifest order.
  final List<String> correctGates;
}

/// docs/02 §Offline validation algorithm, steps 1-7, in spec order: the first failure is the result. Pure: no
/// IO and no clock of its own; the caller injects K_e, the corrected time, the gates and the two lookups, and
/// writes the scan row (step 8). Re-entry is NONE (plan lot-2 Q33): any used row is a DUPLICATE.
Future<Verdict> validate(
  String raw, {
  required int eventId,
  required Uint8List eventKey,
  required int nowMs,
  required String gateId,
  required List<GateRule> gates,
  required Future<ManifestEntry?> Function(String ticketId) manifest,
  required Future<UsedEntry?> Function(String ticketId) used,
}) async {
  // Steps 1-2: base64url, version byte, length.
  final ParsedToken token;
  try {
    token = decode(raw.trim());
  } on TokenFormatException {
    return const Verdict(ScanResult.BAD_FORMAT);
  }
  final id = uuidString(token.ticketId);
  final ticketNo = id.substring(28).toUpperCase();
  final version = token.version;

  // Steps 3-4: event, slot (v2), tag in constant time (packages/core).
  final nowSlot = slotAt(DateTime.fromMillisecondsSinceEpoch(nowMs));
  final refused = verify(token, eventId: eventId, eventKey: eventKey, nowSlot: nowSlot);
  if (refused != null) return Verdict(refused, tokenVersion: version, ticketNo: ticketNo);

  // Step 5: manifest.
  final entry = await manifest(id);
  if (entry == null) return Verdict(ScanResult.UNKNOWN, tokenVersion: version, ticketNo: ticketNo, ticketId: id);
  Verdict seen(ScanResult result, {UsedEntry? first, List<String> correctGates = const []}) => Verdict(
    result,
    tokenVersion: version,
    ticketNo: ticketNo,
    ticketId: id,
    zoneId: entry.zoneId,
    initials: entry.initials,
    firstAt: first?.firstAt,
    firstGateCode: first?.gateCode,
    correctGates: correctGates,
  );
  switch (entry.status) {
    case 'VALID':
      break;
    case 'CANCELLED' || 'REFUNDED':
      return seen(ScanResult.CANCELLED);
    default:
      // BLOCKED, and any status this build does not know: fail closed.
      return seen(ScanResult.BLOCKED);
  }

  // Step 6: zone against the device gate. A gate missing from the manifest serves no zone (fail closed).
  final allowed = gates.where((g) => g.gateId == gateId).firstOrNull?.zoneIds ?? const {};
  if (!allowed.contains(entry.zoneId)) {
    return seen(
      ScanResult.WRONG_ZONE,
      correctGates: [
        for (final g in gates)
          if (g.zoneIds.contains(entry.zoneId)) g.code,
      ],
    );
  }

  // Step 7: used-set (own scans merged with remote ones).
  final first = await used(id);
  if (first != null) return seen(ScanResult.DUPLICATE, first: first);
  return seen(ScanResult.OK);
}

/// Canonical lowercase 8-4-4-4-12 form of 16 bytes.
String uuidString(Uint8List bytes) {
  final hex = [for (final b in bytes) b.toRadixString(16).padLeft(2, '0')].join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
      '${hex.substring(16, 20)}-${hex.substring(20)}';
}
