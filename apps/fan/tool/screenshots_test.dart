// Review screenshots of the fan app at 390 x 844 (2x), with the bundled fonts. Not part of `flutter test` (it lives
// in tool/); run it on demand:
//   flutter test tool/screenshots_test.dart --dart-define=SHOTS_DIR=<absolute output folder>
// PNGs are written there and never committed.
import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' show ImageByteFormat;

import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';

import '../test/helpers.dart';

const _dir = String.fromEnvironment('SHOTS_DIR', defaultValue: 'build/shots');

final _eventKey = Uint8List.fromList(List.filled(32, 0x11));

Map<String, Object?> _ticket(String id, {String startsAt = '2027-03-13T19:00:00Z', String status = 'VALID'}) {
  final idBytes = Uint8List.fromList([
    for (var i = 0; i < 32; i += 2) int.parse(id.replaceAll('-', '').substring(i, i + 2), radix: 16),
  ]);
  return {
    'id': id,
    'ticket_no': id.substring(id.length - 8).toUpperCase().replaceRange(4, 4, ' '),
    'status': status,
    'holder_label': 'Yazid A.',
    'seat_label': null,
    'event': {
      'id': 1,
      'title': 'Atlas FC vs Rapid Oued',
      'event_type': 'FOOTBALL_MATCH',
      'starts_at': startsAt,
      'doors_open_at': null,
      'timezone': 'Africa/Casablanca',
      'venue': {'name': "Stade de l'Atlas", 'city': 'Rabat'},
      'metadata': {'opponent': 'Rapid Oued', 'competition': 'Championnat', 'matchday': 11},
      'organizer': {...organizerBlock(clubJson()), 'id': testOrganizer},
    },
    'zone': {'id': 3, 'code': 'N', 'name': 'Tribune Nord'},
    'gate_codes': ['4'],
    if (status == 'VALID') 'k_t': base64Url.encode(deriveTicketKey(_eventKey, idBytes)),
  };
}

const _mine = 'aaaaaaaa-bbbb-4ccc-8ddd-7f3a91c2e001';

final List<Map<String, Object?>> _tickets = [
  _ticket(_mine),
  _ticket('aaaaaaaa-bbbb-4ccc-8ddd-0c55e17d4b9a', startsAt: '2027-03-27T20:00:00Z')
    ..['event'] = {
      ...(_ticket(_mine)['event']! as Map<String, Object?>),
      'id': 3,
      'title': 'Atlas FC vs Étoile de Fès',
      'starts_at': '2027-03-27T20:00:00Z',
    },
  _ticket('aaaaaaaa-bbbb-4ccc-8ddd-9e01b2c3d4e5', startsAt: '2027-02-20T19:00:00Z', status: 'CANCELLED')
    ..['event'] = {
      ...(_ticket(_mine)['event']! as Map<String, Object?>),
      'id': 9,
      'title': 'Atlas FC vs Union Tanger',
      'starts_at': '2027-02-20T19:00:00Z',
      'status': 'CLOSED',
    },
];

Future<http.Response> Function(http.Request) _backend(DateTime now) =>
    (r) async => switch (r.url.path) {
      '/v1/fan/events' => json([
        eventJson(),
        eventJson(
          id: 2,
          startsAt: '2027-03-20T18:00:00Z',
          onSale: false,
          metadata: {'opponent': 'Union Tanger', 'competition': 'Championnat'},
        ),
        eventJson(
          id: 3,
          startsAt: '2027-03-27T20:00:00Z',
          metadata: {'opponent': 'Étoile de Fès', 'competition': 'Championnat'},
        ),
        eventJson(
          id: 4,
          startsAt: '2027-04-04T19:00:00Z',
          fromPriceMad: 8000,
          metadata: {'opponent': 'Derby', 'competition': 'Coupe du Trône'},
        ),
      ]),
      '/v1/fan/events/1' => json(
        eventJson(
          zones: [
            zoneJson(1, 'Tribune Nord', [(5000, 2300)], seated: false),
            zoneJson(2, 'Latérale Est', [(12000, 860)]),
            zoneJson(3, "Tribune d'honneur", [(30000, 40)]),
            zoneJson(4, 'Tribune Sud', [(5000, 0)]),
          ],
        ),
      ),
      '/v1/fan/tickets' => json({'server_time': now.toIso8601String(), 'tickets': _tickets}),
      '/v1/fan/me' => json({'id': 'u1', 'phone': '+212612345678', 'display_name': 'Yazid Aqel', 'locale': 'fr'}),
      _ => json(<Object?>[]),
    };

Future<void> _loadFonts() async {
  Future<ByteData> bytes(String path) async => ByteData.sublistView(await File(path).readAsBytes());
  final fonts = {
    'Inter': ['Inter-Regular', 'Inter-Medium', 'Inter-SemiBold'],
    'Barlow Condensed': ['BarlowCondensed-SemiBold', 'BarlowCondensed-Bold', 'BarlowCondensed-ExtraBold'],
  };
  for (final MapEntry(key: family, value: files) in fonts.entries) {
    final loader = FontLoader(family);
    for (final f in files) {
      loader.addFont(bytes('assets/fonts/$f.ttf'));
    }
    await loader.load();
  }
  // flutter_tester lives in <flutter>/bin/cache/artifacts/engine/<platform>/.
  final artifacts = File(Platform.resolvedExecutable).parent.parent.parent.path;
  await (FontLoader('MaterialIcons')..addFont(bytes('$artifacts/material_fonts/MaterialIcons-Regular.otf'))).load();
}

Future<void> _shot(WidgetTester tester, String name) async {
  final view = tester.binding.renderViews.first;
  final layer = view.debugLayer! as OffsetLayer;
  await tester.runAsync(() async {
    final image = await layer.toImage(Offset.zero & tester.view.physicalSize);
    final png = await image.toByteData(format: ImageByteFormat.png);
    final file = File('$_dir/$name.png');
    await file.parent.create(recursive: true);
    await file.writeAsBytes(png!.buffer.asUint8List());
  });
}

Future<ProviderContainer> _app(WidgetTester tester, DateTime now, {bool signedIn = true}) {
  tester.view
    ..physicalSize = const Size(780, 1688)
    ..devicePixelRatio = 2;
  addTearDown(tester.view.reset);
  return pumpJomhor(
    tester,
    backend: _backend(now),
    clock: TestClock(now),
    tokens: signedIn ? MemoryTokenStore('jwt') : null,
  );
}

Future<void> _go(WidgetTester tester, ProviderContainer c, String path, [Object? extra]) async {
  unawaited(c.read(routerProvider).push(path, extra: extra));
  await tester.pumpAndSettle();
}

void main() {
  setUpAll(_loadFonts);
  // Two days before kickoff, then matchday (T0 - 2 h).
  final before = DateTime.utc(2027, 3, 11, 9, 41);
  final matchday = DateTime.utc(2027, 3, 13, 17, 2, 11);

  testWidgets('accueil', (tester) async {
    await _app(tester, before);
    await _shot(tester, 'accueil');
  });

  testWidgets('accueil-guest', (tester) async {
    await _app(tester, before, signedIn: false);
    await _shot(tester, 'accueil-guest');
  });

  testWidgets('billets', (tester) async {
    final c = await _app(tester, before);
    c.read(routerProvider).go('/tickets');
    await tester.pumpAndSettle();
    await _shot(tester, 'billets');
  });

  testWidgets('billet', (tester) async {
    final c = await _app(tester, before);
    await _go(tester, c, '/tickets/$_mine', PassOrigin.home.name);
    await tester.pump(const Duration(seconds: 2));
    await _shot(tester, 'billet');
  });

  testWidgets('billet-matchday', (tester) async {
    await _app(tester, matchday); // Matchday mode opens the ticket by itself.
    await tester.pump(const Duration(seconds: 2));
    await _shot(tester, 'billet-matchday');
  });

  testWidgets('profil', (tester) async {
    final c = await _app(tester, before);
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    await _shot(tester, 'profil');
  });

  testWidgets('match', (tester) async {
    final c = await _app(tester, before);
    await _go(tester, c, '/events/1');
    await _shot(tester, 'match');
  });

  testWidgets('login', (tester) async {
    final c = await _app(tester, before, signedIn: false);
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    await _shot(tester, 'login');
  });
}
