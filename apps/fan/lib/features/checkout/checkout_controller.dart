import 'dart:async';
import 'dart:math';

import 'package:clock/clock.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart';
import 'package:jomhor_fan/features/auth/phone.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_repository.dart';
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/checkout/method_rows.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';

/// What screen 4 is for: one price category of one event, `qty` places (from screen 3).
typedef CheckoutArgs = ({int eventId, String priceCategoryId, int qty});

enum CheckoutPhase {
  /// Holder rows being filled in.
  editing,

  /// A request is in flight (buttons off).
  working,

  /// `SALE_BUSY`: waiting [CheckoutState.retryIn] seconds before the same request goes again.
  busy,

  /// Places held: the countdown runs, "Payer" opens the card page.
  reserved,

  /// The hold ran out: the modal sends the fan back to screen 3.
  expired,
}

class CheckoutState {
  const new({
    this.phase = CheckoutPhase.editing,
    this.badNames = const {},
    this.badPhones = const {},
    this.error,
    this.retryIn,
    this.order,
    this.left,
    this.consentMissing = false,
    this.cardOpened = false,
    this.method,
    this.refused = const {},
    this.payingCash = false,
    this.splitTooLate = false,
    this.ownPhones = const {},
    this.limitRow,
  });

  final CheckoutPhase phase;

  /// `422 TOO_LATE`: sales close too soon for friends to pay later; the "Paie sa place" switches are off for good.
  final bool splitTooLate;

  /// Rows whose friend pays but whose phone is the buyer's own (`422 PHONE_REQUIRED`).
  final Set<int> ownPhones;

  /// The friend's row over their own account limit (`409 PER_ACCOUNT_LIMIT` with `details.field`), with its label.
  final ({int row, String label})? limitRow;

  /// A cash `/pay` is in flight: if the hold hits 0 meanwhile, its answer decides (a code issued means the
  /// order now waits for cash), so the "Réservation expirée" modal waits for it.
  final bool payingCash;

  /// A friend's phone is on a row and the Q21 confirmation is not ticked.
  final bool consentMissing;

  /// The card (or wallet) page was opened once: screen 4 never starts a second payment (the first may
  /// still succeed); the order screen decides when another attempt is safe.
  final bool cardOpened;

  /// The method the fan picked; null = the first one the event offers.
  final PayMethod? method;

  /// Methods `/pay` answered `METHOD_NOT_AVAILABLE` for: their rows are off.
  final Set<PayMethod> refused;

  /// Holder rows (0-based) whose name or phone is refused.
  final Set<int> badNames;
  final Set<int> badPhones;
  final ApiError? error;
  final int? retryIn;
  final FanOrder? order;

  /// Hold time left, on server time.
  final Duration? left;

  bool get busy => phase == CheckoutPhase.working || phase == CheckoutPhase.busy;

  /// The "MOYEN DE PAIEMENT" rows for [d] ([methodRowsOf]). A row `/pay` refused, or any row once the hosted page
  /// opened or while a request runs, is off.
  List<MethodRow> methodRows(EventDetail d) => methodRowsOf(d, canPick: _canPick);

  /// What screen 4's summary and CTA show (DESIGN.md Lot 4, screen 4): the places the buyer pays and their total,
  /// and the places friends pay themselves. Before the hold, from the rows ([paying] rows whose friend pays, at
  /// [unitMad] each); after it, from the order the server made.
  ({int mine, int friends, int totalMad}) summary({required int unitMad, required int qty, required int paying}) {
    if (order case final o?) {
      final members = o.group?.members ?? const <GroupMember>[];
      return (mine: o.places, friends: members.fold(0, (n, m) => n + m.places), totalMad: o.totalMad);
    }
    return (mine: qty - paying, friends: paying, totalMad: unitMad * (qty - paying));
  }

  /// The picked method, else the first one the event offers that `/pay` has not refused; null when none is left.
  PayMethod? selectedIn(EventDetail d) => method ?? d.paymentMethods.where((m) => !refused.contains(m)).firstOrNull;

  bool _canPick(PayMethod m) => !refused.contains(m) && !cardOpened && !busy;

  /// Hand-written copy: keeps the order and countdown, replaces the rest. A copyWith with "unset" for
  /// every nullable field is not worth a generator for this one state.
  CheckoutState next(
    CheckoutPhase phase, {
    ApiError? error,
    int? retryIn,
    Set<int>? badNames,
    Set<int>? badPhones,
    bool consentMissing = false,
    Duration? left,
    bool? splitTooLate,
    Set<int>? ownPhones,
    ({int row, String label})? limitRow,
  }) => CheckoutState(
    phase: phase,
    badNames: badNames ?? const {},
    badPhones: badPhones ?? const {},
    error: error,
    retryIn: retryIn,
    order: order,
    left: left ?? this.left,
    consentMissing: consentMissing,
    cardOpened: cardOpened,
    method: method,
    refused: refused,
    splitTooLate: splitTooLate ?? this.splitTooLate,
    ownPhones: ownPhones ?? const {},
    limitRow: limitRow,
  );
}

/// The signed-in fan (`GET /fan/me`): row 1 of the holders is prefilled with `display_name`.
final FutureProvider<FanUser> meProvider = FutureProvider.autoDispose<FanUser>(
  (ref) => ref.watch(authRepositoryProvider).me(),
);

final RegExp _fieldPath = RegExp(r'^items\[0\]\.holders\[(\d+)\]\.(name|phone|pays)$');
final RegExp _holderPath = RegExp(r'^items\[0\]\.holders\[(\d+)\]$');

class CheckoutController extends Notifier<CheckoutState> {
  new(this.args);

  final CheckoutArgs args;
  Timer? _hold;
  Timer? _busyTick;

  @override
  CheckoutState build() {
    ref.onDispose(() {
      _hold?.cancel();
      _busyTick?.cancel();
    });
    return const CheckoutState();
  }

  CheckoutRepository get _repo => ref.read(checkoutRepositoryProvider);

  /// The fan picks a method row (any time before the hosted page opens).
  void select(PayMethod method) {
    if (state.cardOpened || state.refused.contains(method)) return;
    state = _with(state, method: method);
  }

  /// Validates the rows (row 0 is the fan: no phone), saves an edited name on `/fan/me`, holds the places
  /// (retrying `SALE_BUSY` on its own) and starts the payment with [method]. Rows in [pays] whose friend has a phone
  /// pay their own place (split pay), unless split pay was refused as too late. Returns where to send the fan
  /// to pay (the hosted page, or the cash order screen), or null when something needs their attention
  /// (state says what).
  Future<PayStart?> submit(
    List<String> names,
    List<String> phones, {
    required bool friendsConsent,
    PayMethod method = PayMethod.card,
    Set<int> pays = const {},
  }) async {
    if (state.cardOpened || state.phase == CheckoutPhase.working || state.phase == CheckoutPhase.busy) return null;
    state = _with(state, method: method);
    if (state.order != null) return await pay();

    final holders = <HolderInput>[];
    final badNames = <int>{};
    final badPhones = <int>{};
    for (var i = 0; i < args.qty; i++) {
      final name = normalizeHolderName(names[i]);
      final rawPhone = i == 0 ? '' : phones[i].trim();
      final phone = rawPhone.isEmpty ? null : normalizePhone(rawPhone);
      if (name == null) badNames.add(i);
      if (rawPhone.isNotEmpty && phone == null) badPhones.add(i);
      holders.add((name: name ?? '', phone: phone, pays: phone != null && pays.contains(i) && !state.splitTooLate));
    }
    // Q21: friends get their ticket on WhatsApp; the buyer confirms they agree.
    final consentMissing = !friendsConsent && holders.any((h) => h.phone != null);
    if (badNames.isNotEmpty || badPhones.isNotEmpty || consentMissing) {
      state = state.next(
        CheckoutPhase.editing,
        badNames: badNames,
        badPhones: badPhones,
        consentMissing: consentMissing,
      );
      return null;
    }

    state = state.next(CheckoutPhase.working);
    try {
      // Leaving the screen (Cancel, back) disposes this controller: whatever is in flight stops here and
      // never goes on to hold places, /pay or the card page.
      await _saveMyName(holders.first.name);
      if (!ref.mounted) return null;
      final (order, serverDate) = await _reserveWhenNotBusy(holders);
      if (!ref.mounted) return null;
      _startHold(order, serverDate);
    } on ApiError catch (e) {
      if (ref.mounted) state = _failed(e, holders);
      return null;
    } on Object {
      if (ref.mounted) state = state.next(CheckoutPhase.editing, error: unexpectedAnswer);
      return null;
    }
    return await pay();
  }

  /// `POST /fan/orders/{id}/pay` for the held order with the picked method, until the hosted page has been
  /// opened once. A hosted payment returns null (no payment page) once the hold ran out, even if it ran out
  /// while `/pay` was in flight. A cash code ends screen 4 even then: the code exists and the order now waits
  /// for cash until its own deadline.
  Future<PayStart?> pay() async {
    final order = state.order!;
    final method = state.method ?? PayMethod.card;
    if (state.phase == CheckoutPhase.expired) return null;
    state = _with(state.next(CheckoutPhase.working), payingCash: !method.hosted);
    // Kept before the await: leaving screen 4 mid-/pay disposes this controller, yet the strip must still refresh.
    final container = ref.container;
    try {
      final start = await _repo.pay(order.id, method);
      if (!ref.mounted) return null;
      if (!start.method.hosted) {
        _hold?.cancel();
        state = state.next(CheckoutPhase.reserved);
        return start;
      }
      if (state.phase == CheckoutPhase.expired) return null;
      if (!isPaymentPage(start.redirectUrl!)) {
        state = state.next(CheckoutPhase.reserved, error: unexpectedAnswer);
        return null;
      }
      state = state.next(CheckoutPhase.reserved);
      return start;
    } on ApiError catch (e) {
      if (!ref.mounted) return null;
      if (e.code == 'ORDER_EXPIRED' || state.phase == CheckoutPhase.expired) {
        state = state.next(CheckoutPhase.expired);
      } else if (e.code == 'METHOD_NOT_AVAILABLE') {
        // TOO_LATE, CASH_CAP, NOT_CONFIGURED: that row goes off (and loses the selection), the fan picks another.
        state = _with(state.next(CheckoutPhase.reserved, error: e), refused: {...state.refused, method});
      } else {
        state = state.next(CheckoutPhase.reserved, error: e);
      }
      return null;
    } on Object {
      if (!ref.mounted) return null;
      state = state.phase == CheckoutPhase.expired
          ? state.next(CheckoutPhase.expired)
          : state.next(CheckoutPhase.reserved, error: unexpectedAnswer);
      return null;
    } finally {
      // A cash /pay that failed on our side (a timeout, say) may still have issued the code: screen 1's strip
      // asks the server, so the code stays reachable even when this screen shows the hold as over, or is gone.
      if (!method.hosted) container.invalidate(pendingCashProvider);
    }
  }

  Future<void> _saveMyName(String name) async {
    FanUser? me;
    try {
      me = await ref.read(meProvider.future);
    } on Object {
      me = null; // Unknown: save it anyway, PATCH is idempotent.
    }
    if (me?.displayName != name) {
      await ref.read(authRepositoryProvider).setDisplayName(name);
      ref.invalidate(profileProvider); // The Profil tab shows the new name.
    }
  }

  /// `SALE_BUSY` answers wait `retry_after_s` (+ jitter up to 3 s) with a visible countdown, then the same
  /// request goes again. No queue position exists, so none is shown.
  Future<(FanOrder, DateTime?)> _reserveWhenNotBusy(List<HolderInput> holders) async {
    while (true) {
      try {
        return await _repo.reserve(eventId: args.eventId, priceCategoryId: args.priceCategoryId, holders: holders);
      } on ApiError catch (e) {
        if (e.code != 'SALE_BUSY') rethrow;
        final after = switch (e.details['retry_after_s']) {
          final int s => s.clamp(1, 60),
          _ => 10,
        };
        await _countDown(Duration(seconds: after) + ref.read(jitterProvider)(const Duration(seconds: 3)));
        if (!ref.mounted) rethrow;
        state = state.next(CheckoutPhase.working);
      }
    }
  }

  Future<void> _countDown(Duration wait) {
    final done = Completer<void>();
    var left = (wait.inMilliseconds / 1000).ceil();
    state = state.next(CheckoutPhase.busy, retryIn: left);
    _busyTick?.cancel();
    _busyTick = Timer.periodic(const Duration(seconds: 1), (t) {
      left--;
      if (left > 0) {
        state = state.next(CheckoutPhase.busy, retryIn: left);
      } else {
        t.cancel();
        done.complete();
      }
    });
    return done.future;
  }

  /// The countdown runs on server time: `reserved_until` minus the server's `Date` at the answer, then a
  /// monotonic stopwatch (changing the device clock does not move it).
  void _startHold(FanOrder order, DateTime? serverDate) {
    final since = clock.stopwatch()..start();
    final held = order.reservedUntil!.difference(serverDate ?? ref.read(nowProvider)());
    Duration left() {
      final d = held - since.elapsed;
      return d.isNegative ? Duration.zero : d;
    }

    state = CheckoutState(
      phase: CheckoutPhase.reserved,
      order: order,
      left: left(),
      method: state.method,
      refused: state.refused,
      splitTooLate: state.splitTooLate,
    );
    _hold = Timer.periodic(const Duration(seconds: 1), (t) {
      final l = left();
      final phase = l == Duration.zero ? CheckoutPhase.expired : state.phase;
      state = CheckoutState(
        phase: phase,
        order: state.order,
        left: l,
        error: state.error,
        retryIn: state.retryIn,
        badNames: state.badNames,
        badPhones: state.badPhones,
        consentMissing: state.consentMissing,
        cardOpened: state.cardOpened,
        method: state.method,
        refused: state.refused,
        payingCash: state.payingCash,
        splitTooLate: state.splitTooLate,
      );
      if (phase == CheckoutPhase.expired) t.cancel();
    });
  }

  /// The card (or wallet) page is open: from now on the order screen handles any further attempt.
  void cardOpened() => state = CheckoutState(
    phase: state.phase,
    order: state.order,
    left: state.left,
    cardOpened: true,
    method: state.method,
    refused: state.refused,
    splitTooLate: state.splitTooLate,
  );

  /// [s] with another method, refused set or cash flag; everything else kept. Giving [refused] also clears the
  /// method unless one is given (the refused row loses the selection).
  static CheckoutState _with(CheckoutState s, {PayMethod? method, Set<PayMethod>? refused, bool? payingCash}) =>
      CheckoutState(
        phase: s.phase,
        badNames: s.badNames,
        badPhones: s.badPhones,
        error: s.error,
        retryIn: s.retryIn,
        order: s.order,
        left: s.left,
        consentMissing: s.consentMissing,
        cardOpened: s.cardOpened,
        method: refused != null ? method : method ?? s.method,
        refused: refused ?? s.refused,
        payingCash: payingCash ?? s.payingCash,
        splitTooLate: s.splitTooLate,
        ownPhones: s.ownPhones,
        limitRow: s.limitRow,
      );

  CheckoutState _failed(ApiError e, List<HolderInput> holders) {
    // Split pay (docs/04-api.md §Split pay): too late to share, so every switch goes off and the fan pays again
    // without them.
    if (e.code == 'VALIDATION_FAILED' && e.details['reason'] == 'TOO_LATE') {
      return state.next(CheckoutPhase.editing, splitTooLate: true);
    }
    if (e.details['fields'] case final Map<String, Object?> fields when e.code == 'VALIDATION_FAILED') {
      final badNames = <int>{};
      final badPhones = <int>{};
      final ownPhones = <int>{};
      final mine = ref.read(meProvider).value?.phone;
      for (final key in fields.keys) {
        if (_fieldPath.firstMatch(key) case final m?) {
          final row = int.parse(m[1]!);
          switch (m[2]) {
            case 'name':
              badNames.add(row);
            // PHONE_REQUIRED on `pays` with the buyer's own number; any other phone problem marks the phone.
            case 'pays' when mine != null && row < holders.length && holders[row].phone == mine:
              ownPhones.add(row);
            default:
              badPhones.add(row);
          }
        }
      }
      if (badNames.isNotEmpty || badPhones.isNotEmpty || ownPhones.isNotEmpty) {
        return state.next(CheckoutPhase.editing, badNames: badNames, badPhones: badPhones, ownPhones: ownPhones);
      }
    }
    // A friend who pays is over their own limit: said on their row.
    if ((e.code, e.details['field']) case ('PER_ACCOUNT_LIMIT', final String field)) {
      if (_holderPath.firstMatch(field) case final m? when int.parse(m[1]!) < holders.length) {
        final row = int.parse(m[1]!);
        return state.next(CheckoutPhase.editing, limitRow: (row: row, label: holderLabel(holders[row].name)));
      }
    }
    // The PATCH /fan/me of row 1 answers a bad display_name with a 422 on `display_name`.
    if (e.details['fields'] case {'display_name': _} when e.code == 'VALIDATION_FAILED') {
      return state.next(CheckoutPhase.editing, badNames: {0});
    }
    return state.next(CheckoutPhase.editing, error: e);
  }
}

final NotifierProviderFamily<CheckoutController, CheckoutState, CheckoutArgs> checkoutControllerProvider =
    NotifierProvider.autoDispose.family<CheckoutController, CheckoutState, CheckoutArgs>(CheckoutController.new);

/// Places a fan can pick in one order: the account limit, and what is left in the category.
int maxPlaces({required int perAccountLimit, required int remaining}) => max(0, min(perAccountLimit, remaining));
