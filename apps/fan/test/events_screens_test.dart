import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

void main() {
  final requests = <http.Request>[];
  setUp(requests.clear);

  Future<http.Response> Function(http.Request) backend(
    List<Map<String, Object?>> events, [
    Map<String, Object?>? detail,
  ]) => (request) async {
    requests.add(request);
    return switch (request.url.path) {
      '/v1/fan/events' => json(events),
      '/v1/fan/events/1' when detail != null => json(detail),
      _ => apiError(404, 'NOT_FOUND'),
    };
  };

  FilledButton cta(WidgetTester tester, String key) => tester.widget<FilledButton>(find.byKey(Key(key)));

  group('screen 1 · Accueil hero per sale state', () {
    testWidgets('on sale: "Prendre mes places · dès 50 DH", enabled', (tester) async {
      await pumpJomhor(tester, backend: backend([eventJson()]));
      expect(find.text('Prendre mes places · dès ${formatDh(5000)}'), findsOneWidget);
      expect(cta(tester, 'hero.cta').onPressed, isNotNull);
    });

    testWidgets('not started: "Vente le {date} à {time}" in the venue zone, disabled', (tester) async {
      const opens = '2027-02-21T10:00:00Z';
      await pumpJomhor(tester, backend: backend([eventJson(onSale: false, saleStartsAt: opens)]));
      final at = DateTime.parse(opens);
      const zone = 'Africa/Casablanca';
      expect(find.text('Vente le ${venueDate(at, zone, 'fr')} à ${venueClock(at, zone, 'fr')}'), findsOneWidget);
      expect(cta(tester, 'hero.cta').onPressed, isNull);
    });

    testWidgets('sold out: "Complet", disabled', (tester) async {
      await pumpJomhor(tester, backend: backend([eventJson(soldOut: true, fromPriceMad: null)]));
      expect(find.text('Complet'), findsOneWidget);
      expect(cta(tester, 'hero.cta').onPressed, isNull);
    });

    testWidgets('closed: "Vente terminée", disabled', (tester) async {
      await pumpJomhor(tester, backend: backend([eventJson(onSale: false, salesCloseAt: '2027-02-20T11:00:00Z')]));
      expect(find.text('Vente terminée'), findsOneWidget);
      expect(cta(tester, 'hero.cta').onPressed, isNull);
    });
  });

  testWidgets('home lists the next events by opponent, asks for every listed club, and never sends the token', (
    tester,
  ) async {
    await pumpJomhor(
      tester,
      tokens: MemoryTokenStore('jwt'),
      backend: backend([
        eventJson(),
        eventJson(id: 2, onSale: false, saleStartsAt: '2027-03-01T10:00:00Z', metadata: {'opponent': 'Union Tanger'}),
      ]),
    );
    expect(find.text('PROCHAIN MATCH · DOMICILE'), findsOneWidget);
    // The top bar is Jomhor's; the club (the event's `organizer`) is content only: the home side of the hero.
    // Opponent from metadata.
    expect(find.descendant(of: find.byType(BrandBar), matching: find.byType(JomhorLockup)), findsOneWidget);
    expect(find.text('ATLAS FC'), findsOneWidget);
    expect(find.text('RAPID OUED'), findsOneWidget);
    expect(find.text('CHAMPIONNAT · JOURNÉE 11'), findsNothing); // the hero shows it in sentence case
    expect(find.text('Championnat · Journée 11'), findsOneWidget);
    // The calendar sits under the hero (the page scrolls; the chip row is the inner, sideways scrollable).
    await tester.scrollUntilVisible(find.text('Union Tanger'), 200, scrollable: find.byType(Scrollable).first);
    expect(find.text('Union Tanger'), findsOneWidget);
    expect(find.text('Bientôt'), findsOneWidget);

    final list = requests.singleWhere((r) => r.url.path == '/v1/fan/events');
    expect(list.url.hasQuery, isFalse); // `Tout`: no follows, no `organizer`, not even a bare `?`
    expect(list.headers.containsKey('Authorization'), isFalse);
  });

  testWidgets('a network failure shows the copy and retries on tap', (tester) async {
    var fail = true;
    await pumpJomhor(
      tester,
      backend: (request) async {
        if (fail) throw http.ClientException('offline');
        return json([eventJson()]);
      },
    );
    expect(find.text('Pas de connexion. Vérifie ton réseau et réessaie.'), findsOneWidget);
    fail = false;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('hero.cta')), findsOneWidget);
  });

  group('screen 2 · Match', () {
    final detail = eventJson(
      zones: [
        zoneJson(1, 'Tribune Nord', [(5000, 2300)], seated: false),
        zoneJson(2, "Tribune d'honneur", [(30000, 30), (25000, 20)]),
        zoneJson(3, 'Tribune Sud', [(5000, 0)]),
      ],
    );

    Future<void> open(WidgetTester tester, Map<String, Object?> event) async {
      final container = await pumpJomhor(tester, backend: backend([event], event));
      container.read(routerProvider).go('/events/1');
      await tester.pumpAndSettle();
    }

    testWidgets('zone rows: remaining, "Dernières places" at <= 50, "Complet" at 0', (tester) async {
      await open(tester, detail);
      expect(find.text('${NumberFormat.decimalPattern('fr').format(2300)} restants'), findsOneWidget);
      expect(find.text('Dernières places'), findsOneWidget);
      expect(find.text('dès ${formatDh(25000)}'), findsOneWidget); // two categories left
      expect(find.text('Complet'), findsOneWidget);
      final soldOut = tester.widget<Opacity>(
        find.ancestor(of: find.byKey(const Key('zone.3')), matching: find.byType(Opacity)).first,
      );
      expect(soldOut.opacity, lessThan(1));
    });

    testWidgets('on sale: rules banner with the account limit, "Choisir ma zone" asks to log in first', (tester) async {
      await open(tester, detail);
      expect(find.text('Vente ouverte · limite 4 billets par compte · billets nominatifs'), findsOneWidget);
      expect(find.text('CHAMPIONNAT · JOURNÉE 11'), findsOneWidget);
      await tester.tap(find.text('Choisir ma zone'));
      await tester.pumpAndSettle();
      expect(find.text('Connecte-toi'), findsOneWidget); // buying needs a session (screen 3 is guarded)
      expect(find.byKey(const Key('event.cta')), findsNothing);
    });

    testWidgets('not started: banner says when it opens, CTA disabled', (tester) async {
      final notStarted = {...detail, 'on_sale': false, 'sale_starts_at': '2027-02-21T10:00:00Z'};
      await open(tester, notStarted);
      expect(
        find.textContaining('Vente le ${venueDate(DateTime.utc(2027, 2, 21, 10), 'Africa/Casablanca', 'fr')}'),
        findsNWidgets(2), // banner + footer button
      );
      expect(cta(tester, 'event.cta').onPressed, isNull);
    });

    testWidgets('an unknown event shows not-found copy', (tester) async {
      final container = await pumpJomhor(tester, backend: backend([]));
      container.read(routerProvider).go('/events/99');
      await tester.pumpAndSettle();
      expect(find.text('Introuvable. Il a peut-être été retiré.'), findsOneWidget);
    });
  });

  group('the sale opening while a screen is open', () {
    const opens = '2027-02-20T12:00:00Z';
    final before = DateTime.utc(2027, 2, 20, 11, 59);

    /// First read of each path: not open yet. Every later read: open.
    Future<http.Response> Function(http.Request) opening() {
      final reads = <String, int>{};
      return (request) async {
        requests.add(request);
        final path = request.url.path;
        final open = (reads[path] = (reads[path] ?? 0) + 1) > 1;
        final e = eventJson(
          onSale: open,
          saleStartsAt: opens,
          zones: [
            zoneJson(1, 'Tribune Nord', [(5000, 100)]),
          ],
        );
        return path == '/v1/fan/events' ? json([e]) : json(e);
      };
    }

    testWidgets('home: at T+1 min it says "imminente", never "Vente terminée", and refetches by itself', (
      tester,
    ) async {
      final clock = TestClock(before);
      await pumpJomhor(tester, backend: opening(), clock: clock);
      expect(find.textContaining('Vente le'), findsOneWidget);

      clock.advance(const Duration(minutes: 1)); // the 30 s tick lands after the opening
      await tester.pump(); // tick delivered
      await tester.pump(); // rebuilt
      expect(find.text('Vente terminée'), findsNothing);
      expect(find.text('Ouverture de la vente imminente'), findsOneWidget);

      await tester.pump(const Duration(seconds: 66)); // fake time to the opening + max jitter
      await tester.pumpAndSettle();
      expect(requests.where((r) => r.url.path == '/v1/fan/events'), hasLength(2));
      expect(find.text('Prendre mes places · dès ${formatDh(5000)}'), findsOneWidget);
      expect(find.text('Vente terminée'), findsNothing);
    });

    testWidgets('screen 2: same, and pull-to-refresh refetches', (tester) async {
      final clock = TestClock(before);
      final container = await pumpJomhor(tester, backend: opening(), clock: clock);
      container.read(routerProvider).go('/events/1');
      await tester.pumpAndSettle();
      final detailReads = requests.where((r) => r.url.path == '/v1/fan/events/1');
      expect(detailReads, hasLength(1));

      clock.advance(const Duration(minutes: 1));
      await tester.pump();
      await tester.pump();
      expect(find.text('Vente terminée'), findsNothing);
      expect(find.text('Ouverture de la vente imminente'), findsNWidgets(2)); // banner + footer

      await tester.fling(find.text('Tarifs par zone'), const Offset(0, 400), 1000);
      await tester.pumpAndSettle();
      expect(detailReads, hasLength(2));
      expect(find.text('Choisir ma zone'), findsOneWidget);
    });

    testWidgets('device clock 30 s ahead: an early "not on sale" read backs off, never "Vente terminée"', (
      tester,
    ) async {
      // Reduced motion: no launch splash, whose second of fake time the device clock below would not follow.
      tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(
        disableAnimations: true,
      );
      addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
      const skew = Duration(seconds: 30); // device ahead of the server
      final opensAt = DateTime.parse(opens);
      final clock = TestClock(opensAt.subtract(const Duration(seconds: 60))); // server: opening - 90 s
      var reads = 0;
      await pumpJomhor(
        tester,
        clock: clock,
        backend: (request) async {
          requests.add(request);
          // Reads 1 and 2: not open yet (the 2nd is the refetch at the opening); open from read 3.
          final open = ++reads >= 3;
          return http.Response.bytes(
            utf8.encode(jsonEncode([eventJson(onSale: open, saleStartsAt: opens)])),
            200,
            headers: {'content-type': 'application/json', 'date': HttpDate.format(clock.now.subtract(skew))},
          );
        },
      );

      /// Moves the device clock and fake time together, tick by tick, checking the copy on the way.
      Future<void> elapse(Duration total) async {
        for (var t = Duration.zero; t < total; t += const Duration(seconds: 1)) {
          clock.advance(const Duration(seconds: 1));
          await tester.pump(const Duration(seconds: 1));
          expect(find.text('Vente terminée'), findsNothing, reason: 'at device ${clock.now}');
        }
      }

      await elapse(const Duration(seconds: 61)); // device past the opening, server 29 s before it
      expect(find.text('Ouverture de la vente imminente'), findsOneWidget);
      expect(reads, 1, reason: 'the first refetch waits for the opening on the server clock');

      await elapse(const Duration(seconds: 30)); // the server opening (jitter is 0 in tests): read 2 still says no
      expect(reads, 2);
      expect(find.text('Ouverture de la vente imminente'), findsOneWidget);

      await elapse(const Duration(seconds: 3)); // back-off of 2 s: read 3 is open
      await tester.pumpAndSettle();
      expect(reads, 3);
      expect(find.text('Prendre mes places · dès ${formatDh(5000)}'), findsOneWidget);
    });

    testWidgets('coming back to the app refetches', (tester) async {
      await pumpJomhor(tester, backend: opening(), clock: TestClock(before));
      tester.binding
        ..handleAppLifecycleStateChanged(AppLifecycleState.inactive)
        ..handleAppLifecycleStateChanged(AppLifecycleState.resumed);
      await tester.pumpAndSettle();
      expect(requests.where((r) => r.url.path == '/v1/fan/events'), hasLength(2));
    });
  });

  testWidgets("any club's event opens, with its crest and name in the header (one app for every club)", (tester) async {
    final other = {...eventJson(club: tensiftJson), 'zones': <Object?>[]};
    final container = await pumpJomhor(tester, backend: backend([], other));
    container.read(routerProvider).go('/events/1');
    await tester.pumpAndSettle();
    expect(find.text('Introuvable. Il a peut-être été retiré.'), findsNothing);
    expect(find.text('Olympique Tensift'), findsOneWidget);
    expect(find.widgetWithText(ClubLine, 'Olympique Tensift'), findsOneWidget);
  });

  testWidgets('back from an error opened by deep link goes home', (tester) async {
    final container = await pumpJomhor(tester, backend: backend([]));
    container.read(routerProvider).go('/events/99');
    await tester.pumpAndSettle();
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/');
  });

  group('screen 1 · club chips (multi-club)', () {
    setUp(() => SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty());

    final kindClub = clubJson(id: 'org-venue', slug: 'grand-theatre', name: 'Grand Théâtre', kind: 'VENUE');

    /// Events per organizer filter, like the backend: no `organizer` = every club's events.
    Future<http.Response> Function(http.Request) clubsBackend(Map<String, List<Map<String, Object?>>> byClub) =>
        (r) async {
          requests.add(r);
          return switch (r.url.path) {
            '/v1/fan/organizers' => json([clubJson(), tensiftJson, kindClub]),
            '/v1/fan/events' => json([
              for (final MapEntry(:key, :value) in byClub.entries)
                if (r.url.queryParametersAll['organizer']?.contains(key) ?? true) ...value,
            ]),
            _ => apiError(404, 'NOT_FOUND'),
          };
        };

    final atlas = eventJson();
    final tensift = eventJson(id: 2, startsAt: '2027-03-14T19:00:00Z', club: tensiftJson);
    final both = {
      testOrganizer: [atlas],
      otherOrganizer: [tensift],
    };

    bool selected(WidgetTester tester, String chip) => tester
        .widget<Semantics>(
          find.descendant(of: find.byKey(Key('home.chip.$chip')), matching: find.byType(Semantics)).first,
        )
        .properties
        .selected!;

    /// The last `organizer` filter; null = no query at all (a bare `?` reads as an empty filter and fails).
    List<String>? lastFilter() {
      final url = requests.lastWhere((r) => r.url.path == '/v1/fan/events').url;
      return url.hasQuery ? url.queryParametersAll['organizer'] ?? const [] : null;
    }

    testWidgets('no follows: only `Tout`, selected, and the request has no `organizer`', (tester) async {
      await pumpJomhor(tester, backend: clubsBackend(both));
      expect(find.byKey(const Key('home.chip.all')), findsOneWidget);
      expect(find.byKey(const Key('home.chip.mine')), findsNothing);
      expect(selected(tester, 'all'), isTrue);
      expect(lastFilter(), isNull);
      expect(requests.where((r) => r.url.path == '/v1/fan/organizers'), isEmpty); // no follows, no directory read
    });

    testWidgets('follows A, B: `Mes clubs · A · B · Tout`, `Mes clubs` by default with organizer=A&organizer=B', (
      tester,
    ) async {
      await pumpJomhor(
        tester,
        backend: clubsBackend(both),
        follows: (ids: const [testOrganizer, otherOrganizer], seen: true, chip: null),
      );
      final xs = [
        for (final k in ['mine', testOrganizer, otherOrganizer, 'all'])
          tester.getCenter(find.byKey(Key('home.chip.$k'))).dx,
      ];
      expect(xs, orderedEquals([...xs]..sort()));
      expect(find.descendant(of: find.byKey(const Key('home.chip.mine')), matching: find.text('Mes clubs')), findsOne);
      expect(selected(tester, 'mine'), isTrue);
      expect(lastFilter(), [testOrganizer, otherOrganizer]);

      // Chip A: organizer=A only; the choice is saved on the phone.
      await tester.tap(find.byKey(const Key('home.chip.$testOrganizer')));
      await tester.pumpAndSettle();
      expect(selected(tester, testOrganizer), isTrue);
      expect(lastFilter(), [testOrganizer]);
      expect((await savedFollows(SharedPreferencesAsync())).chip, testOrganizer);

      // `Tout`: no organizer.
      await tester.tap(find.byKey(const Key('home.chip.all')));
      await tester.pumpAndSettle();
      expect(lastFilter(), isNull);
    });

    testWidgets('the chosen chip survives a restart; a remembered club no longer followed falls back to the default', (
      tester,
    ) async {
      await pumpJomhor(
        tester,
        backend: clubsBackend(both),
        follows: (ids: const [testOrganizer, otherOrganizer], seen: true, chip: otherOrganizer),
      );
      expect(selected(tester, otherOrganizer), isTrue);
      expect(lastFilter(), [otherOrganizer]);

      await pumpJomhor(
        tester,
        backend: clubsBackend(both),
        follows: (ids: const [testOrganizer], seen: true, chip: otherOrganizer),
      );
      expect(selected(tester, 'mine'), isTrue);
      expect(lastFilter(), [testOrganizer]);
    });

    testWidgets('in `Tout`, "Suivre" sits on unfollowed clubs, "Suivi" on followed ones; a tap keeps `Tout`', (
      tester,
    ) async {
      await pumpJomhor(
        tester,
        backend: clubsBackend(both),
        follows: (ids: const [testOrganizer], seen: true, chip: 'all'),
      );
      expect(find.byKey(const Key('home.follow.1')), findsNothing); // Atlas: followed
      expect(find.descendant(of: find.byKey(const Key('home.following.1')), matching: find.text('Suivi')), findsOne);
      await tester.scrollUntilVisible(
        find.byKey(const Key('home.follow.2')),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.tap(find.byKey(const Key('home.follow.2')));
      await tester.pumpAndSettle();
      // The card turns to "Suivi" in place; `Tout` stays selected and the list is not re-filtered.
      expect(find.byKey(const Key('home.follow.2')), findsNothing);
      expect(find.descendant(of: find.byKey(const Key('home.following.2')), matching: find.text('Suivi')), findsOne);
      expect(lastFilter(), isNull);
      final saved = await savedFollows(SharedPreferencesAsync());
      expect(saved.ids, [testOrganizer, otherOrganizer]);
      expect(saved.chip, 'all');
      await tester.scrollUntilVisible(
        find.byKey(const Key('home.chip.$otherOrganizer')),
        -200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(selected(tester, 'all'), isTrue);
      expect(selected(tester, 'mine'), isFalse); // `Mes clubs` and the new club's chip now sit in the row
      expect(selected(tester, otherOrganizer), isFalse);
    });

    testWidgets('hero and calendar rows carry the club line: crest + organizer name', (tester) async {
      await pumpJomhor(tester, backend: clubsBackend(both));
      expect(find.widgetWithText(ClubLine, 'Atlas FC'), findsOneWidget);
      await tester.scrollUntilVisible(
        find.widgetWithText(ClubLine, 'Olympique Tensift'),
        200,
        scrollable: find.byType(Scrollable).first,
      );
      expect(
        find.descendant(of: find.widgetWithText(ClubLine, 'Olympique Tensift'), matching: find.byType(Crest)),
        findsOneWidget,
      );
    });

    for (final (kind, club, copy) in [
      ('a sports club', otherOrganizer, 'Pas de match à venir'),
      ('another kind', 'org-venue', "Pas d'événement à venir"),
    ]) {
      testWidgets('an empty club chip, $kind: "$copy" and "Voir tout" → `Tout`', (tester) async {
        await pumpJomhor(
          tester,
          backend: clubsBackend({
            testOrganizer: [atlas],
          }),
          follows: (ids: [testOrganizer, club], seen: true, chip: club),
        );
        expect(find.text(copy), findsOneWidget);
        await tester.tap(find.byKey(const Key('home.seeAll')));
        await tester.pumpAndSettle();
        expect(selected(tester, 'all'), isTrue);
        expect(lastFilter(), isNull);
        expect(find.byKey(const Key('hero.cta')), findsOneWidget);
      });
    }

    testWidgets('AR: the chip row starts at the right and club names sit in isolates', (tester) async {
      await pumpJomhor(
        tester,
        backend: clubsBackend(both),
        locale: const Locale('ar'),
        follows: (ids: const [testOrganizer], seen: true, chip: null),
      );
      final mine = tester.getRect(find.byKey(const Key('home.chip.mine')));
      final all = tester.getRect(find.byKey(const Key('home.chip.all')));
      expect(mine.left, greaterThan(all.left));
      expect(tester.view.physicalSize.width / tester.view.devicePixelRatio - mine.right, lessThan(40));
      expect(find.text('فرقي'), findsOneWidget);
      expect(find.text('\u2068Atlas FC\u2069'), findsWidgets);
    });
  });
}
