import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:qr_flutter/qr_flutter.dart';

import 'helpers.dart';
import 'tickets_test.dart' show seedCache, slotStart, ticketId, ticketJson;

/// Events, saved tickets and `/fan/me` answer; nothing else is reachable.
Future<http.Response> fullBackend(http.Request r) async => switch (r.url.path) {
  '/v1/fan/events' => json([
    eventJson(id: 7),
    eventJson(id: 8, metadata: {'opponent': 'Union Tanger'}),
  ]),
  '/v1/fan/events/7' => json(
    eventJson(
      id: 7,
      zones: [
        zoneJson(1, 'Tribune Nord', [(5000, 2300)], seated: false),
        zoneJson(2, "Tribune d'honneur", [(30000, 40), (25000, 12)]),
        zoneJson(3, 'Tribune Sud', [(5000, 0)]),
      ],
    ),
  ),
  '/v1/fan/me' => json({'id': 'u1', 'phone': '+212612345678', 'display_name': 'Yazid Aqel', 'locale': 'fr'}),
  _ => throw http.ClientException('offline'),
};

void phone(WidgetTester tester) {
  tester.view
    ..physicalSize = const Size(390, 844)
    ..devicePixelRatio = 1;
  addTearDown(tester.view.reset);
}

void main() {
  test('first name and initials come from display_name', () {
    expect(firstName('  Yazid   Aqel '), 'Yazid');
    expect(firstName(''), isNull);
    expect(firstName(null), isNull);
    expect(initials('Yazid Aqel Benani'), 'YA');
    expect(initials('yazid'), 'Y');
    expect(initials('  '), isNull);
  });

  testWidgets('Accueil greets the signed-in fan by first name, a guest with "Bienvenue"', (tester) async {
    await pumpJomhor(tester, backend: fullBackend, tokens: MemoryTokenStore('jwt'));
    expect(find.text('Salut Yazid'), findsOneWidget);
    expect(find.text('YA'), findsOneWidget, reason: 'avatar initials in the top bar');
  });

  testWidgets('a guest is welcomed without a name and no /fan/me call', (tester) async {
    final paths = <String>[];
    await pumpJomhor(
      tester,
      backend: (r) {
        paths.add(r.url.path);
        return fullBackend(r);
      },
    );
    expect(find.text('Bienvenue'), findsOneWidget);
    expect(paths, isNot(contains('/v1/fan/me')));
  });

  testWidgets('the "Mon billet" strip opens screen 5 with the hero flight from Accueil', (tester) async {
    phone(tester);
    seedCache([ticketJson()]);
    await pumpJomhor(
      tester,
      backend: fullBackend,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(slotStart),
      keepSecureStorage: true,
    );
    expect(find.byType(Hero), findsOneWidget);
    await tester.ensureVisible(find.byKey(const Key('home.myTicket')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('home.myTicket')));
    await tester.pumpAndSettle();
    expect(tester.widget<TicketScreen>(find.byType(TicketScreen)).origin, PassOrigin.home);
    expect(find.byWidgetPredicate((w) => w is Hero && w.tag == passHeroTag(PassOrigin.home, ticketId)), findsOneWidget);
  });

  testWidgets('reduced motion: the strip opens screen 5 without the hero flight', (tester) async {
    phone(tester);
    tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    seedCache([ticketJson()]);
    await pumpJomhor(
      tester,
      backend: fullBackend,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(slotStart),
      keepSecureStorage: true,
    );
    await tester.ensureVisible(find.byKey(const Key('home.myTicket')));
    await tester.tap(find.byKey(const Key('home.myTicket')));
    await tester.pumpAndSettle();
    expect(tester.widget<TicketScreen>(find.byType(TicketScreen)).origin, PassOrigin.home);
    expect(find.byType(TicketQr), findsOneWidget);
    expect(
      find.descendant(of: find.byType(TicketScreen), matching: find.byType(Hero)),
      findsNothing,
      reason: 'no flight under reduced motion',
    );
  });

  testWidgets('the hero shuttle is a paper shape: no QR flies', (tester) async {
    await tester.pumpWidget(
      Directionality(
        textDirection: TextDirection.ltr,
        child: Center(
          child: SizedBox(
            width: 200,
            height: 300,
            child: Builder(
              builder: (context) =>
                  passFlight(context, const AlwaysStoppedAnimation(0.5), HeroFlightDirection.push, context, context),
            ),
          ),
        ),
      ),
    );
    expect(find.byType(QrImageView), findsNothing);
    expect(find.byType(TicketQr), findsNothing);
  });

  testWidgets('screen 5 gives a haptic tick when the code rotates, not before', (tester) async {
    final haptics = <Object?>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') haptics.add(call.arguments);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    seedCache([ticketJson()]);
    final clock = TestClock(slotStart.add(const Duration(seconds: 2)));
    final c = await pumpJomhor(
      tester,
      backend: fullBackend,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      keepSecureStorage: true,
    );
    unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
    await tester.pumpAndSettle();
    haptics.clear();
    // Up to one second before the next code, as the screen counts it.
    final shown = find.textContaining('Nouveau code dans');
    final left = int.parse(RegExp(r'\d+').firstMatch(tester.widget<Text>(shown).data!)!.group(0)!);
    for (var s = 0; s < left - 1; s++) {
      await tester.pump(const Duration(seconds: 1));
    }
    expect(haptics, isEmpty, reason: 'same slot');
    await tester.pump(const Duration(seconds: 2));
    expect(haptics, ['HapticFeedbackType.selectionClick']);
    await tester.pumpAndSettle();
  });

  group('text at 200 % lays out without overflow', () {
    Future<void> at200(WidgetTester tester, Future<void> Function() open) async {
      phone(tester);
      tester.platformDispatcher.textScaleFactorTestValue = 2;
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      await open();
      expect(tester.takeException(), isNull);
    }

    testWidgets('Accueil, Billets, screen 5 and Profil', (tester) async {
      seedCache([ticketJson()]);
      await at200(tester, () async {
        final c = await pumpJomhor(
          tester,
          backend: fullBackend,
          tokens: MemoryTokenStore('jwt'),
          clock: TestClock(slotStart),
          keepSecureStorage: true,
        );
        expect(tester.takeException(), isNull, reason: 'Accueil');
        for (final path in ['/tickets', '/profile']) {
          c.read(routerProvider).go(path);
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull, reason: path);
        }
        unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
        await tester.pumpAndSettle();
      });
    });

    testWidgets('Match, Choix de zone and Paiement', (tester) async {
      await at200(tester, () async {
        final c = await pumpJomhor(tester, backend: fullBackend, tokens: MemoryTokenStore('jwt'));
        final rendered = {
          '/events/7': find.byKey(const Key('zone.1')),
          '/events/7/zones': find.byKey(const Key('zone.1')),
          '/events/7/checkout?price=pc-2-0&qty=2': find.byKey(const Key('holder.1.name')),
        };
        for (final MapEntry(key: path, value: content) in rendered.entries) {
          unawaited(c.read(routerProvider).push(path));
          await tester.pumpAndSettle();
          expect(tester.takeException(), isNull, reason: path);
          await tester.scrollUntilVisible(content, 200, scrollable: find.byType(Scrollable).first);
          expect(content, findsOneWidget, reason: path);
        }
      });
    });

    testWidgets('screen 5 right to left (AR)', (tester) async {
      seedCache([ticketJson()]);
      await at200(tester, () async {
        final c = await pumpJomhor(
          tester,
          backend: fullBackend,
          tokens: MemoryTokenStore('jwt'),
          clock: TestClock(slotStart),
          keepSecureStorage: true,
        );
        tester.platformDispatcher.localesTestValue = [const Locale('ar')];
        unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
        await tester.pumpAndSettle();
        expect(Directionality.of(tester.element(find.byType(TicketQr))), TextDirection.rtl);
        // The ticket number reads left to right inside the mirrored card.
        expect(tester.widget<Text>(find.text('EEEE EEEE')).textDirection, TextDirection.ltr);
      });
    });

    testWidgets('right to left (AR) too', (tester) async {
      FlutterSecureStorage.setMockInitialValues({});
      await at200(tester, () async {
        await pumpJomhor(tester, backend: fullBackend, tokens: MemoryTokenStore('jwt'));
        tester.platformDispatcher.localesTestValue = [const Locale('ar')];
        await tester.pumpAndSettle();
        expect(Directionality.of(tester.element(find.byKey(const Key('home.greeting')))), TextDirection.rtl);
      });
    });
  });

  testWidgets('reduced motion: entrances render in place, no tween', (tester) async {
    Widget app({required bool reduce}) => MediaQuery(
      data: MediaQueryData(disableAnimations: reduce),
      child: const Directionality(
        textDirection: TextDirection.ltr,
        child: Entrance(child: Text('x')),
      ),
    );
    await tester.pumpWidget(app(reduce: true));
    expect(find.byType(TweenAnimationBuilder<double>), findsNothing);
    await tester.pumpWidget(app(reduce: false));
    expect(find.byType(TweenAnimationBuilder<double>), findsOneWidget);
    await tester.pumpAndSettle();
  });
}
