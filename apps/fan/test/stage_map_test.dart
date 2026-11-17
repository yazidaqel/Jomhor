import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/features/checkout/layouts.g.dart';
import 'package:jomhor_fan/features/checkout/stage_map.dart';
import 'package:jomhor_fan/features/checkout/zone_pick.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';

import 'helpers.dart';

/// FL-34: screen 3's venue map (DESIGN.md §Venue map adjustments, docs/04-api.md §Venue map).
void main() {
  final now = DateTime.utc(2027, 2, 20, 12);

  /// Contract-first fixture: STADIUM, Nord on NORTH (a members price), Sud on SOUTH (sold out), Est on EAST, Honneur
  /// on WEST, the corners free.
  Map<String, Object?> stadium() => realAnswer('event_stadium', dir: 'goldens').body;
  EventDetail detail([void Function(Map<String, Object?> body)? change]) {
    final body = stadium();
    change?.call(body);
    return EventDetail.fromJson(body, fetchedAt: now);
  }

  List<Map<String, Object?>> zonesOf(Map<String, Object?> body) =>
      (body['zones']! as List<Object?>).cast<Map<String, Object?>>();

  Map<String, BlockState> states(({VenueLayout layout, List<MapBlock> blocks})? view) => {
    for (final b in view!.blocks) b.slot: b.state,
  };

  group('real run (FL-34, dev API)', () {
    Map<String, Object?> real(String name) => realAnswer(name, dir: 'venue_map').body;
    // The captures' `Date`.
    final captured = DateTime.utc(2026, 11, 17, 20, 29, 27);

    test('the golden fixture has the shape of the captured answers', () {
      expect(keyPaths(stadium()), keyPaths(real('event_mapped')));
      expect(keyPaths(real('event_unmapped')), keyPaths(real('event_mapped')));
    });

    test('ATLAS FC (dev seed STADIUM): the four stands on their slots, the corners inert', () {
      final d = EventDetail.fromJson(real('event_mapped'), fetchedAt: captured);
      expect(d.layout, 'STADIUM');
      final view = mapView(d, selectedZoneId: null, now: captured)!;
      expect(
        {for (final b in view.blocks) b.slot: b.zone?.code},
        {
          'NORTH_WEST': null,
          'NORTH': 'NORD',
          'NORTH_EAST': null,
          'WEST': 'HONNEUR',
          'EAST': 'EST',
          'SOUTH_WEST': null,
          'SOUTH': 'SUD',
          'SOUTH_EAST': null,
        },
      );
      expect(states(view).values.where((s) => s == BlockState.open), hasLength(4));
    });

    test('Olympique Tensift (unmapped): layout and every map_slot null, the list only', () {
      final d = EventDetail.fromJson(real('event_unmapped'), fetchedAt: captured);
      expect(d.layout, isNull);
      expect(d.zones.map((z) => z.mapSlot), everyElement(isNull));
      expect(mapView(d, selectedZoneId: null, now: captured), isNull);
    });
  });

  group('mapView', () {
    test('states: selectable, selected, sold out, inert; slots in the JSON order with the list colour index', () {
      final view = mapView(detail(), selectedZoneId: 3, now: now);
      expect(states(view), {
        'NORTH_WEST': BlockState.inert,
        'NORTH': BlockState.open,
        'NORTH_EAST': BlockState.inert,
        'WEST': BlockState.open,
        'EAST': BlockState.selected,
        'SOUTH_WEST': BlockState.inert,
        'SOUTH': BlockState.soldOut,
        'SOUTH_EAST': BlockState.inert,
      });
      expect(
        [for (final b in view!.blocks) (b.zone?.id, b.index)],
        [(null, -1), (1, 0), (null, -1), (4, 3), (3, 2), (null, -1), (2, 1), (null, -1)],
      );
      expect(view.blocks[1].zone!.hasMembersPrice, isTrue, reason: 'the members line');
      expect(view.blocks[1].rect, (80, 0, 240, 62));
    });

    test('sale not open: every mapped block disabled (sold out stays sold out)', () {
      final view = mapView(
        detail(
          (b) => b
            ..['on_sale'] = false
            ..['sale_starts_at'] = '2027-02-21T10:00:00Z',
        ),
        selectedZoneId: 3,
        now: now,
      );
      expect(states(view), containsPair('NORTH', BlockState.closed));
      expect(states(view), containsPair('EAST', BlockState.closed));
      expect(states(view), containsPair('WEST', BlockState.closed));
      expect(states(view), containsPair('SOUTH', BlockState.soldOut));
    });

    test('a room: disabled without a turn, selectable during it', () {
      final roomed = detail((b) => b['room'] = roomJson(status: 'OPEN'));
      expect(states(mapView(roomed, selectedZoneId: null, now: now)), containsPair('EAST', BlockState.closed));
      const waiting = QueuePass(queueToken: 'qt-1');
      expect(
        states(mapView(roomed, selectedZoneId: null, now: now, pass: waiting)),
        containsPair('EAST', BlockState.closed),
      );
      final turn = QueuePass(
        queueToken: 'qt-1',
        admissionToken: 'adm-1',
        admissionUntil: now.add(const Duration(minutes: 5)),
      );
      expect(
        states(mapView(roomed, selectedZoneId: null, now: now, pass: turn)),
        containsPair('EAST', BlockState.open),
      );
    });

    test('list only: null, unknown or unmapped layout; no zone on a known slot', () {
      expect(mapView(detail((b) => b['layout'] = null), selectedZoneId: null, now: now), isNull);
      expect(mapView(detail((b) => b['layout'] = 'ARENA'), selectedZoneId: null, now: now), isNull);
      expect(mapView(detail((b) => b.remove('layout')), selectedZoneId: null, now: now), isNull);
      for (final slot in [null, 'MOON']) {
        expect(
          mapView(detail((b) => zonesOf(b).forEach((z) => z['map_slot'] = slot)), selectedZoneId: null, now: now),
          isNull,
          reason: '$slot',
        );
      }
      // A slot of another layout is unknown here too.
      expect(
        mapView(detail((b) => zonesOf(b).forEach((z) => z['map_slot'] = 'ORCHESTRA')), selectedZoneId: null, now: now),
        isNull,
      );
    });

    test('an unknown slot leaves its zone in the list only; the others stay on the map', () {
      final view = mapView(detail((b) => zonesOf(b)[3]['map_slot'] = 'MOON'), selectedZoneId: null, now: now);
      expect(states(view), containsPair('WEST', BlockState.inert));
      expect(states(view), containsPair('EAST', BlockState.open));
      expect(view!.blocks.where((b) => b.zone?.id == 4), isEmpty);
    });
  });

  group('screen 3', () {
    Future<http.Response> backend(http.Request r) async => switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events/1') => json({...stadium(), 'id': 1}),
      ('GET', '/v1/fan/events') => json(<Object?>[]),
      _ => apiError(404, 'NOT_FOUND'),
    };

    Future<ProviderContainer> open(WidgetTester tester, {Locale locale = const Locale('fr')}) async {
      tester.view
        ..physicalSize = const Size(800, 2400)
        ..devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final c = await pumpJomhor(tester, backend: backend, tokens: MemoryTokenStore('jwt'), locale: locale);
      c.read(routerProvider).go('/events/1/zones');
      await tester.pumpAndSettle();
      return c;
    }

    testWidgets('the map sits above the rows; a block tap leaves the same state as the row tap', (tester) async {
      final c = await open(tester);
      expect(
        tester.getRect(find.byKey(const Key('map'))).bottom,
        lessThan(tester.getRect(find.byKey(const Key('zone.1'))).top),
      );
      ZonePickState pick() => c.read(zonePickProvider(1));

      await tester.tap(find.byKey(const Key('zone.3')));
      await tester.pumpAndSettle();
      final byRow = pick();
      expect((byRow.zone?.id, byRow.price?.priceCategoryId, byRow.qty), (3, 'pc-3-0', 1));

      // The "Nord" row moves the highlight to Nord.
      await tester.tap(find.byKey(const Key('zone.1')));
      await tester.pumpAndSettle();
      expect(tester.widget<StageMap>(find.byType(StageMap)).view.blocks[1].state, BlockState.selected);
      final nordByRow = pick();

      await tester.tap(find.byKey(const Key('map.EAST')));
      await tester.pumpAndSettle();
      expect(pick(), byRow);
      expect(tester.widget<StageMap>(find.byType(StageMap)).view.blocks[4].state, BlockState.selected);

      await tester.tap(find.byKey(const Key('map.NORTH')));
      await tester.pumpAndSettle();
      expect(pick(), nordByRow, reason: 'a members zone: Zone.firstOpen, the cheapest price anyone can pay');

      // Sold out: not tappable.
      await tester.tap(find.byKey(const Key('map.SOUTH')), warnIfMissed: false);
      await tester.pumpAndSettle();
      expect(pick(), nordByRow);
    });

    for (final locale in ['fr', 'ar']) {
      testWidgets('$locale semantics: the group, a button per block in the JSON order, selected state; inert absent', (
        tester,
      ) async {
        final semantics = tester.ensureSemantics();
        await open(tester, locale: Locale(locale));
        await tester.tap(find.byKey(const Key('map.EAST')));
        await tester.pumpAndSettle();
        final l = lookupAppLocalizations(Locale(locale));
        final from = l.chipFrom;

        final map = tester.getSemantics(find.byKey(const Key('map')));
        expect(map, isSemantics(label: l.venueMap));
        expect(
          [for (final n in map.debugListChildrenInOrder(DebugSemanticsDumpOrder.traversalOrder)) n.label],
          [
            l.mapBlock(l.mapBlock('Tribune Nord', from(l.dh(8000))), l.membersChip.toLowerCase()),
            l.mapBlock("Tribune d'honneur", from(l.dh(30000))),
            l.mapBlock('Tribune Est', from(l.dh(12000))),
            l.mapBlock('Virage Sud', l.soldOut.toLowerCase()),
          ],
        );
        if (locale == 'fr') {
          expect(
            map.debugListChildrenInOrder(DebugSemanticsDumpOrder.traversalOrder).first.label,
            'Tribune Nord, dès ${formatDh(8000)}, membres',
          );
        }
        expect(
          tester.getSemantics(find.byKey(const Key('map.EAST'))),
          isSemantics(isButton: true, isEnabled: true, isSelected: true, hasTapAction: true),
        );
        expect(
          tester.getSemantics(find.byKey(const Key('map.NORTH'))),
          isSemantics(isButton: true, isEnabled: true, isSelected: false, hasTapAction: true),
        );
        expect(
          tester.getSemantics(find.byKey(const Key('map.SOUTH'))),
          isSemantics(isButton: true, isEnabled: false, hasTapAction: false),
        );
        semantics.dispose();
      });
    }

    testWidgets('a zone with only its members price left shows that price (Zone.firstOpen) and "Membres"', (
      tester,
    ) async {
      final semantics = tester.ensureSemantics();
      Future<http.Response> membersOnly(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/events/1') => json(
          {...stadium(), 'id': 1}..update('zones', (zs) {
            final nord = (zs! as List<Object?>).first! as Map<String, Object?>;
            ((nord['prices']! as List<Object?>).first! as Map<String, Object?>)['remaining'] = 0;
            return zs;
          }),
        ),
        _ => apiError(404, 'NOT_FOUND'),
      };
      final c = await pumpJomhor(tester, backend: membersOnly, tokens: MemoryTokenStore('jwt'));
      c.read(routerProvider).go('/events/1/zones');
      await tester.pumpAndSettle();
      final north = find.byKey(const Key('map.NORTH'));
      expect(find.descendant(of: north, matching: find.text('dès ${formatDh(5000)}')), findsOneWidget);
      expect(find.descendant(of: north, matching: find.text('Membres')), findsOneWidget);
      expect(tester.getSemantics(north), isSemantics(label: 'Tribune Nord, dès ${formatDh(5000)}, membres'));
      await tester.tap(north);
      await tester.pumpAndSettle();
      expect(c.read(zonePickProvider(1)).price?.priceCategoryId, 'pc-1-1', reason: 'the price the line shows');
      semantics.dispose();
    });

    testWidgets('an unmapped venue: the list only', (tester) async {
      Future<http.Response> plain(http.Request r) async => switch ((r.method, r.url.path)) {
        ('GET', '/v1/fan/events/1') => json({...stadium(), 'id': 1, 'layout': null}),
        _ => apiError(404, 'NOT_FOUND'),
      };
      final c = await pumpJomhor(tester, backend: plain, tokens: MemoryTokenStore('jwt'));
      c.read(routerProvider).go('/events/1/zones');
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('zone.1')), findsOneWidget);
      expect(find.byType(StageMap), findsNothing);
    });
  });

  // Every layout at 320 px in AR, inside screen 3's 20 px padding (so edge to edge): no overflow, x counted from the
  // left (never mirrored), every block at least 44 x 44, three lines asked of each. Under 320 px the canvas stays
  // 320 wide and scrolls sideways. The centre label follows the Arabic label rule (no tracking, weight 700).
  for (final MapEntry(key: id, value: layout) in VenueLayouts.all.entries) {
    for (final screen in [320.0, 300.0]) {
      testWidgets('$id at ${screen.toInt()} px in ar: no overflow, not mirrored, blocks >= 44 x 44', (tester) async {
        tester.view
          ..physicalSize = Size(screen, 700)
          ..devicePixelRatio = 1;
        addTearDown(tester.view.reset);
        final d = EventDetail.fromJson({
          ...stadium(),
          'layout': id,
          'zones': [
            for (final (i, (slot, _)) in layout.slots.indexed)
              {
                ...zoneJson(i + 1, 'المدرجة رقم ${i + 1} الكبيرة', [(12000, 10)]),
                'map_slot': slot,
                'prices': [
                  {'price_category_id': 'pc-$i', 'name': 'x', 'price_mad': 12000, 'remaining': 10, 'audience': 'ALL'},
                  {
                    'price_category_id': 'pm-$i',
                    'name': 'y',
                    'price_mad': 9000,
                    'remaining': 10,
                    'audience': 'MEMBERS',
                  },
                ],
              },
          ],
        }, fetchedAt: now);
        final view = mapView(d, selectedZoneId: 1, now: now)!;
        await tester.pumpWidget(
          MaterialApp(
            locale: const Locale('ar'),
            supportedLocales: AppLocalizations.supportedLocales,
            localizationsDelegates: AppLocalizations.localizationsDelegates,
            home: Scaffold(
              body: ListView(
                padding: const EdgeInsets.all(JomhorSpace.xl),
                children: [StageMap(view: view, onTap: (_) {})],
              ),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
        expect(Directionality.of(tester.element(find.byType(StageMap))), TextDirection.rtl);
        final canvas = tester.getRect(find.byKey(const Key('map')));
        expect(canvas.width, 320, reason: 'edge to edge under 360 px, never narrower than 320');
        if (screen == 320) expect(canvas.left, 0);
        expect(find.byType(SingleChildScrollView), screen < 320 ? findsOneWidget : findsNothing);
        final ar = lookupAppLocalizations(const Locale('ar'));
        final label = layout.center == MapCenter.field ? ar.mapField : ar.mapStage;
        final centre = tester.widget<Text>(find.text(label));
        expect((centre.style!.letterSpacing, centre.style!.fontWeight), (0.0, FontWeight.w700));
        const scale = 320 / VenueLayouts.canvasW;
        for (final (slot, (x, y, _, _)) in layout.slots) {
          final r = tester.getRect(find.byKey(Key('map.$slot')));
          expect(r.left - canvas.left, closeTo(x * scale, 0.01), reason: '$slot from the left');
          expect(r.top - canvas.top, closeTo(y * scale, 0.01), reason: slot);
          expect(r.width, greaterThanOrEqualTo(JomhorSize.touchMin), reason: slot);
          expect(r.height, greaterThanOrEqualTo(JomhorSize.touchMin), reason: slot);
        }
      });
    }
  }
}
