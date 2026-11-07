import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/tickets/pass_hero.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Screen 1 "Mon billet" strip: the next upcoming ticket from the saved tickets; nothing when there is none.
/// The pass peeks out of the card and flies into screen 5 on tap.
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
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m),
      child: TapCard(
        inkKey: const Key('home.myTicket'),
        onTap: () => context.push('/tickets/${t.id}', extra: PassOrigin.home.name),
        color: JomhorColors.successBg,
        borderColor: JomhorColors.success,
        radius: JomhorRadius.card,
        padding: EdgeInsets.zero,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Padding(
                padding: const EdgeInsetsDirectional.fromSTEB(JomhorSpace.xl, 18, 0, 18),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            l.myTicketTitle(
                              factsLine(l, e.facts) ?? e.title,
                              venueDate(e.startsAt, e.timezone, l.localeName),
                            ),
                            style: JomhorType.body.copyWith(color: JomhorColors.text, fontWeight: FontWeight.w600),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            l.dotPair(
                              l.dotPair(t.zone.name, l.ticketGatesList(t.gateCodes.length, gatesText(l, t))),
                              when,
                            ),
                            style: JomhorType.bodySmall.copyWith(color: JomhorColors.successText),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            // The pass, peeking from the bottom edge (the card clips it).
            SizedBox(
              width: 84,
              height: 74,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  PositionedDirectional(
                    top: 14,
                    end: 16,
                    child: Hero(
                      tag: passHeroTag(PassOrigin.home, t.id),
                      flightShuttleBuilder: passFlight,
                      child: const MiniPass(width: 50),
                    ),
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
