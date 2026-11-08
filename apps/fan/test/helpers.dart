import 'dart:async';
import 'dart:convert';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:timezone/data/latest.dart' as tzdata;

class MemoryTokenStore implements TokenStore {
  new([this.token]);
  String? token;

  @override
  Future<String?> read() async => token;

  @override
  Future<void> write(String token) async => this.token = token;

  @override
  Future<void> clear() async => token = null;
}

/// ATLAS FC, the club of [eventJson] by default; [otherOrganizer] is Olympique Tensift.
const testOrganizer = '0b8e7a52-0000-4000-8000-000000000001';
const otherOrganizer = '0b8e7a52-0000-4000-8000-000000000002';

/// `/fan/organizers` rows (docs/04-api.md §Club directory).
Map<String, Object?> clubJson({
  String id = testOrganizer,
  String slug = 'atlas-fc',
  String name = 'Atlas FC',
  String kind = 'SPORTS_CLUB',
  String? crestUrl,
}) => {'id': id, 'slug': slug, 'name': name, 'kind': kind, 'crest_url': crestUrl};

final Map<String, Object?> tensiftJson = clubJson(
  id: otherOrganizer,
  slug: 'olympique-tensift',
  name: 'Olympique Tensift',
);

/// The `organizer` block of an event (no id: `organizer_id` sits beside it).
Map<String, Object?> organizerBlock(Map<String, Object?> club) => {
  'slug': club['slug'],
  'name': club['name'],
  'crest_url': club['crest_url'],
};

/// JSON response helper for `MockClient` handlers.
http.Response json(Object? body, [int status = 200]) =>
    http.Response.bytes(utf8.encode(jsonEncode(body)), status, headers: {'content-type': 'application/json'});

http.Response apiError(int status, String code) =>
    json({'code': code, 'message': code, 'details': <String, Object?>{}}, status);

/// A `/fan/events` item (docs/04-api.md §Fan), on sale by default.
Map<String, Object?> eventJson({
  int id = 1,
  String eventType = 'FOOTBALL_MATCH',
  String title = 'Atlas FC vs Rapid Oued',
  String startsAt = '2027-03-13T19:00:00Z',
  String? saleStartsAt = '2027-02-01T10:00:00Z',
  String salesCloseAt = '2027-03-13T18:00:00Z',
  bool onSale = true,
  bool soldOut = false,
  int? fromPriceMad = 5000,
  int perAccountLimit = 4,
  Map<String, Object?> metadata = const {'opponent': 'Rapid Oued', 'competition': 'Championnat', 'matchday': 11},
  List<Map<String, Object?>>? zones,
  Map<String, Object?>? club,
}) => {
  'id': id,
  'organizer_id': (club ?? clubJson())['id'],
  'organizer': organizerBlock(club ?? clubJson()),
  'event_type': eventType,
  'title': title,
  'starts_at': startsAt,
  'doors_open_at': null,
  'timezone': 'Africa/Casablanca',
  'venue': {'name': "Stade de l'Atlas", 'city': 'Rabat'},
  'metadata': metadata,
  'sale_starts_at': saleStartsAt,
  'sales_close_at': salesCloseAt,
  'on_sale': onSale,
  'sold_out': soldOut,
  'from_price_mad': fromPriceMad,
  'per_account_limit': perAccountLimit,
  'zones': ?zones,
};

Map<String, Object?> zoneJson(int id, String name, List<(int priceMad, int remaining)> prices, {bool seated = true}) =>
    {
      'id': id,
      'code': 'Z$id',
      'name': name,
      'seated': seated,
      'gate_codes': ['3', '4'],
      'prices': [
        for (final (i, (price, remaining)) in prices.indexed)
          {'price_category_id': 'pc-$id-$i', 'name': 'Tarif $i', 'price_mad': price, 'remaining': remaining},
      ],
    };

/// A clock the test moves: [advance] changes "now" and emits a tick like the 30 s timer would.
class TestClock {
  new(this.now);

  DateTime now;
  final _ticks = StreamController<DateTime>.broadcast();

  void advance(Duration by) {
    now = now.add(by);
    _ticks.add(now);
  }
}

/// Pumps the whole app (FR unless [locale] says otherwise: the device language) with a fake backend and an empty in-memory secure storage (the ticket cache);
/// [follows] is what `main` read from the phone: by default the picker was seen and no club is followed;
/// [keepSecureStorage] keeps what a test put there with `FlutterSecureStorage.setMockInitialValues`. Returns the container to drive the router and providers.
Future<ProviderContainer> pumpJomhor(
  WidgetTester tester, {
  required Future<http.Response> Function(http.Request request) backend,
  MemoryTokenStore? tokens,
  FollowsState follows = (ids: const [], seen: true, chip: null),
  TestClock? clock,
  List<Override> overrides = const [],
  bool keepSecureStorage = false,
  Locale locale = const Locale('fr', 'MA'),
}) async {
  final time = clock ?? TestClock(DateTime.utc(2027, 2, 20, 12));
  tzdata.initializeTimeZones();
  if (!keepSecureStorage) FlutterSecureStorage.setMockInitialValues({});
  tester.platformDispatcher.localesTestValue = [locale];
  addTearDown(tester.platformDispatcher.clearLocalesTestValue);
  final store = tokens ?? MemoryTokenStore();
  final container = ProviderContainer(
    retry: (_, _) => null,
    overrides: [
      httpClientProvider.overrideWithValue(MockClient(backend)),
      tokenStoreProvider.overrideWithValue(store),
      initialSignedInProvider.overrideWithValue(store.token != null),
      initialFollowsProvider.overrideWithValue(follows),
      nowProvider.overrideWithValue(() => time.now),
      jitterProvider.overrideWithValue((_) => Duration.zero),
      ...overrides,
      clockProvider.overrideWith((ref) async* {
        yield time.now;
        yield* time._ticks.stream;
      }),
    ],
  );
  await tester.pumpWidget(
    _DisposeWithTree(
      container,
      child: UncontrolledProviderScope(container: container, child: const JomhorApp()),
    ),
  );
  await tester.pumpAndSettle();
  return container;
}

/// Disposes the container when the test unmounts the tree, so provider timers (the refetch at a sale
/// opening) are cancelled before flutter_test checks for pending timers.
class _DisposeWithTree extends StatefulWidget {
  const new(this.container, {required this.child});

  final ProviderContainer container;
  final Widget child;

  @override
  State<_DisposeWithTree> createState() => _DisposeWithTreeState();
}

class _DisposeWithTreeState extends State<_DisposeWithTree> {
  @override
  void dispose() {
    widget.container.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => widget.child;
}
