import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/tickets/gate_load.dart';
import 'package:jomhor_fan/features/tickets/gate_side.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';
import 'tickets_test.dart' show otherId, savedCache, seedCache, ticketId, ticketJson;

/// FL-19: screen 5 gate side, load chips and "Tu es dedans" (DESIGN.md §Lot 4 adjustments, docs/04-api.md
/// `GET /fan/tickets` `gates[]` and `GET /fan/gates/{event_id}/load`).
final kickoff = DateTime.utc(2027, 3, 13, 19);

/// The ticket of tickets_test.dart (event 7, zone Nord) with Lot 4 `gates[]`, sorted by code; "your gate" is 4.
Map<String, Object?> gatedTicket({String id = ticketId, bool located = true}) => {
  ...ticketJson(id: id),
  'gate_codes': ['4', '6', '7'],
  'gates': [
    {
      'code': '4',
      'name': 'Porte Nord',
      'directions': 'Entrée avenue Hassan II, suivre le fléchage vert.',
      'latitude': located ? 34.012345 : null,
      'longitude': located ? -6.8345 : null,
    },
    {'code': '6', 'name': 'Porte Est', 'directions': null, 'latitude': null, 'longitude': null},
    {'code': '7', 'name': 'Porte Ouest', 'directions': null, 'latitude': null, 'longitude': null},
  ],
};

/// A `/fan/gates/7/load` answer, shaped as BE-32 sends it.
Map<String, Object?> loadJson(
  DateTime serverTime, {
  Map<String, String> levels = const {'4': 'MEDIUM', '6': 'LOW', '7': 'HIGH'},
  List<Map<String, Object?>> entries = const [],
}) => {
  'event_id': 7,
  'server_time': serverTime.toIso8601String(),
  'gates': [
    for (final MapEntry(:key, :value) in levels.entries) {'code': key, 'level': value},
  ],
  'entries': entries,
};

Map<String, Object?> entryJson({String id = ticketId, String at = '2027-03-13T17:42:10.12Z'}) => {
  'ticket_id': id,
  'entered_at': at,
  'gate_code': '4',
};

void main() {
  final calls = <http.Request>[];
  final haptics = <Object?>[];
  setUp(() {
    calls.clear();
    haptics.clear();
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
  });

  Iterable<http.Request> loads() => calls.where((r) => r.url.path.startsWith('/v1/fan/gates/'));

  /// Opens the app at [at] (inside matchday, so straight on screen 5) with the saved [tickets]; `/load` answers
  /// with [load] (null = offline), everything else fails like airplane mode.
  Future<ProviderContainer> start(
    WidgetTester tester, {
    required DateTime at,
    TestClock? clock,
    Duration Function(Duration max)? jitter,
    List<Map<String, Object?>>? tickets,
    FutureOr<http.Response> Function(http.Request r)? load,
    List<Override> overrides = const [],
  }) async {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') haptics.add(call.arguments);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    seedCache(tickets ?? [gatedTicket()]);
    return await pumpJomhor(
      tester,
      backend: (r) async {
        calls.add(r);
        if (load != null && r.url.path.startsWith('/v1/fan/gates/')) return await load(r);
        throw Exception('no network');
      },
      tokens: MemoryTokenStore('jwt'),
      clock: clock ?? TestClock(at),
      jitter: jitter,
      keepSecureStorage: true,
      overrides: overrides,
    );
  }

  final rowFade = find.descendant(of: find.byKey(const Key('gate.rowFade')), matching: find.byType(AnimatedOpacity));
  double opacity(WidgetTester tester) => tester.widget<AnimatedOpacity>(rowFade).opacity;

  bool shows(WidgetTester tester, String face) =>
      tester.widget<Visibility>(find.byKey(Key('ticket.face.$face'))).visible;

  /// The face's turn about the vertical axis: 1 = flat, facing the fan.
  double turn(WidgetTester tester, String face) => tester
      .widget<Transform>(
        find.descendant(of: find.byKey(Key('ticket.face.$face')), matching: find.byType(Transform)).first,
      )
      .transform
      .entry(0, 0);

  const heavy = 'HapticFeedbackType.heavyImpact';

  group('gate side', () {
    testWidgets('the PORTE fact flips the card in 420 ms, "Retourner le billet" flips it back; directions offline', (
      tester,
    ) async {
      await start(tester, at: kickoff.subtract(const Duration(hours: 2)));
      expect(shows(tester, 'front'), isTrue);
      expect(shows(tester, 'gate'), isFalse);
      expect(tester.getSize(find.byKey(const Key('ticket.gate'))).height, greaterThanOrEqualTo(44));
      expect(
        tester.getSemantics(find.byKey(const Key('ticket.gate'))),
        matchesSemantics(isButton: true, hasTapAction: true, label: "Porte 4, voir l'itinéraire"),
      );
      // The front names your gate only (the first of `gates[]`), "+2" for the zone's others, on one line.
      final fact = find.byKey(const Key('ticket.gate'));
      expect(find.descendant(of: fact, matching: find.text('PORTE')), findsOneWidget);
      expect(find.descendant(of: fact, matching: find.text('4')), findsOneWidget);
      expect(find.descendant(of: fact, matching: find.text('+2')), findsOneWidget);
      expect(tester.getSize(find.descendant(of: fact, matching: find.text('4'))).height, lessThan(40));
      expect(opacity(tester), 1);

      await tester.tap(find.byKey(const Key('ticket.gate')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 100));
      expect((shows(tester, 'front'), shows(tester, 'gate')), (true, false), reason: 'the front turns away first');
      await tester.pump(const Duration(milliseconds: 300));
      expect((shows(tester, 'front'), shows(tester, 'gate')), (false, true));
      expect(turn(tester, 'gate'), lessThan(1), reason: 'still turning at 400 ms');
      await tester.pump(const Duration(milliseconds: 20));
      expect(turn(tester, 'gate'), closeTo(1, 1e-9), reason: 'settled at 420 ms');
      // The guidance row under the card would repeat the gate side: faded out and out of reach.
      expect(opacity(tester), 0);
      expect(tester.widget<AnimatedOpacity>(rowFade).duration, const Duration(milliseconds: 220));
      expect(find.byKey(const Key('gate.route')).hitTestable(), findsNothing);

      // The gate side, from the saved ticket, offline: code, name, directions, Maps; no chips without a load.
      expect(find.byKey(const Key('gate.code')), findsOneWidget);
      expect(tester.widget<Text>(find.byKey(const Key('gate.code'))).data, '4');
      expect(find.text('Porte Nord'), findsOneWidget);
      expect(find.text('Entrée avenue Hassan II, suivre le fléchage vert.'), findsOneWidget);
      expect(find.byKey(const Key('gate.maps')), findsOneWidget);
      expect(find.byKey(const Key('gate.other.6')), findsOneWidget);
      expect(find.byType(LoadChip).evaluate().every((e) => (e.widget as LoadChip).level == GateLevel.unknown), isTrue);
      expect(find.byKey(const Key('gate.load.low')), findsNothing);
      expect(find.byType(ErrorPanel), findsNothing);

      await tester.ensureVisible(find.byKey(const Key('gate.back')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('gate.back')));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 420));
      expect((shows(tester, 'front'), shows(tester, 'gate')), (true, false));
      expect(find.byType(TicketQr), findsOneWidget);

      // "Itinéraire" in the gate row flips it too.
      await tester.tap(find.byKey(const Key('gate.route')));
      await tester.pumpAndSettle();
      expect(shows(tester, 'gate'), isTrue);
    });

    testWidgets('reduced motion: the flip is instant', (tester) async {
      tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(
        disableAnimations: true,
      );
      addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
      await start(tester, at: kickoff.subtract(const Duration(hours: 2)));
      await tester.tap(find.byKey(const Key('ticket.gate')));
      await tester.pump();
      expect((shows(tester, 'front'), shows(tester, 'gate')), (false, true));
      expect(turn(tester, 'gate'), closeTo(1, 1e-9));
      expect((opacity(tester), tester.widget<AnimatedOpacity>(rowFade).duration), (0, Duration.zero));
    });

    test('Maps link: geo: on Android, Apple Maps on iOS, 6 decimals, an encoded label; none without coordinates', () {
      const g = TicketGate(code: '4', name: 'Porte Nord (Tribune)', latitude: 34.012345, longitude: -6.8345);
      expect(
        mapsUrl(g, TargetPlatform.android).toString(),
        'geo:34.012345,-6.834500?q=34.012345,-6.834500(Porte%20Nord%20Tribune)',
      );
      expect(
        mapsUrl(g, TargetPlatform.iOS).toString(),
        'https://maps.apple.com/?ll=34.012345,-6.834500&q=Porte%20Nord%20Tribune',
      );
      expect(mapsUrl(const TicketGate(code: '4', name: 'Porte Nord'), TargetPlatform.android), isNull);
    });

    for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
      testWidgets('"Ouvrir dans Plans" opens the ${platform.name} link', (tester) async {
        debugDefaultTargetPlatformOverride = platform;
        final opened = <Uri>[];
        await start(
          tester,
          at: kickoff.subtract(const Duration(hours: 2)),
          overrides: [openMapsProvider.overrideWithValue((url) async => opened.add(url))],
        );
        await tester.tap(find.byKey(const Key('ticket.gate')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('gate.maps')));
        expect(
          opened.single.toString(),
          startsWith(platform == TargetPlatform.iOS ? 'https://maps.apple.com/' : 'geo:'),
        );
        debugDefaultTargetPlatformOverride = null;
      });
    }

    testWidgets('no Maps link without coordinates', (tester) async {
      await start(tester, at: kickoff.subtract(const Duration(hours: 2)), tickets: [gatedTicket(located: false)]);
      await tester.tap(find.byKey(const Key('ticket.gate')));
      await tester.pumpAndSettle();
      expect(find.text('Porte Nord'), findsOneWidget);
      expect(find.byKey(const Key('gate.maps')), findsNothing);
    });

    testWidgets('a cache saved before Lot 4 (no `gates`) opens offline with no wipe; the gate side has the code', (
      tester,
    ) async {
      final before = ticketJson()..remove('gates');
      await start(tester, at: kickoff.subtract(const Duration(hours: 2)), tickets: [before]);
      expect(find.byType(TicketQr), findsOneWidget);
      expect(await savedCache(), isNotNull);
      await tester.tap(find.byKey(const Key('ticket.gate')));
      await tester.pumpAndSettle();
      expect(tester.widget<Text>(find.byKey(const Key('gate.code'))).data, '4');
      expect(find.byKey(const Key('gate.directions')), findsNothing);
      expect(find.byKey(const Key('gate.maps')), findsNothing);
    });

    test('a malformed `gates` block never fails the ticket: no gates, codes shown', () {
      for (final gates in [
        'x',
        [
          {'code': 4},
        ],
        [
          {'code': '4', 'name': 'N', 'latitude': '34.1', 'longitude': -6.0},
        ],
      ]) {
        final t = FanTicket.fromJson({...ticketJson(), 'gates': gates});
        expect(t.shownGates.map((g) => g.code), ['4']);
        expect(t.shownGates.single.latitude, isNull);
      }
    });
  });

  group('load chips', () {
    final open = kickoff.subtract(const Duration(hours: 2));

    testWidgets('chips per level, hidden for UNKNOWN and for a gate absent from the answer', (tester) async {
      await start(
        tester,
        at: open,
        load: (_) => json(loadJson(open, levels: {'4': 'UNKNOWN', '6': 'LOW'})),
      );
      final row = find.byKey(const Key('gate.row'));
      expect(find.descendant(of: row, matching: find.text('Porte 4')), findsOneWidget);
      expect(find.descendant(of: row, matching: find.byKey(const Key('gate.load.unknown'))), findsNothing);
      expect(find.descendant(of: row, matching: find.text('Itinéraire')), findsOneWidget);
      await tester.tap(find.byKey(const Key('gate.route')));
      await tester.pumpAndSettle();
      expect(find.descendant(of: find.byKey(const Key('gate.other.6')), matching: find.text('Fluide')), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('gate.other.7')), matching: find.byType(Text)), findsNWidgets(1));
      expect(find.text('Chargée'), findsNothing);
    });

    for (final (levels, smoother) in [
      ({'4': 'HIGH', '6': 'LOW', '7': 'LOW'}, true),
      ({'4': 'HIGH', '6': 'MEDIUM', '7': 'HIGH'}, false),
      ({'4': 'MEDIUM', '6': 'LOW'}, false),
      ({'4': 'HIGH', '6': 'UNKNOWN'}, false),
    ]) {
      testWidgets('"plus fluide" only for HIGH vs LOW: $levels', (tester) async {
        await start(
          tester,
          at: open,
          load: (_) => json(loadJson(open, levels: levels)),
        );
        expect(find.text('Porte 6 plus fluide en ce moment'), smoother ? findsOneWidget : findsNothing);
        expect(find.byKey(const Key('gate.smoother')), smoother ? findsOneWidget : findsNothing);
      });
    }

    testWidgets('offline after a load: no error, the last level kept under 2 min, then hidden', (tester) async {
      var offline = false;
      await start(
        tester,
        at: open,
        load: (_) => offline ? throw Exception('no network') : json(loadJson(open, levels: {'4': 'HIGH', '6': 'LOW'})),
      );
      final row = find.byKey(const Key('gate.row'));
      expect(find.descendant(of: row, matching: find.text('Chargée')), findsOneWidget);
      offline = true;
      await tester.pump(const Duration(seconds: 61));
      expect(loads(), hasLength(2));
      expect(
        find.descendant(of: row, matching: find.text('Chargée')),
        findsOneWidget,
        reason: '61 s old',
      );
      expect(find.byType(ErrorPanel), findsNothing);
      await tester.pump(const Duration(seconds: 60));
      expect(
        find.descendant(of: row, matching: find.text('Chargée')),
        findsNothing,
        reason: 'over 2 min old',
      );
      expect(find.text('Porte 4'), findsOneWidget, reason: 'the gate row stays');
    });
  });

  group('polling', () {
    testWidgets('only from T0 - 3 h, every 50 s with zero jitter', (tester) async {
      await start(
        tester,
        at: kickoff.subtract(const Duration(hours: 3, seconds: 5)),
        load: (r) => json(loadJson(kickoff)),
      );
      expect(loads(), isEmpty, reason: 'before the window');
      await tester.pump(const Duration(seconds: 4));
      expect(loads(), isEmpty);
      await tester.pump(const Duration(seconds: 2));
      expect(loads(), hasLength(1));
      expect(loads().single.url.path, '/v1/fan/gates/7/load');
      expect(loads().single.headers['Authorization'], 'Bearer jwt');
      await tester.pump(const Duration(seconds: 48));
      expect(loads(), hasLength(1));
      await tester.pump(const Duration(seconds: 2));
      expect(loads(), hasLength(2));
    });

    testWidgets('70 s with the largest jitter: 60 s ± 10 s', (tester) async {
      await start(
        tester,
        at: kickoff.subtract(const Duration(hours: 2)),
        load: (r) => json(loadJson(kickoff)),
        jitter: (max) => max,
      );
      expect(loads(), hasLength(1));
      await tester.pump(const Duration(seconds: 69));
      expect(loads(), hasLength(1));
      await tester.pump(const Duration(seconds: 2));
      expect(loads(), hasLength(2));
    });

    test('the interval is 50 s + jitter of up to 20 s', () {
      expect(loadPollInterval((_) => Duration.zero), const Duration(seconds: 50));
      expect(loadPollInterval((max) => max), const Duration(seconds: 70));
    });

    testWidgets('none after T0 + 1 h', (tester) async {
      await start(
        tester,
        at: kickoff.add(const Duration(hours: 1)).subtract(const Duration(seconds: 10)),
        load: (r) => json(loadJson(kickoff)),
      );
      expect(loads(), hasLength(1));
      await tester.pump(const Duration(minutes: 3));
      expect(loads(), hasLength(1));
    });

    testWidgets('only while visible: not in the background, not once screen 5 is closed', (tester) async {
      final clock = TestClock(kickoff.subtract(const Duration(hours: 2)));
      final c = await start(tester, at: clock.now, clock: clock, load: (r) => json(loadJson(kickoff)));
      expect(loads(), hasLength(1));
      void lifecycle(List<AppLifecycleState> states) => states.forEach(tester.binding.handleAppLifecycleStateChanged);
      lifecycle([AppLifecycleState.inactive, AppLifecycleState.hidden, AppLifecycleState.paused]);
      await tester.pump(const Duration(minutes: 3));
      expect(loads(), hasLength(1), reason: 'paused');
      clock.now = clock.now.add(const Duration(minutes: 3)); // The resume re-anchors on the device clock.
      lifecycle([AppLifecycleState.hidden, AppLifecycleState.inactive, AppLifecycleState.resumed]);
      await tester.pump(const Duration(seconds: 2));
      expect(loads(), hasLength(2), reason: 'back on screen');
      c.read(routerProvider).go('/tickets');
      await tester.pumpAndSettle();
      await tester.pump(const Duration(minutes: 3));
      expect(loads(), hasLength(2), reason: 'closed');
    });

    testWidgets('closing and re-opening screen 5 within 50 s does not poll again', (tester) async {
      final c = await start(
        tester,
        at: kickoff.subtract(const Duration(hours: 2)),
        load: (r) => json(loadJson(kickoff)),
      );
      expect(loads(), hasLength(1));
      for (var i = 0; i < 3; i++) {
        c.read(routerProvider).go('/tickets');
        await tester.pumpAndSettle();
        unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
        await tester.pumpAndSettle();
        await tester.pump(const Duration(seconds: 10));
      }
      expect(loads(), hasLength(1));
      await tester.pump(const Duration(seconds: 25));
      expect(loads(), hasLength(2), reason: 'due again 50 s after the first');
    });

    testWidgets('phones parked on screen 5 at T0 - 3 h: the first poll waits its jitter (here the largest, 20 s)', (
      tester,
    ) async {
      await start(
        tester,
        at: kickoff.subtract(const Duration(hours: 3, seconds: 5)),
        load: (r) => json(loadJson(kickoff)),
        jitter: (max) => max,
      );
      await tester.pump(const Duration(seconds: 6));
      expect(loads(), isEmpty, reason: 'not at T0 - 3 h exactly');
      await tester.pump(const Duration(seconds: 18));
      expect(loads(), isEmpty);
      await tester.pump(const Duration(seconds: 2));
      expect(loads(), hasLength(1));
    });

    testWidgets('paused while the transfer sheet is open', (tester) async {
      await start(
        tester,
        at: kickoff.subtract(const Duration(hours: 2)),
        tickets: [
          {...gatedTicket(), 'transfer_until': '2027-03-13T18:00:00Z', 'transferred': null},
        ],
        load: (r) => json(loadJson(kickoff)),
      );
      expect(loads(), hasLength(1));
      await tester.tap(find.byKey(const Key('ticket.transfer')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('transfer.phone')), findsOneWidget);
      await tester.pump(const Duration(minutes: 3));
      expect(loads(), hasLength(1), reason: 'the sheet covers screen 5');
      Navigator.of(tester.element(find.byKey(const Key('transfer.phone')))).pop();
      await tester.pumpAndSettle();
      await tester.pump(const Duration(seconds: 2));
      expect(loads(), hasLength(2));
    });

    testWidgets('a 404 stops polling that event', (tester) async {
      await start(tester, at: kickoff.subtract(const Duration(hours: 2)), load: (_) => apiError(404, 'NOT_FOUND'));
      await tester.pump(const Duration(minutes: 3));
      expect(loads(), hasLength(1));
      expect(find.byType(ErrorPanel), findsNothing);
    });

    testWidgets("only the shown ticket's event is polled (tickets of two clubs, both on matchday)", (tester) async {
      final other = gatedTicket(id: otherId);
      await start(
        tester,
        at: kickoff.subtract(const Duration(hours: 2)),
        tickets: [
          gatedTicket(),
          {
            ...other,
            'event': {
              ...other['event']! as Map<String, Object?>,
              'id': 8,
              'starts_at': '2027-03-13T20:00:00Z',
              'organizer': {...organizerBlock(tensiftJson), 'id': otherOrganizer},
            },
          },
        ],
        load: (r) => json(loadJson(kickoff)),
      );
      await tester.pump(const Duration(minutes: 2));
      expect(loads().map((r) => r.url.path).toSet(), {'/v1/fan/gates/7/load'});
    });
  });

  group('"Tu es dedans"', () {
    final open = kickoff.subtract(const Duration(hours: 2));

    testWidgets('an entry flips the card once with a heavy haptic; polling stops; transfer says admitted', (
      tester,
    ) async {
      var entered = false;
      final c = await start(
        tester,
        at: open,
        load: (_) => json(loadJson(open, entries: [if (entered) entryJson()])),
      );
      expect(loads(), hasLength(1));
      expect(find.byKey(const Key('ticket.entered')), findsNothing);
      // The gate side is open when the scan syncs: the card flips from it to "Tu es dedans".
      await tester.tap(find.byKey(const Key('ticket.gate')));
      await tester.pumpAndSettle();
      entered = true;
      await tester.pump(const Duration(seconds: 49));
      for (var i = 0; i < 30 && loads().length < 2; i++) {
        await tester.pump(const Duration(milliseconds: 100));
      }
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(shows(tester, 'entered'), isTrue);
      expect(turn(tester, 'entered'), lessThan(0.999), reason: 'flipping');
      await tester.pumpAndSettle();
      expect(turn(tester, 'entered'), closeTo(1, 1e-9));
      expect(haptics.where((h) => h == heavy), hasLength(1));
      expect(find.byKey(const Key('ticket.band.success')), findsWidgets);
      expect(find.text('TU ES DEDANS'), findsOneWidget);
      final at = venueClock(DateTime.parse('2027-03-13T17:42:10.12Z'), 'Africa/Casablanca', 'fr');
      expect(find.text('Entré à $at · Porte 4'), findsOneWidget);
      expect(find.byKey(const Key('gate.row')), findsNothing);
      expect(find.byKey(const Key('ticket.transferBlocked')), findsOneWidget);
      expect(find.text('Ce billet est déjà passé à la porte : il ne peut plus être transféré.'), findsOneWidget);

      await tester.pump(const Duration(minutes: 3));
      expect(loads(), hasLength(2), reason: 'stopped after entry');
      expect(haptics.where((h) => h == heavy), hasLength(1), reason: 'one flip, one haptic');

      // Re-open: the entered state at once, no flip, no haptic, no poll.
      c.read(routerProvider).go('/tickets');
      await tester.pumpAndSettle();
      unawaited(c.read(routerProvider).push('/tickets/$ticketId'));
      await tester.pump();
      expect(find.byKey(const Key('ticket.face.entered')), findsNothing);
      expect(shows(tester, 'front'), isTrue);
      expect(find.byKey(const Key('ticket.entered')), findsOneWidget);
      await tester.pumpAndSettle();
      await tester.pump(const Duration(minutes: 2));
      expect(loads(), hasLength(2));
      expect(haptics.where((h) => h == heavy), hasLength(1));

      // Restart (same phone): still entered, statically; then a logout wipes it.
      await tester.pumpWidget(const SizedBox()); // The app is killed.
      await start(tester, at: open.add(const Duration(minutes: 5)), load: (_) => json(loadJson(open)));
      expect(find.byKey(const Key('ticket.entered')), findsOneWidget);
      expect(find.byKey(const Key('ticket.face.entered')), findsNothing);
      expect(loads(), hasLength(2));
      expect(haptics.where((h) => h == heavy), hasLength(1));
    });

    testWidgets('an answer landing after sign-out writes nothing back', (tester) async {
      final answer = Completer<http.Response>();
      final c = await start(tester, at: open, load: (_) => answer.future);
      expect(loads(), hasLength(1));
      await c.read(sessionProvider.notifier).signOut();
      await tester.pump();
      answer.complete(json(loadJson(open, entries: [entryJson()])));
      await tester.pumpAndSettle();
      expect(await SharedPreferencesAsync().getString('tickets.entered'), isNull);
      expect(c.read(enteredProvider).value, isEmpty);
    });

    testWidgets('logout wipes the entered tickets with the ticket cache', (tester) async {
      final c = await start(
        tester,
        at: open,
        load: (_) => json(loadJson(open, entries: [entryJson()])),
      );
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('ticket.entered')), findsOneWidget);
      await c.read(sessionProvider.notifier).signOut();
      await tester.pumpAndSettle();
      await tester.pumpWidget(const SizedBox());
      final c2 = await start(tester, at: open, load: (_) => json(loadJson(open)));
      expect(await c2.read(enteredProvider.future), isEmpty);
      expect(find.byKey(const Key('ticket.entered')), findsNothing);
    });

    testWidgets('AR: "راك داخل"', (tester) async {
      seedCache([gatedTicket()]);
      await pumpJomhor(
        tester,
        backend: (r) async => r.url.path.startsWith('/v1/fan/gates/')
            ? json(loadJson(open, entries: [entryJson()]))
            : throw Exception('no network'),
        tokens: MemoryTokenStore('jwt'),
        clock: TestClock(open),
        keepSecureStorage: true,
        locale: const Locale('ar'),
      );
      await tester.pumpAndSettle();
      expect(find.text('راك داخل'), findsOneWidget);
    });
  });
}
