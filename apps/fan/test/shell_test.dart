import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';

import 'helpers.dart';

Future<http.Response> noEvents(http.Request _) async => json(<Object?>[]);

void main() {
  testWidgets('renders the four tabs in FR; public Accueil, protected tabs go to login', (tester) async {
    final c = await pumpJomhor(tester, backend: noEvents);
    expect(find.text('Accueil'), findsOneWidget);
    expect(find.text('Billets'), findsOneWidget);
    expect(find.text('Club'), findsOneWidget);
    expect(find.text('Profil'), findsOneWidget);
    expect(find.text("Pas de match à venir pour l'instant. Reviens bientôt."), findsOneWidget);

    await tester.tap(find.text('Billets'));
    await tester.pumpAndSettle();
    expect(find.text('Connecte-toi'), findsOneWidget);

    // Club (lot-4 Q94): behind the login too, then back to it.
    c.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    await tester.tap(find.text('Club'));
    await tester.pumpAndSettle();
    expect(find.text('Connecte-toi'), findsOneWidget);
    expect(c.read(routerProvider).state.uri.queryParameters['from'], '/club');
  });

  testWidgets('signed in, Billets opens its tab', (tester) async {
    await pumpJomhor(tester, backend: noEvents, tokens: MemoryTokenStore('jwt'));
    await tester.tap(find.text('Billets'));
    await tester.pumpAndSettle();
    expect(find.text('Mes billets'), findsOneWidget);
  });

  testWidgets('signed in, Club is the third tab and Profil the fourth', (tester) async {
    final c = await pumpJomhor(tester, backend: noEvents, tokens: MemoryTokenStore('jwt'));
    final labels = [
      for (final d in tester.widgetList<NavigationDestination>(find.byType(NavigationDestination))) d.label,
    ];
    expect(labels, ['Accueil', 'Billets', 'Club', 'Profil']);
    await tester.tap(find.text('Club'));
    await tester.pumpAndSettle();
    expect(c.read(routerProvider).state.uri.path, '/club');
    expect(find.text('Choisis tes clubs'), findsOneWidget); // no follows
    await tester.tap(find.text('Profil'));
    await tester.pumpAndSettle();
    expect(c.read(routerProvider).state.uri.path, '/profile');
  });

  for (final (locale, labels) in [
    ('en', ['Home', 'Tickets', 'Club', 'Profile']),
    ('ar', ['الرئيسية', 'تذاكري', 'النادي', 'حسابي']),
  ]) {
    testWidgets('labels follow the device locale ($locale)', (tester) async {
      await pumpJomhor(tester, backend: noEvents);
      tester.platformDispatcher.localesTestValue = [Locale(locale)];
      await tester.pumpAndSettle();
      expect([
        for (final d in tester.widgetList<NavigationDestination>(find.byType(NavigationDestination))) d.label,
      ], labels);
    });
  }

  testWidgets('an unsupported device locale falls back to FR', (tester) async {
    await pumpJomhor(tester, backend: noEvents);
    tester.platformDispatcher.localesTestValue = [const Locale('es', 'ES')];
    await tester.pumpAndSettle();
    expect(find.text('Accueil'), findsOneWidget);
  });

  test('after login only an in-app path is followed', () {
    expect(afterLogin('/profile'), '/profile');
    expect(afterLogin('//evil.example/x'), '/');
    expect(afterLogin('https://evil.example'), '/');
    expect(afterLogin('/login?from=/x'), '/');
    expect(afterLogin(null), '/');
  });
}
