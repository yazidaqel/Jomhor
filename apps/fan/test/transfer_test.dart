import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/features/tickets/transfer.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

import 'helpers.dart';
import 'tickets_test.dart' show openTicket, otherId, savedCache, seedCache, slotStart, ticketId, ticketJson, ticketKey;

/// T0 − 90 for the 19:00 UTC kickoff of [ticketJson].
const transferUntil = '2027-03-13T17:30:00Z';

/// A VALID ticket inside its transfer window, as `/fan/tickets` lists it in Lot 4.
Map<String, Object?> openTicketJson({String id = ticketId, String? until = transferUntil}) => {
  ...ticketJson(id: id),
  'transfer_until': until,
  'transferred': null,
};

/// The same ticket once given away: CANCELLED, no k_t, `transferred` set.
Map<String, Object?> transferredJson({String id = ticketId, String toLabel = 'Karim B.'}) => {
  ...ticketJson(id: id, withKey: false),
  'status': 'CANCELLED',
  'transfer_until': transferUntil,
  'transferred': {'to_label': toLabel, 'at': '2027-02-20T12:00:05Z'},
};

/// The old K_t as the cache stores it.
final String oldKey = base64Url.encode(ticketKey);

/// A fake backend for transfers and re-issues; [tickets] is what `/fan/tickets` lists now.
class FakeFan {
  new(this.clock, [List<Map<String, Object?>>? tickets]) : tickets = tickets ?? [openTicketJson()];

  final TestClock clock;

  /// Server clock − device clock: `server_time` = device time + this.
  Duration serverAhead = Duration.zero;
  List<Map<String, Object?>> tickets;
  final requests = <http.Request>[];
  http.Response Function(http.Request r)? onTransfer;
  http.Response Function(http.Request r)? onReissue;
  bool offline = false;

  /// The next `/fan/tickets` answer waits for it (built when asked: the data of that moment).
  Completer<void>? stall;

  Future<http.Response> handle(http.Request r) async {
    requests.add(r);
    if (offline) throw Exception('no network');
    final path = r.url.path;
    if (r.method == 'POST' && path.endsWith('/transfer')) return onTransfer!(r);
    if (r.method == 'POST' && path.endsWith('/reissue')) return onReissue!(r);
    switch ((r.method, path)) {
      case ('GET', '/v1/fan/tickets'):
        final answer = json({'server_time': clock.now.add(serverAhead).toIso8601String(), 'tickets': tickets});
        if (stall case final s?) {
          stall = null;
          await s.future;
        }
        return answer;
      case ('GET', '/v1/fan/me'):
        return json({'id': 'u1', 'phone': '+212612345678', 'display_name': 'Yazid Aqel', 'locale': 'fr'});
      case ('GET', '/v1/fan/orders'):
        return json({'orders': <Object?>[]});
      case ('GET', '/v1/fan/events'):
        return json(<Object?>[]);
    }
    return apiError(404, 'NOT_FOUND');
  }

  List<http.Request> calls(String method, String pathEnd) =>
      requests.where((r) => r.method == method && r.url.path.endsWith(pathEnd)).toList();

  int get ticketReads => calls('GET', '/v1/fan/tickets').length;

  /// 201 as BE-28 answers, and the list from then on shows the ticket given away.
  http.Response accept(http.Request r) {
    tickets = [transferredJson()];
    return json({'transfer_id': 'tr-1', 'to_label': 'Karim B.', 'transferred_at': '2027-02-20T12:00:05Z'}, 201);
  }
}

http.Response refusal(int status, String code, Map<String, Object?> details) =>
    json({'code': code, 'message': code, 'details': details}, status);

void main() {
  final fr = lookupAppLocalizations(const Locale('fr'));

  Future<(ProviderContainer, FakeFan)> start(
    WidgetTester tester, {
    List<Map<String, Object?>>? tickets,
    DateTime? now,
    Duration serverAhead = Duration.zero,
  }) async {
    final clock = TestClock(now ?? slotStart.add(const Duration(seconds: 2)));
    final fan = FakeFan(clock, tickets)..serverAhead = serverAhead;
    seedCache(fan.tickets, offset: serverAhead);
    final c = await pumpJomhor(
      tester,
      backend: fan.handle,
      tokens: MemoryTokenStore('jwt'),
      clock: clock,
      keepSecureStorage: true,
    );
    return (c, fan);
  }

  Future<void> openSheet(WidgetTester tester, ProviderContainer c) async {
    await openTicket(tester, c);
    await tester.tap(find.byKey(const Key('ticket.transfer')));
    await tester.pumpAndSettle();
  }

  Future<void> fill(WidgetTester tester, {String phone = '06 61 23 45 67', String name = '  Karim   Benali '}) async {
    await tester.enterText(find.byKey(const Key('transfer.phone')), phone);
    await tester.enterText(find.byKey(const Key('transfer.name')), name);
    await tester.ensureVisible(find.byKey(const Key('transfer.next')));
    await tester.tap(find.byKey(const Key('transfer.next')));
    await tester.pumpAndSettle();
  }

  /// Keeps a finger on the hold button for [ms], in frames, then lifts it.
  Future<void> hold(WidgetTester tester, int ms) async {
    await tester.ensureVisible(find.byKey(const Key('transfer.hold')));
    await tester.pumpAndSettle();
    final finger = await tester.startGesture(tester.getCenter(find.byKey(const Key('transfer.hold'))));
    for (var t = 0; t < ms; t += 20) {
      await tester.pump(const Duration(milliseconds: 20));
    }
    await finger.up();
  }

  Offset slide(WidgetTester tester) => tester.widget<AnimatedSlide>(find.byKey(const Key('ticket.leave'))).offset;

  bool transferEnabled(WidgetTester tester) =>
      tester.widget<ButtonStyleButton>(find.byKey(const Key('ticket.transfer'))).onPressed != null;

  testWidgets('201: body {to_phone, name}; the card slides out, the list refetches with keys, the old K_t is gone '
      'and Billets shows "Transféré à Karim B."', (tester) async {
    final haptics = <Object?>[];
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'HapticFeedback.vibrate') haptics.add(call.arguments);
      return null;
    });
    addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    expect(await savedCache(), contains(oldKey));

    await openSheet(tester, c);
    await fill(tester);
    // The confirm step shows what will be sent: the normalised phone and name.
    expect(tester.widget<Text>(find.byKey(const Key('transfer.confirmPhone'))).data, '+212661234567');
    expect(tester.widget<Text>(find.byKey(const Key('transfer.confirmName'))).data, 'Karim Benali');
    expect(find.text('Ton QR ne marchera plus. Karim Benali reçoit un nouveau billet.'), findsOneWidget);
    final readsBefore = fan.ticketReads;

    await hold(tester, 1000);
    // The slide-out starts once the sheet has closed on the 201.
    for (var i = 0; i < 100 && slide(tester) == Offset.zero; i++) {
      await tester.pump(const Duration(milliseconds: 16));
    }
    expect(slide(tester), isNot(Offset.zero));
    expect(
      tester.widget<AnimatedSlide>(find.byKey(const Key('ticket.leave'))).duration,
      const Duration(milliseconds: 420),
    );
    expect(haptics, contains('HapticFeedbackType.heavyImpact'));
    await tester.pumpAndSettle();

    final post = fan.calls('POST', '/fan/tickets/$ticketId/transfer').single;
    expect(jsonDecode(post.body), {'to_phone': '+212661234567', 'name': 'Karim Benali'});
    final refetch = fan.calls('GET', '/v1/fan/tickets').last;
    expect(fan.ticketReads, greaterThan(readsBefore));
    expect(refetch.url.queryParameters['with_keys'], 'true');
    expect(await savedCache(), isNot(contains(oldKey)));
    expect(c.read(routerProvider).state.uri.path, '/tickets');
    expect(find.byKey(const Key('ticket.transferred.$ticketId')), findsOneWidget);
    expect(find.textContaining('Transféré à Karim B. · '), findsOneWidget);
    expect(find.text(fr.transferDone('Karim B.')), findsOneWidget);
  });

  testWidgets('the old K_t leaves the cache at the 201, even when the refetch then fails', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = (r) {
      final answer = fan.accept(r);
      fan.offline = true;
      return answer;
    };
    await openSheet(tester, c);
    await fill(tester);
    await hold(tester, 1000);
    await tester.pumpAndSettle();

    final saved = await savedCache();
    expect(saved, isNot(contains(oldKey)));
    expect(saved, contains('"transferred"'));
    final ticket = c.read(ticketsProvider).value!.snapshot!.tickets.single;
    expect((ticket.status, ticket.hasQr, ticket.transferred?.toLabel), ('CANCELLED', false, 'Karim B.'));
    expect(find.textContaining('Transféré à Karim B. · '), findsOneWidget);
  });

  testWidgets('an answer to a request sent before the transfer does not bring the old K_t back', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    await openSheet(tester, c);
    await fill(tester);
    final stale = fan.stall = Completer<void>();
    unawaited(c.read(ticketsProvider.notifier).refresh()); // e.g. a resume, just before the hold
    await tester.pump();
    await hold(tester, 1000);
    await tester.pumpAndSettle();
    stale.complete(); // lands last, with the old ticket and its K_t
    await tester.pumpAndSettle();

    expect(await savedCache(), isNot(contains(oldKey)));
    expect(c.read(ticketsProvider).value!.snapshot!.tickets.single.hasQr, isFalse);
  });

  testWidgets('hold-to-confirm needs the full hold: a short press sends nothing', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    await openSheet(tester, c);
    await fill(tester);

    await hold(tester, 500);
    await tester.pumpAndSettle();
    expect(fan.calls('POST', '/transfer'), isEmpty);
    expect(find.byKey(const Key('transfer.hold')), findsOneWidget, reason: 'still on the confirm step');

    await hold(tester, 1000);
    await tester.pumpAndSettle();
    expect(fan.calls('POST', '/transfer'), hasLength(1));
  });

  testWidgets('the form refuses a bad phone or name before any call; "Modifier" goes back', (tester) async {
    final (c, fan) = await start(tester);
    await openSheet(tester, c);
    await fill(tester, phone: '12345', name: 'Karim كريم');
    expect(find.text(fr.holderPhoneError('06 12 34 56 78')), findsOneWidget);
    expect(find.text(fr.holderNameError), findsOneWidget);
    expect(find.byKey(const Key('transfer.hold')), findsNothing);

    await fill(tester);
    await tester.tap(find.byKey(const Key('transfer.edit')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('transfer.next')), findsOneWidget);
    expect(fan.calls('POST', '/transfer'), isEmpty);
  });

  testWidgets('own phone (422 SELF) → the phone field error, back on the form, no refetch', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = (_) => refusal(422, 'VALIDATION_FAILED', {
      'fields': {'to_phone': 'not your own phone: use a re-issue'},
      'reason': 'SELF',
    });
    await openSheet(tester, c);
    await fill(tester, phone: '0612345678');
    final reads = fan.ticketReads;
    await hold(tester, 1000);
    await tester.pumpAndSettle();
    expect(find.text(fr.transferSelf), findsOneWidget);
    expect(find.byKey(const Key('transfer.phone')), findsOneWidget);
    expect(fan.ticketReads, reads);
  });

  testWidgets('a 422 on name → the name field error', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = (_) => refusal(422, 'VALIDATION_FAILED', {
      'fields': {'name': 'invalid'},
    });
    await openSheet(tester, c);
    await fill(tester);
    await hold(tester, 1000);
    await tester.pumpAndSettle();
    expect(find.text(fr.holderNameError), findsOneWidget);
  });

  group('the action is disabled with its copy', () {
    testWidgets('after transfer_until, including when it passes while screen 5 is open', (tester) async {
      final (c, _) = await start(tester, now: DateTime.utc(2027, 3, 13, 17, 29, 58));
      await openTicket(tester, c);
      expect(transferEnabled(tester), isTrue);
      expect(find.byKey(const Key('ticket.transferBlocked')), findsNothing);
      await tester.pump(const Duration(seconds: 3));
      expect(transferEnabled(tester), isFalse);
      expect(find.text('Transferts fermés 1 h 30 min avant le début'), findsOneWidget);
    });

    testWidgets('without a cutoff (transfer_until null)', (tester) async {
      final (c, _) = await start(tester, tickets: [openTicketJson(until: null)]);
      await openTicket(tester, c);
      expect(transferEnabled(tester), isFalse);
      expect(find.text(fr.transferUnavailable), findsOneWidget);
    });

    testWidgets('a ticket that is not VALID: off, and the card says why', (tester) async {
      final (c, _) = await start(tester, tickets: [transferredJson()]);
      await openTicket(tester, c);
      expect(transferEnabled(tester), isFalse);
      expect(find.byKey(const Key('ticket.transferBlocked')), findsNothing);
      expect(find.textContaining('Transféré à Karim B.'), findsOneWidget);
    });

    for (final (name, answer, copy) in [
      (
        'TRANSFER_CLOSED from the server (device behind): its transfer_until',
        refusal(409, 'TRANSFER_CLOSED', {'transfer_until': '2027-03-13T18:00:00Z'}),
        'Transferts fermés 1 h avant le début',
      ),
      (
        'an admitted ticket (TICKET_NOT_VALID, ADMITTED)',
        refusal(409, 'TICKET_NOT_VALID', {'reason': 'ADMITTED'}),
        fr.transferAdmitted,
      ),
    ]) {
      testWidgets(name, (tester) async {
        final (c, fan) = await start(tester);
        fan.onTransfer = (_) => answer;
        await openSheet(tester, c);
        await fill(tester);
        final reads = fan.ticketReads;
        await hold(tester, 1000);
        await tester.pumpAndSettle();
        expect(
          tester
              .widget<Text>(find.descendant(of: find.byKey(const Key('transfer.failure')), matching: find.byType(Text)))
              .data,
          copy,
        );
        expect(fan.ticketReads, greaterThan(reads), reason: 'the list is fetched again');
        await tester.tap(find.byKey(const Key('transfer.close')));
        await tester.pumpAndSettle();
        expect(transferEnabled(tester), isFalse);
        expect(find.text(copy), findsOneWidget);
      });
    }
  });

  for (final (name, failure) in [('offline', null), ('a 503', refusal(503, 'UNAVAILABLE', {}))]) {
    testWidgets('unknown outcome ($name): the copy says to check, and a new request fetches the list', (tester) async {
      final (c, fan) = await start(tester);
      // The server did transfer the ticket; only its answer was lost.
      fan.onTransfer = (_) {
        fan.tickets = [transferredJson()];
        return failure ?? (throw Exception('no network'));
      };
      await openSheet(tester, c);
      await fill(tester);
      final stale = fan.stall = Completer<void>();
      unawaited(c.read(ticketsProvider.notifier).refresh()); // sent before the POST: the old ticket and its K_t
      await tester.pump();
      final reads = fan.ticketReads;
      await hold(tester, 1000);
      await tester.pumpAndSettle();
      expect(find.text(fr.transferUnknown), findsOneWidget);
      expect(fan.ticketReads, greaterThan(reads), reason: 'a new request, not the one sent before the POST');
      stale.complete(); // lands last
      await tester.pumpAndSettle();
      expect(await savedCache(), isNot(contains(oldKey)));
      expect(c.read(ticketsProvider).value!.snapshot!.tickets.single.transferred?.toLabel, 'Karim B.');
      expect(c.read(routerProvider).state.uri.path, '/tickets/$ticketId', reason: 'no slide-out without a 201');
    });
  }

  testWidgets('a re-issue whose 201 was lost: a new request fetches the list, the old K_t does not come back', (
    tester,
  ) async {
    const newId = 'aaaaaaaa-bbbb-4ccc-8ddd-000000000001';
    final (c, fan) = await start(tester);
    fan.onReissue = (_) {
      fan.tickets = [
        {...openTicketJson(id: newId), 'k_t': base64Url.encode(List.filled(32, 7))},
      ];
      throw Exception('no network');
    };
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    final stale = fan.stall = Completer<void>();
    unawaited(c.read(ticketsProvider.notifier).refresh());
    await tester.pump();
    final reads = fan.ticketReads;
    await tester.ensureVisible(find.byKey(const Key('profile.lostPhone')));
    await tester.tap(find.byKey(const Key('profile.lostPhone')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('lostPhone.confirm')));
    await tester.pumpAndSettle();
    expect(fan.ticketReads, greaterThan(reads));
    stale.complete();
    await tester.pumpAndSettle();
    expect(await savedCache(), isNot(contains(oldKey)));
    expect(c.read(ticketsProvider).value!.snapshot!.tickets.single.id, newId);
    expect(find.text(fr.lostPhoneFailed), findsOneWidget);
  });

  testWidgets('screen readers confirm with the long-press action; there is no tap action', (tester) async {
    final semantics = tester.ensureSemantics();
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    await openSheet(tester, c);
    await fill(tester);
    final node = tester.getSemantics(find.byKey(const Key('transfer.hold')));
    final data = node.getSemanticsData();
    expect(data.hasAction(SemanticsAction.longPress), isTrue);
    expect(data.hasAction(SemanticsAction.tap), isFalse);
    expect(data.label, fr.transferHold);
    expect(data.hint, fr.transferHoldHint);
    expect(fan.calls('POST', '/transfer'), isEmpty);
    tester.semantics.performAction(find.semantics.byLabel(fr.transferHold), SemanticsAction.longPress);
    await tester.pumpAndSettle();
    expect(fan.calls('POST', '/transfer'), hasLength(1));
    semantics.dispose();
  });

  testWidgets('a hardware keyboard holds Space on the focused control: a short press sends nothing', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    await openSheet(tester, c);
    await fill(tester);
    Focus.of(tester.element(find.byKey(const Key('hold.fill')))).requestFocus();
    await tester.pump();
    Future<void> press(LogicalKeyboardKey key, int ms) async {
      await tester.sendKeyDownEvent(key);
      for (var t = 0; t < ms; t += 20) {
        await tester.pump(const Duration(milliseconds: 20));
      }
      await tester.sendKeyUpEvent(key);
      await tester.pumpAndSettle();
    }

    await press(LogicalKeyboardKey.space, 300);
    await press(LogicalKeyboardKey.enter, 300);
    expect(fan.calls('POST', '/transfer'), isEmpty);
    await press(LogicalKeyboardKey.space, 1000);
    expect(fan.calls('POST', '/transfer'), hasLength(1));
  });

  testWidgets('focus lost mid-hold ends the hold: no transfer, the fill drains', (tester) async {
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    await openSheet(tester, c);
    await fill(tester);
    final node = Focus.of(tester.element(find.byKey(const Key('hold.fill'))))..requestFocus();
    await tester.pump();
    await tester.sendKeyDownEvent(LogicalKeyboardKey.space);
    for (var t = 0; t < 400; t += 20) {
      await tester.pump(const Duration(milliseconds: 20));
    }
    node.unfocus();
    for (var t = 0; t < 1000; t += 20) {
      await tester.pump(const Duration(milliseconds: 20));
    }
    await tester.pumpAndSettle();
    expect(fan.calls('POST', '/transfer'), isEmpty);
    expect(tester.widget<Align>(find.byKey(const Key('hold.fill'))).widthFactor, 0);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.space);
    await tester.pumpAndSettle();
  });

  testWidgets('"Téléphone perdu ?" without a re-issuable ticket says so, without the sign-out line', (tester) async {
    final (c, _) = await start(tester, tickets: [openTicketJson(until: null)]);
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('profile.lostPhone')));
    await tester.tap(find.byKey(const Key('profile.lostPhone')));
    await tester.pumpAndSettle();
    expect(find.text(fr.lostPhoneNone), findsOneWidget);
    expect(find.text(fr.lostPhoneSignedOut), findsNothing);
    expect(find.byKey(const Key('lostPhone.confirm')), findsNothing);
  });

  for (final (name, serverAhead, open) in [
    ('device clock 10 min ahead of the server, past transfer_until on the device: still open', -10, true),
    ('device clock 10 min behind the server, before transfer_until on the device: closed', 10, false),
  ]) {
    testWidgets('server time decides: $name', (tester) async {
      final device = DateTime.utc(2027, 3, 13, 17, 30).subtract(Duration(minutes: serverAhead.sign * 5));
      final (c, _) = await start(
        tester,
        now: device,
        serverAhead: Duration(minutes: serverAhead),
      );
      await openTicket(tester, c);
      expect(transferEnabled(tester), open);
      expect(find.text('Transferts fermés 1 h 30 min avant le début'), open ? findsNothing : findsOneWidget);
    });
  }

  testWidgets('reduced motion: no slide-out, straight to Billets', (tester) async {
    tester.platformDispatcher.accessibilityFeaturesTestValue = const FakeAccessibilityFeatures(disableAnimations: true);
    addTearDown(tester.platformDispatcher.clearAccessibilityFeaturesTestValue);
    final (c, fan) = await start(tester);
    fan.onTransfer = fan.accept;
    await openSheet(tester, c);
    expect(tester.widget<AnimatedSlide>(find.byKey(const Key('ticket.leave'))).duration, Duration.zero);
    await fill(tester);
    await hold(tester, 1000);
    await tester.pumpAndSettle();
    expect(c.read(routerProvider).state.uri.path, '/tickets');
    expect(find.textContaining('Transféré à Karim B. · '), findsOneWidget);
  });

  testWidgets('"Téléphone perdu ?" lists the re-issuable tickets, re-issues them and refetches with keys', (
    tester,
  ) async {
    final newKey = base64Url.encode(List.filled(32, 7));
    final (c, fan) = await start(
      tester,
      tickets: [
        openTicketJson(),
        // No cutoff: cannot be re-issued, so not listed.
        {...openTicketJson(id: otherId, until: null), 'k_t': newKey},
      ],
    );
    fan.onReissue = (r) {
      fan.tickets = [
        {...openTicketJson(id: 'aaaaaaaa-bbbb-4ccc-8ddd-000000000001'), 'k_t': newKey},
        fan.tickets.last,
      ];
      return json({'ticket_id': 'aaaaaaaa-bbbb-4ccc-8ddd-000000000001'}, 201);
    };
    c.read(routerProvider).go('/profile');
    await tester.pumpAndSettle();
    await tester.ensureVisible(find.byKey(const Key('profile.lostPhone')));
    await tester.tap(find.byKey(const Key('profile.lostPhone')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('lostPhone.ticket.$ticketId')), findsOneWidget);
    expect(find.byKey(const Key('lostPhone.ticket.$otherId')), findsNothing);
    expect(find.text(fr.lostPhoneSignedOut), findsOneWidget);
    final reads = fan.ticketReads;

    await tester.tap(find.byKey(const Key('lostPhone.confirm')));
    await tester.pumpAndSettle();
    expect(fan.calls('POST', '/fan/tickets/$ticketId/reissue'), hasLength(1));
    expect(fan.calls('POST', '/reissue'), hasLength(1));
    expect(fan.ticketReads, greaterThan(reads));
    expect(fan.calls('GET', '/v1/fan/tickets').last.url.queryParameters['with_keys'], 'true');
    expect(await savedCache(), isNot(contains(oldKey)));
    expect(find.text(fr.lostPhoneDone(1)), findsOneWidget);
  });

  test('copy: the closing lead, and every refusal has its text in fr, ar and en', () {
    final t = FanTicket.fromJson(openTicketJson());
    expect(transferClosedText(fr, t), 'Transferts fermés 1 h 30 min avant le début');
    expect(
      transferClosedText(fr, t, until: DateTime.utc(2027, 3, 13, 18, 15)),
      'Transferts fermés 45 min avant le début',
    );
    expect(transferClosedText(fr, FanTicket.fromJson(openTicketJson(until: null))), fr.transferUnavailable);
    for (final locale in ['fr', 'ar', 'en']) {
      final l = lookupAppLocalizations(Locale(locale));
      final texts = {for (final r in TransferRefusal.values) transferRefusalText(l, t, TransferRefused(r))};
      expect(texts, hasLength(TransferRefusal.values.length), reason: '$locale: one text per refusal');
      if (locale != 'fr') {
        expect(
          texts.intersection({for (final r in TransferRefusal.values) transferRefusalText(fr, t, TransferRefused(r))}),
          isEmpty,
        );
      }
    }
  });

  test('blockOf: VALID inside the window is open; the server refusals win', () {
    final t = FanTicket.fromJson(openTicketJson());
    final before = DateTime.utc(2027, 3, 13, 17, 29, 59);
    expect(blockOf(t, before, const {}), isNull);
    expect(blockOf(t, DateTime.utc(2027, 3, 13, 17, 30), const {}), TransferBlock.closed);
    expect(blockOf(t, before, {ticketId: const TransferRefused(TransferRefusal.admitted)}), TransferBlock.admitted);
    expect(blockOf(FanTicket.fromJson(transferredJson()), before, const {}), TransferBlock.notValid);
  });
}
