import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/events/event_screen.dart';
import 'package:jomhor_fan/features/waitlist/waitlist_controller.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

/// A contract-first answer of `/fan/waitlist/{event_id}` (test/fixtures/waitlist, docs/04-api.md §Waitlist).
http.Response waitlistAnswer(String name) {
  final j = jsonDecode(File('test/fixtures/waitlist/$name.json').readAsStringSync()) as Map<String, Object?>;
  return json(j['body'], j['status']! as int);
}

/// Event 1 sold out, on sale, no room: screen 1's hero and screen 2 offer the waitlist.
Map<String, Object?> soldOutJson({int id = 1}) => eventJson(
  id: id,
  soldOut: true,
  fromPriceMad: null,
  zones: [
    zoneJson(1, 'Tribune Nord', [(5000, 0)]),
    zoneJson(2, 'Virage Sud', [(3000, 0)], seated: false),
  ],
)..['room'] = null;

/// A fake backend for event 1 (and a sold-out event 2 in the calendar) and its waitlist.
class WaitlistSale {
  final requests = <http.Request>[];
  Map<String, Object?> event = soldOutJson();

  /// GET's answer.
  String entry = 'get_200_none';

  /// Queued answers of PUT (default: 201 joined).
  final puts = <http.Response Function()>[];

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events') => json([
        {...event}..remove('zones'),
        {...soldOutJson(id: 2), 'starts_at': '2027-04-03T19:00:00Z'}..remove('zones'),
      ]),
      ('GET', '/v1/fan/events/1') => json(event),
      ('GET', '/v1/fan/waitlist/1') => waitlistAnswer(entry),
      ('PUT', '/v1/fan/waitlist/1') => puts.isEmpty ? waitlistAnswer('put_201_joined') : puts.removeAt(0)(),
      ('DELETE', '/v1/fan/waitlist/1') => http.Response('', 204),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  List<http.Request> calls(String method, String path) => [
    ...requests.where((r) => r.method == method && r.url.path == '/v1$path'),
  ];
}

void main() {
  late WaitlistSale sale;
  late TestClock clock;
  final l = lookupAppLocalizations(const Locale('fr'));

  setUp(() {
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
    sale = WaitlistSale();
    clock = TestClock(DateTime.utc(2027, 2, 20, 12));
  });

  Future<ProviderContainer> start(
    WidgetTester tester, {
    String location = '/',
    bool signedIn = true,
    FakePushMessaging? push,
  }) async {
    // Tall: screen 1's hero CTA clear of the bottom nav.
    tester.view
      ..physicalSize = const Size(800, 2400)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final c = await pumpJomhor(
      tester,
      backend: push == null ? sale.handle : PushBackend(sale.handle).handle,
      tokens: MemoryTokenStore(signedIn ? 'jwt' : null),
      clock: clock,
      overrides: [if (push != null) pushMessagingProvider.overrideWithValue(push)],
    );
    if (location != '/') {
      c.read(routerProvider).go(location);
      await tester.pumpAndSettle();
    }
    return c;
  }

  String location(ProviderContainer c) => c.read(routerProvider).state.uri.toString();

  /// The pages on the stack, bottom first.
  List<String> pages(ProviderContainer c) => [
    for (final m in c.read(routerProvider).routerDelegate.currentConfiguration.matches) m.matchedLocation,
  ];

  Future<void> tap(WidgetTester tester, String key) async {
    await tester.ensureVisible(find.byKey(Key(key)));
    await tester.tap(find.byKey(Key(key)));
    await tester.pumpAndSettle();
  }

  String buttonText(WidgetTester tester, String key) =>
      tester.widget<Text>(find.descendant(of: find.byKey(Key(key)), matching: find.byType(Text))).data!;

  testWidgets('sold out, on sale, no room: "Liste d\'attente" on the hero, "Complet" on the calendar; read once', (
    tester,
  ) async {
    await start(tester);
    expect(buttonText(tester, 'hero.waitlist'), "Liste d'attente");
    expect(find.byKey(const Key('hero.cta')), findsNothing);
    expect(find.text(l.soldOut), findsOneWidget); // event 2's chip
    expect(sale.calls('GET', '/fan/waitlist/1').single.headers['authorization'], 'Bearer jwt');
    expect(sale.calls('GET', '/fan/waitlist/2'), isEmpty);

    // No polling: the list is read when the screen opens, not again while it stays.
    clock.advance(const Duration(minutes: 5));
    await tester.pump(const Duration(minutes: 5));
    await tester.pumpAndSettle();
    expect(sale.calls('GET', '/fan/waitlist/1'), hasLength(1));
  });

  testWidgets('join: PUT, then the outlined "Sur la liste d\'attente"; the push prompt once', (tester) async {
    final push = FakePushMessaging();
    await start(tester, push: push);
    await tap(tester, 'hero.waitlist');
    expect(sale.calls('PUT', '/fan/waitlist/1'), hasLength(1));
    expect(buttonText(tester, 'hero.waitlistOn'), "Sur la liste d'attente");
    expect(push.count('requestPermission'), 1);
  });

  testWidgets('signed out: the login, then the PUT, and screen 2 shows the fan on the list', (tester) async {
    final c = await start(tester, signedIn: false);
    expect(sale.calls('GET', '/fan/waitlist/1'), isEmpty);
    await tap(tester, 'hero.waitlist');
    expect(location(c), Uri(path: '/login', queryParameters: {'from': '/events/1'}).toString());
    expect(sale.calls('PUT', '/fan/waitlist/1'), isEmpty);

    await c.read(sessionProvider.notifier).signIn('jwt');
    await tester.pumpAndSettle();
    expect(location(c), '/events/1');
    expect(sale.calls('PUT', '/fan/waitlist/1').single.headers['authorization'], 'Bearer jwt');
    expect(find.byKey(const Key('event.waitlistOn')), findsOneWidget);
    // Screen 1 → login → screen 2: one back is screen 1.
    expect(pages(c), ['/', '/events/1']);
  });

  testWidgets('signed out on screen 2: the login comes back to it, joined, and one back is screen 1', (tester) async {
    final c = await start(tester, signedIn: false);
    unawaited(c.read(routerProvider).push('/events/1'));
    await tester.pumpAndSettle();
    await tap(tester, 'event.waitlist');
    expect(location(c), '/login');

    await c.read(sessionProvider.notifier).signIn('jwt');
    await tester.pumpAndSettle();
    expect(pages(c), ['/', '/events/1']);
    expect(sale.calls('PUT', '/fan/waitlist/1'), hasLength(1));
    expect(find.byKey(const Key('event.waitlistOn')), findsOneWidget);

    c.read(routerProvider).pop();
    await tester.pumpAndSettle();
    expect(location(c), '/');
    expect(find.byKey(const Key('hero.waitlistOn')), findsOneWidget);
  });

  testWidgets('a login left without signing in forgets the join', (tester) async {
    final c = await start(tester, signedIn: false);
    await tap(tester, 'hero.waitlist');
    c.read(routerProvider).pop();
    await tester.pumpAndSettle();
    expect(location(c), '/');

    // Signed in some other way (the Profil tab, say), then screen 2.
    await c.read(sessionProvider.notifier).signIn('jwt');
    await tester.pumpAndSettle();
    c.read(routerProvider).go('/events/1');
    await tester.pumpAndSettle();
    expect(sale.calls('PUT', '/fan/waitlist/1'), isEmpty);
    expect(sale.calls('GET', '/fan/waitlist/1'), isNotEmpty);
    expect(find.byKey(const Key('event.waitlist')), findsOneWidget);
  });

  testWidgets('sign-out forgets a remembered join', (tester) async {
    final c = await start(tester, signedIn: false);
    await tap(tester, 'hero.waitlist');
    await c.read(sessionProvider.notifier).signOut();
    expect(c.read(waitlistJoinAfterLoginProvider), isEmpty);
  });

  testWidgets('the remembered join failing says so', (tester) async {
    sale.puts.add(() => apiError(503, 'UNAVAILABLE'));
    final c = await start(tester, signedIn: false);
    await tap(tester, 'hero.waitlist');
    await c.read(sessionProvider.notifier).signIn('jwt');
    await tester.pumpAndSettle();
    expect(location(c), '/events/1');
    expect(sale.calls('PUT', '/fan/waitlist/1'), hasLength(1));
    expect(find.text(l.errorGeneric), findsOneWidget);
    expect(find.byKey(const Key('event.waitlist')), findsOneWidget);
  });

  testWidgets('on the list: the sheet, then "Quitter la liste" sends DELETE', (tester) async {
    sale.entry = 'get_200_waiting';
    await start(tester);
    await tap(tester, 'hero.waitlistOn');
    expect(find.text(l.waitlistSheetBody), findsOneWidget);
    await tap(tester, 'waitlist.leave');
    expect(sale.calls('DELETE', '/fan/waitlist/1'), hasLength(1));
    expect(find.byKey(const Key('hero.waitlist')), findsOneWidget);
  });

  testWidgets('already notified: "Liste d\'attente" again, and joining puts the fan at the back', (tester) async {
    sale.entry = 'get_200_notified';
    await start(tester);
    await tap(tester, 'hero.waitlist');
    expect(sale.calls('PUT', '/fan/waitlist/1'), hasLength(1));
    expect(find.byKey(const Key('hero.waitlistOn')), findsOneWidget);
  });

  testWidgets('a failed join keeps the button and says so', (tester) async {
    sale.puts.add(() => apiError(503, 'UNAVAILABLE'));
    await start(tester);
    await tap(tester, 'hero.waitlist');
    expect(find.text(l.errorGeneric), findsOneWidget);
    expect(find.byKey(const Key('hero.waitlist')), findsOneWidget);
  });

  group('WAITLIST_CLOSED re-reads the event and shows its state', () {
    final cases = <(String, void Function(Map<String, Object?>), String, String)>[
      (
        'not_sold_out',
        (e) => e.addAll(
          eventJson(
            zones: [
              zoneJson(1, 'Tribune Nord', [(5000, 2)]),
            ],
          ),
        ),
        'ctaBuy',
        'zones',
      ),
      ('room', (e) => e['room'] = roomJson(status: 'OPEN'), 'queue', 'queue'),
      ('closed', (e) => e.addAll({'sales_close_at': '2027-02-20T11:00:00Z', 'on_sale': false}), 'closed', 'closed'),
    ];
    for (final (reason, change, hero, footer) in cases) {
      for (final screen in ['hero', 'event']) {
        testWidgets('$reason on ${screen == 'hero' ? 'screen 1' : 'screen 2'}', (tester) async {
          sale.puts.add(() {
            change(sale.event);
            return waitlistAnswer('put_409_$reason');
          });
          await start(tester, location: screen == 'hero' ? '/' : '/events/1');
          final path = screen == 'hero' ? '/fan/events' : '/fan/events/1';
          final reads = sale.calls('GET', path).length;
          await tap(tester, '$screen.waitlist');
          expect(sale.calls('GET', path).length, greaterThan(reads));
          expect(find.byKey(Key('$screen.waitlist')), findsNothing);
          final expected = switch (screen == 'hero' ? hero : footer) {
            'ctaBuy' => l.ctaBuyFrom(l.dh(5000)),
            'zones' => l.chooseZone,
            'queue' => l.queueJoin,
            _ => l.saleClosed,
          };
          expect(buttonText(tester, '$screen.cta'), expected);
          expect(find.text(l.errorGeneric), findsNothing);
        });
      }
    }
  });

  testWidgets('places left: the normal CTA and no waitlist read', (tester) async {
    sale.event = eventJson(
      zones: [
        zoneJson(1, 'Tribune Nord', [(5000, 2)]),
      ],
    )..['room'] = null;
    await start(tester, location: '/events/1');
    expect(buttonText(tester, 'event.cta'), l.chooseZone);
    expect(find.byKey(const Key('event.waitlist')), findsNothing);
    expect(sale.calls('GET', '/fan/waitlist/1'), isEmpty);
  });

  testWidgets('screen 2 sold out: the waitlist CTA and how the notice comes', (tester) async {
    await start(tester, location: '/events/1');
    expect(buttonText(tester, 'event.waitlist'), "Liste d'attente");
    expect(find.text(l.waitlistChannels), findsOneWidget);
  });

  testWidgets('a WAITLIST_OPEN push (/events/{id}) opens screen 2 on the places back', (tester) async {
    final push = FakePushMessaging();
    sale.entry = 'get_200_waiting';
    final c = await start(tester, push: push);
    sale.event = eventJson(
      zones: [
        zoneJson(1, 'Tribune Nord', [(5000, 2)]),
      ],
    )..['room'] = null;
    push.opened.add(
      const PushMessage(
        title: 'Des places se sont libérées · Atlas FC vs Rapid Oued',
        body: '',
        data: {'path': '/events/1'},
      ),
    );
    await tester.pumpAndSettle();
    expect(location(c), '/events/1');
    expect(find.byType(EventScreen), findsOneWidget);
    expect(buttonText(tester, 'event.cta'), l.chooseZone);
  });
}
