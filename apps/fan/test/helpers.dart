import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/launch_splash.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/push/push.dart';
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
/// [jitter] replaces the zero jitter; [splash] plays the launch splash (off: the app is open, and the test's clock
/// starts, when this returns); [settle] false returns on the first frame; [keepSecureStorage] keeps what a test put there with `FlutterSecureStorage.setMockInitialValues`. Returns the container to drive the router and providers.
Future<ProviderContainer> pumpJomhor(
  WidgetTester tester, {
  required Future<http.Response> Function(http.Request request) backend,
  MemoryTokenStore? tokens,
  FollowsState follows = (ids: const [], seen: true, chip: null),
  TestClock? clock,
  List<Override> overrides = const [],
  Duration Function(Duration max)? jitter,
  bool keepSecureStorage = false,
  Locale locale = const Locale('fr', 'MA'),
  bool splash = false,
  bool settle = true,
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
      jitterProvider.overrideWithValue(jitter ?? (_) => Duration.zero),
      launchSplashProvider.overrideWithValue(splash),
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
  if (settle) await tester.pumpAndSettle();
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

/// A push provider the test drives: [refresh], [foreground] and [opened] emit, [launch] is the cold-start message,
/// and every call is recorded in [calls] (and in [log], when a test shares one with its backend to check ordering).
class FakePushMessaging implements PushMessaging {
  new({this.currentToken = 'fcm-token-1', this.status = PushPermission.notAsked, this.launch, List<String>? log})
    : log = log ?? [];

  String? currentToken;
  PushPermission status;

  /// What the fan answers to the prompt; refused → [status] becomes denied (the settings, as the real mapping).
  bool grant = true;
  PushMessage? launch;
  final List<String> log;
  final calls = <String>[];
  final refresh = StreamController<String>.broadcast();
  final foreground = StreamController<PushMessage>.broadcast();
  final opened = StreamController<PushMessage>.broadcast();

  int count(String call) => calls.where((c) => c == call).length;

  void _call(String name) {
    calls.add(name);
    log.add('push.$name');
  }

  @override
  Future<String?> token() async {
    _call('token');
    return currentToken;
  }

  @override
  Stream<String> get onTokenRefresh => refresh.stream;

  @override
  Stream<PushMessage> get onForeground => foreground.stream;

  @override
  Stream<PushMessage> get onOpened => opened.stream;

  @override
  Future<PushMessage?> initialMessage() async {
    _call('initialMessage');
    return launch;
  }

  @override
  Future<PushPermission> permission() async {
    _call('permission');
    return status;
  }

  @override
  Future<PushPermission> requestPermission() async {
    _call('requestPermission');
    return status = grant ? PushPermission.granted : PushPermission.denied;
  }

  @override
  Future<void> deleteToken() async {
    _call('deleteToken');
    currentToken = null;
  }
}

/// A `GET /fan/me/inbox` row (docs/04-api.md): the campaign id, the club's `organizer` block (with its id), and
/// `event_id` null for a campaign without an event.
Map<String, Object?> inboxJson({
  String id = 'c0000000-0000-4000-8000-000000000001',
  Map<String, Object?>? club,
  String title = 'Ouverture des portes à 17 h',
  String body = 'Viens tôt : animations en tribune Nord avant le match.',
  int? eventId = 1,
  String sentAt = '2027-02-20T11:00:00Z',
}) {
  final c = club ?? clubJson();
  return {
    'id': id,
    'organizer': {'id': c['id'], 'slug': c['slug'], 'name': c['name'], 'crest_url': c['crest_url']},
    'title': title,
    'body': body,
    'event_id': eventId,
    'sent_at': sentAt,
  };
}

/// `PUT /fan/me/push-token` (→ 204) and `GET /fan/me/inbox` in front of [backend]; the PUT bodies are kept in
/// [tokenPuts], and [inbox] (or [inboxStatus], an error) answers the inbox.
class PushBackend {
  new(this.backend, {List<String>? log}) : log = log ?? [];

  final Future<http.Response> Function(http.Request request) backend;
  final List<String> log;
  final tokenPuts = <Map<String, Object?>>[];
  List<Map<String, Object?>> inbox = [];
  int? inboxStatus;
  int inboxReads = 0;

  Future<http.Response> handle(http.Request r) async {
    log.add('${r.method} ${r.url.path}');
    switch ((r.method, r.url.path)) {
      case ('PUT', '/v1/fan/me/push-token'):
        tokenPuts.add(jsonDecode(r.body) as Map<String, Object?>);
        return http.Response('', 204);
      case ('GET', '/v1/fan/me/inbox'):
        inboxReads++;
        if (inboxStatus case final status?) return apiError(status, status == 401 ? 'UNAUTHORIZED' : 'INTERNAL');
        return json({'messages': inbox});
    }
    return await backend(r);
  }
}

/// The sale opens at 12:30 (UTC) on the test day; its room takes joins from 12:00, the test clock's start.
const roomSaleStartsAt = '2027-02-20T12:30:00Z';
const roomOpensAt = '2027-02-20T12:00:00Z';

/// `room` of `GET /fan/events*` (docs/04-api.md §Fan, Lot 5).
Map<String, Object?> roomJson({
  String status = 'PRE_QUEUE',
  String at = roomOpensAt,
  Map<String, Object?>? eligibility,
}) => {'status': status, 'opens_at': at, 'eligibility': eligibility};

/// A fake backend for one event (id 1) with a waiting room; `/fan/queue` shaped as docs/04-api.md.
class RoomSale {
  new(this.clock, {this.serverSkew = Duration.zero});

  final TestClock clock;

  /// Server clock minus device clock.
  final Duration serverSkew;
  final requests = <http.Request>[];
  Map<String, Object?>? room = roomJson();

  /// Queued answers of POST /fan/queue (default: 201 in the pre-queue).
  final joins = <http.Response Function()>[];

  /// Answers of GET /fan/queue in turn; the last one repeats (default: the pre-queue).
  final polls = <http.Response Function()>[];

  /// Queued answers of POST /fan/orders (default: 409 ADMISSION_REQUIRED).
  final orders = <http.Response Function()>[];

  DateTime get serverNow => clock.now.add(serverSkew);

  /// The `GET /fan/queue` shape.
  Map<String, Object?> q({
    String status = 'PRE_QUEUE',
    int? position,
    int inRoom = 120,
    int rate = 0,
    int? wait,
    bool soldOut = false,
    Duration? turn,
    String token = 'qt-1',
    int pollAfter = 5,
  }) => {
    'event_id': 1,
    'status': status,
    'sale_starts_at': roomSaleStartsAt,
    'position': position,
    'in_room': inRoom,
    'rate_per_min': rate,
    'estimated_wait_s': wait,
    'sold_out': soldOut,
    'admission': turn == null ? null : {'token': 'adm-1', 'expires_at': serverNow.add(turn).toIso8601String()},
    'queue_token': token,
    'poll_after_s': pollAfter,
    'server_time': serverNow.toIso8601String(),
  };

  http.Response answer(Object? body, [int status = 200]) => http.Response.bytes(
    utf8.encode(jsonEncode(body)),
    status,
    headers: {'content-type': 'application/json', 'date': HttpDate.format(serverNow)},
  );

  http.Response refusal(int status, String code, Map<String, Object?> details) =>
      answer({'code': code, 'message': code, 'details': details}, status);

  Map<String, Object?> event() => eventJson(
    onSale: false,
    saleStartsAt: roomSaleStartsAt,
    zones: [
      zoneJson(1, 'Tribune Nord', [(5000, 300)]),
      zoneJson(2, 'Virage Sud', [(3000, 120)], seated: false),
    ],
  )..['room'] = room;

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    return switch ((r.method, r.url.path)) {
      ('GET', '/v1/fan/events') => answer([event()..remove('zones')]),
      ('GET', '/v1/fan/events/1') => answer(event()),
      ('POST', '/v1/fan/queue') => joins.isEmpty ? answer(q(), 201) : joins.removeAt(0)(),
      ('GET', '/v1/fan/queue') => switch (polls.length) {
        0 => answer(q()),
        1 => polls.first(),
        _ => polls.removeAt(0)(),
      },
      ('GET', '/v1/fan/me') => answer({'id': 'u1', 'phone': '+212600000001', 'display_name': 'Yazid Aqel'}),
      ('POST', '/v1/fan/orders') =>
        orders.isEmpty ? refusal(409, 'ADMISSION_REQUIRED', {'reason': 'EXPIRED'}) : orders.removeAt(0)(),
      _ => apiError(404, 'NOT_FOUND'),
    };
  }

  List<http.Request> calls(String method, String path) => [
    ...requests.where((r) => r.method == method && r.url.path == '/v1$path'),
  ];
}
