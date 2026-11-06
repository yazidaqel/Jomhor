import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/config.dart';
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

/// JSON client for the fan API. [get], [post] and [patch] send `Authorization: Bearer` when a
/// token is stored. Public endpoints (`GET /fan/events*`) go through [getPublic], which never does.
class ApiClient {
  new({
    required this.baseUrl,
    required this._tokens,
    http.Client? httpClient,
    this.timeout = const Duration(seconds: 20),
  }) : _http = httpClient ?? http.Client();

  /// Includes the `/v1` prefix, e.g. `https://api.jomhor.ma/v1`.
  final String baseUrl;

  /// For the whole exchange: connect, headers and body.
  final Duration timeout;
  final TokenStore _tokens;
  final http.Client _http;

  Future<Object?> get(String path, {Map<String, String>? query}) {
    // A public read through the authenticated path would leak the token to a public, cacheable URL.
    if (path.startsWith('/fan/events')) throw ArgumentError.value(path, 'path', 'public endpoint: use getPublic');
    return _send('GET', path, query: query, auth: true);
  }

  /// For public endpoints: never sends the session token.
  Future<Object?> getPublic(String path, {Map<String, String>? query}) => _send('GET', path, query: query, auth: false);

  Future<Object?> post(String path, {Object? body}) => _send('POST', path, body: body, auth: true);

  Future<Object?> patch(String path, {Object? body}) => _send('PATCH', path, body: body, auth: true);

  /// Returns the decoded JSON body (null when empty, e.g. 202/204).
  /// Throws [ApiError] on any non-2xx status or network failure.
  Future<Object?> _send(
    String method,
    String path, {
    required bool auth,
    Map<String, String>? query,
    Object? body,
  }) async {
    final request = http.Request(method, Uri.parse('$baseUrl$path').replace(queryParameters: query))
      ..headers['Accept'] = 'application/json';
    if (auth) {
      final token = await _tokens.read();
      if (token != null) request.headers['Authorization'] = 'Bearer $token';
    }
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
    final Object? json;
    try {
      // bodyBytes: `response.body` falls back to latin1 without a charset.
      json = response.bodyBytes.isEmpty ? null : jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      throw ApiError(status: status, code: ApiError.unexpectedResponse, message: 'Body is not JSON');
    }
    if (status >= 200 && status < 300) return json;

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

  void close() => _http.close();
}

final apiClientProvider = Provider<ApiClient>((ref) {
  final client = ApiClient(baseUrl: AppConfig.apiBaseUrl, tokens: ref.watch(tokenStoreProvider));
  ref.onDispose(client.close);
  return client;
});
