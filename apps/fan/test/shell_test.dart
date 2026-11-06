import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';

import 'helpers.dart';

Future<http.Response> noEvents(http.Request _) async => json(<Object?>[]);

void main() {
  testWidgets('renders the three tabs in FR; public Accueil, protected tabs go to login', (tester) async {
    await pumpJomhor(tester, backend: noEvents);
    expect(find.text('Accueil'), findsOneWidget);
    expect(find.text('Billets'), findsOneWidget);
    expect(find.text('Profil'), findsOneWidget);
    expect(find.text("Pas de match à venir pour l'instant. Reviens bientôt."), findsOneWidget);

    await tester.tap(find.text('Billets'));
    await tester.pumpAndSettle();
    expect(find.text('Connecte-toi'), findsOneWidget);
  });

  testWidgets('signed in, Billets opens its tab', (tester) async {
    await pumpJomhor(tester, backend: noEvents, tokens: MemoryTokenStore('jwt'));
    await tester.tap(find.text('Billets'));
    await tester.pumpAndSettle();
    expect(find.text('Billets'), findsNWidgets(2)); // tab label + placeholder title
  });

  testWidgets('labels follow the device locale', (tester) async {
    await pumpJomhor(tester, backend: noEvents);
    tester.platformDispatcher.localesTestValue = [const Locale('en', 'US')];
    await tester.pumpAndSettle();
    expect(find.text('Home'), findsOneWidget);
    expect(find.text('Tickets'), findsOneWidget);
    expect(find.text('Profile'), findsOneWidget);
  });

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
