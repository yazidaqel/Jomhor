import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

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
      body = [
        if (state?.error case final error?)
          ErrorPanel(error: error, onRetry: refresh)
        else
          const Center(child: CircularProgressIndicator()),
      ];
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
      body = [
        if (state!.error != null)
          Padding(
            padding: const EdgeInsets.only(bottom: JomhorSpace.m),
            child: Text(
              l.ticketsOffline,
              key: const Key('tickets.offline'),
              style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
            ),
          ),
        if (snapshot.tickets.isEmpty) ...[
          Text(l.ticketsEmpty, style: const TextStyle(color: JomhorColors.textSecondary)),
          const SizedBox(height: JomhorSpace.l),
          OutlinedButton(onPressed: () => context.go('/'), child: Text(l.ticketsBrowse)),
        ],
        for (final (title, list) in [(l.ticketsUpcoming, upcoming), (l.ticketsPast, past)])
          if (list.isNotEmpty) ...[
            const SizedBox(height: JomhorSpace.s),
            Text(title, style: JomhorType.h2),
            const SizedBox(height: JomhorSpace.m),
            for (final t in list) _TicketRow(ticket: t),
          ],
      ];
    }

    return SafeArea(
      child: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, JomhorSpace.xxl),
          children: [
            Text(l.ticketsTitle, style: JomhorType.h1),
            const SizedBox(height: JomhorSpace.l),
            ...body,
          ],
        ),
      ),
    );
  }
}

class _TicketRow extends StatelessWidget {
  const new({required this.ticket});

  final FanTicket ticket;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final t = ticket;
    final e = t.event;
    final (day, month) = venueDayMonth(e.startsAt, e.timezone, l.localeName);
    return Padding(
      padding: const EdgeInsets.only(bottom: JomhorSpace.s),
      child: Material(
        color: JomhorColors.surface,
        shape: const RoundedRectangleBorder(
          side: BorderSide(color: JomhorColors.border),
          borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => context.push('/tickets/${t.id}'),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m, horizontal: JomhorSpace.l),
            child: Row(
              children: [
                SizedBox(
                  width: 48,
                  child: Column(
                    children: [
                      Text(day, style: JomhorType.h2),
                      Text(month, style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
                    ],
                  ),
                ),
                const SizedBox(width: JomhorSpace.m),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(e.title, style: JomhorType.body),
                      Text(
                        l.ticketRowFacts(t.zone.name, t.ticketNo),
                        style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                      ),
                    ],
                  ),
                ),
                if (t.hasQr)
                  const Icon(Icons.qr_code_2, color: JomhorColors.accent)
                else
                  Flexible(
                    child: Text(
                      ticketStatusText(l, t),
                      textAlign: TextAlign.end,
                      style: JomhorType.caption.copyWith(color: JomhorColors.textMuted),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
