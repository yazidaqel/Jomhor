import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart';
import 'package:drift/native.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

/// A [DeviceKey] without Keystore: records what it was asked to sign and "signs" with SHA-256, so a test can
/// check the exact signed bytes and the header encoding. The real ECDSA check is the backend's (BE-17).
class FakeDeviceKey implements DeviceKey {
  final signed = <String>[];
  Uint8List? key;

  @override
  Future<Uint8List> generate() async => key = Uint8List.fromList(List.generate(91, (i) => i));

  @override
  Future<Uint8List?> publicKey() async => key;

  @override
  Future<Uint8List> sign(Uint8List data) async {
    signed.add(utf8.decode(data));
    return Uint8List.fromList(sha256.convert(data).bytes);
  }
}

/// In-memory database; streams close synchronously so widget tests leave no pending timers.
ScannerDatabase memoryDatabase() =>
    ScannerDatabase(DatabaseConnection(NativeDatabase.memory(), closeStreamsSynchronously: true));

/// JSON response helper for `MockClient` handlers.
http.Response json(Object? body, [int status = 200]) =>
    http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});
