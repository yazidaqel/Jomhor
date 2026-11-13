import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/checkout/cash_order.dart';
import 'package:jomhor_fan/features/checkout/group_view.dart';
import 'package:jomhor_fan/features/checkout/member_pay.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// After the card page (or the `jomhor-fan://orders/{id}` deep link): waits for PAID, then the
/// success card slides in. An AWAITING_CASH order shows its code ([CashOrder]). The ticket itself is FL-7 (Billets).
///
/// The first PAID order on this phone asks for the push permission (lot-4 Q126), whatever the variant: a paid group
/// initiator (screen 9), a friend's paid place or a plain order.
class OrderScreen extends ConsumerStatefulWidget {
  const new({required this.orderId, super.key});

  final String orderId;

  @override
  ConsumerState<OrderScreen> createState() => _OrderScreenState();
}

class _OrderScreenState extends ConsumerState<OrderScreen> {
  var _paidSeen = false;

  @override
  void initState() {
    super.initState();
    ref.listenManual(orderStatusProvider(widget.orderId), fireImmediately: true, (_, next) {
      if (_paidSeen || !(next.value?.order.paid ?? false)) return;
      _paidSeen = true;
      unawaited(maybeAskPermission(ref.read(pushMessagingProvider)));
    });
  }

  @override
  Widget build(BuildContext context) {
    final orderId = widget.orderId;
    final l = AppLocalizations.of(context);
    final status = ref.watch(orderStatusProvider(orderId));
    final rePay = ref.watch(rePayProvider(orderId));
    return Scaffold(
      appBar: AppBar(
        leading: CloseButton(onPressed: () => context.go('/')),
        title: Text(l.payTitle),
      ),
      body: SafeArea(
        child: switch (status) {
          // The scrolling variants: a cash code still payable, a split-pay group or a friend's place to pay.
          AsyncValue(value: final view?) => switch (orderVariant(view, rePay)) {
            OrderVariant.cash => CashOrder(view: view),
            OrderVariant.group => GroupView(view: view),
            OrderVariant.member => MemberPay(view: view),
            OrderVariant.memberExpired => const Padding(
              padding: EdgeInsets.all(JomhorSpace.xl),
              child: MemberExpired(),
            ),
            OrderVariant.plain => Padding(
              padding: const EdgeInsets.all(JomhorSpace.xl),
              child: _OrderState(view: view),
            ),
          },
          AsyncError(:final error) => Padding(
            padding: const EdgeInsets.all(JomhorSpace.xl),
            child: Center(
              child: ErrorPanel(error: error, onRetry: () => ref.invalidate(orderStatusProvider(orderId))),
            ),
          ),
          _ => const Center(child: CircularProgressIndicator()),
        },
      ),
    );
  }
}

/// What the order screen draws for one answer.
enum OrderVariant {
  /// A cash code still payable ([CashOrder]).
  cash,

  /// Screen 9 initiator: the buyer paid and friends pay their own places ([GroupView]).
  group,

  /// Screen 9 member: a friend kept this place, RESERVED with no attempt or a FAILED one ([MemberPay]).
  member,

  /// A friend's place released unpaid ([MemberExpired]).
  memberExpired,

  /// Everything else: waiting for the bank, paid, expired, pay again ([_OrderState]).
  plain,
}

/// [OrderVariant] for [view]. A member order follows the same "payer à nouveau" rule as any order ([payAgain]): the
/// member screen is where that payment happens, and a PENDING attempt shows "paiement en cours".
OrderVariant orderVariant(OrderView view, RePayState rePay) {
  final order = view.order;
  if (order.awaitingCash && !view.holdOver) return OrderVariant.cash;
  final group = order.group;
  if (group == null) return OrderVariant.plain;
  if (group.initiator) return order.paid ? OrderVariant.group : OrderVariant.plain;
  if (order.paid || order.awaitingCash) return OrderVariant.plain;
  final again = view.freshFor(rePay)
      ? payAgain(order, waitedLong: rePay.waitedLong, holdOver: view.holdOver)
      : PayAgain.no;
  if (order.settled || again == PayAgain.holdOver) return OrderVariant.memberExpired;
  return again == PayAgain.notStarted || again == PayAgain.failed ? OrderVariant.member : OrderVariant.plain;
}

class _OrderState extends ConsumerWidget {
  const new({required this.view});

  final OrderView view;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final order = view.order;
    if (order.paid) return const _Paid();
    // A cash code past its deadline (the expiry job may not have swept it yet) or its order expired.
    if (order.awaitingCash || (order.settled && order.paymentMethod == PayMethod.cashAgency)) {
      return CashExpired(eventId: order.eventId);
    }
    final rePay = ref.watch(rePayProvider(order.id));
    final again = view.freshFor(rePay)
        ? payAgain(order, waitedLong: rePay.waitedLong, holdOver: view.holdOver)
        : PayAgain.no; // Until a read sent after the newest attempt answers.
    if (order.settled || again == PayAgain.holdOver) return _Expired(eventId: order.eventId);
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Center(child: CircularProgressIndicator()),
        const SizedBox(height: JomhorSpace.xl),
        Text(
          again == PayAgain.notStarted ? l.orderAwaitingPayment : l.orderPending,
          key: const Key('order.pending'),
          style: JomhorType.h2,
          textAlign: TextAlign.center,
        ),
        const SizedBox(height: JomhorSpace.s),
        Text(
          switch (again) {
            PayAgain.no || PayAgain.holdOver => l.orderPendingHint,
            PayAgain.failed => l.orderPaymentFailed,
            PayAgain.notStarted => l.orderNotPaidYet,
            PayAgain.withCaution => l.orderPayAgainCaution,
          },
          key: const Key('order.hint'),
          style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
          textAlign: TextAlign.center,
        ),
        if (again != PayAgain.no) ...[
          const SizedBox(height: JomhorSpace.xl),
          OutlinedButton(
            key: const Key('order.payAgain'),
            onPressed: rePay.inFlight ? null : () => startPayment(context, ref, order.id, PayMethod.card),
            child: Text(again == PayAgain.notStarted ? l.payCta(l.dh(order.totalMad)) : l.payAgain),
          ),
        ],
      ],
    );
  }
}

/// The hold is over (or the order settled unpaid): say so and offer a new order for the same event.
class _Expired extends StatelessWidget {
  const new({required this.eventId});

  final int? eventId;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(l.orderExpired, key: const Key('order.expired'), style: JomhorType.h2, textAlign: TextAlign.center),
        const SizedBox(height: JomhorSpace.xl),
        if (eventId case final id?) ...[
          FilledButton(onPressed: () => context.go('/events/$id/zones'), child: Text(l.expiredCta)),
          const SizedBox(height: JomhorSpace.s),
        ],
        OutlinedButton(onPressed: () => context.go('/'), child: Text(l.backHome)),
      ],
    );
  }
}

/// "C'est validé !" slides up into place (DESIGN.md principle 6: motion that means something).
class _Paid extends ConsumerWidget {
  const new();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    return TweenAnimationBuilder<Offset>(
      tween: Tween(begin: const Offset(0, 0.4), end: Offset.zero),
      duration: motionOf(context, JomhorMotion.ticketFlip),
      curve: Curves.easeOutCubic,
      builder: (context, offset, child) => FractionalTranslation(translation: offset, child: child),
      child: Card(
        key: const Key('order.paid'),
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Center(
                child: TweenAnimationBuilder<double>(
                  tween: Tween(begin: 0.4, end: 1),
                  duration: motionOf(context, JomhorMotion.ticketFlip),
                  curve: Curves.easeOutBack,
                  builder: (context, s, child) => Transform.scale(scale: s, child: child),
                  child: Container(
                    width: 88,
                    height: 88,
                    decoration: const BoxDecoration(
                      color: JomhorColors.successBg,
                      shape: BoxShape.circle,
                      border: Border.fromBorderSide(BorderSide(color: JomhorColors.success, width: 2)),
                    ),
                    child: const Icon(Icons.check_rounded, size: 48, color: JomhorColors.successText),
                  ),
                ),
              ),
              const SizedBox(height: JomhorSpace.m),
              Text(l.orderPaidTitle, style: JomhorType.h1, textAlign: TextAlign.center),
              const SizedBox(height: JomhorSpace.s),
              Text(l.orderPaidBody, textAlign: TextAlign.center),
              const SizedBox(height: JomhorSpace.xl),
              FilledButton(
                key: const Key('order.seeTickets'),
                onPressed: () {
                  // The Billets list was read before this payment (a cash code may be paid hours later).
                  unawaited(ref.read(ticketsProvider.notifier).refresh());
                  context.go('/tickets');
                },
                child: Text(l.seeTickets),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
