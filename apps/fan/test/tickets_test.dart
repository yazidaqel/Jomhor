import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart' show ButtonStyleButton;
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';

import 'helpers.dart';

/// Test K_e of event 7; the app only ever gets K_t = HMAC(K_e, ticket_id), as the backend serves it.
final eventKey = Uint8List.fromList(List.filled(32, 0x11));
const ticketId = 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee';
const otherId = 'aaaaaaaa-bbbb-4ccc-8ddd-ffffffffffff';
final ticketIdBytes = Uint8List.fromList([
  for (var i = 0; i < 32; i += 2) int.parse(ticketId.replaceAll('-', '').substring(i, i + 2), radix: 16),
]);
final Uint8List ticketKey = deriveTicketKey(eventKey, ticketIdBytes);

/// 12:00:00 UTC is a slot boundary; the tests start 2 s into the slot.
final slotStart = DateTime.utc(2027, 2, 20, 12);

Map<String, Object?> ticketJson({
  String id = ticketId,
  String status = 'VALID',
  String startsAt = '2027-03-13T19:00:00Z',
  String? eventStatus,
  bool withKey = true,
}) => {
  'id': id,
  'ticket_no': id.substring(id.length - 8).toUpperCase().replaceRange(4, 4, ' '),
  'status': status,
  'holder_label': 'Yazid A.',
  'seat_label': null,
  'event': {
    'id': 7,
    'title': 'Atlas FC vs Rapid Oued',
    'event_type': 'FOOTBALL_MATCH',
    'status': ?eventStatus,
    'starts_at': startsAt,
    'doors_open_at': null,
    'timezone': 'Africa/Casablanca',
    'venue': {'name': "Stade de l'Atlas", 'city': 'Rabat'},
    'metadata': {'opponent': 'Rapid Oued', 'competition': 'Championnat', 'matchday': 11},
  },
  'zone': {'id': 3, 'code': 'N', 'name': 'Nord'},
  'gate_codes': ['4'],
  // Padded base64url, as java.util.Base64 writes it; the app also reads it unpadded.
  if (withKey) 'k_t': base64Url.encode(ticketKey),
};

/// Puts a saved `/fan/tickets` answer in the in-memory secure storage, as the app writes it.
void seedCache(List<Map<String, Object?>> tickets, {Duration offset = Duration.zero}) =>
    FlutterSecureStorage.setMockInitialValues({
      'fan_tickets_cache': jsonEncode({
        'offset_ms': offset.inMilliseconds,
        'response': {'server_time': '2027-02-20T12:00:00Z', 'tickets': tickets},
      }),
    });

Future<String?> savedCache() => const FlutterSecureStorage().read(key: 'fan_tickets_cache');

String tokenAt(DateTime serverNow) => encodeRotating(ticketKey, 7, ticketIdBytes, 3, slotAt(serverNow));

String shownQr(WidgetTester tester) => tester.widget<TicketQr>(find.byType(TicketQr)).data;

Future<void> openTicket(WidgetTester tester, ProviderContainer c, [String id = ticketId]) async {
  unawaited(c.read(routerProvider).push('/tickets/$id'));
  await tester.pumpAndSettle();
}

String location(ProviderContainer c) => c.read(routerProvider).state.uri.path;

void main() {
  final calls = <http.Request>[];
  setUp(calls.clear);

  /// Airplane mode: every call fails.
  Future<http.Response> offline(http.Request r) async {
    calls.add(r);
    throw Exception('no network');
  }

  test('the QR string is encodeRotating(K_t, ...) and verifies with K_e on the scanner side', () {
    final t = FanTicket.fromJson(ticketJson());
    final now = slotStart.add(const Duration(seconds: 2));
    final token = t.rotatingToken(now)!;
    expect(token, encodeRotating(ticketKey, 7, ticketIdBytes, 3, slotAt(now)));
    final parsed = decode(token);
    expect(verify(parsed, eventId: 7, eventKey: eventKey, nowSlot: slotAt(now)), isNull);
    expect((parsed.version, parsed.zoneId, parsed.slot), (versionRotating, 3, slotAt(now)));
    // No personal data: the token is the 43-byte layout only, the holder label is nowhere in it.
    expect(base64Url.decode(base64Url.normalize(token)).length, 43);
    // An unpadded k_t reads the same.
    final unpadded = FanTicket.fromJson({...ticketJson(), 'k_t': base64Url.encode(ticketKey).replaceAll('=', '')});
    expect(unpadded.rotatingToken(now), token);
  });

  test('matchday mode starts exactly at T0 - 4 h', () {
    final t = FanTicket.fromJson(ticketJson());
    final t0 = t.event.startsAt;
    final boundary = t0.subtract(const Duration(hours: 4));
    expect(matchdayTicket([t], boundary)?.id, ticketId);
    expect(matchdayTicket([t], boundary.subtract(const Duration(milliseconds: 1))), isNull);
    expect(matchdayTicket([t], t0.add(matchdayAfter)), isNull);
    expect(matchdayTicket([FanTicket.fromJson(ticketJson(withKey: false))], boundary), isNull);
  });

  test('past = event CLOSED or started more than 12 h ago', () {
    final now = DateTime.utc(2027, 3, 14, 6);
    expect(FanTicket.fromJson(ticketJson()).isPast(now), isFalse); // 11 h after kickoff
    expect(FanTicket.fromJson(ticketJson(eventStatus: 'CLOSED')).isPast(now), isTrue);
    expect(FanTicket.fromJson(ticketJson()).isPast(now.add(const Duration(hours: 1))), isTrue);
  });

  testWidgets('offline: a saved ticket renders and rotates across a slot boundary, with no call from screen 5', (
    tester,
  ) async {
    seedCache([ticketJson()]);
    final c = await pumpJomhor(
      tester,
      backend: offline,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(slotStart.add(const Duration(seconds: 2))),
      keepSecureStorage: true,
    );
    final launchCalls = calls.length; // the launch refresh, which failed
    await openTicket(tester, c);

    expect(find.text('Yazid A.'), findsOneWidget);
    expect(find.text('EEEE EEEE'), findsOneWidget);
    expect(find.text('Nord'), findsOneWidget);
    expect(find.text('ATLAS FC VS RAPID OUED'), findsOneWidget);
    expect(find.text('Fonctionne sans réseau'), findsOneWidget);
    final first = shownQr(tester);
    expect(first, tokenAt(slotStart));
    expect(find.textContaining('Nouveau code dans'), findsOneWidget);

    await tester.pump(const Duration(seconds: 29));
    final second = shownQr(tester);
    expect(second, tokenAt(slotStart.add(const Duration(seconds: 30))));
    expect(second, isNot(first));
    expect(verify(decode(second), eventId: 7, eventKey: eventKey, nowSlot: slotAt(slotStart) + 1), isNull);

    await tester.pump(const Duration(seconds: 30));
    expect(shownQr(tester), tokenAt(slotStart.add(const Duration(seconds: 60))));
    expect(calls.length, launchCalls, reason: 'screen 5 makes no network call');
    expect(await savedCache(), isNotNull, reason: 'a failed refresh keeps the saved tickets');
  });

  testWidgets('the server offset is applied: a device clock 5 min ahead still shows the server slot', (tester) async {
    final server = slotStart.add(const Duration(seconds: 2));
    final c = await pumpJomhor(
      tester,
      backend: (r) async {
        calls.add(r);
        return r.url.path == '/v1/fan/tickets'
            ? json({
                'server_time': server.toIso8601String(),
                'tickets': [ticketJson()],
              })
            : json(<Object?>[]);
      },
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(server.add(const Duration(minutes: 5))),
    );
    final req = calls.singleWhere((r) => r.url.path == '/v1/fan/tickets');
    expect(req.url.queryParameters, {'with_keys': 'true'});
    expect(req.headers['Authorization'], 'Bearer jwt');

    await openTicket(tester, c);
    expect(shownQr(tester), tokenAt(server));
    expect(jsonDecode((await savedCache())!), containsPair('offset_ms', -5 * 60 * 1000));
  });

  testWidgets('a saved offset is applied after a restart (device 5 min ahead, offline)', (tester) async {
    seedCache([ticketJson()], offset: const Duration(minutes: -5));
    final c = await pumpJomhor(
      tester,
      backend: offline,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(slotStart.add(const Duration(minutes: 5, seconds: 2))),
      keepSecureStorage: true,
    );
    await openTicket(tester, c);
    expect(shownQr(tester), tokenAt(slotStart));
  });

  group('matchday mode at launch', () {
    final t0 = DateTime.parse('2027-03-13T19:00:00Z');

    testWidgets('from T0 - 4 h the app opens straight on the ticket, offline', (tester) async {
      seedCache([ticketJson()]);
      final c = await pumpJomhor(
        tester,
        backend: offline,
        tokens: MemoryTokenStore('jwt'),
        clock: TestClock(t0.subtract(const Duration(hours: 4))),
        keepSecureStorage: true,
      );
      expect(location(c), '/tickets/$ticketId');
      expect(find.text('MODE JOUR DE MATCH'), findsOneWidget);
      expect(find.byType(TicketQr), findsOneWidget);
    });

    testWidgets('a minute earlier it opens on Accueil, with the "Mon billet" strip', (tester) async {
      seedCache([ticketJson()]);
      final c = await pumpJomhor(
        tester,
        backend: offline,
        tokens: MemoryTokenStore('jwt'),
        clock: TestClock(t0.subtract(const Duration(hours: 4, minutes: 1))),
        keepSecureStorage: true,
      );
      expect(location(c), '/');
      expect(find.text('Mon billet · Championnat · Journée 11, Sam. 13 mars'), findsOneWidget);
      expect(find.text("Nord · Porte 4 · aujourd'hui à 19:00"), findsOneWidget);
      await tester.tap(find.byKey(const Key('home.myTicket')));
      await tester.pumpAndSettle();
      expect(location(c), '/tickets/$ticketId');
      expect(find.text('MODE JOUR DE MATCH'), findsNothing);
    });
  });

  testWidgets('Billets: upcoming then past; a ticket without K_t shows its status and no QR', (tester) async {
    seedCache([
      ticketJson(),
      ticketJson(id: otherId, status: 'CANCELLED', withKey: false, startsAt: '2027-01-10T19:00:00Z'),
    ]);
    final c = await pumpJomhor(tester, backend: offline, tokens: MemoryTokenStore('jwt'), keepSecureStorage: true);
    c.read(routerProvider).go('/tickets');
    await tester.pumpAndSettle();
    expect(find.text('Mes billets'), findsOneWidget);
    expect(find.text('Hors ligne · billets enregistrés sur ton téléphone'), findsOneWidget);
    expect(tester.getTopLeft(find.text('À venir')).dy, lessThan(tester.getTopLeft(find.text('Passés')).dy));
    expect(find.text('Billet annulé'), findsOneWidget);

    await openTicket(tester, c, otherId);
    expect(find.byKey(const Key('ticket.status')), findsOneWidget);
    expect(find.byType(TicketQr), findsNothing);
    // WhatsApp re-send: VALID tickets only.
    expect(tester.widget<ButtonStyleButton>(find.byKey(const Key('ticket.whatsapp'))).onPressed, isNull);
  });

  testWidgets('WhatsApp: a double tap sends one POST', (tester) async {
    seedCache([ticketJson()]);
    final answer = Completer<http.Response>();
    final c = await pumpJomhor(
      tester,
      backend: (r) async {
        if (!r.url.path.endsWith('/whatsapp')) return await offline(r);
        calls.add(r);
        return await answer.future;
      },
      tokens: MemoryTokenStore('jwt'),
      keepSecureStorage: true,
    );
    await openTicket(tester, c);
    final button = find.byKey(const Key('ticket.whatsapp'));
    await tester.tap(button);
    await tester.pump();
    await tester.tap(button, warnIfMissed: false);
    await tester.pump();
    answer.complete(http.Response('', 202));
    await tester.pumpAndSettle();
    expect(calls.where((r) => r.url.path.endsWith('/whatsapp')), hasLength(1));
  });

  testWidgets('a session change during the cache write of a refresh leaves no K_t of the old session', (tester) async {
    final cache = _GatedCache();
    final tokens = MemoryTokenStore('jwt');
    final c = await pumpJomhor(
      tester,
      backend: (r) async => json({
        'server_time': '2027-02-20T12:00:02Z',
        'tickets': [ticketJson()],
      }),
      tokens: tokens,
      overrides: [ticketCacheProvider.overrideWithValue(cache)],
    );
    expect(await savedCache(), isNotNull);
    // The token check before the write passes; the session changes between it and the write. A token swap does
    // not flip sessionProvider, so no rebuild wipes the cache: only the check after the write does.
    cache.beforeWrite = () => c.read(sessionProvider.notifier).signIn('jwt-2');
    await c.read(ticketsProvider.notifier).refresh();
    expect(await savedCache(), isNull);

    // Same window with a logout.
    await c.read(sessionProvider.notifier).signIn('jwt');
    await c.read(ticketsProvider.notifier).refresh();
    await tester.pumpAndSettle();
    cache.beforeWrite = () => c.read(sessionProvider.notifier).signOut();
    await c.read(ticketsProvider.notifier).refresh();
    await tester.pumpAndSettle();
    expect((tokens.token, await savedCache()), (null, null));
  });

  group('WhatsApp re-send', () {
    for (final (name, answer, copy) in [
      ('202', () => http.Response('', 202), 'Billet renvoyé sur WhatsApp'),
      ('429', () => apiError(429, 'DELIVERY_RATE_LIMITED'), "Ce billet a déjà été renvoyé 3 fois aujourd'hui"),
      ('409', () => apiError(409, 'TICKET_NOT_VALID'), "Ce billet n'est plus valide"),
      ('404', () => apiError(404, 'NOT_FOUND'), 'Ce billet ne peut pas être renvoyé pour le moment'),
      ('offline', () => throw Exception('no network'), 'Pas de connexion'),
    ]) {
      testWidgets('$name: one POST on tap, none before, and the matching copy', (tester) async {
        seedCache([ticketJson()]);
        final c = await pumpJomhor(
          tester,
          backend: (r) async {
            if (!r.url.path.endsWith('/whatsapp')) return await offline(r);
            calls.add(r);
            return answer();
          },
          tokens: MemoryTokenStore('jwt'),
          keepSecureStorage: true,
        );
        await openTicket(tester, c);
        await tester.pump(const Duration(seconds: 3));
        const whatsapp = '/v1/fan/tickets/$ticketId/whatsapp';
        expect(calls.where((r) => r.url.path == whatsapp), isEmpty, reason: 'no call without a tap');

        await tester.tap(find.byKey(const Key('ticket.whatsapp')));
        await tester.pumpAndSettle();
        final sent = calls.where((r) => r.url.path == whatsapp).toList();
        expect((sent.single.method, sent.single.headers['Authorization']), ('POST', 'Bearer jwt'));
        expect(find.textContaining(copy), findsOneWidget);
      });
    }
  });

  testWidgets('logout wipes the saved tickets and K_t', (tester) async {
    seedCache([ticketJson()]);
    final c = await pumpJomhor(tester, backend: offline, tokens: MemoryTokenStore('jwt'), keepSecureStorage: true);
    expect(await savedCache(), isNotNull);
    await c.read(sessionProvider.notifier).signOut();
    await tester.pumpAndSettle();
    expect(await savedCache(), isNull);
    expect(find.byKey(const Key('home.myTicket')), findsNothing);
  });

  testWidgets('a 401 on the tickets refresh wipes the saved tickets and signs out', (tester) async {
    seedCache([ticketJson()]);
    final tokens = MemoryTokenStore('jwt');
    await pumpJomhor(
      tester,
      backend: (r) async => r.url.path == '/v1/fan/tickets' ? apiError(401, 'UNAUTHORIZED') : json(<Object?>[]),
      tokens: tokens,
      keepSecureStorage: true,
    );
    expect(await savedCache(), isNull);
    expect(tokens.token, isNull);
  });

  testWidgets('resume after deep sleep across T0 - 4 h opens the ticket (re-anchored clock, not the stale one)', (
    tester,
  ) async {
    final t0 = DateTime.parse('2027-03-13T19:00:00Z');
    seedCache([ticketJson()]);
    final clock = TestClock(t0.subtract(const Duration(hours: 6)));
    final c = await pumpJomhor(
      tester,
      backend: offline,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      keepSecureStorage: true,
    );
    expect(location(c), '/');
    [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
    ].forEach(tester.binding.handleAppLifecycleStateChanged);
    // Deep sleep: the wall clock moves 3 h, the monotonic stopwatch does not (no pump in between).
    clock.now = clock.now.add(const Duration(hours: 3));
    [
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ].forEach(tester.binding.handleAppLifecycleStateChanged);
    await tester.pumpAndSettle();
    expect(location(c), '/tickets/$ticketId');
  });

  testWidgets('screen 5 shows "MODE JOUR DE MATCH" when T0 - 4 h is crossed while it is open', (tester) async {
    final t0 = DateTime.parse('2027-03-13T19:00:00Z');
    seedCache([ticketJson()]);
    final c = await pumpJomhor(
      tester,
      backend: offline,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(t0.subtract(const Duration(hours: 4, seconds: 5))),
      keepSecureStorage: true,
    );
    await openTicket(tester, c);
    expect(find.text('MODE JOUR DE MATCH'), findsNothing);
    await tester.pump(const Duration(seconds: 6));
    expect(find.text('MODE JOUR DE MATCH'), findsOneWidget);
  });

  testWidgets('the strip says "a commencé" once the event has started (never "dans -1 jours")', (tester) async {
    seedCache([ticketJson()]);
    // 01:00 venue time the day after a 19:00 kickoff, still inside the 12 h upcoming window.
    await pumpJomhor(
      tester,
      backend: offline,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(DateTime.utc(2027, 3, 14, 1)),
      keepSecureStorage: true,
    );
    expect(find.text('Nord · Porte 4 · a commencé à 19:00'), findsOneWidget);
  });

  testWidgets('an answer is not cached when the session token changed during the request', (tester) async {
    final answer = Completer<http.Response>();
    final tokens = MemoryTokenStore('jwt');
    await pumpJomhor(
      tester,
      backend: (r) => r.url.path == '/v1/fan/tickets' ? answer.future : Future.value(json(<Object?>[])),
      tokens: tokens,
    );
    tokens.token = 'jwt-of-another-account';
    answer.complete(
      json({
        'server_time': '2027-02-20T12:00:00Z',
        'tickets': [ticketJson()],
      }),
    );
    await tester.pumpAndSettle();
    expect(await savedCache(), isNull);
  });

  testWidgets('signed in, jomhor-fan://tickets lands on the Billets tab', (tester) async {
    final c = await pumpJomhor(tester, backend: offline, tokens: MemoryTokenStore('jwt'));
    c.read(routerProvider).go('jomhor-fan://tickets');
    await tester.pumpAndSettle();
    expect(location(c), '/tickets');
    expect(find.text('Mes billets'), findsOneWidget);
  });

  testWidgets('jomhor-fan://tickets opens the Billets tab, behind the login', (tester) async {
    final c = await pumpJomhor(tester, backend: offline);
    c.read(routerProvider).go('jomhor-fan://tickets');
    await tester.pumpAndSettle();
    expect(location(c), '/login');
    expect(isTicketsLink(Uri.parse('jomhor-fan://tickets/x')), isFalse);
    expect(isTicketsLink(Uri.parse('jomhor-fan://tickets?next=//evil')), isFalse);
  });

  testWidgets('a Keystore read failure on resume keeps the ticket, its slot and the cache', (tester) async {
    seedCache([ticketJson()]);
    final storage = _FlakyStorage();
    final c = await pumpJomhor(
      tester,
      backend: offline,
      tokens: MemoryTokenStore('jwt'),
      clock: TestClock(slotStart.add(const Duration(seconds: 2))),
      keepSecureStorage: true,
      overrides: [ticketCacheProvider.overrideWithValue(TicketCache(storage))],
    );
    await openTicket(tester, c);
    expect(find.byType(TicketQr), findsOneWidget);

    storage.failReads = true;
    [
      AppLifecycleState.inactive,
      AppLifecycleState.hidden,
      AppLifecycleState.paused,
      AppLifecycleState.hidden,
      AppLifecycleState.inactive,
      AppLifecycleState.resumed,
    ].forEach(tester.binding.handleAppLifecycleStateChanged);
    await tester.pump();
    await tester.pump(const Duration(seconds: 1));
    expect(find.byType(TicketQr), findsOneWidget);
    expect(shownQr(tester), tokenAt(slotStart));
    storage.failReads = false;
    expect(await savedCache(), isNotNull);
    expect(c.read(ticketsProvider).value?.snapshot, isNotNull);
  });
}

/// The in-memory secure storage, whose reads fail like a Keystore error while [failReads] is set.
class _FlakyStorage extends FlutterSecureStorage {
  bool failReads = false;

  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) {
    if (failReads) throw PlatformException(code: 'keystore');
    return super.read(key: key);
  }
}

/// Runs [beforeWrite] once, right before the next write.
class _GatedCache extends TicketCache {
  Future<void> Function()? beforeWrite;

  @override
  Future<void> write(Map<String, Object?> response, Duration offset) async {
    final hook = beforeWrite;
    beforeWrite = null;
    await hook?.call();
    await super.write(response, offset);
  }
}
