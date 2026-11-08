import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'cash_order_test.dart' show orderId;
import 'helpers.dart';

/// FL-23: the club picker (screen 11) and the follows kept on the phone.
void main() {
  final requests = <http.Request>[];
  setUp(() {
    requests.clear();
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
  });

  Future<http.Response> Function(http.Request) backend({
    List<Map<String, Object?>>? clubs,
    bool directoryFails = false,
  }) => (r) async {
    requests.add(r);
    return switch (r.url.path) {
      '/v1/fan/organizers' when directoryFails => throw http.ClientException('offline'),
      '/v1/fan/organizers' => json(clubs ?? [clubJson(), tensiftJson]),
      '/v1/fan/events' => json([eventJson()]),
      _ => apiError(404, 'NOT_FOUND'),
    };
  };

  const firstLaunch = noFollows;
  String location(ProviderContainer c) => c.read(routerProvider).state.uri.path;

  testWidgets('first launch (no "seen" flag) opens the picker: lockup, title, a card per club, no token sent', (
    tester,
  ) async {
    final c = await pumpJomhor(tester, backend: backend(), follows: firstLaunch, tokens: MemoryTokenStore('jwt'));
    expect(location(c), '/clubs');
    expect(find.byType(JomhorLockup), findsOneWidget);
    expect(find.text('Tes clubs'), findsOneWidget);
    expect(find.text('Atlas FC'), findsOneWidget);
    expect(find.text('Olympique Tensift'), findsOneWidget);
    expect(find.text('Club sportif'), findsNWidgets(2));
    expect(find.byKey(const Key('clubs.back')), findsNothing);
    expect(find.byKey(const Key('clubs.search')), findsNothing); // 2 clubs: no search
    // "Continuer" from one follow.
    expect(tester.widget<FilledButton>(find.byKey(const Key('clubs.continue'))).onPressed, isNull);
    final directory = requests.singleWhere((r) => r.url.path == '/v1/fan/organizers');
    expect(directory.headers.containsKey('Authorization'), isFalse);
    expect(requests.where((r) => r.url.path == '/v1/fan/events'), isEmpty);
  });

  testWidgets('"Tout voir": the flag is saved, Accueil opens on Tout with no follows', (tester) async {
    final c = await pumpJomhor(tester, backend: backend(), follows: firstLaunch);
    await tester.tap(find.byKey(const Key('clubs.seeAll')));
    await tester.pumpAndSettle();
    expect(location(c), '/');
    expect(find.byKey(const Key('home.chip.all')), findsOneWidget);
    expect(find.byKey(const Key('home.chip.mine')), findsNothing);
    final saved = await savedFollows(SharedPreferencesAsync());
    expect(saved.ids, isEmpty);
    expect(saved.seen, isTrue);
    expect(requests.lastWhere((r) => r.url.path == '/v1/fan/events').url.hasQuery, isFalse);
  });

  testWidgets('"Suivre" then "Continuer": the follow is on the phone and the next launch reads it', (tester) async {
    final c = await pumpJomhor(tester, backend: backend(), follows: firstLaunch);
    await tester.tap(find.byKey(const Key('club.$otherOrganizer')));
    await tester.pumpAndSettle();
    expect(find.text('Suivi'), findsOneWidget);
    expect(find.text('Suivre'), findsOneWidget);
    await tester.tap(find.byKey(const Key('clubs.continue')));
    await tester.pumpAndSettle();
    expect(location(c), '/');

    final saved = await savedFollows(SharedPreferencesAsync());
    expect(saved.ids, [otherOrganizer]);
    expect(saved.seen, isTrue);
    // Next launch: what main reads from the phone. No picker, `Mes clubs` with Tensift.
    final again = await pumpJomhor(tester, backend: backend(), follows: saved);
    expect(location(again), '/');
    expect(find.byKey(const Key('home.chip.mine')), findsOneWidget);
    expect(find.byKey(const Key('home.chip.$otherOrganizer')), findsOneWidget);
    expect(requests.last.url.queryParametersAll['organizer'], [otherOrganizer]);
  });

  testWidgets('a deep link on first launch is not intercepted by the picker', (tester) async {
    final c = await pumpJomhor(tester, backend: backend(), follows: firstLaunch, tokens: MemoryTokenStore('jwt'));
    c.read(routerProvider).go('jomhor-fan://orders/$orderId');
    await tester.pumpAndSettle();
    expect(location(c), '/orders/$orderId');
    c.read(routerProvider).go('/events/1');
    await tester.pumpAndSettle();
    expect(location(c), '/events/1');
  });

  testWidgets('Profil → "Mes clubs" opens the picker with back and "Terminé", no "Tout voir"', (tester) async {
    final c = await pumpJomhor(
      tester,
      backend: backend(),
      tokens: MemoryTokenStore('jwt'),
      follows: (ids: const [testOrganizer], seen: true, chip: null),
    );
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('profile.clubs')));
    expect(tester.widget<Text>(find.byKey(const Key('profile.clubs.count'))).data, '1');
    await tester.tap(find.byKey(const Key('profile.clubs')));
    await tester.pumpAndSettle();
    expect(location(c), '/clubs');
    expect(find.byKey(const Key('clubs.back')), findsOneWidget);
    expect(find.byKey(const Key('clubs.seeAll')), findsNothing);
    expect(find.byKey(const Key('clubs.continue')), findsNothing);
    expect(find.text('Suivi'), findsOneWidget);

    await tester.tap(find.byKey(const Key('club.$otherOrganizer')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('clubs.done')));
    await tester.pumpAndSettle();
    expect(location(c), '/profile');
    expect(tester.widget<Text>(find.byKey(const Key('profile.clubs.count'))).data, '2');
  });

  testWidgets('more than 8 clubs: a search field, accent-insensitive', (tester) async {
    tester.view.physicalSize = const Size(390, 2400);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    final clubs = [
      for (final (i, name) in [
        'Étoile de Fès',
        'Raja',
        'Wydad',
        'Fath',
        'Hassania',
        'Ittihad',
        'Kawkab',
        'Maghreb',
      ].indexed)
        clubJson(id: 'club-$i', slug: 'club-$i', name: name),
      tensiftJson,
    ];
    await pumpJomhor(
      tester,
      backend: backend(clubs: clubs),
      follows: firstLaunch,
    );
    expect(find.byKey(const Key('clubs.search')), findsOneWidget);
    await tester.enterText(find.byKey(const Key('clubs.search')), 'Etoile');
    await tester.pumpAndSettle();
    expect(find.text('Étoile de Fès'), findsOneWidget);
    expect(find.text('Raja'), findsNothing);

    await tester.enterText(find.byKey(const Key('clubs.search')), 'zzz');
    await tester.pumpAndSettle();
    expect(find.text('Aucun club ne correspond.'), findsOneWidget);
  });

  test('search folds case and accents', () {
    const clubs = [Club(id: '1', name: 'Étoile de Fès'), Club(id: '2', name: 'Olympique Tensift')];
    expect(searchClubs(clubs, 'etoile').map((c) => c.id), ['1']);
    expect(searchClubs(clubs, ' FES ').map((c) => c.id), ['1']);
    expect(searchClubs(clubs, ''), clubs);
  });

  testWidgets('followed ids missing from the directory are dropped, on screen and on the phone', (tester) async {
    await pumpJomhor(
      tester,
      backend: backend(clubs: [clubJson()]),
      follows: (ids: const [otherOrganizer, testOrganizer], seen: true, chip: null),
    );
    expect(find.byKey(const Key('home.chip.$otherOrganizer')), findsNothing);
    expect(find.byKey(const Key('home.chip.$testOrganizer')), findsOneWidget);
    expect(requests.last.url.queryParametersAll['organizer'], [testOrganizer]);
    expect((await savedFollows(SharedPreferencesAsync())).ids, [testOrganizer]);
  });

  testWidgets('a club listed after launch, followed from a `Tout` card, is kept: the directory is read again first', (
    tester,
  ) async {
    var directoryReads = 0;
    await pumpJomhor(
      tester,
      backend: (r) async {
        requests.add(r);
        return switch (r.url.path) {
          // Launch read: Tensift is not listed yet; every later read lists it.
          '/v1/fan/organizers' => json(++directoryReads == 1 ? [clubJson()] : [clubJson(), tensiftJson]),
          '/v1/fan/events' => json([eventJson(), eventJson(id: 2, club: tensiftJson)]),
          _ => apiError(404, 'NOT_FOUND'),
        };
      },
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
    );
    expect(directoryReads, 1);
    await tester.scrollUntilVisible(
      find.byKey(const Key('home.follow.2')),
      200,
      scrollable: find.byType(Scrollable).first,
    );
    await tester.tap(find.byKey(const Key('home.follow.2')));
    await tester.pumpAndSettle();
    expect(directoryReads, 2);
    expect((await savedFollows(SharedPreferencesAsync())).ids, [testOrganizer, otherOrganizer]);
    await tester.scrollUntilVisible(
      find.byKey(const Key('home.chip.$otherOrganizer')),
      -200,
      scrollable: find.byType(Scrollable).first,
    );
  });

  testWidgets('pull-to-refresh retries a failed directory read', (tester) async {
    var directoryFails = true;
    await pumpJomhor(
      tester,
      backend: (r) async {
        requests.add(r);
        return switch (r.url.path) {
          '/v1/fan/organizers' when directoryFails => throw http.ClientException('offline'),
          '/v1/fan/organizers' => json([clubJson()]),
          '/v1/fan/events' => json([eventJson()]),
          _ => apiError(404, 'NOT_FOUND'),
        };
      },
      follows: (ids: const [testOrganizer], seen: true, chip: null),
    );
    expect(find.text('Pas de connexion. Vérifie ton réseau et réessaie.'), findsOneWidget);
    directoryFails = false;
    await tester.fling(find.byType(Scrollable).first, const Offset(0, 400), 1000);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('hero.cta')), findsOneWidget);
    expect(find.byKey(const Key('home.chip.$testOrganizer')), findsOneWidget);
    expect(requests.last.url.queryParametersAll['organizer'], [testOrganizer]);
  });

  testWidgets('a directory failure shows the error and "Réessayer"; "Tout voir" still works', (tester) async {
    final c = await pumpJomhor(tester, backend: backend(directoryFails: true), follows: firstLaunch);
    expect(find.text('Pas de connexion. Vérifie ton réseau et réessaie.'), findsOneWidget);
    expect(find.text('Réessayer'), findsOneWidget);
    await tester.tap(find.byKey(const Key('clubs.seeAll')));
    await tester.pumpAndSettle();
    expect(location(c), '/');
  });

  testWidgets('a crest that fails to load (offline) falls back to the stand-in letter', (tester) async {
    await pumpJomhor(
      tester,
      backend: backend(
        clubs: [
          clubJson(crestUrl: 'https://assets.jomhor.ma/crests/atlas.png'),
          tensiftJson,
        ],
      ),
      follows: firstLaunch,
    );
    // flutter_test answers every image request with HTTP 400: the errorBuilder draws the letter.
    expect(find.descendant(of: find.byKey(const Key('club.$testOrganizer')), matching: find.text('A')), findsOneWidget);
    expect(
      find.descendant(of: find.byKey(const Key('club.$otherOrganizer')), matching: find.text('O')),
      findsOneWidget,
    );
  });

  test('crest_url: https only, else the stand-in; the stand-in letter is the first letter', () {
    expect(Club.fromJson(clubJson(crestUrl: 'http://x/c.png')).crestUrl, isNull);
    expect(Club.fromJson(clubJson(crestUrl: 'https://assets.jomhor.ma/crests/a.png')).crestUrl, isNotNull);
    expect(crestInitial('Olympique Tensift'), 'O');
    expect(crestInitial('  «Raja»'), 'R');
    expect(crestInitial('الوداد'), 'ا');
  });
}
