import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:intl/intl.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

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

  testWidgets('home lists the next events by opponent, asks for this club, and never sends the token', (tester) async {
    await pumpJomhor(
      tester,
      tokens: MemoryTokenStore('jwt'),
      backend: backend([
        eventJson(),
        eventJson(id: 2, onSale: false, saleStartsAt: '2027-03-01T10:00:00Z', metadata: {'opponent': 'Union Tanger'}),
      ]),
    );
    expect(find.text('PROCHAIN MATCH · DOMICILE'), findsOneWidget);
    // Club from tokens.json (top bar + home side of the hero), opponent from metadata.
    expect(find.text(JomhorClub.name.toUpperCase()), findsNWidgets(2));
    expect(find.text('RAPID OUED'), findsOneWidget);
    expect(find.text('CHAMPIONNAT · JOURNÉE 11'), findsNothing); // the hero shows it in sentence case
    expect(find.text('Championnat · Journée 11'), findsOneWidget);
    expect(find.text('Union Tanger'), findsOneWidget);
    expect(find.text('Bientôt'), findsOneWidget);

    final list = requests.singleWhere((r) => r.url.path == '/v1/fan/events');
    expect(list.url.queryParameters, {'organizer': testOrganizer});
    expect(list.headers.containsKey('Authorization'), isFalse);
  });

  testWidgets('without ORGANIZER_ID the home refuses to load and says so', (tester) async {
    await pumpJomhor(tester, organizerId: '', backend: backend([eventJson()]));
    expect(find.text('Application mal configurée : aucun club défini (ORGANIZER_ID).'), findsOneWidget);
    expect(requests, isEmpty);
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

    testWidgets('on sale: rules banner with the account limit, "Choisir ma zone" opens screen 3', (tester) async {
      await open(tester, detail);
      expect(find.text('Vente ouverte · limite 4 billets par compte · billets nominatifs'), findsOneWidget);
      expect(find.text('CHAMPIONNAT · JOURNÉE 11'), findsOneWidget);
      await tester.tap(find.text('Choisir ma zone'));
      await tester.pumpAndSettle();
      expect(find.text('Choisir ma zone'), findsOneWidget); // the screen 3 placeholder title
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

  testWidgets("another organizer's event reads as not found", (tester) async {
    final other = {...eventJson(), 'organizer_id': 'another-club', 'zones': <Object?>[]};
    final container = await pumpJomhor(tester, backend: backend([], other));
    container.read(routerProvider).go('/events/1');
    await tester.pumpAndSettle();
    expect(find.text('Introuvable. Il a peut-être été retiré.'), findsOneWidget);
  });

  testWidgets('back from an error opened by deep link goes home', (tester) async {
    final container = await pumpJomhor(tester, backend: backend([]));
    container.read(routerProvider).go('/events/99');
    await tester.pumpAndSettle();
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(container.read(routerProvider).routerDelegate.currentConfiguration.uri.path, '/');
  });
}
