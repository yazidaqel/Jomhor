import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_scanner/app/config.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

export 'package:jomhor_scanner/core/server_clock.dart';

/// Turns response bytes into the decoded JSON body.
typedef BodyDecoder = FutureOr<Object?> Function(Uint8List bytes);

/// docs/04-api.md §Gate auth: the exact string the device key signs.
String gateSigningString({
  required String method,
  required String path,
  required int timeMs,
  required List<int> body,
}) => 'JMH-GATE-1\n$method\n$path\n$timeMs\n${sha256.convert(body)}';

/// The signed PATH: raw path plus query, as the request line sends it (`/v1/gate/events/12/manifest?since=3`).
String signedPath(Uri url) => url.hasQuery ? '${url.path}?${url.query}' : url.path;

/// A gate API failure: the 04 `{code, message, details}` body, or a client-side code.
class GateApiError implements Exception {
  const new({required this.status, required this.code, required this.message, this.details = const {}});

  /// No HTTP response at all (offline, DNS, timeout). [status] is 0.
  static const network = 'NETWORK_ERROR';

  /// A response without the 04 error shape (proxy page, bad JSON).
  static const unexpectedResponse = 'UNEXPECTED_RESPONSE';

  static const clockSkew = 'DEVICE_CLOCK_SKEW';

  /// 403, `details.status` PENDING or REVOKED.
  static const notActive = 'DEVICE_NOT_ACTIVE';

  /// 409 before `manifest_available_at`, `details.available_at`.
  static const manifestNotReady = 'MANIFEST_NOT_READY';

  final int status;
  final String code;
  final String message;
  final Map<String, Object?> details;

  @override
  String toString() => 'GateApiError($status $code: $message)';
}

/// JSON client for `/gate/...`, every request signed with the device key (docs/04-api.md §Gate auth).
/// Never used on the scan path: validation is offline (docs/02 §Offline validation algorithm).
class GateClient {
  new({
    required this.baseUrl,
    required this._key,
    required this._http,
    required this._clock,
    required this._deviceId,
    this.onOffset,
    this.onRevoked,
    this.timeout = const Duration(seconds: 30),
  });

  /// Includes `/v1`, which is part of the signed path.
  final String baseUrl;
  final Duration timeout;
  final DeviceKey _key;
  final http.Client _http;
  final ServerClock _clock;

  /// `X-Device-Id`: null until enrolment, so `POST /gate/devices/enrol` goes without it.
  final Future<String?> Function() _deviceId;

  /// Offset samples from a slower round trip are ignored once an offset exists.
  static const maxOffsetRoundTripMs = 2000;

  /// Persists a new offset (server − device ms) measured at device time `atDeviceMs`.
  final Future<void> Function(int offsetMs, int atDeviceMs)? onOffset;

  /// Runs before the error is thrown when any gate call answers 403 `DEVICE_NOT_ACTIVE` with status REVOKED
  /// (docs/04: the scanner wipes K_e, manifest, staff list and scans), so every caller gets the wipe.
  final Future<void> Function()? onRevoked;

  /// [decode] replaces the JSON decoding of the response bytes (the manifest decodes, verifies and parses in a
  /// background isolate; a large sync reply decodes there too). It must return the decoded JSON, at least `server_time` and the 04 error fields.
  Future<Object?> get(String path, {Map<String, String>? query, BodyDecoder? decode}) =>
      _send('GET', path, query: query, decode: decode);

  Future<Object?> post(String path, {Object? body, BodyDecoder? decode}) =>
      _send('POST', path, body: body, decode: decode);

  /// Returns the decoded JSON body (null when empty, e.g. 204). Throws [GateApiError] on a non-2xx status or
  /// a network failure. On `DEVICE_CLOCK_SKEW` the offset is fixed from `details.server_time`, and the
  /// request is signed again with the corrected time and retried once.
  Future<Object?> _send(
    String method,
    String path, {
    Map<String, String>? query,
    Object? body,
    BodyDecoder? decode,
  }) async {
    final parsed = Uri.parse('$baseUrl$path');
    final url = query == null ? parsed : parsed.replace(queryParameters: query);
    // Signed and sent as these exact bytes; requests are never compressed.
    final bytes = body == null ? Uint8List(0) : utf8.encode(jsonEncode(body));
    final deviceId = await _deviceId();
    try {
      return await _exchange(method, url, bytes, deviceId, decode ?? _jsonBody);
    } on GateApiError catch (e) {
      if (e.code != GateApiError.clockSkew) rethrow;
      return await _exchange(method, url, bytes, deviceId, decode ?? _jsonBody);
    }
  }

  static Object? _jsonBody(Uint8List bytes) => jsonDecode(utf8.decode(bytes));

  Future<Object?> _exchange(String method, Uri url, Uint8List bytes, String? deviceId, BodyDecoder decode) async {
    final time = _clock.nowMs();
    final signature = await _key.sign(
      utf8.encode(gateSigningString(method: method, path: signedPath(url), timeMs: time, body: bytes)),
    );
    final request = http.Request(method, url)
      // A redirect would re-send the signed headers elsewhere, and the full manifest carries K_e.
      ..followRedirects = false
      ..headers['Accept'] = 'application/json'
      ..headers['X-Device-Time'] = '$time'
      ..headers['X-Device-Signature'] = base64Url.encode(signature).replaceAll('=', '');
    if (deviceId != null) request.headers['X-Device-Id'] = deviceId;
    if (bytes.isNotEmpty) {
      request
        ..headers['Content-Type'] = 'application/json; charset=utf-8'
        ..bodyBytes = bytes;
    }

    final sentAt = _clock.deviceMs();
    final http.Response response;
    try {
      Future<http.Response> exchange() async => await http.Response.fromStream(await _http.send(request));
      response = await exchange().timeout(timeout);
    } on Exception catch (e) {
      throw GateApiError(status: 0, code: GateApiError.network, message: e.toString());
    }
    final receivedAt = _clock.deviceMs();

    final status = response.statusCode;
    final Object? json;
    try {
      // bodyBytes: `response.body` falls back to latin1 without a charset.
      json = response.bodyBytes.isEmpty ? null : await decode(response.bodyBytes);
    } on FormatException {
      throw GateApiError(status: status, code: GateApiError.unexpectedResponse, message: 'Body is not JSON');
    }

    // `server_time` is in every gate JSON response; `details.server_time` on DEVICE_CLOCK_SKEW.
    final serverTime = switch (json) {
      {'server_time': final String t} => t,
      {'details': {'server_time': final String t}} => t,
      _ => null,
    };
    final roundTrip = receivedAt - sentAt;
    final skew = json is Map && json['code'] == GateApiError.clockSkew;
    if (DateTime.tryParse(serverTime ?? '') case final server?
        when roundTrip <= maxOffsetRoundTripMs || !_clock.measured || skew) {
      // Offset = server_time − the midpoint of request send and response receive. A slow round trip makes a
      // poor sample: kept only when there is no offset yet, or the current one is known to be > 5 min off.
      final offset = server.millisecondsSinceEpoch - (sentAt + (receivedAt - sentAt) ~/ 2);
      _clock
        ..offsetMs = offset
        ..measured = true;
      await onOffset?.call(offset, receivedAt);
    }

    if (status >= 200 && status < 300) return json;
    if (json case {'code': final String code, 'message': final String message}) {
      final details = json['details'];
      final error = GateApiError(
        status: status,
        code: code,
        message: message,
        details: details is Map<String, Object?> ? details : const {},
      );
      if (status == 403 && code == GateApiError.notActive && error.details['status'] == 'REVOKED') {
        try {
          await onRevoked?.call();
        } on Object {
          // The caller still gets the 403; the next gate call answers REVOKED again and retries the wipe.
        }
      }
      throw error;
    }
    throw GateApiError(status: status, code: GateApiError.unexpectedResponse, message: 'HTTP $status');
  }
}

/// Overridden in tests with `MockClient`.
final httpClientProvider = Provider<http.Client>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return client;
});

final gateClientProvider = Provider<GateClient>((ref) {
  final db = ref.watch(databaseProvider);
  return GateClient(
    baseUrl: AppConfig.apiBaseUrl,
    key: ref.watch(deviceKeyProvider),
    http: ref.watch(httpClientProvider),
    clock: ref.watch(serverClockProvider),
    deviceId: () async => (await db.readConfig()).deviceId,
    onOffset: db.saveServerOffset,
    // REVOKED: the database first, then K_e from memory and all of secure storage (K_e, PIN lockout), each
    // step running even if an earlier one throws.
    onRevoked: () async {
      try {
        await db.wipeAll();
      } finally {
        ref.read(eventKeyProvider.notifier).wipe();
        await ref.read(secureStorageProvider).deleteAll();
      }
    },
  );
});
