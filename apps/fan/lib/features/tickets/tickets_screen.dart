import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/features/tickets/transfer.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
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
            // Held tickets first, then the muted rows of the ones given away (DESIGN.md Lot 4, screen 8).
            for (final t in [...list.where((t) => t.transferred == null), ...list.where((t) => t.transferred != null)])
              Entrance(
                index: i++,
                child: switch (t.transferred) {
                  final to? => _TransferredRow(ticket: t, to: to),
                  null => _TicketRow(ticket: t, past: t.isPast(now)),
                },
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
            const BrandBar(),
            const SizedBox(height: JomhorSpace.s),
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
                    l.ticketRowFacts(t.zone.name, l.ltr(t.ticketNo)),
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

/// "Transféré à Karim B. · {date}": what stays of a ticket given away. Not a link: the ticket is no longer the fan's.
class _TransferredRow extends StatelessWidget {
  const new({required this.ticket, required this.to});

  final FanTicket ticket;
  final Transferred to;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Container(
        key: Key('ticket.transferred.${ticket.id}'),
        padding: const EdgeInsetsDirectional.fromSTEB(14, 14, JomhorSpace.l, 14),
        decoration: BoxDecoration(
          color: JomhorColors.surface.withValues(alpha: 0.6),
          border: Border.all(color: JomhorColors.border),
          borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.control)),
        ),
        child: Row(
          children: [
            const SizedBox.square(
              dimension: 44,
              child: DecoratedBox(
                decoration: BoxDecoration(color: JomhorColors.surface2, shape: BoxShape.circle),
                child: Icon(Icons.swap_horiz_rounded, size: 20, color: JomhorColors.textSecondary),
              ),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    ticket.event.title,
                    style: JomhorType.body.copyWith(fontWeight: FontWeight.w600, color: JomhorColors.textSecondary),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    transferredText(l, ticket, to),
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                  ),
                ],
              ),
            ),
            const SizedBox(width: JomhorSpace.m),
            Text(
              ticket.ticketNo,
              textDirection: TextDirection.ltr,
              style: JomhorType.h2.copyWith(fontSize: 16, color: JomhorColors.textMuted, letterSpacing: 1.5),
            ),
          ],
        ),
      ),
    );
  }
}
