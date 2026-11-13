import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/events/event_screen.dart';
import 'package:jomhor_fan/features/inbox/inbox.dart';
import 'package:jomhor_fan/features/inbox/inbox_screen.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

/// FL-22: screen 10 (`GET /fan/me/inbox`, one inbox across clubs) and the bell's unread dot (lot-4 Q121, Q122, Q129).
void main() {
  setUp(() => SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty());

  Future<http.Response> base(http.Request r) async => switch ((r.method, r.url.path)) {
    ('GET', '/v1/fan/events') => json([eventJson()]),
    ('GET', '/v1/fan/events/1') => json({
      ...eventJson(
        zones: [
          zoneJson(1, 'Tribune Nord', [(5000, 100)]),
        ],
      ),
      'payment_methods': ['CARD'],
      'cash_until': null,
    }),
    _ => apiError(404, 'NOT_FOUND'),
  };

  final twoClubs = [
    inboxJson(
      club: tensiftJson,
      title: 'Billetterie ouverte',
      body: 'Derby le 20 mars.',
      sentAt: '2027-02-20T11:55:00Z',
    ),
    inboxJson(
      id: 'c0000000-0000-4000-8000-000000000002',
      eventId: null,
      title: 'Merci pour la saison',
      body: 'Rendez-vous en août.',
      sentAt: '2027-02-18T09:00:00Z',
    ),
  ];

  Future<ProviderContainer> open(WidgetTester tester, PushBackend backend, {bool signedIn = true}) =>
      pumpJomhor(tester, backend: backend.handle, tokens: MemoryTokenStore(signedIn ? 'jwt' : null));

  Future<void> openInbox(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('home.bell')));
    await tester.pumpAndSettle();
  }

  final dot = find.byKey(const Key('home.bell.dot'));
  String location(ProviderContainer c) => c.read(routerProvider).state.uri.toString();

  testWidgets('rows from two clubs, newest first, each led by its club line (crest 20 + name)', (tester) async {
    final backend = PushBackend(base)..inbox = twoClubs;
    await open(tester, backend);
    await openInbox(tester);
    expect(find.byType(InboxScreen), findsOneWidget);
    for (final (id, club, title, ago) in [
      ('c0000000-0000-4000-8000-000000000001', 'Olympique Tensift', 'Billetterie ouverte', 'il y a 5 min'),
      ('c0000000-0000-4000-8000-000000000002', 'Atlas FC', 'Merci pour la saison', 'il y a 2 j'),
    ]) {
      final row = find.byKey(Key('inbox.row.$id'));
      final line = tester.widget<ClubLine>(find.descendant(of: row, matching: find.byType(ClubLine)));
      expect(line.name, club);
      expect(tester.widget<Crest>(find.descendant(of: row, matching: find.byType(Crest))).size, 20);
      // The club line comes first, then the title.
      expect(
        tester.getRect(find.descendant(of: row, matching: find.byType(ClubLine))).bottom,
        lessThanOrEqualTo(tester.getRect(find.descendant(of: row, matching: find.text(title))).top),
      );
      expect(find.descendant(of: row, matching: find.text(ago)), findsOneWidget);
    }
    expect(
      tester.getRect(find.text('Billetterie ouverte')).top,
      lessThan(tester.getRect(find.text('Merci pour la saison')).top),
    );
  });

  testWidgets('a row with event_id opens screen 2; without, nothing happens', (tester) async {
    final backend = PushBackend(base)..inbox = [inboxJson(), twoClubs[1]];
    final c = await open(tester, backend);
    await openInbox(tester);
    await tester.tap(find.text('Merci pour la saison'));
    await tester.pumpAndSettle();
    expect(location(c), '/inbox');
    await tester.tap(find.text('Ouverture des portes à 17 h'));
    await tester.pumpAndSettle();
    expect(find.byType(EventScreen), findsOneWidget);
    expect(location(c), '/events/1');
  });

  testWidgets('empty: "Rien pour l\'instant", no dot', (tester) async {
    await open(tester, PushBackend(base));
    expect(dot, findsNothing);
    await openInbox(tester);
    expect(find.text("Rien pour l'instant"), findsOneWidget);
  });

  testWidgets('an error: no dot, the error panel, retry loads (lot-4 Q129)', (tester) async {
    final backend = PushBackend(base)
      ..inbox = twoClubs
      ..inboxStatus = 502;
    await open(tester, backend);
    expect(dot, findsNothing);
    await openInbox(tester);
    expect(find.byType(ErrorPanel), findsOneWidget);
    backend.inboxStatus = null;
    await tester.tap(find.text('Réessayer'));
    await tester.pumpAndSettle();
    expect(find.text('Billetterie ouverte'), findsOneWidget);
  });

  testWidgets('the dot: newer than the last visit; opening clears it; one key for every club; wiped on sign-out', (
    tester,
  ) async {
    final backend = PushBackend(base)..inbox = twoClubs;
    final c = await open(tester, backend);
    expect(dot, findsOneWidget);
    expect(tester.widget<IconButton>(find.byKey(const Key('home.bell'))).tooltip, 'Notifications · nouveaux messages');
    expect(tester.getSize(dot), const Size(8, 8));

    await openInbox(tester);
    final prefs = SharedPreferencesAsync();
    expect(await prefs.getString(inboxSeenKey), '2027-02-20T11:55:00.000Z');
    expect({...await prefs.getKeys()}.where((k) => k.startsWith('inbox')), {inboxSeenKey});
    await tester.tap(find.byType(BackButton));
    await tester.pumpAndSettle();
    expect(dot, findsNothing);
    expect(tester.widget<IconButton>(find.byKey(const Key('home.bell'))).tooltip, 'Notifications');

    // A newer message from the other club: the same single key brings the dot back.
    backend.inbox = [
      inboxJson(id: 'c0000000-0000-4000-8000-000000000003', sentAt: '2027-02-20T11:59:00Z'),
      ...twoClubs,
    ];
    c.invalidate(inboxProvider);
    await tester.pumpAndSettle();
    expect(dot, findsOneWidget);

    await c.read(sessionProvider.notifier).signOut();
    await tester.pumpAndSettle();
    expect(await prefs.getString(inboxSeenKey), isNull);
  });

  testWidgets('signed out: no bell', (tester) async {
    final backend = PushBackend(base)..inbox = twoClubs;
    await open(tester, backend, signedIn: false);
    expect(find.byKey(const Key('home.bell')), findsNothing);
    expect(find.byKey(const Key('home.profile')), findsOneWidget);
    expect(backend.inboxReads, 0);
  });

  test('relative times', () {
    final l = lookupAppLocalizations(const Locale('fr'));
    final now = DateTime.utc(2027, 2, 20, 12);
    String ago(Duration d) => sentAgo(l, now, now.subtract(d));
    expect(ago(Duration.zero), "À l'instant");
    expect(ago(const Duration(seconds: -30)), "À l'instant"); // phone clock behind the server
    expect(ago(const Duration(minutes: 1)), 'il y a 1 min');
    expect(ago(const Duration(minutes: 59)), 'il y a 59 min');
    expect(ago(const Duration(hours: 3)), 'il y a 3 h');
    expect(ago(const Duration(hours: 30)), 'hier');
    expect(ago(const Duration(days: 4)), 'il y a 4 j');
    expect(
      sentAgo(lookupAppLocalizations(const Locale('ar')), now, now.subtract(const Duration(minutes: 5))),
      'هادي 5 دقايق',
    );
  });
}
