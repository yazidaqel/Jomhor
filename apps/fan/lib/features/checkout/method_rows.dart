import 'package:flutter/material.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// One payment method row: `tooLate` = cash set up but past `cash_until`.
typedef MethodRow = ({PayMethod method, bool enabled, bool tooLate});

/// The "MOYEN DE PAIEMENT" rows for [d] (DESIGN.md Lot 3, screen 4; also screen 9 member): card and wallet as
/// offered; cash when offered, or off with "Trop tard pour le cash" when the organizer has cash (`cash_until`) but it
/// is too late now; no cash row at all without `cash_until`. A row [canPick] refuses is off.
List<MethodRow> methodRowsOf(EventDetail d, {required bool Function(PayMethod) canPick}) {
  final offered = d.paymentMethods;
  final cash = offered.contains(PayMethod.cashAgency);
  return [
    for (final m in offered.where((m) => m.hosted)) (method: m, enabled: canPick(m), tooLate: false),
    if (cash) (method: PayMethod.cashAgency, enabled: canPick(PayMethod.cashAgency), tooLate: false),
    if (!cash && d.cashUntil != null) (method: PayMethod.cashAgency, enabled: false, tooLate: true),
  ];
}

/// The cash code's deadline as the app can guess it before `/pay`: `least(now + 24 h, sales close)` on the server
/// time of the event read (docs/plans/lot-3.md Q45). `/pay` answers the real `reserved_until`.
DateTime cashDeadline(FanEvent e) {
  final day = e.fetchedAt.add(const Duration(hours: 24));
  return day.isBefore(e.salesCloseAt) ? day : e.salesCloseAt;
}

/// The CTA under the rows: "Payer {total}" / "Payer {total} par wallet" / "Obtenir mon code cash".
String payCtaText(AppLocalizations l, PayMethod? method, int totalMad) => switch (method) {
  PayMethod.wallet => l.payWalletCta(l.dh(totalMad)),
  PayMethod.cashAgency => l.payCashCta,
  _ => l.payCta(l.dh(totalMad)),
};

/// The rows of [rows] for [event], [selected] marked; tapping an enabled row calls [onSelect].
class MethodRows extends StatelessWidget {
  const new({required this.rows, required this.event, required this.selected, required this.onSelect, super.key});

  final List<MethodRow> rows;
  final FanEvent event;
  final PayMethod? selected;
  final ValueChanged<PayMethod> onSelect;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (:method, :enabled, :tooLate) in rows) ...[
          _MethodRow(
            method: method,
            selected: method == selected,
            enabled: enabled,
            hint: switch (method) {
              _ when tooLate => l.methodCashTooLate,
              PayMethod.cashAgency => l.methodCashBefore(venueWhen(cashDeadline(event), event.timezone, l.localeName)),
              _ => l.instant,
            },
            onTap: () => onSelect(method),
          ),
          const SizedBox(height: JomhorSpace.s),
        ],
      ],
    );
  }
}

/// One "MOYEN DE PAIEMENT" row (DESIGN.md screen 4): icon, name, detail, hint; 2 px accent border when picked.
class _MethodRow extends StatelessWidget {
  const new({
    required this.method,
    required this.selected,
    required this.enabled,
    required this.hint,
    required this.onTap,
  });

  final PayMethod method;
  final bool selected;
  final bool enabled;
  final String hint;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final on = selected && enabled;
    final ink = enabled ? JomhorColors.text : JomhorColors.textDisabled;
    final (icon, name, detail) = switch (method) {
      PayMethod.card => (Icons.credit_card_rounded, l.methodCard, l.methodCardHint),
      PayMethod.wallet => (Icons.smartphone_rounded, l.methodWallet, l.methodWalletHint),
      PayMethod.cashAgency => (Icons.payments_outlined, l.methodCash, l.methodCashHint),
    };
    return Semantics(
      selected: on,
      button: true,
      enabled: enabled,
      child: TapCard(
        inkKey: Key('method.${method.wire}'),
        onTap: enabled ? onTap : null,
        borderColor: on ? JomhorColors.accent : JomhorColors.border,
        borderWidth: 2,
        padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m, horizontal: 14),
        child: Row(
          children: [
            Icon(icon, size: 22, color: ink),
            const SizedBox(width: JomhorSpace.m),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    name,
                    style: JomhorType.body.copyWith(fontWeight: FontWeight.w600, color: ink),
                  ),
                  Text(detail, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
                ],
              ),
            ),
            const SizedBox(width: JomhorSpace.s),
            Flexible(
              child: Text(
                hint,
                key: Key('method.${method.wire}.hint'),
                textAlign: TextAlign.end,
                style: JomhorType.caption.copyWith(
                  color: enabled && method.hosted ? JomhorColors.successText : JomhorColors.textMuted,
                ),
              ),
            ),
            const SizedBox(width: JomhorSpace.s),
            AnimatedSwitcher(
              duration: motionOf(context, JomhorMotion.base),
              child: Icon(
                on ? Icons.radio_button_checked_rounded : Icons.radio_button_off_rounded,
                key: ValueKey(on),
                size: 20,
                color: on ? JomhorColors.accent : JomhorColors.borderMuted,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
