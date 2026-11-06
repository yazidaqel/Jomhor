import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// Screen 1 "Mon billet" strip: the next upcoming ticket from the saved tickets; nothing when there is none.
class MyTicketStrip extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final snapshot = ref.watch(ticketsProvider).value?.snapshot;
    final t = snapshot?.next;
    if (snapshot == null || t == null) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    final e = t.event;
    final now = snapshot.clock.now();
    final clock = venueClock(e.startsAt, e.timezone, l.localeName);
    final when = now.isBefore(e.startsAt)
        ? l.myTicketWhen(venueDaysUntil(now, e.startsAt, e.timezone), clock)
        : l.myTicketStarted(clock);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.s),
      child: Material(
        color: JomhorColors.successBg,
        shape: const RoundedRectangleBorder(
          side: BorderSide(color: JomhorColors.success),
          borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
        ),
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          key: const Key('home.myTicket'),
          onTap: () => context.push('/tickets/${t.id}'),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m, horizontal: JomhorSpace.l),
            child: Row(
              children: [
                const Icon(Icons.confirmation_number_outlined, color: JomhorColors.successText),
                const SizedBox(width: JomhorSpace.m),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        l.myTicketTitle(
                          factsLine(l, e.facts) ?? e.title,
                          venueDate(e.startsAt, e.timezone, l.localeName),
                        ),
                        style: JomhorType.body.copyWith(color: JomhorColors.successText, fontWeight: FontWeight.w600),
                      ),
                      Text(
                        l.dotPair(l.dotPair(t.zone.name, l.ticketGatesList(t.gateCodes.length, gatesText(l, t))), when),
                        style: JomhorType.bodySmall.copyWith(color: JomhorColors.successText),
                      ),
                    ],
                  ),
                ),
                const Icon(Icons.chevron_right, color: JomhorColors.successText),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
