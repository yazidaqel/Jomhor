import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Billets tab: held tickets, upcoming then past. Saved tickets show at once (and offline); pull to refresh.
class TicketsScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final state = ref.watch(ticketsProvider).value;
    final snapshot = state?.snapshot;
    Future<void> refresh() => ref.read(ticketsProvider.notifier).refresh();

    final List<Widget> body;
    if (snapshot == null) {
      body = [if (state?.error case final error?) ErrorPanel(error: error, onRetry: refresh) else const SkeletonList()];
    } else {
      final now = snapshot.clock.now();
      final upcoming = [
        for (final t in snapshot.tickets)
          if (!t.isPast(now)) t,
      ];
      final past = [
        for (final t in snapshot.tickets)
          if (t.isPast(now)) t,
      ];
      var i = 0;
      body = [
        if (state!.error != null)
          Padding(
            padding: const EdgeInsets.only(bottom: JomhorSpace.l),
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: InfoChip(l.ticketsOffline, key: const Key('tickets.offline'), icon: Icons.cloud_off_rounded),
            ),
          ),
        if (snapshot.tickets.isEmpty)
          StatePanel(
            title: l.ticketsEmptyTitle,
            message: l.ticketsEmpty,
            action: FilledButton(onPressed: () => context.go('/'), child: Text(l.ticketsBrowse)),
          ),
        for (final (title, list) in [(l.ticketsUpcoming, upcoming), (l.ticketsPast, past)])
          if (list.isNotEmpty) ...[
            const SizedBox(height: JomhorSpace.s),
            Text(title, style: JomhorType.h2),
            const SizedBox(height: JomhorSpace.m),
            for (final t in list)
              Entrance(
                index: i++,
                child: _TicketRow(ticket: t, past: t.isPast(now)),
              ),
            const SizedBox(height: JomhorSpace.m),
          ],
      ];
    }

    return SafeArea(
      bottom: false,
      child: RefreshIndicator(
        color: JomhorColors.accent,
        backgroundColor: JomhorColors.surface,
        onRefresh: refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, JomhorSpace.xxl),
          children: [
            Text(l.ticketsTitle, style: JomhorType.h1.copyWith(fontSize: 34)),
            const SizedBox(height: JomhorSpace.xl),
            ...body,
          ],
        ),
      ),
    );
  }
}

class _TicketRow extends StatelessWidget {
  const new({required this.ticket, required this.past});

  final FanTicket ticket;
  final bool past;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final t = ticket;
    final e = t.event;
    final (day, month) = venueDayMonth(e.startsAt, e.timezone, l.localeName);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TapCard(
        onTap: () => context.push('/tickets/${t.id}', extra: PassOrigin.list.name),
        padding: const EdgeInsetsDirectional.fromSTEB(14, 14, JomhorSpace.l, 14),
        child: Row(
          children: [
            DateBadge(day: day, month: month, highlight: t.hasQr, past: past),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(e.title, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(
                    l.ticketRowFacts(t.zone.name, t.ticketNo),
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                  ),
                  if (!t.hasQr) ...[
                    const SizedBox(height: 2),
                    Text(
                      ticketStatusText(l, t),
                      style: JomhorType.caption.copyWith(color: JomhorColors.textSecondary, fontSize: 12),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: JomhorSpace.m),
            Semantics(
              label: l.ticketOpen,
              child: Hero(
                tag: passHeroTag(PassOrigin.list, t.id),
                flightShuttleBuilder: passFlight,
                child: Opacity(
                  opacity: past ? 0.5 : 1,
                  child: MiniPass(width: 36, dimmed: !t.hasQr),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
