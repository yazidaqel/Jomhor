import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/order.dart';
import 'package:jomhor_fan/features/checkout/order_status.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// The app cash order screen (DESIGN.md Lot 3 adjustments; W4 layout in app components): the code on paper,
/// the exact amount, the time left, where to pay, three steps, and card / wallet meanwhile. [OrderStatus]
/// polls every 10 s; PAID and EXPIRED are drawn by the order screen.
class CashOrder extends ConsumerWidget {
  const new({required this.view, super.key});

  final OrderView view;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    ref.watch(clockProvider); // The countdown moves between two polls.
    final order = view.order;
    final cash = order.cash;
    final detail = switch (order.eventId) {
      final id? => ref.watch(eventDetailProvider(id)).value,
      null => null,
    };
    final event = detail?.event;
    final left = view.left;
    final hosted = [...?detail?.paymentMethods.where((m) => m.hosted)];
    final where = [...?cash?.points.map((p) => p.name), ...?cash?.networks].join(l.listSeparator);
    final rePay = ref.watch(rePayProvider(order.id));
    // The event read (zone, title, methods) may land after the order: its parts fade into fixed, keyed slots, so
    // nothing below shifts and no entrance plays twice.
    final until = order.reservedUntil;
    Widget slot(String name, Widget? child) => AnimatedSwitcher(
      duration: motionOf(context, JomhorMotion.base),
      child: child ?? SizedBox.shrink(key: ValueKey('$name.empty')),
    );

    return ListView(
      padding: const EdgeInsets.all(JomhorSpace.xl),
      children: [
        Entrance(
          key: const ValueKey('cash.slot.deadline'),
          child: slot(
            'deadline',
            until == null || event == null
                ? null
                : Padding(
                    padding: const EdgeInsets.only(bottom: JomhorSpace.l),
                    child: InfoBanner(
                      key: const Key('cash.deadline'),
                      accent: true,
                      icon: Icons.schedule_rounded,
                      text: l.cashHoldBanner(venueWhen(until, event.timezone, l.localeName)),
                    ),
                  ),
          ),
        ),
        Entrance(
          key: const ValueKey('cash.slot.title'),
          index: 1,
          child: Text(l.cashTitle, style: JomhorType.h1.copyWith(fontSize: 34)),
        ),
        slot(
          'event',
          event == null
              ? null
              : Padding(
                  key: const ValueKey('cash.event'),
                  padding: const EdgeInsets.only(top: JomhorSpace.xs),
                  child: Text(
                    l.dotPair(event.title, venueWhen(event.startsAt, event.timezone, l.localeName)),
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                  ),
                ),
        ),
        const SizedBox(height: JomhorSpace.l),
        if (cash != null)
          Entrance(
            key: const ValueKey('cash.slot.code'),
            index: 2,
            child: _CodeCard(cash: cash),
          ),
        const SizedBox(height: JomhorSpace.l),
        Entrance(
          key: const ValueKey('cash.slot.countdown'),
          index: 3,
          child: Row(
            children: [
              Expanded(
                child: Text(l.cashTimeLeft, style: JomhorType.body.copyWith(color: JomhorColors.textSecondary)),
              ),
              SizedBox(
                width: 76,
                child: CountdownTile(key: const Key('cash.hours'), value: left.inHours, label: l.countdownHours),
              ),
              const SizedBox(width: JomhorSpace.s),
              SizedBox(
                width: 76,
                child: CountdownTile(
                  key: const Key('cash.minutes'),
                  value: left.inMinutes % 60,
                  label: l.countdownMinutes,
                ),
              ),
            ],
          ),
        ),
        if (cash != null && (cash.points.isNotEmpty || cash.networks.isNotEmpty)) ...[
          const SizedBox(height: JomhorSpace.xl),
          SectionLabel(l.cashWhereTitle),
          for (final p in cash.points)
            Padding(
              padding: const EdgeInsets.only(bottom: JomhorSpace.s),
              child: _Where(icon: Icons.place_outlined, title: p.name, lines: [?p.address, ?p.hours]),
            ),
          if (cash.networks.isNotEmpty)
            _Where(icon: Icons.storefront_outlined, title: l.cashNetworks(cash.networks.join(l.listSeparator))),
        ],
        const SizedBox(height: JomhorSpace.xl),
        SectionLabel(l.cashStepsTitle),
        _Step(n: 1, title: l.cashStep1Title, body: where.isEmpty ? null : where),
        if (cash != null)
          _Step(n: 2, title: l.cashStep2Title, body: l.dotPair(l.ltr(cash.display), l.dh(cash.amountMad))),
        _Step(n: cash == null ? 2 : 3, title: l.cashStep3Title, body: l.cashStep3Body),
        if (hosted.isNotEmpty) ...[
          const SizedBox(height: JomhorSpace.l),
          // A card or wallet attempt still PENDING may succeed: a second one could charge twice. Same while the
          // read on screen was sent before the newest attempt ended (loading, or the next read failed).
          if (order.hostedPaymentPending || !view.freshFor(rePay))
            InfoBanner(
              key: const Key('cash.hostedPending'),
              text: l.cashHostedPending,
              icon: Icons.hourglass_top_rounded,
            )
          else
            Card(
              key: const Key('cash.payNow'),
              child: Padding(
                padding: const EdgeInsets.all(JomhorSpace.l),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(l.cashPayNowTitle, style: JomhorType.body.copyWith(color: JomhorColors.textSecondary)),
                    const SizedBox(height: JomhorSpace.m),
                    Wrap(
                      spacing: JomhorSpace.s,
                      runSpacing: JomhorSpace.s,
                      children: [
                        for (final m in hosted)
                          OutlinedButton.icon(
                            key: Key('cash.pay.${m.wire}'),
                            style: OutlinedButton.styleFrom(minimumSize: const Size(0, JomhorSize.touchMin)),
                            onPressed: rePay.inFlight ? null : () => startPayment(context, ref, order.id, m),
                            icon: Icon(
                              m == PayMethod.card ? Icons.credit_card_rounded : Icons.smartphone_rounded,
                              size: 20,
                            ),
                            label: Text(m == PayMethod.card ? l.cashPayByCard : l.cashPayByWallet),
                          ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
        ],
        const SizedBox(height: JomhorSpace.l),
        Container(
          padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m, horizontal: 14),
          decoration: const BoxDecoration(
            color: JomhorColors.ground,
            border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
            borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip)),
          ),
          child: Row(
            children: [
              // Static: the wait may last hours, so no spinner loops over it (DESIGN.md principle 6).
              const Icon(Icons.sync_rounded, size: 16, color: JomhorColors.accent),
              const SizedBox(width: JomhorSpace.m),
              Expanded(
                child: Text(
                  l.cashStatus,
                  key: const Key('cash.status'),
                  style: JomhorType.caption.copyWith(color: JomhorColors.textMuted),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// The code on paper (the only light surface next to the ticket): display 44, letter-spacing 3, always LTR.
class _CodeCard extends StatelessWidget {
  const new({required this.cash});

  final CashCode cash;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.all(JomhorSpace.xxl),
      decoration: const BoxDecoration(
        color: JomhorColors.ticketPaper,
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.card)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            l.cashCodeLabel.toUpperCase(),
            style: JomhorType.label.copyWith(color: JomhorColors.ticketMuted).tracked(context),
          ),
          const SizedBox(height: JomhorSpace.s),
          FittedBox(
            fit: BoxFit.scaleDown,
            alignment: AlignmentDirectional.centerStart,
            child: Text(
              cash.display,
              key: const Key('cash.code'),
              textDirection: TextDirection.ltr,
              style: JomhorType.hero.copyWith(color: JomhorColors.ticketInk, letterSpacing: 3, height: 1),
            ),
          ),
          const SizedBox(height: JomhorSpace.s),
          Text(
            l.cashExactAmount(l.dh(cash.amountMad)),
            key: const Key('cash.amount'),
            style: JomhorType.bodySmall.copyWith(color: JomhorColors.ticketMuted),
          ),
          const SizedBox(height: JomhorSpace.l),
          OutlinedButton.icon(
            key: const Key('cash.copy'),
            style: OutlinedButton.styleFrom(
              backgroundColor: JomhorColors.ticketPaper,
              foregroundColor: JomhorColors.ticketInk,
              side: const BorderSide(color: JomhorColors.ticketRule),
              minimumSize: const Size(0, JomhorSize.touchMin),
            ),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: cash.display));
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l.cashCopied)));
              }
            },
            icon: const Icon(Icons.copy_rounded, size: 18),
            label: Text(l.cashCopy),
          ),
        ],
      ),
    );
  }
}

/// A cash point (name, address, hours) or the agency networks.
class _Where extends StatelessWidget {
  const new({required this.icon, required this.title, this.lines = const []});

  final IconData icon;
  final String title;
  final List<String> lines;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: const BoxDecoration(
        color: JomhorColors.surface,
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: JomhorColors.accent),
          const SizedBox(width: JomhorSpace.m),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(l.isolate(title), style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                for (final line in lines)
                  Text(l.isolate(line), style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A numbered step: accent disc, title, detail.
class _Step extends StatelessWidget {
  const new({required this.n, required this.title, this.body});

  final int n;
  final String title;
  final String? body;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: JomhorSpace.m),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 28,
          height: 28,
          alignment: Alignment.center,
          decoration: const BoxDecoration(color: JomhorColors.accent, shape: BoxShape.circle),
          child: Text(
            '$n',
            style: JomhorType.body.copyWith(color: JomhorColors.onAccent, fontWeight: FontWeight.w700),
          ),
        ),
        const SizedBox(width: JomhorSpace.m),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
              if (body case final body?)
                Text(body, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
            ],
          ),
        ),
      ],
    ),
  );
}

/// The code's deadline passed (or the order expired): say so, back to the event (Q55: no fan cancel, it just ends).
class CashExpired extends StatelessWidget {
  const new({required this.eventId, super.key});

  final int? eventId;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Icon(Icons.timer_off_outlined, size: 56, color: JomhorColors.textMuted),
        const SizedBox(height: JomhorSpace.l),
        Text(l.cashExpiredTitle, key: const Key('cash.expired'), style: JomhorType.h2, textAlign: TextAlign.center),
        const SizedBox(height: JomhorSpace.s),
        Text(
          l.cashExpiredBody,
          textAlign: TextAlign.center,
          style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
        ),
        const SizedBox(height: JomhorSpace.xl),
        if (eventId case final id?) ...[
          FilledButton(onPressed: () => context.go('/events/$id'), child: Text(l.backToEvent)),
          const SizedBox(height: JomhorSpace.s),
        ],
        OutlinedButton(onPressed: () => context.go('/'), child: Text(l.backHome)),
      ],
    );
  }
}

/// An attempt from the order screen through [RePay] (one at a time): card or wallet then open the hosted page; cash
/// gets a code, which the order screen then shows.
Future<void> startPayment(BuildContext context, WidgetRef ref, String orderId, PayMethod method) async {
  final l = AppLocalizations.of(context);
  final launch = ref.read(cardPaymentLauncherProvider);
  final error = await ref.read(rePayProvider(orderId).notifier).start((url) async {
    if (context.mounted) await launch(context, url, orderId);
  }, method: method);
  if (error != null && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(apiErrorText(l, error))));
  }
}
