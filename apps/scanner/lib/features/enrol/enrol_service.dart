import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/manifest.dart' show base64UrlBytes;

/// docs/04 §Gate auth: first 8 hex chars of SHA-256(SPKI DER), uppercase, grouped 4-4 (`AB12 CD34`).
String fingerprint(List<int> spki) {
  final hex = sha256.convert(spki).toString().substring(0, 8).toUpperCase();
  return '${hex.substring(0, 4)} ${hex.substring(4)}';
}

/// The enrol QR holds exactly the `enrol_code` JWT (plan lot-2 Q30); anything else (a ticket QR) is refused
/// before a request is sent.
final enrolCodePattern = RegExp(r'^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*\.[A-Za-z0-9_-]+$');

/// docs/04 `label` 1–40 characters.
bool validLabel(String label) => label.trim().isNotEmpty && label.trim().length <= 40;

String _b64(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

/// Enrolment (docs/02 §Manifest step 1): the Keystore key, `POST /gate/devices/enrol`, the pinned manifest key.
class EnrolService {
  new(this._db, {required this._gate, required this._key});

  final ScannerDatabase _db;
  final GateClient _gate;
  final DeviceKey _key;

  /// This device's fingerprint, null before a key exists.
  Future<String?> currentFingerprint() async {
    final spki = await _key.publicKey();
    return spki == null ? null : fingerprint(spki);
  }

  /// Signs up with [code] and [label]; the device lands PENDING (or as the server knows it). An existing key is
  /// reused, so a retry after a lost response finds the same device (enrol is idempotent per key); a key the
  /// server already revoked is replaced once. Throws [GateApiError].
  Future<void> enrol(String code, String label) async {
    var spki = await _key.publicKey() ?? await _key.generate();
    var json = await _post(spki, code, label);
    if (json['status'] == 'REVOKED') {
      spki = await _key.generate();
      json = await _post(spki, code, label);
    }
    final Uint8List manifestKey;
    try {
      manifestKey = base64UrlBytes(json['manifest_public_key']! as String);
    } on Object {
      throw const GateApiError(status: 201, code: GateApiError.unexpectedResponse, message: 'manifest_public_key');
    }
    final (deviceId, status) = (json['device_id'], json['status']);
    if (manifestKey.length != 32 || deviceId is! String || status is! String) {
      throw const GateApiError(status: 201, code: GateApiError.unexpectedResponse, message: 'enrol');
    }
    await _db
        .update(_db.config)
        .write(
          ConfigCompanion(
            deviceId: Value(deviceId),
            deviceStatus: Value(status),
            manifestPublicKey: Value(_b64(manifestKey)),
          ),
        );
  }

  Future<Map<String, Object?>> _post(Uint8List spki, String code, String label) async {
    final json = await _gate.post(
      '/gate/devices/enrol',
      body: {'public_key': _b64(spki), 'label': label.trim(), 'enrol_code': code},
    );
    if (json is Map<String, Object?>) return json;
    throw const GateApiError(status: 201, code: GateApiError.unexpectedResponse, message: 'enrol');
  }
}

final enrolServiceProvider = Provider<EnrolService>(
  (ref) =>
      EnrolService(ref.watch(databaseProvider), gate: ref.watch(gateClientProvider), key: ref.watch(deviceKeyProvider)),
);
