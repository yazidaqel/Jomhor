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
  new(this.order, DateTime? serverDate, DateTime deviceNow)
    : _holdLeft = order.reservedUntil?.difference(serverDate ?? deviceNow),
      _since = clock.stopwatch()..start();

  final FanOrder order;
  final Duration? _holdLeft;
  final Stopwatch _since;

  /// No hold time left (or none reported).
  bool get holdOver => switch (_holdLeft) {
    final left? => left - _since.elapsed <= Duration.zero,
    null => true,
  };
}

/// `GET /fan/orders/{id}` every [OrderStatus.every] until the order settles (PAID, EXPIRED, CANCELLED).
/// The card callback reaches the server on its own path, so the return URL alone proves nothing.
class OrderStatus extends AsyncNotifier<OrderView> {
  new(this.id);

  final String id;
  static const every = Duration(seconds: 2);

  @override
  Future<OrderView> build() async {
    try {
      final (order, serverDate) = await ref.watch(checkoutRepositoryProvider).order(id);
      if (!order.settled) _again();
      return OrderView(order, serverDate, ref.read(nowProvider)());
    } on ApiError catch (e) {
      if (e.code == ApiError.network) _again(); // A blip while waiting for the bank: keep asking.
      rethrow;
    }
  }

  void _again() {
    final timer = Timer(every, ref.invalidateSelf);
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
/// has passed since the newest attempt.
typedef RePayState = ({bool inFlight, bool waitedLong});

/// Another card attempt from the order screen: one at a time, and the wait restarts with each `/pay`.
class RePay extends Notifier<RePayState> {
  new(this.orderId);

  final String orderId;
  Timer? _wait;

  @override
  RePayState build() {
    ref.onDispose(() => _wait?.cancel());
    _restartWait();
    return (inFlight: false, waitedLong: false);
  }

  void _restartWait() {
    _wait?.cancel();
    _wait = Timer(payAgainAfter, () => state = (inFlight: state.inFlight, waitedLong: true));
  }

  /// `POST /fan/orders/{id}/pay`, then [open] shows the card page. Returns the error to show, or null.
  Future<ApiError?> start(Future<void> Function(String redirectUrl) open) async {
    if (state.inFlight) return null;
    state = (inFlight: true, waitedLong: false);
    _restartWait();
    try {
      final start = await ref.read(checkoutRepositoryProvider).payByCard(orderId);
      if (!ref.mounted) return null;
      if (!isPaymentPage(start.redirectUrl)) return unexpectedAnswer;
      await open(start.redirectUrl);
      return null;
    } on ApiError catch (e) {
      return e;
    } on Object {
      return unexpectedAnswer;
    } finally {
      if (ref.mounted) {
        state = (inFlight: false, waitedLong: state.waitedLong);
        ref.invalidate(orderStatusProvider(orderId));
      }
    }
  }
}

final NotifierProviderFamily<RePay, RePayState, String> rePayProvider = NotifierProvider.autoDispose
    .family<RePay, RePayState, String>(RePay.new);

/// A `/pay` answer the app will not act on (e.g. a redirect to another origin).
const unexpectedAnswer = ApiError(status: 0, code: ApiError.unexpectedResponse, message: 'unexpected');
