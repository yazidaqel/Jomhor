import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/checkout/cash_order.dart' show startPayment;
import 'package:jomhor_fan/features/checkout/method_rows.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:jomhor_fan/features/passes/passes_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';

/// Screen 4, product variant (DESIGN.md Lot 5 step 3): a pass or membership order, RESERVED with no attempt or a
/// FAILED one. The hold, one summary line (the product's name, or 3b "Échéance {k}/{n}"; no zone, quantity or friend
/// rows), the holder, the screen 4 method rows from the order's `product` (`payment_methods`, `cash_until`), and the
/// CTA. Card and wallet open the hosted page, cash gets a code ([RePay]); the order screen then shows the code,
/// "paiement en cours" or the paid view. Amounts are the order's, as the server sent them.
class ProductPay extends ConsumerStatefulWidget {
  const new({required this.view, super.key});

  final OrderView view;

  @override
  ConsumerState<ProductPay> createState() => _ProductPayState();
}

class _ProductPayState extends ConsumerState<ProductPay> {
  /// The row the fan picked; null = the first enabled one.
  PayMethod? _picked;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    ref.watch(clockProvider); // The hold moves between two polls.
    final view = widget.view;
    final order = view.order;
    final item = order.product!;
    // Only the cash row's date reads the product (its sales close); off sale (404), 24 h from now on server time. A
    // later installment (3b, seq > 1) is held 24 h whatever the product's sales close: the product is not read.
    final installment = order.installment;
    final later = installment != null && installment.seq > 1;
    final product = later ? null : ref.watch(productProvider(item.id)).value;
    final rePay = ref.watch(rePayProvider(order.id));
    final refused = ref.read(rePayProvider(order.id).notifier).refused;
    final rows = methodRowsOf(
      item.paymentMethods,
      item.cashUntil,
      canPick: (m) => !rePay.inFlight && !refused.contains(m),
    );
    final enabled = [
      for (final r in rows)
        if (r.enabled) r.method,
    ];
    final selected = enabled.contains(_picked) ? _picked : enabled.firstOrNull;
    final holder = order.items.firstOrNull?.holderLabels.firstOrNull;
    // 3b: an order that pays an installment says which one ("Échéance 1/3" for the purchase of a plan, then 2/3…).
    final summary = switch (installment) {
      (:final seq, of: final n) => l.installmentOf(seq, n),
      null => item.name,
    };
    final left = view.left;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(JomhorSpace.xl),
            children: [
              InfoBanner(
                key: const Key('product.hold'),
                // Whole minutes, rounded up: the hold moves with the app's 30 s clock.
                text: l.productHoldLeft((left.inSeconds + 59) ~/ 60),
                success: true,
                icon: Icons.timer_outlined,
              ),
              if (order.paymentStatus == 'FAILED') ...[
                const SizedBox(height: JomhorSpace.s),
                Text(
                  l.orderPaymentFailed,
                  key: const Key('product.failed'),
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                ),
              ],
              const SizedBox(height: JomhorSpace.l),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(JomhorSpace.xl),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _Line(summary, l.dh(order.totalMad), key: const Key('product.summary')),
                      _Line(l.serviceFee, l.dh(0)),
                      const Divider(color: JomhorColors.border),
                      _Line(l.total, l.dh(order.totalMad), strong: true),
                      if (holder != null) ...[
                        const SizedBox(height: JomhorSpace.s),
                        Row(
                          children: [
                            const Icon(Icons.badge_outlined, size: 18, color: JomhorColors.textMuted),
                            const SizedBox(width: JomhorSpace.s),
                            Expanded(
                              child: Text(
                                l.productHolder(l.isolate(holder)),
                                key: const Key('product.holder'),
                                style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                              ),
                            ),
                          ],
                        ),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: JomhorSpace.xl),
              SectionLabel(l.methodTitle),
              const SizedBox(height: JomhorSpace.xs),
              MethodRows(
                rows: rows,
                cashDeadline: cashDeadlineAt(product?.fetchedAt ?? view.serverNow, product?.salesCloseAt),
                timezone: item.timezone,
                selected: selected,
                onSelect: (m) => setState(() => _picked = m),
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
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  FilledButton(
                    key: const Key('product.pay'),
                    // Off at 00:00: the next read then says the hold is over.
                    onPressed: rePay.inFlight || selected == null || view.holdOver
                        ? null
                        : () => startPayment(context, ref, order.id, selected),
                    child: Text(payCtaText(l, selected, order.totalMad)),
                  ),
                  const SizedBox(height: JomhorSpace.xs),
                  Text(
                    item.isPass ? l.productCaptionPass : l.productCaptionMembership,
                    key: const Key('product.caption'),
                    textAlign: TextAlign.center,
                    style: JomhorType.caption.copyWith(color: JomhorColors.textMuted),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _Line extends StatelessWidget {
  const new(this.label, this.amount, {this.strong = false, super.key});

  final String label;
  final String amount;
  final bool strong;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xs),
    child: Row(
      children: [
        Expanded(child: Text(label, style: strong ? JomhorType.h2 : JomhorType.body)),
        Text(amount, style: strong ? JomhorType.h2 : JomhorType.body),
      ],
    ),
  );
}
