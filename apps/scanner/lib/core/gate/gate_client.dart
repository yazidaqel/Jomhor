import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_scanner/app/config.dart';
import 'package:jomhor_scanner/core/device_key.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';

export 'package:jomhor_scanner/core/server_clock.dart';

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

  Future<Object?> get(String path, {Map<String, String>? query}) => _send('GET', path, query: query);

  Future<Object?> post(String path, {Object? body}) => _send('POST', path, body: body);

  /// Returns the decoded JSON body (null when empty, e.g. 204). Throws [GateApiError] on a non-2xx status or
  /// a network failure. On `DEVICE_CLOCK_SKEW` the offset is fixed from `details.server_time`, and the
  /// request is signed again with the corrected time and retried once.
  Future<Object?> _send(String method, String path, {Map<String, String>? query, Object? body}) async {
    final parsed = Uri.parse('$baseUrl$path');
    final url = query == null ? parsed : parsed.replace(queryParameters: query);
    // Signed and sent as these exact bytes; requests are never compressed.
    final bytes = body == null ? Uint8List(0) : utf8.encode(jsonEncode(body));
    final deviceId = await _deviceId();
    try {
      return await _exchange(method, url, bytes, deviceId);
    } on GateApiError catch (e) {
      if (e.code != GateApiError.clockSkew) rethrow;
      return await _exchange(method, url, bytes, deviceId);
    }
  }

  Future<Object?> _exchange(String method, Uri url, Uint8List bytes, String? deviceId) async {
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
      json = response.bodyBytes.isEmpty ? null : jsonDecode(utf8.decode(response.bodyBytes));
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
      throw GateApiError(
        status: status,
        code: code,
        message: message,
        details: details is Map<String, Object?> ? details : const {},
      );
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
  );
});
