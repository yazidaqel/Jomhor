import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/auth/login_screen.dart';

import 'helpers.dart';

void main() {
  final requests = <http.Request>[];
  setUp(requests.clear);

  Future<http.Response> Function(http.Request) backend({
    http.Response? otp,
    http.Response? verify,
    http.Response? me,
  }) => (request) async {
    requests.add(request);
    return switch (request.url.path) {
      '/v1/fan/events' => json(<Object?>[]),
      '/v1/fan/auth/otp' => otp ?? http.Response('', 202),
      '/v1/fan/auth/verify' =>
        verify ??
            json({
              'token': 'jwt-new',
              'expires_at': '2027-03-01T00:00:00Z',
              'user': {'id': 'u1', 'phone': '+212612345678', 'display_name': null, 'locale': 'fr'},
            }),
      '/v1/fan/me' => me ?? apiError(401, 'UNAUTHORIZED'),
      _ => apiError(404, 'NOT_FOUND'),
    };
  };

  Map<String, Object?> body(String path) =>
      jsonDecode(requests.lastWhere((r) => r.url.path == path).body) as Map<String, Object?>;

  testWidgets('a protected tab asks for login; phone then code stores the token and returns to the tab', (
    tester,
  ) async {
    final tokens = MemoryTokenStore();
    // The Profil tab reads /fan/me once signed in.
    final me = json({'id': 'u1', 'phone': '+212612345678', 'display_name': null, 'locale': 'fr'});
    final container = await pumpJomhor(
      tester,
      backend: backend(me: me),
      tokens: tokens,
    );

    await tester.tap(find.text('Profil'));
    await tester.pumpAndSettle();
    expect(find.byType(LoginScreen), findsOneWidget);

    await tester.enterText(find.byKey(const Key('login.phone')), '06 12 34 56 78');
    await tester.tap(find.text('Recevoir le code'));
    await tester.pumpAndSettle();
    expect(body('/v1/fan/auth/otp'), {'phone': '+212612345678'});
    expect(requests.last.headers.containsKey('Authorization'), isFalse);

    await tester.enterText(find.byKey(const Key('login.code')), '123456');
    await tester.tap(find.text('Valider'));
    await tester.pumpAndSettle();
    expect(body('/v1/fan/auth/verify'), {'phone': '+212612345678', 'code': '123456'});
    expect(tokens.token, 'jwt-new');
    expect(find.byType(LoginScreen), findsNothing);
    expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/profile');
  });

  testWidgets('429 OTP_RATE_LIMITED shows the rate-limit copy', (tester) async {
    final container = await pumpJomhor(tester, backend: backend(otp: apiError(429, 'OTP_RATE_LIMITED')));
    container.read(routerProvider).go('/login');
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('login.phone')), '0612345678');
    await tester.tap(find.text('Recevoir le code'));
    await tester.pumpAndSettle();
    expect(find.text('Trop de demandes pour ce numéro. Patiente un peu avant de réessayer.'), findsOneWidget);
    expect(find.byKey(const Key('login.code')), findsNothing);
  });

  testWidgets('a wrong code shows OTP_INVALID copy and stores nothing', (tester) async {
    final tokens = MemoryTokenStore();
    final container = await pumpJomhor(
      tester,
      backend: backend(verify: apiError(401, 'OTP_INVALID')),
      tokens: tokens,
    );
    container.read(routerProvider).go('/login');
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('login.phone')), '0612345678');
    await tester.tap(find.text('Recevoir le code'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('login.code')), '000000');
    await tester.tap(find.text('Valider'));
    await tester.pumpAndSettle();
    expect(find.text('Code incorrect. Vérifie-le et réessaie.'), findsOneWidget);
    expect(tokens.token, isNull);
  });

  testWidgets('a non-Moroccan number shows copy and sends nothing', (tester) async {
    final container = await pumpJomhor(tester, backend: backend());
    container.read(routerProvider).go('/login');
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('login.phone')), '+33 6 12 34 56 78');
    await tester.tap(find.text('Recevoir le code'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('login.error')), findsOneWidget);
    expect(requests.where((r) => r.url.path == '/v1/fan/auth/otp'), isEmpty);
  });

  testWidgets('a 401 on any authenticated call clears the token and routes to login', (tester) async {
    final tokens = MemoryTokenStore('jwt-revoked');
    final container = await pumpJomhor(tester, backend: backend(), tokens: tokens);
    await tester.tap(find.text('Billets'));
    await tester.pumpAndSettle();
    expect(find.byType(LoginScreen), findsNothing);

    await expectLater(container.read(apiClientProvider).get('/fan/me'), throwsA(isA<ApiError>()));
    await tester.pumpAndSettle();
    expect(tokens.token, isNull);
    expect(find.byType(LoginScreen), findsOneWidget);
  });

  testWidgets('an unexpected failure (bad response shape) shows generic copy and re-enables the buttons', (
    tester,
  ) async {
    final tokens = MemoryTokenStore();
    final container = await pumpJomhor(
      tester,
      backend: backend(verify: json({'unexpected': true})),
      tokens: tokens,
    );
    container.read(routerProvider).go('/login');
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('login.phone')), '0612345678');
    await tester.tap(find.text('Recevoir le code'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byKey(const Key('login.code')), '123456');
    await tester.tap(find.text('Valider'));
    await tester.pumpAndSettle();
    expect(find.text('Un problème est survenu. Réessaie dans un instant.'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNotNull);
    expect(tokens.token, isNull);
  });
}
