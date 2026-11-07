import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 4 · Paiement: named holders, summary, method (card, wallet, cash from `payment_methods`). "Payer"
/// holds the places (POST /fan/orders, with the holders the contract requires), then opens the card or wallet
/// page, or gets the cash code and goes to the cash order screen; the 8-min countdown starts with the hold.
class CheckoutScreen extends ConsumerWidget {
  const new({required this.args, super.key});

  final CheckoutArgs args;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final detail = ref.watch(eventDetailProvider(args.eventId));
    return Scaffold(
      appBar: AppBar(title: Text(l.payTitle)),
      body: detail.when(
        loading: () => ListView(
          physics: const NeverScrollableScrollPhysics(),
          padding: const EdgeInsets.all(JomhorSpace.xl),
          children: const [SkeletonList(rows: 4)],
        ),
        error: (e, _) => Center(
          child: ErrorPanel(error: e, onRetry: () => ref.invalidate(eventDetailProvider(args.eventId))),
        ),
        data: (d) {
          for (final z in d.zones) {
            for (final p in z.prices) {
              if (p.priceCategoryId == args.priceCategoryId) return _Form(args: args, detail: d, zone: z, price: p);
            }
          }
          return Center(child: Text(l.errorNotFound));
        },
      ),
    );
  }
}

class _Form extends ConsumerStatefulWidget {
  const new({required this.args, required this.detail, required this.zone, required this.price});

  final CheckoutArgs args;
  final EventDetail detail;
  final Zone zone;
  final Price price;

  @override
  ConsumerState<_Form> createState() => _FormState();
}

class _FormState extends ConsumerState<_Form> {
  late final List<TextEditingController> _names = [for (var i = 0; i < widget.args.qty; i++) TextEditingController()];
  late final List<TextEditingController> _phones = [
    for (var i = 0; i < widget.args.qty; i++) TextEditingController()..addListener(_phoneChanged),
  ];
  var _consent = false;

  /// The card page is on screen (pushed above this one).
  var _cardOpen = false;

  // The Q21 confirmation appears as soon as a friend's phone is typed.
  void _phoneChanged() => setState(() {});

  @override
  void initState() {
    super.initState();
    // Row 1 = me, prefilled once from /fan/me (no phone: the buyer holds it).
    ref.listenManual(meProvider, (_, me) {
      final name = me.value?.displayName;
      if (name != null && _names.first.text.isEmpty) _names.first.text = name;
    }, fireImmediately: true);
  }

  @override
  void dispose() {
    for (final c in [..._names, ..._phones]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _pay() async {
    final provider = checkoutControllerProvider(widget.args);
    final current = ref.read(provider);
    if (current.cardOpened) {
      // One card attempt from here; the order screen says when another one is safe.
      context.go('/orders/${current.order!.id}');
      return;
    }
    final controller = ref.read(provider.notifier);
    final method = current.selectedIn(widget.detail);
    if (method == null) return;
    final start = await controller.submit(
      [for (final c in _names) c.text],
      [for (final c in _phones) c.text],
      friendsConsent: _consent,
      method: method,
    );
    if (start == null || !mounted) return; // Left the screen: no payment page.
    final order = ref.read(provider).order!;
    final url = start.redirectUrl;
    if (url == null) return context.go('/orders/${order.id}'); // Cash: the code and where to pay.
    controller.cardOpened();
    _cardOpen = true;
    final returned = await ref.read(cardPaymentLauncherProvider)(context, url, order.id);
    _cardOpen = false;
    if (!mounted) return;
    // Back on our return URL, or the hold ran out meanwhile: the order screen polls the real status (a
    // payment started before the end may still succeed, docs/04-api.md §Payments "Late success").
    if (returned || ref.read(provider).phase == CheckoutPhase.expired) context.go('/orders/${order.id}');
  }

  void _onExpired(BuildContext context, AppLocalizations l) {
    final s = ref.read(checkoutControllerProvider(widget.args));
    if (!s.cardOpened) return _showExpired(context, l);
    // Never a modal over (or after) the card page: the bank may still confirm. While the card page is open,
    // [_pay] goes to the order screen when it closes.
    if (!_cardOpen) context.go('/orders/${s.order!.id}');
  }

  bool get _hasFriendPhone => _phones.skip(1).any((c) => c.text.trim().isNotEmpty);

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final args = widget.args;
    final state = ref.watch(checkoutControllerProvider(args));
    // A cash /pay in flight when the hold ends decides first: a code issued goes to the order screen.
    ref.listen(checkoutControllerProvider(args).select((s) => (s.phase, s.payingCash)), (_, now) {
      if (now == (CheckoutPhase.expired, false)) _onExpired(context, l);
    });
    final locked = state.order != null; // The holders are on the order now.
    final busy = state.busy;
    final total = state.order?.totalMad ?? widget.price.priceMad * args.qty;
    final error = state.error;
    final selected = state.selectedIn(widget.detail);
    final event = widget.detail.event;

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.all(JomhorSpace.xl),
            children: [
              if (state.left case final left?)
                InfoBanner(
                  key: const Key('checkout.hold'),
                  text: l.holdLeft(_mmss(left)),
                  success: true,
                  icon: Icons.timer_outlined,
                ),
              if (state.phase == CheckoutPhase.busy)
                Padding(
                  padding: const EdgeInsets.only(top: JomhorSpace.s),
                  child: InfoBanner(
                    key: const Key('checkout.busy'),
                    text: l.busyRetry(state.retryIn ?? 0),
                    icon: Icons.hourglass_top_rounded,
                  ),
                ),
              const SizedBox(height: JomhorSpace.l),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(JomhorSpace.xl),
                  child: Column(
                    children: [
                      _Line(l.summaryLine(args.qty, widget.zone.name), formatDh(widget.price.priceMad * args.qty)),
                      _Line(l.serviceFee, formatDh(0)),
                      const Divider(color: JomhorColors.border),
                      _Line(l.total, formatDh(total), strong: true),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: JomhorSpace.xl),
              SectionLabel(l.holdersTitle),
              const SizedBox(height: JomhorSpace.xs),
              for (var i = 0; i < args.qty; i++) ...[
                Text(
                  i == 0 ? l.holderMe : l.holderN(i + 1),
                  style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: JomhorSpace.s),
                TextField(
                  key: Key('holder.$i.name'),
                  controller: _names[i],
                  enabled: !locked,
                  textCapitalization: TextCapitalization.words,
                  autofillHints: i == 0 ? const [AutofillHints.name] : null,
                  decoration: InputDecoration(
                    labelText: l.holderNameLabel,
                    errorText: state.badNames.contains(i) ? l.holderNameError : null,
                    prefixIcon: const Icon(Icons.badge_outlined),
                  ),
                ),
                if (i > 0) ...[
                  const SizedBox(height: JomhorSpace.s),
                  TextField(
                    key: Key('holder.$i.phone'),
                    controller: _phones[i],
                    enabled: !locked,
                    keyboardType: TextInputType.phone,
                    decoration: InputDecoration(
                      labelText: l.holderPhoneLabel,
                      helperText: l.holderPhoneHelp,
                      helperMaxLines: 2,
                      errorText: state.badPhones.contains(i) ? l.holderPhoneError : null,
                      prefixIcon: const Icon(Icons.chat_outlined),
                    ),
                  ),
                ],
                const SizedBox(height: JomhorSpace.l),
              ],
              if (_hasFriendPhone) ...[
                CheckboxListTile(
                  key: const Key('checkout.consent'),
                  value: _consent,
                  onChanged: locked ? null : (v) => setState(() => _consent = v ?? false),
                  controlAffinity: ListTileControlAffinity.leading,
                  contentPadding: EdgeInsets.zero,
                  title: Text(l.friendsConsent, style: JomhorType.bodySmall),
                  subtitle: state.consentMissing
                      ? Text(
                          l.friendsConsentMissing,
                          style: JomhorType.caption.copyWith(color: Theme.of(context).colorScheme.error),
                        )
                      : null,
                ),
                const SizedBox(height: JomhorSpace.l),
              ],
              SectionLabel(l.methodTitle),
              const SizedBox(height: JomhorSpace.xs),
              for (final (:method, :enabled, :tooLate) in state.methodRows(widget.detail)) ...[
                _MethodRow(
                  method: method,
                  selected: method == selected,
                  enabled: enabled,
                  hint: switch (method) {
                    _ when tooLate => l.methodCashTooLate,
                    PayMethod.cashAgency => l.methodCashBefore(
                      venueWhen(cashDeadline(event), event.timezone, l.localeName),
                    ),
                    _ => l.instant,
                  },
                  onTap: () => ref.read(checkoutControllerProvider(args).notifier).select(method),
                ),
                const SizedBox(height: JomhorSpace.s),
              ],
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
                  if (error != null) ...[
                    Text(
                      apiErrorText(l, error),
                      key: const Key('checkout.error'),
                      style: JomhorType.bodySmall.copyWith(color: Theme.of(context).colorScheme.error),
                    ),
                    const SizedBox(height: JomhorSpace.s),
                  ],
                  FilledButton(
                    key: const Key('checkout.pay'),
                    onPressed: busy || selected == null || (state.phase == CheckoutPhase.expired && !state.cardOpened)
                        ? null
                        : _pay,
                    child: Text(switch (selected) {
                      _ when state.cardOpened => l.checkPayment,
                      PayMethod.wallet => l.payWalletCta(formatDh(total)),
                      PayMethod.cashAgency => l.payCashCta,
                      _ => l.payCta(formatDh(total)),
                    }),
                  ),
                  const SizedBox(height: JomhorSpace.xs),
                  Text(
                    l.payCaption,
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

  void _showExpired(BuildContext context, AppLocalizations l) {
    final eventId = widget.args.eventId;
    unawaited(
      showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (dialog) => AlertDialog(
          title: Text(l.expiredTitle),
          content: Text(l.expiredBody),
          actions: [
            FilledButton(
              onPressed: () {
                Navigator.of(dialog).pop();
                context.go('/events/$eventId/zones');
              },
              child: Text(l.expiredCta),
            ),
          ],
        ),
      ),
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

class _Line extends StatelessWidget {
  const new(this.label, this.amount, {this.strong = false});

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

String _mmss(Duration d) =>
    '${d.inMinutes.toString().padLeft(2, '0')}:${(d.inSeconds % 60).toString().padLeft(2, '0')}';
