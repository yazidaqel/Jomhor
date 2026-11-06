import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jomhor_fan/core/api/api_client.dart';

import 'helpers.dart';

void main() {
  late http.Request last;

  ApiClient client(String? token, {int status = 200, String body = '{}'}) => ApiClient(
    baseUrl: 'https://api.test/v1',
    tokens: MemoryTokenStore(token),
    http: MockClient((request) async {
      last = request;
      return http.Response.bytes(utf8.encode(body), status, headers: {'content-type': 'application/json'});
    }),
  );

  test('sends Bearer when a token is stored', () async {
    await client('jwt-123').get('/fan/me');
    expect(last.url.toString(), 'https://api.test/v1/fan/me');
    expect(last.headers['Authorization'], 'Bearer jwt-123');
  });

  test('sends no Authorization without a token', () async {
    await client(null).get('/fan/me');
    expect(last.headers.containsKey('Authorization'), isFalse);
  });

  test('getPublic never sends the token; get refuses /fan/events', () async {
    final api = client('jwt-123');
    await api.getPublic('/fan/events', query: {'organizer': 'org-1'});
    expect(last.headers.containsKey('Authorization'), isFalse);
    expect(last.url.queryParameters, {'organizer': 'org-1'});

    expect(() => api.get('/fan/events/e1'), throwsArgumentError);
  });

  test('encodes the body as UTF-8 JSON and decodes the response', () async {
    final api = client('t', status: 201, body: '{"id":"o1","holder":"Élodie"}');
    final json = await api.post('/fan/orders', body: {'name': 'Élodie'});
    expect(last.headers['Content-Type'], startsWith('application/json'));
    expect(jsonDecode(last.body), {'name': 'Élodie'});
    expect(json, {'id': 'o1', 'holder': 'Élodie'});
  });

  test('returns null on an empty 2xx body', () async {
    expect(await client('t', status: 204, body: '').post('/fan/me/logout'), isNull);
  });

  test('parses the {code, message, details} error shape', () async {
    final api = client(
      't',
      status: 409,
      body: '{"code":"PER_ACCOUNT_LIMIT","message":"Limite atteinte","details":{"limit":4,"held":3}}',
    );
    await expectLater(
      api.post('/fan/orders', body: const <String, Object?>{}),
      throwsA(
        isA<ApiError>()
            .having((e) => e.status, 'status', 409)
            .having((e) => e.code, 'code', 'PER_ACCOUNT_LIMIT')
            .having((e) => e.message, 'message', 'Limite atteinte')
            .having((e) => e.details, 'details', {'limit': 4, 'held': 3}),
      ),
    );
  });

  test('a body without the error shape is UNEXPECTED_RESPONSE', () async {
    await expectLater(
      client('t', status: 502, body: '<html>Bad gateway</html>').get('/fan/me'),
      throwsA(
        isA<ApiError>().having((e) => e.status, 'status', 502).having((e) => e.code, 'code', 'UNEXPECTED_RESPONSE'),
      ),
    );
  });

  test('the timeout covers the body, not just the headers', () async {
    final api = ApiClient(
      baseUrl: 'https://api.test/v1',
      tokens: MemoryTokenStore(),
      timeout: const Duration(milliseconds: 50),
      // Headers arrive at once; the body never ends.
      http: MockClient.streaming(
        (request, _) async => http.StreamedResponse(StreamController<List<int>>().stream, 200),
      ),
    );
    await expectLater(api.get('/fan/me'), throwsA(isA<ApiError>().having((e) => e.code, 'code', 'NETWORK_ERROR')));
  });

  test('a network failure is NETWORK_ERROR with status 0', () async {
    final api = ApiClient(
      baseUrl: 'https://api.test/v1',
      tokens: MemoryTokenStore(),
      http: MockClient((_) => throw http.ClientException('Failed host lookup')),
    );
    await expectLater(
      api.get('/fan/me'),
      throwsA(isA<ApiError>().having((e) => e.status, 'status', 0).having((e) => e.code, 'code', 'NETWORK_ERROR')),
    );
  });

  test('a 401 on a call that carried the token reports that token; a 401 without a token does not', () async {
    final rejected = <String>[];
    ApiClient api(String? token) => ApiClient(
      baseUrl: 'https://api.test/v1',
      tokens: MemoryTokenStore(token),
      http: MockClient((_) async => apiError(401, 'OTP_INVALID')),
      onUnauthorized: (token) async => rejected.add(token),
    );

    await expectLater(
      api('jwt').postPublic('/fan/auth/verify', body: const <String, Object?>{}),
      throwsA(isA<ApiError>()),
    );
    expect(rejected, isEmpty);
    await expectLater(api('jwt').get('/fan/me'), throwsA(isA<ApiError>()));
    expect(rejected, ['jwt']);
  });

  test('OTP calls never carry the token; post refuses /fan/auth/*', () async {
    final api = client('jwt', status: 202, body: '');
    await api.postPublic('/fan/auth/otp', body: {'phone': '+212612345678'});
    expect(last.headers.containsKey('Authorization'), isFalse);
    expect(() => api.post('/fan/auth/verify', body: const <String, Object?>{}), throwsArgumentError);
  });

  test('getPublicResponse exposes the server Date header (null when absent or unreadable)', () async {
    Future<DateTime?> date(Map<String, String> headers) async => (await ApiClient(
      baseUrl: 'https://api.test/v1',
      tokens: MemoryTokenStore(),
      http: MockClient(
        (_) async => http.Response('[]', 200, headers: {'content-type': 'application/json', ...headers}),
      ),
    ).getPublicResponse('/fan/events')).date;

    expect(await date({'date': 'Sat, 20 Feb 2027 11:59:30 GMT'}), DateTime.utc(2027, 2, 20, 11, 59, 30));
    expect(await date({}), isNull);
    expect(await date({'date': 'yesterday'}), isNull);
  });
}
