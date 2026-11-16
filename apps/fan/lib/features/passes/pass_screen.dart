import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart' show DateFormat;
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/zone_row.dart' show MembersChip;
import 'package:jomhor_fan/features/passes/buy_controller.dart';
import 'package:jomhor_fan/features/passes/buy_screen.dart';
import 'package:jomhor_fan/features/passes/pass.dart';
import 'package:jomhor_fan/features/passes/passes_repository.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart' show TicketBand;
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 13 · Abonnement, owned (DESIGN.md Lot 5 step 3 and wow table): the season in one card, then every event
/// of the season (`GET /fan/passes` `events`) with its ticket as it arrives (`ticket_id`, else "Bientôt").
/// `PENDING`: "Paiement en attente" + "Reprendre le paiement". 3b: `SUSPENDED` banner, the schedule (Payée / À payer /
/// En retard) and "Payer l'échéance · {amount}" → `POST /fan/passes/{id}/installments` → screen 4.
class PassScreen extends ConsumerStatefulWidget {
  const new({required this.passId, super.key});

  final String passId;

  @override
  ConsumerState<PassScreen> createState() => _PassScreenState();
}

class _PassScreenState extends ConsumerState<PassScreen> {
  @override
  void initState() {
    super.initState();
    // A "Billet" chip opens screen 5, which shows the wallet: read again on open so a new pass ticket is in it.
    unawaited(Future.microtask(() => ref.read(ticketsProvider.notifier).refresh()));
  }

  Future<void> _refresh() async {
    ref.invalidate(passesProvider);
    await ref.read(ticketsProvider.notifier).refresh();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final passes = ref.watch(passesProvider);
    final pass = passes.value?.where((p) => p.id == widget.passId).firstOrNull;
    final Widget body;
    if (pass != null) {
      body = _Owned(pass: pass, onRefresh: _refresh);
    } else if (passes.error case final error?) {
      body = Center(
        child: ErrorPanel(error: error, onRetry: () => ref.invalidate(passesProvider)),
      );
    } else if (passes.hasValue) {
      body = Center(child: StatePanel(message: l.errorNotFound));
    } else {
      body = ListView(
        physics: const NeverScrollableScrollPhysics(),
        padding: const EdgeInsets.all(JomhorSpace.xl),
        children: const [
          Skeleton(height: 240, radius: JomhorRadius.cardLarge),
          SkeletonList(),
        ],
      );
    }
    return Scaffold(
      appBar: AppBar(title: Text(pass?.productName ?? l.passChip, key: const Key('pass.title'))),
      body: body,
    );
  }
}

class _Owned extends ConsumerWidget {
  const new({required this.pass, required this.onRefresh});

  final FanPass pass;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final orderId = pass.orderId;
    final next = pass.nextDue;
    final paying = ref.watch(installmentProvider(pass.id));

    return RefreshIndicator(
      color: JomhorColors.accent,
      backgroundColor: JomhorColors.surface,
      onRefresh: onRefresh,
      child: ListView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, JomhorSpace.xxl),
        children: [
          Entrance(child: PassCard(pass: pass)),
          if (pass.suspended && next != null) ...[
            const SizedBox(height: JomhorSpace.l),
            InfoBanner(
              key: const Key('pass.suspended'),
              text: l.passSuspendedBanner(venueDay(next.dueAt, pass.timezone, l.localeName)),
              danger: true,
              icon: Icons.lock_outline_rounded,
            ),
          ],
          if (pass.pending) ...[
            const SizedBox(height: JomhorSpace.l),
            InfoBanner(
              key: const Key('pass.pending'),
              text: l.passPendingBody,
              accent: true,
              icon: Icons.schedule_rounded,
            ),
            if (orderId != null) ...[
              const SizedBox(height: JomhorSpace.m),
              FilledButton(
                key: const Key('pass.resume'),
                onPressed: () => context.push('/orders/$orderId'),
                child: Text(l.passResume),
              ),
            ],
          ],
          if (pass.installments.length > 1) ...[
            const SizedBox(height: JomhorSpace.xl),
            SectionLabel(l.passSchedule),
            const SizedBox(height: JomhorSpace.s),
            for (final i in pass.installments)
              _InstallmentRow(
                key: Key('pass.installment.${i.seq}'),
                text: l.installmentRow(
                  i.seq,
                  pass.installments.length,
                  l.dh(i.amountMad),
                  venueDay(i.dueAt, pass.timezone, l.localeName),
                ),
                state: pass.stateOf(i),
                stateKey: Key('pass.installment.${i.seq}.state'),
              ),
          ],
          if (pass.canPay && next != null) ...[
            const SizedBox(height: JomhorSpace.m),
            if (paying.error case final e?) ...[
              Text(
                apiErrorText(l, e),
                key: const Key('pass.payError'),
                style: JomhorType.bodySmall.copyWith(color: Theme.of(context).colorScheme.error),
              ),
              const SizedBox(height: JomhorSpace.s),
            ],
            FilledButton(
              key: const Key('pass.payInstallment'),
              onPressed: paying.busy
                  ? null
                  : () async {
                      final to = await ref.read(installmentProvider(pass.id).notifier).pay();
                      if (to != null && context.mounted) unawaited(context.push(to));
                    },
              child: Text(l.passPayInstallment(l.dh(next.amountMad))),
            ),
          ],
          const SizedBox(height: JomhorSpace.xl),
          SectionLabel(l.passProgramme),
          const SizedBox(height: JomhorSpace.xs),
          Text(
            pass.matches ? l.passOneTicketEach : l.passOneTicketEachAny,
            style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
          ),
          const SizedBox(height: JomhorSpace.m),
          for (final (i, e) in pass.events.indexed)
            Entrance(
              index: i + 1,
              child: SeasonEventRow(
                key: Key('pass.event.${e.id}'),
                title: e.title,
                startsAt: e.startsAt,
                timezone: pass.timezone,
                // A ticket that lands while the screen is open swaps in.
                trailing: AnimatedSwitcher(
                  duration: motionOf(context, JomhorMotion.base),
                  child: switch (e.ticketId) {
                    final id? => _TicketChip(
                      key: Key('pass.ticket.${e.id}'),
                      onTap: () => context.push('/tickets/$id', extra: PassOrigin.list.name),
                    ),
                    null => soonPill(l.passSoon, key: Key('pass.soon.${e.id}')),
                  },
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// A schedule row: "{seq}/{n} · {amount} · {date}" and its chip (Payée in the success colours, À payer muted, En retard
/// outlined in the danger colour: the word carries the meaning, not the colour).
class _InstallmentRow extends StatelessWidget {
  const new({required this.text, required this.state, required this.stateKey, super.key});

  final String text;
  final InstallmentState state;
  final Key stateKey;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final (label, fg, bg, line) = switch (state) {
      InstallmentState.paid => (
        l.installmentPaid,
        JomhorColors.successText,
        JomhorColors.successBg,
        JomhorColors.success,
      ),
      InstallmentState.due => (
        l.installmentDue,
        JomhorColors.textSecondary,
        Colors.transparent,
        JomhorColors.borderMuted,
      ),
      InstallmentState.late => (l.installmentLate, JomhorColors.danger, Colors.transparent, JomhorColors.danger),
    };
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xs),
      child: Row(
        children: [
          Expanded(
            child: Text(
              text,
              style: JomhorType.body.copyWith(
                color: state == InstallmentState.paid ? JomhorColors.textMuted : JomhorColors.text,
              ),
            ),
          ),
          const SizedBox(width: JomhorSpace.s),
          Container(
            key: stateKey,
            padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.s, vertical: 2),
            decoration: BoxDecoration(
              color: bg,
              border: Border.all(color: line),
              borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
            ),
            child: Text(
              label,
              style: JomhorType.caption.copyWith(color: fg, fontWeight: FontWeight.w600, fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}

/// "Billet" with the pass glyph: opens screen 5. A 44 px target.
class _TicketChip extends StatelessWidget {
  const new({required this.onTap, super.key});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Semantics(
      button: true,
      label: l.ticketOpen,
      excludeSemantics: true,
      child: InkWell(
        onTap: onTap,
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
        child: ConstrainedBox(
          constraints: const BoxConstraints(minHeight: JomhorSize.touchMin),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              MembersChip(label: l.passTicket),
              const SizedBox(width: JomhorSpace.xs),
              const MiniPass(width: 22),
            ],
          ),
        ),
      ),
    );
  }
}

/// The pass card, as the member card (screen 7): `surface2`, radius 28, the accent band with the club's crest and
/// name under the series' name; then the zone in display type (the product's name is the screen's title), the holder
/// and the season's months.
class PassCard extends StatelessWidget {
  const new({required this.pass, super.key});

  final FanPass pass;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final p = pass;
    final month = DateFormat('MMM y', intlLocale(l.localeName));
    final muted = JomhorType.label.copyWith(color: JomhorColors.textMuted).tracked(context);
    Widget fact(String name, String value, {Key? key}) => Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(name.toUpperCase(), style: muted),
        const SizedBox(height: 2),
        Text(
          value,
          key: key,
          style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
        ),
      ],
    );
    return ClipRRect(
      key: const Key('pass.card'),
      borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
      child: ColoredBox(
        color: JomhorColors.surface2,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TicketBand(
              overline: p.series.name,
              title: p.club.name,
              club: p.club,
              crestKey: const Key('pass.crest'),
              titleMaxLines: 1,
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 18, JomhorSpace.xl, JomhorSpace.xl),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(l.ticketZone.toUpperCase(), style: muted),
                  Text(
                    p.zone.name.toUpperCase(),
                    key: const Key('pass.zone'),
                    style: JomhorType.hero.copyWith(fontSize: 34, color: JomhorColors.accent),
                  ),
                  const SizedBox(height: JomhorSpace.l),
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(child: fact(l.ticketHolder, l.isolate(p.holderLabel), key: const Key('pass.holder'))),
                      const SizedBox(width: JomhorSpace.m),
                      Expanded(
                        child: fact(
                          l.passSeason,
                          l.passSeasonRange(month.format(p.series.startsOn), month.format(p.series.endsOn)),
                          key: const Key('pass.season'),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
