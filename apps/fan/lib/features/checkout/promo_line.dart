import 'package:flutter/material.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';

/// "Code {CODE} · −{discount}" (DESIGN.md Lot 5 step 4, screen 4 and the order screen), in the accent, when the
/// server took something off [order]. The amount is `discount_mad` as sent: no maths here. At the start edge in a
/// summary card; [alignment] center on the order screen's centred states.
class PromoLine extends StatelessWidget {
  const new({required this.order, this.alignment = AlignmentDirectional.centerStart, super.key});

  final FanOrder order;
  final AlignmentGeometry alignment;

  @override
  Widget build(BuildContext context) {
    final code = order.promoCode;
    if (code == null || order.discountMad <= 0) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xs),
      child: Align(
        alignment: alignment,
        child: Text(
          l.promoLine(l.ltr(code), l.ltr('−${formatDh(order.discountMad)}')),
          key: const Key('promo.line'),
          textAlign: alignment == Alignment.center ? TextAlign.center : TextAlign.start,
          style: JomhorType.body.copyWith(color: JomhorColors.accent, fontWeight: FontWeight.w600),
        ),
      ),
    );
  }
}
