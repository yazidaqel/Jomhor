import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/checkout/cash_order.dart';
import 'package:jomhor_fan/features/checkout/method_rows.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:jomhor_fan/features/events/event_screen.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';

/// Screen 9, member (DESIGN.md Lot 4 and wow table): a friend kept this place. The event hero, "{initiator_label}
/// t'a gardé une place", the amount, the time left, the screen 4 method rows and the CTA. Card and wallet open the
/// hosted page, cash gets a code ([RePay]); the order screen then shows the cash code, "paiement en cours" or the paid
/// view. Shown while the order is RESERVED with no attempt or a FAILED one.
class MemberPay extends ConsumerStatefulWidget {
  const new({required this.view, super.key});

  final OrderView view;

  @override
  ConsumerState<MemberPay> createState() => _MemberPayState();
}

class _MemberPayState extends ConsumerState<MemberPay> {
  /// The row the fan picked; null = the first enabled one.
  PayMethod? _picked;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    ref.watch(clockProvider); // The countdown moves between two polls.
    final view = widget.view;
    final order = view.order;
    final detail = switch (order.eventId) {
      final id? => ref.watch(eventDetailProvider(id)).value,
      null => null,
    };
    final rePay = ref.watch(rePayProvider(order.id));
    final refused = ref.read(rePayProvider(order.id).notifier).refused;
    final rows = detail == null
        ? const <MethodRow>[]
        : methodRowsOf(detail, canPick: (m) => !rePay.inFlight && !refused.contains(m));
    // The picked row while it is on, else the first one on (a refused row loses the selection).
    final enabled = [
      for (final r in rows)
        if (r.enabled) r.method,
    ];
    final selected = enabled.contains(_picked) ? _picked : enabled.firstOrNull;
    final label = order.group?.initiatorLabel;
    final left = view.left;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: EdgeInsets.zero,
            children: [
              if (detail != null) EventHeader(event: detail.event),
              Padding(
                padding: const EdgeInsets.all(JomhorSpace.xl),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      label == null ? l.memberKeptAnon : l.memberKept(l.isolate(label)),
                      key: const Key('member.kept'),
                      style: JomhorType.h1,
                    ),
                    if (order.paymentStatus == 'FAILED') ...[
                      const SizedBox(height: JomhorSpace.s),
                      Text(
                        l.orderPaymentFailed,
                        key: const Key('member.failed'),
                        style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                      ),
                    ],
                    const SizedBox(height: JomhorSpace.l),
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            l.dh(order.totalMad),
                            key: const Key('member.amount'),
                            style: JomhorType.hero.copyWith(color: JomhorColors.accent),
                          ),
                        ),
                        SizedBox(
                          width: 76,
                          child: CountdownTile(
                            key: const Key('member.hours'),
                            value: left.inHours,
                            label: l.countdownHours,
                          ),
                        ),
                        const SizedBox(width: JomhorSpace.s),
                        SizedBox(
                          width: 76,
                          child: CountdownTile(
                            key: const Key('member.minutes'),
                            value: left.inMinutes % 60,
                            label: l.countdownMinutes,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: JomhorSpace.xl),
                    SectionLabel(l.methodTitle),
                    const SizedBox(height: JomhorSpace.xs),
                    if (detail != null)
                      MethodRows(
                        rows: rows,
                        event: detail.event,
                        selected: selected,
                        onSelect: (m) => setState(() => _picked = m),
                      ),
                  ],
                ),
              ),
            ],
          ),
        ),
        DecoratedBox(
          decoration: const BoxDecoration(
            color: JomhorColors.surface,
            border: Border(top: BorderSide(color: JomhorColors.border)),
          ),
          child: SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.all(JomhorSpace.l),
              child: FilledButton(
                key: const Key('member.pay'),
                // Off at 00:00: the next read then says the place was released.
                onPressed: rePay.inFlight || selected == null || view.holdOver
                    ? null
                    : () => startPayment(context, ref, order.id, selected),
                child: Text(payCtaText(l, selected, order.totalMad)),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// The friend's hold ran out (or the order was released): "La place a été libérée", back home.
class MemberExpired extends StatelessWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Icon(Icons.event_busy_outlined, size: 56, color: JomhorColors.textMuted),
        const SizedBox(height: JomhorSpace.l),
        Text(l.memberExpired, key: const Key('member.expired'), style: JomhorType.h2, textAlign: TextAlign.center),
        const SizedBox(height: JomhorSpace.xl),
        OutlinedButton(onPressed: () => context.go('/'), child: Text(l.backHome)),
      ],
    );
  }
}
