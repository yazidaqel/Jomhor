import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/club/club_repository.dart';
import 'package:jomhor_fan/features/club/join_sheet.dart';
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

/// `member` of `GET /fan/me/membership` (docs/04-api.md §Fan); `paid_until` since Lot 5 step 3 (null = never paid).
Map<String, Object?> memberJson({int number = 42, bool push = false, bool whatsapp = false, String? paidUntil}) => {
  'number': number,
  'joined_at': '2026-11-03T10:00:00.123456Z',
  'marketing_push': push,
  'marketing_whatsapp': whatsapp,
  'paid_until': paidUntil,
};

/// The club's PASS product and its MEMBERSHIP product (design content, test/fixtures/goldens/club/products_200.json),
/// and the order the membership purchase opens.
const passProductId = 'b1000000-0000-4000-8000-000000000001';
const duesProductId = 'b1000000-0000-4000-8000-000000000002';
const duesOrderId = 'd1000000-0000-4000-8000-000000000002';

/// An answer of test/fixtures/club (captured from the dev API, FL-29/FL-30 real run), as `(status, body)`. [dir]
/// `goldens/club`: the design content the goldens and the screen tests use, in the captured answer's shape.
({int status, Object? body}) clubAnswer(String name, {String dir = 'club'}) {
  final j = jsonDecode(File('test/fixtures/$dir/$name.json').readAsStringSync()) as Map<String, Object?>;
  return (status: j['status']! as int, body: j['body']);
}

/// `GET /fan/products?organizer=` for Atlas FC: the pass and the membership, both on sale.
List<Object?> clubProducts() => clubAnswer('products_200', dir: 'goldens/club').body! as List<Object?>;

/// A `last_events` row; ids from 101, two weeks apart, oldest first.
Map<String, Object?> pastJson(int id, {bool attended = true, String eventType = 'FOOTBALL_MATCH'}) => {
  'event_id': id,
  'title': 'Atlas FC vs Club $id',
  'event_type': eventType,
  'starts_at': DateTime.utc(2026, 7, 4, 19).add(Duration(days: 14 * (id - 101))).toIso8601String(),
  'timezone': 'Africa/Casablanca',
  'attended': attended,
};

/// 11 closed events, 7 attended (the 2nd, 5th, 8th and 10th missed).
final List<Map<String, Object?>> elevenPast = [
  for (var id = 101; id <= 111; id++) pastJson(id, attended: !{102, 105, 108, 110}.contains(id)),
];

/// `loyalty`: Fidèle with 8 events attended, Pilier at 10.
Map<String, Object?> loyaltyJson({
  int attended = 8,
  List<Map<String, Object?>>? last,
  bool topTier = false,
  String tier = 'Fidèle',
  String nextTier = 'Pilier',
}) => {
  'events_attended': attended,
  'tier': topTier ? {'code': 'T3', 'name': nextTier} : {'code': 'T2', 'name': tier},
  'next_tier': topTier ? null : {'code': 'T3', 'name': nextTier, 'min_events': 10},
  'last_events': last ?? elevenPast,
};

/// The fan backend of these tests: memberships per club, kept across calls (PUT joins, DELETE leaves).
class ClubBackend {
  new({Map<String, Map<String, Object?>?>? members, Map<String, Map<String, Object?>>? loyalty})
    : members = members ?? {},
      loyalty = loyalty ?? {};

  final Map<String, Map<String, Object?>?> members;
  final Map<String, Map<String, Object?>> loyalty;
  final List<http.Request> requests = [];
  bool putFails = false;

  /// Lot 5 step 3: the club sells a membership (`paid_required`).
  bool paidRequired = false;

  /// `GET /fan/products` rows; null = 404.
  List<Object?>? products;

  /// Queued answers of `POST /fan/product-orders` (default: the design membership's 201).
  final productOrders = <({int status, Object? body})>[];

  /// `/fan/me` `display_name`.
  String? displayName = 'Yazid Aqel';

  /// A left member's `paid_until`, kept on rejoin (docs/04-api.md: leaving keeps it).
  String? keptPaidUntil;

  /// `POST /fan/product-orders` answers after this.
  Duration orderDelay = Duration.zero;

  /// The `Date` of `GET /fan/products` (server time); null = none sent.
  DateTime? serverDate;

  Iterable<http.Request> calls(String method, String path) =>
      requests.where((r) => r.method == method && r.url.path == '/v1$path');

  Map<String, Object?> _membership(String org) => {
    'member': members[org],
    'loyalty': loyalty[org] ?? loyaltyJson(),
    'paid_required': paidRequired,
  };

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    final org = r.url.queryParameters['organizer'];
    switch ((r.method, r.url.path)) {
      case ('GET', '/v1/fan/organizers'):
        return json([clubJson(), tensiftJson]);
      case ('GET', '/v1/fan/events'):
        return json([
          eventJson(),
          eventJson(id: 3, title: 'Olympique Tensift vs Union Rif', club: tensiftJson),
          eventJson(id: 2, title: 'Atlas FC vs Union Sahel', startsAt: '2027-04-03T19:00:00Z'),
        ]);
      case ('GET', '/v1/fan/me/membership'):
        return json(_membership(org!));
      case ('PUT', '/v1/fan/me/membership'):
        if (putFails) {
          await Future<void>.delayed(const Duration(milliseconds: 100));
          return apiError(500, 'INTERNAL');
        }
        final body = jsonDecode(r.body) as Map<String, Object?>;
        final id = body['organizer_id']! as String;
        final joined = members[id] == null;
        members[id] = {
          ...memberJson(paidUntil: joined ? keptPaidUntil : null),
          ...?members[id],
          'marketing_push': body['marketing_push'],
          'marketing_whatsapp': body['marketing_whatsapp'],
        };
        return json(_membership(id), joined ? 201 : 200);
      case ('DELETE', '/v1/fan/me/membership'):
        members[org!] = null;
        return http.Response('', 204);
      case ('GET', '/v1/fan/tickets'):
        return json({'server_time': '2027-02-20T12:00:00Z', 'tickets': <Object?>[]});
      case ('GET', '/v1/fan/orders'):
        return json({'orders': <Object?>[]});
      case ('GET', '/v1/fan/me'):
        return json({'id': 'u1', 'phone': '+212600000001', 'display_name': displayName, 'locale': 'fr'});
      case ('GET', '/v1/fan/products'):
        if (products == null) return apiError(404, 'NOT_FOUND');
        final res = json(products);
        return serverDate == null
            ? res
            : http.Response.bytes(res.bodyBytes, 200, headers: {...res.headers, 'date': HttpDate.format(serverDate!)});
      case ('POST', '/v1/fan/product-orders'):
        final (:status, :body) = productOrders.isEmpty
            ? clubAnswer('product_order_201_membership', dir: 'goldens/club')
            : productOrders.removeAt(0);
        await Future<void>.delayed(orderDelay);
        return json(body, status);
      default:
        return apiError(404, 'NOT_FOUND');
    }
  }
}

/// Followed clubs A (Atlas FC) then B (Olympique Tensift); Accueil on `Tout`, so its own events read names no club.
const FollowsState followsAB = (ids: [testOrganizer, otherOrganizer], seen: true, chip: chipAll);

/// FL-20: screen 7, the Club tab, and the Accueil member strip.
void main() {
  setUp(() => SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty());

  /// A 390 px phone, tall enough to show the whole tab without scrolling.
  void tallView(WidgetTester tester, {double width = JomhorSize.phoneWidth}) {
    tester.view
      ..physicalSize = Size(width, 1600)
      ..devicePixelRatio = 1;
    addTearDown(tester.view.reset);
  }

  Future<ProviderContainer> open(
    WidgetTester tester,
    ClubBackend backend, {
    FollowsState follows = followsAB,
    String path = '/club',
    double width = JomhorSize.phoneWidth,
  }) async {
    tallView(tester, width: width);
    final c = await pumpJomhor(tester, backend: backend.handle, tokens: MemoryTokenStore('jwt'), follows: follows);
    c.read(routerProvider).go(path);
    await tester.pumpAndSettle();
    return c;
  }

  /// Pumps frame by frame until [finder] shows, so a first-reveal animation can be caught mid-way.
  Future<void> pumpUntil(WidgetTester tester, Finder finder) async {
    for (var i = 0; i < 50 && finder.evaluate().isEmpty; i++) {
      await tester.pump(const Duration(milliseconds: 10));
    }
    expect(finder, findsOneWidget);
  }

  /// The switcher between a club's loading, join and member cards.
  AnimatedSwitcher switcherOf(WidgetTester tester, String org) => tester.widget<AnimatedSwitcher>(
    find.ancestor(of: find.byKey(Key('club.card.$org')), matching: find.byType(AnimatedSwitcher)).first,
  );

  int dots(String kind) => find
      .byWidgetPredicate(
        (w) => w.key is ValueKey<String> && (w.key! as ValueKey<String>).value.startsWith('club.dot.$kind'),
      )
      .evaluate()
      .length;

  Future<Set<String>> seen() async => {...?await SharedPreferencesAsync().getStringList('club.seen')};

  testWidgets('follows A, B: two cards in follow order, one membership read per club, one events read for both', (
    tester,
  ) async {
    final backend = ClubBackend(members: {testOrganizer: memberJson(), otherOrganizer: memberJson(number: 7)});
    await open(tester, backend);
    final a = tester.getRect(find.byKey(const Key('club.card.$testOrganizer')));
    final b = tester.getRect(find.byKey(const Key('club.card.$otherOrganizer')));
    expect(a.left, lessThan(b.left));
    expect(b.left, lessThan(tester.getRect(find.byKey(const Key('club.pager'))).right)); // the next card peeks
    expect(find.text('Tes clubs'), findsOneWidget);
    for (final org in [testOrganizer, otherOrganizer]) {
      expect(
        backend.calls('GET', '/fan/me/membership').where((r) => r.url.queryParameters['organizer'] == org),
        hasLength(1),
      );
    }
    final events = backend.calls('GET', '/fan/events').where((r) => r.url.hasQuery);
    expect(events, hasLength(1));
    expect(events.single.url.queryParametersAll['organizer'], [testOrganizer, otherOrganizer]);
    expect(events.single.headers.containsKey('Authorization'), isFalse);
    // The twelfth dot's caption: A's first upcoming event.
    expect(find.byKey(const Key('club.next.$testOrganizer')), findsOneWidget);
    expect(find.text('Prochain · Sam. 13 mars'), findsOneWidget);
  });

  testWidgets('each card carries its club as crest + name on the Jomhor amber band, never a club colour', (
    tester,
  ) async {
    await open(tester, ClubBackend(members: {testOrganizer: memberJson(), otherOrganizer: memberJson(number: 7)}));
    expect(find.byKey(const Key('club.crest.$testOrganizer')), findsOneWidget);
    expect(find.byKey(const Key('club.crest.$otherOrganizer')), findsOneWidget);
    expect(find.text('ATLAS FC'), findsOneWidget);
    expect(find.text('OLYMPIQUE TENSIFT'), findsOneWidget);
    final bands = tester.widgetList<ColoredBox>(find.byKey(const Key('ticket.band')));
    expect(bands, hasLength(2));
    expect(bands.map((b) => b.color), everyElement(JomhorBrand.amber));
  });

  testWidgets('no follows: "Choisis tes clubs" → screen 11', (tester) async {
    final backend = ClubBackend();
    final c = await open(tester, backend, follows: (ids: const [], seen: true, chip: null));
    expect(find.text('Choisis tes clubs'), findsOneWidget);
    expect(backend.calls('GET', '/fan/me/membership'), isEmpty);
    await tester.tap(find.byKey(const Key('club.pickClubs')));
    await tester.pumpAndSettle();
    expect(c.read(routerProvider).state.uri.path, '/clubs');
  });

  testWidgets('join: switches off by default, PUT with exactly three keys, 201 → the card rises', (tester) async {
    final backend = ClubBackend();
    await open(tester, backend, follows: (ids: const [testOrganizer], seen: true, chip: chipAll));
    expect(find.byKey(const Key('club.joinCard.$testOrganizer')), findsOneWidget);
    expect(find.text('Rejoindre le club · gratuit'), findsOneWidget);
    expect(find.byKey(const Key('club.leave')), findsNothing);
    // Loyalty shows to non-members too.
    expect(find.byKey(const Key('club.dot.next')), findsOneWidget);

    await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
    await tester.pumpAndSettle();
    expect(find.text('Rejoindre Atlas FC'), findsOneWidget);
    expect(find.byKey(const Key('club.joinDues')), findsNothing); // the club sells no membership
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('club.optin.push'))).value, isFalse);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('club.optin.whatsapp'))).value, isFalse);
    await tester.tap(find.byKey(const Key('club.optin.push')));
    await tester.pump();
    await tester.tap(find.byKey(const Key('club.joinConfirm')));
    await tester.pump();
    await tester.pump();
    await pumpUntil(tester, find.byKey(const Key('club.card.$testOrganizer')));
    final put = backend.calls('PUT', '/fan/me/membership').single;
    expect(jsonDecode(put.body), {'organizer_id': testOrganizer, 'marketing_push': true, 'marketing_whatsapp': false});
    // Mid-rise: the card is on its way up (420 ms, join → member only), the number rolling.
    expect(find.byKey(const Key('club.number.roll')), findsOneWidget);
    expect(switcherOf(tester, testOrganizer).duration, const Duration(milliseconds: JomhorMotion.ticketFlip));
    // The PUT answer is the new state: no second read.
    expect(backend.calls('GET', '/fan/me/membership'), hasLength(1));
    await tester.pumpAndSettle();
    expect(find.text('000042'), findsOneWidget);
    expect(find.byKey(const Key('club.joinCard.$testOrganizer')), findsNothing);
    expect(find.byKey(const Key('club.leave')), findsOneWidget);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('club.optin.push'))).value, isTrue);
  });

  testWidgets('the number is padded to 6 and rolls once: not on reopen, not after a relaunch', (tester) async {
    final backend = ClubBackend(members: {testOrganizer: memberJson()});
    const follows = (ids: [testOrganizer], seen: true, chip: chipAll);
    final c = await pumpJomhor(tester, backend: backend.handle, tokens: MemoryTokenStore('jwt'), follows: follows);
    c.read(routerProvider).go('/club');
    await pumpUntil(tester, find.byKey(const Key('club.number.roll')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('club.number.roll')), findsNothing);
    expect(tester.widget<Text>(find.byKey(const Key('club.number'))).data, '000042');
    expect(tester.widget<Text>(find.byKey(const Key('club.number'))).textDirection, TextDirection.ltr);
    expect(await seen(), contains(testOrganizer));

    // Reopen: Accueil, then the tab again.
    c.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    c.read(routerProvider).go('/club');
    await tester.pump();
    expect(find.byKey(const Key('club.number.roll')), findsNothing);
    await tester.pumpAndSettle();

    // Relaunch: a new app on the same phone.
    await tester.pumpWidget(const SizedBox());
    final again = await pumpJomhor(tester, backend: backend.handle, tokens: MemoryTokenStore('jwt'), follows: follows);
    again.read(routerProvider).go('/club');
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 10));
      expect(find.byKey(const Key('club.number.roll')), findsNothing);
    }
    await tester.pumpAndSettle();
    expect(find.text('000042'), findsOneWidget);
  });

  testWidgets('reduced motion: no roll and no fill, and both count as seen', (tester) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    final backend = ClubBackend(members: {testOrganizer: memberJson()});
    final c = await pumpJomhor(
      tester,
      backend: backend.handle,
      tokens: MemoryTokenStore('jwt'),
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
    );
    c.read(routerProvider).go('/club');
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 10));
      expect(find.byKey(const Key('club.number.roll')), findsNothing);
      expect(find.byKey(const Key('club.dot.filling')), findsNothing);
    }
    await tester.pumpAndSettle();
    expect(find.text('000042'), findsOneWidget);
    expect(await seen(), containsAll([testOrganizer, '$testOrganizer:101']));
  });

  testWidgets('twelve dots: 11 closed events (7 attended, 4 missed) + the next one in accent', (tester) async {
    await open(
      tester,
      ClubBackend(members: {testOrganizer: memberJson()}),
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
    );
    expect((dots('attended'), dots('missed'), dots('placeholder'), dots('next')), (7, 4, 0, 1));
    expect(find.byKey(const Key('club.dot.missed.102')), findsOneWidget);
    expect(find.byKey(const Key('club.dot.attended.111')), findsOneWidget);
    final next = tester.widget<Container>(
      find.descendant(of: find.byKey(const Key('club.dot.next')), matching: find.byType(Container)),
    );
    expect((next.decoration! as BoxDecoration).color, JomhorColors.accent);
    // Oldest at the start, the twelfth at the end (reading direction).
    expect(
      tester.getCenter(find.byKey(const Key('club.dot.attended.101'))).dx,
      lessThan(tester.getCenter(find.byKey(const Key('club.dot.next'))).dx),
    );
    expect(
      tester.getSemantics(find.byKey(const Key('club.season.$testOrganizer'))).label,
      '7 sur 11 · prochain : Atlas FC vs Rapid Oued',
    );
    expect(find.text('8 matchs au stade'), findsOneWidget);
    expect(find.text('Encore 2 pour Pilier'), findsOneWidget);
    // "Derniers": the 3 newest, newest first, attended or not.
    final rows = [111, 110, 109].map((id) => tester.getTopLeft(find.byKey(Key('club.past.$id'))).dy).toList();
    expect(rows, orderedEquals([...rows]..sort()));
    expect(find.byKey(const Key('club.past.108')), findsNothing);
    expect(find.text("J'y étais"), findsNWidgets(2));
    expect(find.text('Manqué'), findsOneWidget);
  });

  testWidgets('4 closed events: 7 faint placeholders on the oldest side; top tier: no progress line', (tester) async {
    await open(
      tester,
      ClubBackend(
        members: {testOrganizer: memberJson()},
        loyalty: {
          testOrganizer: loyaltyJson(
            attended: 12,
            topTier: true,
            last: [for (var id = 101; id <= 104; id++) pastJson(id, attended: id != 103)],
          ),
        },
      ),
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
    );
    expect((dots('attended'), dots('missed'), dots('placeholder'), dots('next')), (3, 1, 7, 1));
    expect(
      tester.getCenter(find.byKey(const Key('club.dot.placeholder.6'))).dx,
      lessThan(tester.getCenter(find.byKey(const Key('club.dot.attended.101'))).dx),
    );
    expect(find.byKey(const Key('club.toNext.$testOrganizer')), findsNothing);
    expect(find.text('PILIER'), findsOneWidget);
  });

  testWidgets('a newly attended dot fills once', (tester) async {
    // This phone already showed the number and the dots of 101 to 109 (110 was missed then).
    await SharedPreferencesAsync().setStringList('club.seen', [
      testOrganizer,
      for (var id = 101; id <= 109; id++) '$testOrganizer:$id',
    ]);
    final backend = ClubBackend(members: {testOrganizer: memberJson()});
    const follows = (ids: [testOrganizer], seen: true, chip: chipAll);
    final c = await pumpJomhor(tester, backend: backend.handle, tokens: MemoryTokenStore('jwt'), follows: follows);
    c.read(routerProvider).go('/club');
    await pumpUntil(tester, find.byKey(const Key('club.dot.filling')));
    // Only 111: the attended ones before it were shown filled already.
    expect(
      find.descendant(
        of: find.byKey(const Key('club.dot.attended.111')),
        matching: find.byKey(const Key('club.dot.filling')),
      ),
      findsOneWidget,
    );
    expect(find.byKey(const Key('club.number.roll')), findsNothing);
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('club.dot.filling')), findsNothing);
    expect(await seen(), contains('$testOrganizer:111'));

    await tester.pumpWidget(const SizedBox());
    final again = await pumpJomhor(tester, backend: backend.handle, tokens: MemoryTokenStore('jwt'), follows: follows);
    again.read(routerProvider).go('/club');
    for (var i = 0; i < 30; i++) {
      await tester.pump(const Duration(milliseconds: 10));
      expect(find.byKey(const Key('club.dot.filling')), findsNothing);
    }
    await tester.pumpAndSettle();
  });

  test('"matchs" vs "événements" (lot-4 Q92)', () {
    final sports = Club.fromJson(clubJson());
    final venue = Club.fromJson(clubJson(kind: 'VENUE'));
    Loyalty loyalty(List<Map<String, Object?>> last) => Loyalty.fromJson(loyaltyJson(last: last));
    expect(loyalty(elevenPast).countsMatches(venue), isTrue);
    expect(loyalty([...elevenPast, pastJson(112, eventType: 'CONCERT')]).countsMatches(sports), isFalse);
    expect(loyalty(const []).countsMatches(sports), isTrue);
    expect(loyalty(const []).countsMatches(venue), isFalse);
    expect(Loyalty.fromJson(loyaltyJson()).toNextTier, 2);
    expect(Membership.fromJson({'member': memberJson(number: 7), 'loyalty': loyaltyJson()}).member!.padded, '000007');
  });

  testWidgets('a club with a concert among its events counts "événements"', (tester) async {
    await open(
      tester,
      ClubBackend(
        loyalty: {
          testOrganizer: loyaltyJson(
            attended: 3,
            last: [
              pastJson(101),
              pastJson(102, eventType: 'CONCERT'),
            ],
          ),
        },
      ),
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
    );
    expect(find.text('3 événements sur place'), findsOneWidget);
  });

  testWidgets('"Quitter le club" → confirm → DELETE → the join card', (tester) async {
    final backend = ClubBackend(members: {testOrganizer: memberJson()});
    await open(tester, backend, follows: (ids: const [testOrganizer], seen: true, chip: chipAll));
    await tester.ensureVisible(find.byKey(const Key('club.leave')));
    await tester.tap(find.byKey(const Key('club.leave')));
    await tester.pumpAndSettle();
    expect(find.text('Quitter Atlas FC ?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('club.leaveConfirm')));
    await tester.pumpAndSettle();
    final delete = backend.calls('DELETE', '/fan/me/membership').single;
    expect(delete.url.queryParameters, {'organizer': testOrganizer});
    expect(find.byKey(const Key('club.joinCard.$testOrganizer')), findsOneWidget);
    expect(find.byKey(const Key('club.leave')), findsNothing);
    // Not a member any more, the season stays; no second read.
    expect(find.byKey(const Key('club.dot.next')), findsOneWidget);
    expect(backend.calls('GET', '/fan/me/membership'), hasLength(1));
  });

  testWidgets('an opt-in switch PUTs both booleans; a 500 puts it back with the error', (tester) async {
    final backend = ClubBackend(members: {testOrganizer: memberJson(whatsapp: true)});
    await open(tester, backend, follows: (ids: const [testOrganizer], seen: true, chip: chipAll));
    final push = find.byKey(const Key('club.optin.push'));
    await tester.ensureVisible(push);
    await tester.tap(push);
    await tester.pumpAndSettle();
    expect(jsonDecode(backend.calls('PUT', '/fan/me/membership').single.body), {
      'organizer_id': testOrganizer,
      'marketing_push': true,
      'marketing_whatsapp': true,
    });
    expect(tester.widget<SwitchListTile>(push).value, isTrue);

    backend.putFails = true;
    await tester.tap(push);
    await tester.pump();
    expect(tester.widget<SwitchListTile>(push).value, isFalse); // at once
    // One PUT at a time: both switches are locked until it answers.
    expect(tester.widget<SwitchListTile>(push).onChanged, isNull);
    expect(tester.widget<SwitchListTile>(find.byKey(const Key('club.optin.whatsapp'))).onChanged, isNull);
    await tester.pumpAndSettle();
    expect(tester.widget<SwitchListTile>(push).value, isTrue); // back
    expect(find.byType(SnackBar), findsOneWidget);
  });

  testWidgets('Accueil strip: the first followed club where the fan is a member → its card', (tester) async {
    // A is followed first but not a member; B is.
    final backend = ClubBackend(members: {otherOrganizer: memberJson()});
    final c = await open(tester, backend, path: '/');
    expect(find.text('N° 000042 · Fidèle · 8 matchs'), findsOneWidget);
    await tester.tap(find.byKey(const Key('home.member')));
    await tester.pumpAndSettle();
    expect(c.read(routerProvider).state.uri.toString(), '/club?organizer=$otherOrganizer');
    // B's card is the one in view.
    expect(
      tester.getCenter(find.byKey(const Key('club.card.$otherOrganizer'))).dx,
      moreOrLessEquals(tester.getCenter(find.byKey(const Key('club.pager'))).dx),
    );
    expect(find.byKey(const Key('club.leave')), findsOneWidget);
    // Shared with Accueil: no second read.
    expect(backend.calls('GET', '/fan/me/membership'), hasLength(2));

    // Swipe to A, back to Accueil, tap the strip again: the same link brings B back into view.
    await tester.drag(find.byKey(const Key('club.pager')), const Offset(300, 0));
    await tester.pumpAndSettle();
    expect(
      tester.getCenter(find.byKey(const Key('club.joinCard.$testOrganizer'))).dx,
      moreOrLessEquals(tester.getCenter(find.byKey(const Key('club.pager'))).dx),
    );
    c.read(routerProvider).go('/');
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('home.member')));
    await tester.pumpAndSettle();
    expect(
      tester.getCenter(find.byKey(const Key('club.card.$otherOrganizer'))).dx,
      moreOrLessEquals(tester.getCenter(find.byKey(const Key('club.pager'))).dx),
    );
  });

  testWidgets('Accueil strip: none when not a member anywhere', (tester) async {
    final backend = ClubBackend();
    await open(tester, backend, path: '/');
    expect(backend.calls('GET', '/fan/me/membership'), hasLength(2));
    expect(find.byKey(const Key('home.member')), findsNothing);
  });

  testWidgets('Accueil strip: none signed out, and no membership read', (tester) async {
    final backend = ClubBackend(members: {testOrganizer: memberJson()});
    await pumpJomhor(tester, backend: backend.handle, follows: followsAB);
    expect(find.byKey(const Key('home.member')), findsNothing);
    expect(backend.calls('GET', '/fan/me/membership'), isEmpty);
  });

  testWidgets('130 % text: the card still fits', (tester) async {
    tester.platformDispatcher.textScaleFactorTestValue = 1.3;
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    await open(
      tester,
      ClubBackend(members: {testOrganizer: memberJson()}),
      follows: (ids: const [testOrganizer, otherOrganizer], seen: true, chip: null),
    );
    expect(tester.takeException(), isNull);
    expect(find.text('000042'), findsOneWidget);
  });

  testWidgets('logout wipes club.seen', (tester) async {
    final c = await open(
      tester,
      ClubBackend(members: {testOrganizer: memberJson()}),
      follows: (ids: const [testOrganizer], seen: true, chip: chipAll),
    );
    expect(await seen(), isNotEmpty);
    await c.read(sessionProvider.notifier).signOut();
    await tester.pumpAndSettle();
    expect(await SharedPreferencesAsync().getStringList('club.seen'), isNull);
  });

  testWidgets('the peeking card waits: its number rolls when swiped to, and only then counts as seen', (tester) async {
    await open(tester, ClubBackend(members: {testOrganizer: memberJson(), otherOrganizer: memberJson(number: 7)}));
    expect(await seen(), contains(testOrganizer));
    expect(await seen(), isNot(contains(otherOrganizer)));
    // Loaded with no animation (skeleton → card is not a join).
    expect(switcherOf(tester, testOrganizer).duration, Duration.zero);
    expect(find.byKey(const Key('club.number.waiting')), findsOneWidget); // B, hidden until its turn
    await tester.drag(find.byKey(const Key('club.pager')), const Offset(-300, 0));
    await pumpUntil(tester, find.byKey(const Key('club.number.roll')));
    await tester.pumpAndSettle();
    expect(find.text('000007'), findsOneWidget);
    expect(await seen(), contains(otherOrganizer));
  });

  testWidgets('the cards line up with the 20 px gutter, in FR and AR, the next one peeking', (tester) async {
    for (final locale in ['fr', 'ar']) {
      tallView(tester);
      final c = await pumpJomhor(
        tester,
        backend: ClubBackend(members: {testOrganizer: memberJson(), otherOrganizer: memberJson(number: 7)}).handle,
        tokens: MemoryTokenStore('jwt'),
        follows: followsAB,
        locale: Locale(locale),
      );
      c.read(routerProvider).go('/club');
      await tester.pumpAndSettle();
      final a = tester.getRect(find.byKey(const Key('club.card.$testOrganizer')));
      final b = tester.getRect(find.byKey(const Key('club.card.$otherOrganizer')));
      final season = tester.getRect(find.byKey(const Key('club.season.$testOrganizer')));
      if (locale == 'fr') {
        expect(a.left, moreOrLessEquals(JomhorSpace.xl));
        expect(a.right, moreOrLessEquals(JomhorSize.phoneWidth - JomhorSpace.xl));
        expect(b.left, lessThan(JomhorSize.phoneWidth)); // peeks on the right
      } else {
        expect(a.right, moreOrLessEquals(JomhorSize.phoneWidth - JomhorSpace.xl));
        expect(b.right, greaterThan(0)); // peeks on the left
        expect(b.right, lessThan(a.left));
      }
      // Same gutter as the season card below (the dots sit inside its 1 px border and 20 px padding).
      expect(season.left - 1 - JomhorSpace.xl, moreOrLessEquals(a.left));
      await tester.pumpWidget(const SizedBox());
    }
  });

  testWidgets('"{n} matchs au stade" rolls when it changes', (tester) async {
    final backend = ClubBackend(members: {testOrganizer: memberJson()});
    final c = await open(tester, backend, follows: (ids: const [testOrganizer], seen: true, chip: chipAll));
    backend.loyalty[testOrganizer] = loyaltyJson(attended: 9);
    c.invalidate(membershipProvider(testOrganizer));
    await pumpUntil(tester, find.text('9 matchs au stade'));
    expect(find.text('8 matchs au stade'), findsOneWidget); // on its way out
    await tester.pumpAndSettle();
    expect(find.text('8 matchs au stade'), findsNothing);
  });

  group('FL-30 cotisation and Abonnements (Lot 5 step 3)', () {
    const only = (ids: [testOrganizer], seen: true, chip: chipAll);
    final duesPay = find.byKey(const Key('club.duesPay'));

    ClubBackend backend({String? paidUntil, bool member = true, bool required = true, bool membershipOnSale = true}) =>
        ClubBackend(members: {if (member) testOrganizer: memberJson(paidUntil: paidUntil)})
          ..paidRequired = required
          ..products = [
            for (final p in clubProducts())
              if (membershipOnSale || (p! as Map<String, Object?>)['kind'] == 'PASS') p,
          ];

    String path(ProviderContainer c) => c.read(routerProvider).state.uri.path;

    test('the fixtures parse, and the fake backend answers in their shape', () {
      final paid = Membership.fromJson(clubAnswer('membership_200_paid').body! as Map<String, Object?>);
      expect((paid.paidRequired, paid.member!.paidUntil), (true, DateTime.utc(2027, 6, 30, 22, 59, 59)));
      final unpaid = Membership.fromJson(clubAnswer('membership_200_unpaid').body! as Map<String, Object?>);
      expect((unpaid.paidRequired, unpaid.member!.paidUntil), (true, null));
      // Before BE-54 (no `paid_required`, no `paid_until`): nothing required.
      final old = Membership.fromJson({'member': memberJson()..remove('paid_until'), 'loyalty': loyaltyJson()});
      expect((old.paidRequired, old.member!.paidUntil), (false, null));
      final fixture = clubAnswer('membership_200_paid').body! as Map<String, Object?>;
      final fake = {'member': memberJson(), 'loyalty': loyaltyJson(), 'paid_required': true};
      expect(fake.keys.toSet(), fixture.keys.toSet());
      expect(memberJson().keys.toSet(), (fixture['member']! as Map<String, Object?>).keys.toSet());
    });

    test("the design answers (test/fixtures/goldens/club) have the real answers' shape", () {
      for (final name in ['products_200', 'product_order_201_membership']) {
        expect(keyPaths(clubAnswer(name, dir: 'goldens/club').body), keyPaths(clubAnswer(name).body), reason: name);
      }
    });

    test('real run: the paid membership order reads as a product order (no event, zone or pass)', () {
      final order = FanOrder.fromJson(clubAnswer('order_200_membership_paid').body! as Map<String, Object?>);
      expect((order.eventId, order.passId, order.product!.isPass), (null, null, false));
      expect(order.product!.timezone, 'Africa/Casablanca');
      expect(order.product!.paymentMethods, [PayMethod.card, PayMethod.wallet, PayMethod.cashAgency]);
      expect(order.product!.cashUntil, DateTime.utc(2027, 6, 30, 10));
      expect(order.items.single.zoneId, isNull);
    });

    testWidgets('real run: screen 7 on the captured products: the cotisation to pay, its order opened; a product '
        'whose sales closed is not offered', (tester) async {
      final rows = clubAnswer('products_200').body! as List<Object?>;
      String idOf(String kind, bool Function(String close) closed) => [
        for (final p in rows.cast<Map<String, Object?>>())
          if (p['kind'] == kind && closed(p['sales_close_at']! as String)) p['id']! as String,
      ].single;
      // 20:39Z: after the short pass's sales_close_at (20:38:11Z), before the others' (2027).
      bool before(String at) => DateTime.parse(at).isBefore(DateTime.utc(2026, 11, 15, 20, 39));
      final b = ClubBackend(members: {testOrganizer: memberJson()})
        ..paidRequired = true
        ..products = rows
        ..serverDate = DateTime.utc(2026, 11, 15, 20, 39)
        ..productOrders.add(clubAnswer('product_order_201_membership'));
      final c = await open(tester, b, follows: only);
      expect(find.byKey(const Key('club.dues.$testOrganizer.unpaid')), findsOneWidget);
      expect(find.byKey(Key('club.pass.${idOf('PASS', (at) => !before(at))}')), findsOneWidget);
      expect(find.byKey(Key('club.pass.${idOf('PASS', before)}')), findsNothing);
      await tester.ensureVisible(duesPay);
      await tester.tap(find.descendant(of: duesPay, matching: find.byType(FilledButton)));
      await tester.pumpAndSettle();
      final post = jsonDecode(b.calls('POST', '/fan/product-orders').single.body) as Map<String, Object?>;
      expect(post['product_id'], idOf('MEMBERSHIP', (_) => true));
      final order = clubAnswer('product_order_201_membership').body! as Map<String, Object?>;
      expect(path(c), '/orders/${order['id']}');
    });

    test('duesOf: none, unpaid, paid, expired', () {
      final now = DateTime.utc(2027, 2, 20, 12);
      Membership m({bool required = true, String? paidUntil, bool member = true}) => Membership.fromJson({
        'member': member ? memberJson(paidUntil: paidUntil) : null,
        'loyalty': loyaltyJson(),
        'paid_required': required,
      });
      expect(duesOf(m(required: false), onSale: true, now: now), isNull);
      expect(duesOf(m(member: false), onSale: true, now: now), isNull);
      expect(duesOf(m(), onSale: true, now: now), Dues.unpaid);
      expect(duesOf(m(), onSale: false, now: now), isNull); // nothing to pay
      expect(duesOf(m(paidUntil: '2027-06-30T22:00:00Z'), onSale: false, now: now), Dues.paid);
      expect(duesOf(m(paidUntil: '2027-02-20T12:00:00Z'), onSale: false, now: now), Dues.expired); // paid_until > T
      expect(duesOf(m(paidUntil: '2026-06-30T22:00:00Z'), onSale: true, now: now), Dues.expired);
    });

    testWidgets('paid_required false: no cotisation row; the Abonnements cards read public, no token', (tester) async {
      final b = backend(required: false);
      await open(tester, b, follows: only);
      expect(find.byKey(const Key('club.dues.$testOrganizer.unpaid')), findsNothing);
      expect(find.byKey(const Key('club.duesPaid.$testOrganizer')), findsNothing);
      expect(duesPay, findsNothing);
      expect(find.text('ABONNEMENTS'), findsOneWidget);
      expect(find.text('Abonnement Tribune Est'), findsOneWidget);
      expect(find.text('Tribune Est · 2 matchs annoncés'), findsOneWidget);
      final read = b.calls('GET', '/fan/products').single;
      expect(read.url.queryParameters, {'organizer': testOrganizer});
      expect(read.headers.containsKey('Authorization'), isFalse);
    });

    testWidgets('unpaid: "Payer ma cotisation" → POST /fan/product-orders (MEMBERSHIP) in the fan name → screen 4', (
      tester,
    ) async {
      final b = backend();
      final c = await open(tester, b, follows: only);
      expect(find.byKey(const Key('club.dues.$testOrganizer.unpaid')), findsOneWidget);
      expect(find.textContaining('Cotisation Saison 2026-27 · '), findsOneWidget);
      await tester.ensureVisible(duesPay);
      await tester.tap(find.descendant(of: duesPay, matching: find.text('Payer ma cotisation')));
      await tester.pumpAndSettle();
      expect(jsonDecode(b.calls('POST', '/fan/product-orders').single.body), {
        'product_id': duesProductId,
        'channel': 'APP',
        'holder_name': 'Yazid Aqel',
      });
      expect(path(c), '/orders/$duesOrderId');
    });

    testWidgets('unpaid with no name on /fan/me: screen 13 asks for it, nothing ordered yet', (tester) async {
      final b = backend()..displayName = null;
      final c = await open(tester, b, follows: only);
      await tester.ensureVisible(duesPay);
      await tester.tap(find.descendant(of: duesPay, matching: find.byType(FilledButton)));
      await tester.pumpAndSettle();
      expect(path(c), '/products/$duesProductId');
      expect(b.calls('POST', '/fan/product-orders'), isEmpty);
    });

    testWidgets("paid: the accent chip \"À jour jusqu'au {date}\", the day in the venue zone", (tester) async {
      // 23:30 UTC on 30 June: still 30 June in Casablanca (+00 since tzdata 2026e), 1 July on a Paris phone.
      await open(tester, backend(paidUntil: '2027-06-30T23:30:00Z'), follows: only);
      expect(find.byKey(const Key('club.duesPaid.$testOrganizer')), findsOneWidget);
      expect(find.text("À jour jusqu'au 30 juin"), findsOneWidget);
      expect(duesPay, findsNothing);
    });

    testWidgets('expired: "Cotisation expirée" + "Renouveler" only with a product on sale', (tester) async {
      await open(tester, backend(paidUntil: '2026-06-30T22:00:00Z'), follows: only);
      expect(find.byKey(const Key('club.dues.$testOrganizer.expired')), findsOneWidget);
      expect(find.text('Cotisation expirée'), findsOneWidget);
      expect(find.descendant(of: duesPay, matching: find.text('Renouveler')), findsOneWidget);
      await tester.pumpWidget(const SizedBox());

      await open(tester, backend(paidUntil: '2026-06-30T22:00:00Z', membershipOnSale: false), follows: only);
      expect(find.text('Cotisation expirée'), findsOneWidget);
      expect(duesPay, findsNothing);
    });

    testWidgets('MEMBERSHIP_PAID (paid elsewhere) → the membership is read again → the paid chip', (tester) async {
      final b = backend()..productOrders.add(clubAnswer('product_order_409_membership_paid'));
      final c = await open(tester, b, follows: only);
      b.members[testOrganizer] = memberJson(paidUntil: '2027-06-30T22:00:00Z'); // paid on the shop meanwhile
      await tester.ensureVisible(duesPay);
      await tester.tap(find.descendant(of: duesPay, matching: find.byType(FilledButton)));
      await tester.pumpAndSettle();
      expect(b.calls('GET', '/fan/me/membership'), hasLength(2));
      expect(find.byKey(const Key('club.duesPaid.$testOrganizer')), findsOneWidget);
      expect(find.text("À jour jusqu'au 30 juin"), findsOneWidget);
      expect(path(c), '/club');
    });

    testWidgets('not a member: the join sheet adds "Cotisation {price}": PUT with the switches as shown, then the '
        'POST → screen 4', (tester) async {
      final b = backend(member: false);
      final c = await open(tester, b, follows: only);
      await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('club.optin.whatsapp')));
      await tester.pump();
      final dues = find.byKey(const Key('club.joinDues'));
      expect(find.descendant(of: dues, matching: find.textContaining('Cotisation ')), findsOneWidget);
      await tester.ensureVisible(dues);
      await tester.tap(find.descendant(of: dues, matching: find.byType(OutlinedButton)));
      await tester.pumpAndSettle();
      // The opt-ins the fan set are kept: the join comes first, then the order.
      final put = b.calls('PUT', '/fan/me/membership').single;
      expect(jsonDecode(put.body), {
        'organizer_id': testOrganizer,
        'marketing_push': false,
        'marketing_whatsapp': true,
      });
      final post = b.calls('POST', '/fan/product-orders').single;
      expect(b.requests.indexOf(put), lessThan(b.requests.indexOf(post)));
      expect((jsonDecode(post.body) as Map)['product_id'], duesProductId);
      expect(path(c), '/orders/$duesOrderId');
      expect(find.text('Rejoindre Atlas FC'), findsNothing); // the sheet closed
    });

    testWidgets('join sheet cotisation: a failed join orders nothing and says why', (tester) async {
      final b = backend(member: false)..putFails = true;
      final c = await open(tester, b, follows: only);
      await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
      await tester.pumpAndSettle();
      final dues = find.byKey(const Key('club.joinDues'));
      await tester.ensureVisible(dues);
      await tester.tap(find.descendant(of: dues, matching: find.byType(OutlinedButton)));
      await tester.pumpAndSettle();
      expect(b.calls('POST', '/fan/product-orders'), isEmpty);
      expect(find.byKey(const Key('club.joinError')), findsOneWidget);
      expect(path(c), '/club');
    });

    testWidgets('join sheet cotisation: the sheet stays locked until the order answers', (tester) async {
      final b = backend(member: false)..orderDelay = const Duration(seconds: 1);
      final c = await open(tester, b, follows: only);
      await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
      await tester.pumpAndSettle();
      final dues = find.byKey(const Key('club.joinDues'));
      await tester.ensureVisible(dues);
      await tester.tap(find.descendant(of: dues, matching: find.byType(OutlinedButton)));
      await tester.pump(const Duration(milliseconds: 100)); // joined, the order on its way
      expect(b.calls('PUT', '/fan/me/membership'), hasLength(1));
      expect(b.calls('POST', '/fan/product-orders'), hasLength(1));
      // The sheet's own switches (the member's settings now show under it with the same keys).
      Finder inSheet(String key) => find.descendant(of: find.byType(JoinSheet), matching: find.byKey(Key(key)));
      final whatsapp = inSheet('club.optin.whatsapp');
      expect(tester.widget<SwitchListTile>(whatsapp).onChanged, isNull);
      expect(tester.widget<SwitchListTile>(inSheet('club.optin.push')).onChanged, isNull);
      expect(tester.widget<FilledButton>(find.byKey(const Key('club.joinConfirm'))).onPressed, isNull);
      // A flip or a "Rejoindre" mid-order does nothing: no second PUT, the sheet still open.
      await tester.tap(whatsapp, warnIfMissed: false);
      await tester.tap(find.byKey(const Key('club.joinConfirm')), warnIfMissed: false);
      await tester.pump();
      expect(tester.widget<SwitchListTile>(whatsapp).value, isFalse);
      expect(b.calls('PUT', '/fan/me/membership'), hasLength(1));
      expect(find.text('Rejoindre Atlas FC'), findsOneWidget);
      await tester.pumpAndSettle();
      expect(path(c), '/orders/$duesOrderId');
      expect(b.calls('PUT', '/fan/me/membership'), hasLength(1));
    });

    testWidgets('join sheet cotisation: a rejoin already paid up closes the sheet, no order', (tester) async {
      final b = backend(member: false)..keptPaidUntil = '2027-06-30T22:00:00Z';
      final c = await open(tester, b, follows: only);
      await tester.tap(find.byKey(const Key('club.join.$testOrganizer')));
      await tester.pumpAndSettle();
      final dues = find.byKey(const Key('club.joinDues'));
      await tester.ensureVisible(dues);
      await tester.tap(find.descendant(of: dues, matching: find.byType(OutlinedButton)));
      await tester.pumpAndSettle();
      expect(b.calls('PUT', '/fan/me/membership'), hasLength(1));
      expect(b.calls('POST', '/fan/product-orders'), isEmpty);
      expect(find.text('Rejoindre Atlas FC'), findsNothing);
      expect(path(c), '/club');
      expect(find.byKey(const Key('club.duesPaid.$testOrganizer')), findsOneWidget);
    });

    testWidgets('paid or expired is decided on server time (the products read), not the phone clock', (tester) async {
      // The phone says 12:00 (paid until 13:00); the server already says 14:00.
      final b = backend(paidUntil: '2027-02-20T13:00:00Z')..serverDate = DateTime.utc(2027, 2, 20, 14);
      await open(tester, b, follows: only);
      expect(find.byKey(const Key('club.dues.$testOrganizer.expired')), findsOneWidget);
      expect(find.byKey(const Key('club.duesPaid.$testOrganizer')), findsNothing);
    });

    testWidgets('an Abonnements card → screen 13 buy', (tester) async {
      final c = await open(tester, backend(), follows: only);
      final card = find.byKey(const Key('club.pass.$passProductId'));
      await tester.ensureVisible(card);
      await tester.tap(card);
      await tester.pumpAndSettle();
      expect(path(c), '/products/$passProductId');
    });
  });

  test('signed out, a membership read fails at once instead of hanging', () async {
    final c = ProviderContainer(retry: (_, _) => null);
    addTearDown(c.dispose);
    await expectLater(c.read(membershipProvider(testOrganizer).future), throwsA(isA<SignedOut>()));
  });
}
