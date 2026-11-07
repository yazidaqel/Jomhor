import 'dart:async';

import 'package:clock/clock.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_repository.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

/// One answer of `GET /fan/orders/{id}` and the hold time it had left on server time (`reserved_until` minus
/// the answer's `Date`), counted down on a monotonic stopwatch from the answer.
class OrderView {
  new(this.order, DateTime? serverDate, DateTime deviceNow, {this.seenAttempts = 0})
    : _holdLeft = order.reservedUntil?.difference(serverDate ?? deviceNow),
      _since = clock.stopwatch()..start();

  final FanOrder order;

  /// `RePayState.attempts` when this read was sent: a read sent before the newest attempt ended may not show it.
  final int seenAttempts;

  /// This read was sent after every attempt from this screen ended, so it shows the latest one: only such a
  /// read may offer another card or wallet payment (an older one, still on screen while loading or after an
  /// error, could offer a second charge).
  bool freshFor(RePayState rePay) => seenAttempts >= rePay.attempts;
  final Duration? _holdLeft;
  final Stopwatch _since;

  /// Hold time left now, on server time; zero once over (or none reported).
  Duration get left => switch (_holdLeft) {
    final left? when left > _since.elapsed => left - _since.elapsed,
    _ => Duration.zero,
  };

  /// No hold time left (or none reported).
  bool get holdOver => left == Duration.zero;
}

/// `GET /fan/orders/{id}` every [OrderStatus.every] ([OrderStatus.everyCash] while a cash code waits) until
/// the order settles (PAID, EXPIRED, CANCELLED), the screen goes, or the answer is 401 / 404 (signed out, not
/// this fan's order). Network, 5xx and unreadable answers keep the poll going. The card callback reaches the
/// server on its own path, so the return URL alone proves nothing.
class OrderStatus extends AsyncNotifier<OrderView> {
  new(this.id);

  final String id;
  static const every = Duration(seconds: 2);

  /// A cash code is paid at a counter, minutes or hours away (DESIGN.md Lot 3, app cash order screen).
  static const everyCash = Duration(seconds: 10);

  @override
  Future<OrderView> build() async {
    final before = state.value?.order;
    final waitingCash = before?.awaitingCash ?? false;
    final seen = ref.read(rePayProvider(id)).attempts;
    try {
      final (order, serverDate) = await ref.watch(checkoutRepositoryProvider).order(id);
      // Into or out of AWAITING_CASH (paid, expired) since the last read: screen 1's strip reads it again. Not on
      // the first read: the strip (or the cash /pay) that led here already knows.
      if (before != null && order.awaitingCash != waitingCash) ref.invalidate(pendingCashProvider);
      if (!order.settled) _again(order.awaitingCash ? everyCash : every);
      return OrderView(order, serverDate, ref.read(nowProvider)(), seenAttempts: seen);
    } on ApiError catch (e) {
      // A blip while waiting for the bank or the counter (offline, 5xx, a proxy page): keep asking.
      if (e.status != 401 && e.status != 404) _again(waitingCash ? everyCash : every);
      rethrow;
    }
  }

  void _again(Duration after) {
    final timer = Timer(after, ref.invalidateSelf);
    ref.onDispose(timer.cancel);
  }
}

final AsyncNotifierProviderFamily<OrderStatus, OrderView, String> orderStatusProvider = AsyncNotifierProvider
    .autoDispose
    .family<OrderStatus, OrderView, String>(OrderStatus.new);

/// How long after the newest `/pay` (or landing on the order screen) a payment whose status is not reported
/// may be tried again.
const payAgainAfter = Duration(seconds: 60);

/// `inFlight`: a `/pay` (and its card page) is running, so the button is off. `waitedLong`: [payAgainAfter]
/// has passed since the newest attempt. `attempts`: attempts from this screen that have ended (see
/// [OrderView.freshFor]).
typedef RePayState = ({bool inFlight, bool waitedLong, int attempts});

/// Another card (or wallet) attempt from the order screen: one at a time, and the wait restarts with each `/pay`.
class RePay extends Notifier<RePayState> {
  new(this.orderId);

  final String orderId;
  Timer? _wait;

  @override
  RePayState build() {
    ref.onDispose(() => _wait?.cancel());
    _restartWait();
    return (inFlight: false, waitedLong: false, attempts: 0);
  }

  void _restartWait() {
    _wait?.cancel();
    _wait = Timer(payAgainAfter, () => state = (inFlight: state.inFlight, waitedLong: true, attempts: state.attempts));
  }

  /// `POST /fan/orders/{id}/pay` with a hosted [method] (card or wallet), then [open] shows its page. Returns
  /// the error to show, or null.
  Future<ApiError?> start(Future<void> Function(String redirectUrl) open, {PayMethod method = PayMethod.card}) async {
    if (state.inFlight || !method.hosted) return null;
    state = (inFlight: true, waitedLong: false, attempts: state.attempts);
    _restartWait();
    try {
      final start = await ref.read(checkoutRepositoryProvider).pay(orderId, method);
      if (!ref.mounted) return null;
      final url = start.redirectUrl!;
      if (!isPaymentPage(url)) return unexpectedAnswer;
      await open(url);
      return null;
    } on ApiError catch (e) {
      return e;
    } on Object {
      return unexpectedAnswer;
    } finally {
      if (ref.mounted) {
        // Counted once /pay and its page are over: only a read sent from now on shows this attempt.
        state = (inFlight: false, waitedLong: state.waitedLong, attempts: state.attempts + 1);
        ref.invalidate(orderStatusProvider(orderId));
      }
    }
  }
}

final NotifierProviderFamily<RePay, RePayState, String> rePayProvider = NotifierProvider.autoDispose
    .family<RePay, RePayState, String>(RePay.new);

/// A `/pay` answer the app will not act on (e.g. a redirect to another origin).
const unexpectedAnswer = ApiError(status: 0, code: ApiError.unexpectedResponse, message: 'unexpected');
