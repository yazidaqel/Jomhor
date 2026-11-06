import 'dart:convert';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/config.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/token_store.dart';

/// An API failure: the `{code, message, details}` body of docs/04-api.md, or
/// a client-side code when there is no such body.
class ApiError implements Exception {
  const new({required this.status, required this.code, required this.message, this.details = const {}});

  /// No HTTP response at all (offline, DNS, timeout). [status] is 0.
  static const network = 'NETWORK_ERROR';

  /// A response without the 04 error shape (proxy HTML page, bad JSON).
  static const unexpectedResponse = 'UNEXPECTED_RESPONSE';

  final int status;
  final String code;
  final String message;
  final Map<String, Object?> details;

  @override
  String toString() => 'ApiError($status $code: $message)';
}

/// A 2xx response: decoded JSON and the server's clock.
class ApiResponse {
  const new(this.body, this.date);

  final Object? body;

  /// The `Date` header (server time, 1 s resolution); null when absent or unreadable.
  final DateTime? date;
}

DateTime? _date(String? header) {
  if (header == null) return null;
  try {
    return HttpDate.parse(header);
  } on Exception {
    return null;
  }
}

/// JSON client for the fan API. [get], [post] and [patch] send `Authorization: Bearer` when a
/// token is stored. Public endpoints (`GET /fan/events*`, `POST /fan/auth/*`) go through [getPublic]
/// and [postPublic], which never do; [get] and [post] refuse those paths.
class ApiClient {
  new({
    required this.baseUrl,
    required this._tokens,
    required this._http,
    this.timeout = const Duration(seconds: 20),
    this.onUnauthorized,
  });

  /// Includes the `/v1` prefix, e.g. `https://api.jomhor.ma/v1`.
  final String baseUrl;

  /// For the whole exchange: connect, headers and body.
  final Duration timeout;
  final TokenStore _tokens;
  final http.Client _http;

  /// Called with the token a request carried when it got a 401 (revoked or expired session).
  /// A 401 without a token (e.g. `OTP_INVALID` on verify) does not call it.
  final Future<void> Function(String token)? onUnauthorized;

  Future<Object?> get(String path, {Map<String, String>? query}) => _body(getResponse(path, query: query));

  /// [get] plus the server's `Date` header (e.g. to tell whether a hold is still running on server time).
  Future<ApiResponse> getResponse(String path, {Map<String, String>? query}) {
    // A public read through the authenticated path would leak the token to a public, cacheable URL.
    if (path.startsWith('/fan/events')) throw ArgumentError.value(path, 'path', 'public endpoint: use getPublic');
    return _send('GET', path, query: query, auth: true);
  }

  /// For public endpoints: never sends the session token.
  Future<Object?> getPublic(String path, {Map<String, String>? query}) => _body(getPublicResponse(path, query: query));

  /// [getPublic] plus the server's `Date` header, for reads whose meaning depends on server time.
  Future<ApiResponse> getPublicResponse(String path, {Map<String, String>? query}) =>
      _send('GET', path, query: query, auth: false);

  Future<Object?> post(String path, {Object? body}) {
    if (path.startsWith('/fan/auth/')) throw ArgumentError.value(path, 'path', 'public endpoint: use postPublic');
    return _body(postResponse(path, body: body));
  }

  /// [post] plus the server's `Date` header (e.g. to run a hold countdown on server time).
  Future<ApiResponse> postResponse(String path, {Object? body}) {
    if (path.startsWith('/fan/auth/')) throw ArgumentError.value(path, 'path', 'public endpoint: use postPublic');
    return _send('POST', path, body: body, auth: true);
  }

  /// For public endpoints (OTP request and verify): never sends the session token.
  Future<Object?> postPublic(String path, {Object? body}) => _body(_send('POST', path, body: body, auth: false));

  Future<Object?> patch(String path, {Object? body}) => _body(_send('PATCH', path, body: body, auth: true));

  static Future<Object?> _body(Future<ApiResponse> response) async => (await response).body;

  /// Returns the decoded JSON body (null when empty, e.g. 202/204) and the `Date` header.
  /// Throws [ApiError] on any non-2xx status or network failure.
  Future<ApiResponse> _send(
    String method,
    String path, {
    required bool auth,
    Map<String, String>? query,
    Object? body,
  }) async {
    final request = http.Request(method, Uri.parse('$baseUrl$path').replace(queryParameters: query))
      ..headers['Accept'] = 'application/json';
    final token = auth ? await _tokens.read() : null;
    if (token != null) request.headers['Authorization'] = 'Bearer $token';
    if (body != null) {
      request
        ..headers['Content-Type'] = 'application/json; charset=utf-8'
        ..bodyBytes = utf8.encode(jsonEncode(body));
    }

    final http.Response response;
    try {
      Future<http.Response> exchange() async => await http.Response.fromStream(await _http.send(request));
      response = await exchange().timeout(timeout);
    } on Exception catch (e) {
      throw ApiError(status: 0, code: ApiError.network, message: e.toString());
    }

    final status = response.statusCode;
    if (status == 401 && token != null) await onUnauthorized?.call(token);
    final Object? json;
    try {
      // bodyBytes: `response.body` falls back to latin1 without a charset.
      json = response.bodyBytes.isEmpty ? null : jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      throw ApiError(status: status, code: ApiError.unexpectedResponse, message: 'Body is not JSON');
    }
    if (status >= 200 && status < 300) return ApiResponse(json, _date(response.headers['date']));

    if (json case {'code': final String code, 'message': final String message}) {
      final details = json['details'];
      throw ApiError(
        status: status,
        code: code,
        message: message,
        details: details is Map<String, Object?> ? details : const {},
      );
    }
    throw ApiError(status: status, code: ApiError.unexpectedResponse, message: 'HTTP $status');
  }
}

/// Overridden in tests with `MockClient`.
final httpClientProvider = Provider<http.Client>((ref) {
  final client = http.Client();
  ref.onDispose(client.close);
  return client;
});

final apiClientProvider = Provider<ApiClient>(
  (ref) => ApiClient(
    baseUrl: AppConfig.apiBaseUrl,
    tokens: ref.watch(tokenStoreProvider),
    http: ref.watch(httpClientProvider),
    // Any 401 on an authenticated call ends the session; the router then shows the login.
    onUnauthorized: (token) => ref.read(sessionProvider.notifier).rejected(token),
  ),
);
