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
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
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
  });

  final CheckoutPhase phase;

  /// A friend's phone is on a row and the Q21 confirmation is not ticked.
  final bool consentMissing;

  /// The card page was opened once: screen 4 never starts a second payment (the first may still
  /// succeed); the order screen decides when another attempt is safe.
  final bool cardOpened;

  /// Holder rows (0-based) whose name or phone is refused.
  final Set<int> badNames;
  final Set<int> badPhones;
  final ApiError? error;
  final int? retryIn;
  final FanOrder? order;

  /// Hold time left, on server time.
  final Duration? left;

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
  );
}

/// The signed-in fan (`GET /fan/me`): row 1 of the holders is prefilled with `display_name`.
final FutureProvider<FanUser> meProvider = FutureProvider.autoDispose<FanUser>(
  (ref) => ref.watch(authRepositoryProvider).me(),
);

final RegExp _fieldPath = RegExp(r'^items\[0\]\.holders\[(\d+)\]\.(name|phone)$');

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

  /// Validates the rows (row 0 is the fan: no phone), saves an edited name on `/fan/me`, holds the places
  /// (retrying `SALE_BUSY` on its own) and starts the card payment. Returns where to send the fan to pay,
  /// or null when something needs their attention (state says what).
  Future<PayStart?> submit(List<String> names, List<String> phones, {required bool friendsConsent}) async {
    if (state.cardOpened || state.phase == CheckoutPhase.working || state.phase == CheckoutPhase.busy) return null;
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
      holders.add((name: name ?? '', phone: phone));
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
      if (ref.mounted) state = _failed(e);
      return null;
    } on Object {
      if (ref.mounted) state = state.next(CheckoutPhase.editing, error: unexpectedAnswer);
      return null;
    }
    return await pay();
  }

  /// `POST /fan/orders/{id}/pay` for the held order, until the card page has been opened once. Returns null
  /// (no card page) once the hold ran out, even if it ran out while `/pay` was in flight.
  Future<PayStart?> pay() async {
    final order = state.order!;
    if (state.phase == CheckoutPhase.expired) return null;
    state = state.next(CheckoutPhase.working);
    try {
      final start = await _repo.payByCard(order.id);
      if (!ref.mounted || state.phase == CheckoutPhase.expired) return null;
      if (!isPaymentPage(start.redirectUrl)) {
        state = state.next(CheckoutPhase.reserved, error: unexpectedAnswer);
        return null;
      }
      state = state.next(CheckoutPhase.reserved);
      return start;
    } on ApiError catch (e) {
      if (!ref.mounted) return null;
      state = e.code == 'ORDER_EXPIRED' || state.phase == CheckoutPhase.expired
          ? state.next(CheckoutPhase.expired)
          : state.next(CheckoutPhase.reserved, error: e);
      return null;
    } on Object {
      if (!ref.mounted) return null;
      state = state.phase == CheckoutPhase.expired
          ? state.next(CheckoutPhase.expired)
          : state.next(CheckoutPhase.reserved, error: unexpectedAnswer);
      return null;
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

    state = CheckoutState(phase: CheckoutPhase.reserved, order: order, left: left());
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
      );
      if (phase == CheckoutPhase.expired) t.cancel();
    });
  }

  /// The card page is open: from now on the order screen handles any further attempt.
  void cardOpened() =>
      state = CheckoutState(phase: state.phase, order: state.order, left: state.left, cardOpened: true);

  CheckoutState _failed(ApiError e) {
    if (e.details['fields'] case final Map<String, Object?> fields when e.code == 'VALIDATION_FAILED') {
      final badNames = <int>{};
      final badPhones = <int>{};
      for (final key in fields.keys) {
        if (_fieldPath.firstMatch(key) case final m?) {
          (m[2] == 'name' ? badNames : badPhones).add(int.parse(m[1]!));
        }
      }
      if (badNames.isNotEmpty || badPhones.isNotEmpty) {
        return state.next(CheckoutPhase.editing, badNames: badNames, badPhones: badPhones);
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
