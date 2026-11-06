import 'dart:ui';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/app.dart';

Future<void> pumpApp(WidgetTester tester, Locale locale) async {
  tester.platformDispatcher.localesTestValue = [locale];
  addTearDown(tester.platformDispatcher.clearLocalesTestValue);
  await tester.pumpWidget(const ProviderScope(child: JomhorApp()));
  await tester.pumpAndSettle();
}

void main() {
  testWidgets('renders the three tabs in FR and switches tab', (tester) async {
    await pumpApp(tester, const Locale('fr', 'MA'));
    expect(find.text('Accueil'), findsNWidgets(2)); // tab label + placeholder title
    expect(find.text('Billets'), findsOneWidget);
    expect(find.text('Profil'), findsOneWidget);

    await tester.tap(find.text('Billets'));
    await tester.pumpAndSettle();
    expect(find.text('Billets'), findsNWidgets(2));
  });

  testWidgets('labels follow the device locale', (tester) async {
    await pumpApp(tester, const Locale('en', 'US'));
    expect(find.text('Home'), findsWidgets);
    expect(find.text('Tickets'), findsOneWidget);
    expect(find.text('Profile'), findsOneWidget);
  });

  testWidgets('an unsupported device locale falls back to FR', (tester) async {
    await pumpApp(tester, const Locale('es', 'ES'));
    expect(find.text('Accueil'), findsWidgets);
  });
}
