import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

import 'helpers.dart';

/// docs/gate-vectors.json (BE-17, BE-19).
final Map<String, Object?> gateVectors =
    jsonDecode(File('../../docs/gate-vectors.json').readAsStringSync()) as Map<String, Object?>;

List<Map<String, Object?>> vectors(String name) => (gateVectors[name]! as List<Object?>).cast<Map<String, Object?>>();

/// The backend's manifest vectors: v1 full of event 12, then v2 delta since 1, signed by one test key.
Map<String, Object?> get manifestV1 => vectors('manifest')[0];
Map<String, Object?> get manifestV2 => vectors('manifest')[1];
String get vectorKey => manifestV1['public_key']! as String;

/// A test K_e (never a real key) whose bytes are easy to search for in a file.
final testEventKey = Uint8List.fromList(List.generate(32, (i) => 0xC3 ^ (i * 29 & 0xff)));
String b64(List<int> bytes) => base64Url.encode(bytes).replaceAll('=', '');

/// 2026-11-07 17:00:00 UTC, the device clock in these tests.
const deviceNowMs = 1794070800000;

String rfc3339(int ms) => DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true).toIso8601String();

/// A second Ed25519 key, for payloads the vectors do not cover (and for "signed by someone else").
Future<(String, Future<String> Function(String))> testSigner([int seed = 7]) async {
  final algorithm = Ed25519();
  final pair = await algorithm.newKeyPairFromSeed(List.filled(32, seed));
  final public = await pair.extractPublicKey();
  return (
    b64(public.bytes),
    (String payload) async => b64((await algorithm.sign(utf8.encode(payload), keyPair: pair)).bytes),
  );
}

/// The `GET /gate/events/{id}/manifest` body for [payload], its header values copied to the envelope.
Map<String, Object?> envelope(String payload, String signature, {List<int>? eventKey}) {
  int header(String name) => int.parse(RegExp('^$name\t(\\d+)\$', multiLine: true).firstMatch(payload)![1]!);
  return {
    'event_id': header('event'),
    'version': header('version'),
    'since': header('since'),
    'rows': header('rows'),
    'payload': payload,
    'signature': signature,
    'event_key': ?(eventKey == null ? null : b64(eventKey)),
    'server_time': rfc3339(deviceNowMs),
  };
}

/// A fake gate API: [handler] answers every request; [requests] records them.
class FakeGate {
  final requests = <http.Request>[];
  late Future<http.Response> Function(http.Request request) handler;

  http.Client get client => MockClient((r) {
    requests.add(r);
    return handler(r);
  });

  /// `since` of each manifest request (null = full).
  List<String?> get manifestSince => [
    for (final r in requests)
      if (r.url.path.endsWith('/manifest')) r.url.queryParameters['since'],
  ];
}

ProviderContainer gateContainer(ScannerDatabase db, FakeGate gate, {DeviceKey? key, int Function()? deviceNow}) {
  final container = ProviderContainer(
    overrides: [
      databaseProvider.overrideWithValue(db),
      httpClientProvider.overrideWithValue(gate.client),
      deviceKeyProvider.overrideWithValue(key ?? FakeDeviceKey()),
      serverClockProvider.overrideWithValue(
        ServerClock(
          measured: true,
          deviceNow: () => DateTime.fromMillisecondsSinceEpoch(deviceNow?.call() ?? deviceNowMs),
        ),
      ),
    ],
  );
  addTearDown(container.dispose);
  return container;
}

const gate1 = 'a1a1a1a1-0000-4000-8000-000000000001';

/// An ACTIVE device with the vectors' manifest key pinned, a measured clock, and event 12 at gate G1 selected
/// (no manifest yet).
Future<void> seedActiveDevice(ScannerDatabase db, {String? manifestKey, bool selected = true}) => db
    .update(db.config)
    .write(
      ConfigCompanion(
        deviceId: const Value('0b8e7a52-0000-4000-8000-00000000d001'),
        deviceStatus: const Value('ACTIVE'),
        manifestPublicKey: Value(manifestKey ?? vectorKey),
        lastServerTimeAt: const Value(deviceNowMs),
        eventId: selected ? const Value(12) : const Value.absent(),
        gateId: selected ? const Value(gate1) : const Value.absent(),
        startsAt: selected ? const Value(deviceNowMs + 3 * 3600 * 1000) : const Value.absent(),
        eventTimezone: selected ? const Value('Africa/Casablanca') : const Value.absent(),
      ),
    );

/// `GET /gate/events` body: one assignment (event 12, gate G1) and two staff members.
Map<String, Object?> eventsBody({int? manifestVersion, String timezone = 'Africa/Casablanca'}) => {
  'server_time': rfc3339(deviceNowMs),
  'device': {'id': '0b8e7a52-0000-4000-8000-00000000d001', 'label': 'Tablette 3', 'status': 'ACTIVE'},
  'events': [
    {
      'id': 12,
      'title': 'ATLAS FC – Raja',
      'event_type': 'MATCH',
      'status': 'ON_SALE',
      'starts_at': rfc3339(deviceNowMs + 3 * 3600 * 1000),
      'timezone': timezone,
      'venue': {'name': 'Stade Atlas'},
      'gate': {'id': gate1, 'code': 'G1', 'name': 'Porte 1'},
      'reentry_policy': 'NONE',
      'manifest_available_at': rfc3339(deviceNowMs),
      'manifest_version': manifestVersion,
    },
  ],
  'staff': [
    {'staff_id': 's-1', 'label': 'Yassine A.', 'role': 'GATE', 'pin_verifier': vectors('pins')[0]['verifier']},
    {'staff_id': 's-2', 'label': 'Salma B.', 'role': 'SUPERVISOR', 'pin_verifier': vectors('pins')[2]['verifier']},
  ],
};
