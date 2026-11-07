import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:drift/drift.dart' show Value;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

import 'helpers.dart';

const base = 'https://api.jomhor.test/v1';
const deviceId = '0b8e7a52-0000-4000-8000-00000000d001';
final emptySha = sha256.convert(const <int>[]).toString();

String rfc3339(int ms) => DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true).toIso8601String();

void main() {
  group('signing string (docs/04-api.md §Gate auth)', () {
    test('the 04 example path, empty body', () {
      expect(
        gateSigningString(method: 'GET', path: '/v1/gate/events/12/manifest?since=3', timeMs: 1767225600000, body: []),
        'JMH-GATE-1\nGET\n/v1/gate/events/12/manifest?since=3\n1767225600000\n'
        'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
      );
    });

    test('the body hash is lowercase hex SHA-256 of the raw bytes', () {
      final body = utf8.encode('{"cursor":0,"scans":[]}');
      expect(
        gateSigningString(method: 'POST', path: '/v1/gate/events/12/scans', timeMs: 1, body: body).split('\n').last,
        sha256.convert(body).toString(),
      );
    });

    test('the signed path keeps the query exactly as sent', () {
      expect(signedPath(Uri.parse('$base/gate/events/12/manifest?since=3')), '/v1/gate/events/12/manifest?since=3');
      expect(signedPath(Uri.parse('$base/gate/events')), '/v1/gate/events');
    });

    // Golden: docs/gate-vectors.json `signing`, written by the backend (BE-17, GateVectorsTest). Skipped only
    // while the file does not exist; once it is committed this test is required.
    final vectors = File('../../docs/gate-vectors.json');
    test('docs/gate-vectors.json signing vectors', () {
      final all = jsonDecode(vectors.readAsStringSync()) as Map<String, Object?>;
      final signing = (all['signing']! as List<Object?>).cast<Map<String, Object?>>();
      expect(signing, isNotEmpty);
      for (final v in signing) {
        final body = utf8.encode(v['body'] as String? ?? '');
        expect(sha256.convert(body).toString(), v['expected_body_sha256'], reason: '$v');
        expect(
          gateSigningString(
            method: v['method']! as String,
            path: v['path']! as String,
            timeMs: int.parse('${v['time']}'),
            body: body,
          ),
          v['expected_string'],
          reason: '$v',
        );
      }
    }, skip: vectors.existsSync() ? false : 'docs/gate-vectors.json not written yet (BE-17)');
  });

  group('GateClient', () {
    late int deviceNow;
    late ServerClock clock;
    late FakeDeviceKey key;
    late List<http.Request> requests;
    late List<(int, int)> saved;

    GateClient client(Future<http.Response> Function(http.Request) handler, {String? id = deviceId}) {
      requests = [];
      return GateClient(
        baseUrl: base,
        key: key,
        http: MockClient((r) {
          requests.add(r);
          return handler(r);
        }),
        clock: clock,
        deviceId: () async => id,
        onOffset: (offset, at) async => saved.add((offset, at)),
      );
    }

    setUp(() {
      deviceNow = 1767225600000;
      clock = ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(deviceNow));
      key = FakeDeviceKey();
      saved = [];
    });

    test('sends the 3 headers, signed over the exact path, device time and empty body', () async {
      clock.offsetMs = 1500;
      final gate = client((_) async => json({'events': <Object?>[]}));
      await gate.get('/gate/events/12/manifest', query: {'since': '3'});

      final r = requests.single;
      final time = deviceNow + 1500;
      final expected = gateSigningString(
        method: 'GET',
        path: '/v1/gate/events/12/manifest?since=3',
        timeMs: time,
        body: [],
      );
      expect(r.url.toString(), '$base/gate/events/12/manifest?since=3');
      expect(r.headers['X-Device-Id'], deviceId);
      expect(r.headers['X-Device-Time'], '$time');
      expect(key.signed, [expected]);
      final signature = r.headers['X-Device-Signature']!;
      expect(signature, isNot(contains('=')));
      expect(base64Url.decode(base64Url.normalize(signature)), sha256.convert(utf8.encode(expected)).bytes);
      expect(expected.split('\n').last, emptySha);
    });

    test('POST signs the SHA-256 of the exact bytes sent', () async {
      final gate = client((_) async => json({'acked': <Object?>[]}));
      await gate.post('/gate/events/12/scans', body: {'cursor': 0, 'scans': <Object?>[], 'note': 'été'});

      final r = requests.single;
      expect(r.headers['Content-Type'], 'application/json; charset=utf-8');
      expect(key.signed.single.split('\n'), [
        'JMH-GATE-1',
        'POST',
        '/v1/gate/events/12/scans',
        '$deviceNow',
        sha256.convert(r.bodyBytes).toString(),
      ]);
    });

    test('enrol goes without X-Device-Id while the device has none', () async {
      final gate = client((_) async => json({'device_id': deviceId}, 201), id: null);
      await gate.post('/gate/devices/enrol', body: {'label': 'Porte 3 A'});
      expect(requests.single.headers.containsKey('X-Device-Id'), isFalse);
      expect(requests.single.headers.keys, containsAll(['X-Device-Time', 'X-Device-Signature']));
    });

    test('offset = server_time − midpoint of send and receive, then applied to the next request', () async {
      final sent = deviceNow;
      final gate = client((_) async {
        deviceNow += 400; // round trip
        return json({'server_time': rfc3339(sent + 200 + 7000)});
      });
      await gate.get('/gate/events');
      expect(clock.offsetMs, 7000);
      expect(saved, [(7000, sent + 400)]);

      await gate.get('/gate/events');
      expect(requests.last.headers['X-Device-Time'], '${sent + 400 + 7000}');
    });

    test('a round trip over 2 s is ignored once an offset exists, used when there is none', () async {
      var roundTrip = 2500;
      final gate = client((_) async {
        deviceNow += roundTrip;
        return json({'server_time': rfc3339(deviceNow + 9000)});
      });
      clock.measured = true;
      await gate.get('/gate/events');
      expect(clock.offsetMs, 0);
      expect(saved, isEmpty);

      clock.measured = false;
      await gate.get('/gate/events');
      expect((clock.offsetMs, clock.measured, saved.length), (9000 + 1250, true, 1));

      roundTrip = 2000; // at the limit: kept
      await gate.get('/gate/events');
      expect((clock.offsetMs, saved.length), (9000 + 1000, 2));
    });

    test('never follows a redirect (the signed headers would go elsewhere)', () async {
      final gate = client((_) async => http.Response('', 302, headers: {'location': 'https://elsewhere.test/'}));
      await expectLater(gate.get('/gate/events'), throwsA(isA<GateApiError>().having((e) => e.status, 'status', 302)));
      expect(requests.single.followRedirects, isFalse);
    });

    test('DEVICE_CLOCK_SKEW: fixes the offset from details.server_time and retries once', () async {
      var calls = 0;
      final gate = client((_) async {
        calls++;
        if (calls == 1) {
          return json({
            'code': 'DEVICE_CLOCK_SKEW',
            'message': 'skew',
            'details': {'server_time': rfc3339(deviceNow + 600000)},
          }, 401);
        }
        return json({'server_time': rfc3339(deviceNow + 600000), 'events': <Object?>[]});
      });
      final body = await gate.get('/gate/events');

      expect(body, containsPair('events', isEmpty));
      expect(requests, hasLength(2));
      expect(requests.last.headers['X-Device-Time'], '${deviceNow + 600000}');
      expect(key.signed.last, contains('\n${deviceNow + 600000}\n'));
    });

    test('a second DEVICE_CLOCK_SKEW is thrown, not retried again', () async {
      final gate = client(
        (_) async => json({
          'code': 'DEVICE_CLOCK_SKEW',
          'message': 'skew',
          'details': {'server_time': rfc3339(deviceNow)},
        }, 401),
      );
      await expectLater(
        gate.get('/gate/events'),
        throwsA(isA<GateApiError>().having((e) => e.code, 'code', GateApiError.clockSkew)),
      );
      expect(requests, hasLength(2));
    });

    test('04 error bodies and network failures become GateApiError', () async {
      final revoked = client(
        (_) async => json({
          'code': 'DEVICE_NOT_ACTIVE',
          'message': 'revoked',
          'details': {'status': 'REVOKED'},
        }, 403),
      );
      await expectLater(
        revoked.get('/gate/events'),
        throwsA(
          isA<GateApiError>()
              .having((e) => (e.status, e.code), 'code', (403, 'DEVICE_NOT_ACTIVE'))
              .having((e) => e.details['status'], 'status', 'REVOKED'),
        ),
      );
      final offline = client((_) => throw const SocketException('offline'));
      await expectLater(
        offline.get('/gate/events'),
        throwsA(isA<GateApiError>().having((e) => (e.status, e.code), 'code', (0, GateApiError.network))),
      );
    });
  });

  test('the provider persists the offset in config and reads X-Device-Id from it', () async {
    final db = memoryDatabase();
    addTearDown(db.close);
    await db.update(db.config).write(const ConfigCompanion(deviceId: Value(deviceId)));
    late http.Request request;
    final container = ProviderContainer(
      overrides: [
        databaseProvider.overrideWithValue(db),
        deviceKeyProvider.overrideWithValue(FakeDeviceKey()),
        httpClientProvider.overrideWithValue(
          MockClient((r) async {
            request = r;
            return json({'server_time': rfc3339(DateTime.now().millisecondsSinceEpoch + 60000)});
          }),
        ),
      ],
    );
    addTearDown(container.dispose);

    await container.read(gateClientProvider).get('/gate/events');

    expect(request.headers['X-Device-Id'], deviceId);
    final config = await db.readConfig();
    expect(config.serverTimeOffsetMs, closeTo(60000, 1000));
    expect(config.lastServerTimeAt, isNotNull);
    expect(container.read(serverClockProvider).offsetMs, config.serverTimeOffsetMs);
  });
}
