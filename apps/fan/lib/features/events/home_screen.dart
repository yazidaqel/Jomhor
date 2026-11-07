import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';
import 'package:jomhor_fan/features/tickets/my_ticket_strip.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 1 · Accueil: greeting, next event hero, the "Mon billet" strip (saved tickets) and the calendar.
class HomeScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final events = ref.watch(upcomingEventsProvider);
    final now = ref.watch(clockProvider).value ?? ref.read(nowProvider)();
    // The name for the greeting: the Profil tab's `/fan/me`, read once per session (shared with that tab).
    final name = ref.watch(sessionProvider) ? ref.watch(profileProvider).value?.displayName : null;

    return SafeArea(
      bottom: false,
      child: RefreshIndicator(
        color: JomhorColors.accent,
        backgroundColor: JomhorColors.surface,
        onRefresh: () => ref.refresh(upcomingEventsProvider.future),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, JomhorSpace.xxl),
          children: [
            Entrance(child: _TopBar(initials: initials(name))),
            const SizedBox(height: JomhorSpace.xl),
            Entrance(
              index: 1,
              child: Text(
                switch ((ref.watch(sessionProvider), firstName(name))) {
                  (false, _) => l.homeGreetingGuest,
                  (true, final first?) => l.homeGreeting(first),
                  (true, null) => l.homeGreetingNoName,
                },
                key: const Key('home.greeting'),
                style: JomhorType.h1.copyWith(fontSize: 34),
              ),
            ),
            const SizedBox(height: JomhorSpace.l),
            ...events.when(
              // Without the hero (offline on matchday, say) the strip still leads to the ticket.
              loading: () => [const MyTicketStrip(), const SkeletonList(hero: true)],
              error: (e, _) => [
                const MyTicketStrip(),
                ErrorPanel(error: e, onRetry: () => ref.invalidate(upcomingEventsProvider)),
              ],
              data: (list) => list.isEmpty
                  ? [const MyTicketStrip(), StatePanel(title: l.homeEmptyTitle, message: l.homeEmpty)]
                  : [
                      Entrance(
                        index: 2,
                        child: _Hero(event: list.first, now: now),
                      ),
                      const Entrance(index: 3, child: MyTicketStrip()),
                      if (list.length > 1) ...[
                        const SizedBox(height: JomhorSpace.l),
                        Entrance(index: 4, child: Text(l.calendar, style: JomhorType.h2)),
                        const SizedBox(height: JomhorSpace.m),
                        for (final (i, e) in list.skip(1).take(3).indexed)
                          Entrance(
                            index: 5 + i,
                            child: _CalendarRow(event: e, now: now),
                          ),
                      ],
                    ],
            ),
          ],
        ),
      ),
    );
  }
}

/// The club of this build (tokens.json `club`) and the fan's avatar (Profil). The bell (notifications) is Lot 4.
class _TopBar extends StatelessWidget {
  const new({required this.initials});

  final String? initials;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Row(
      children: [
        const Crest(initial: JomhorClub.crestInitial),
        const SizedBox(width: 10),
        const Expanded(
          child: Text(JomhorClub.name, style: JomhorType.h2, maxLines: 1, overflow: TextOverflow.ellipsis),
        ),
        IconButton(
          key: const Key('home.profile'),
          tooltip: l.homeOpenProfile,
          onPressed: () => context.go('/profile'),
          icon: switch (initials) {
            final i? => Text(
              i,
              textScaler: TextScaler.noScaling,
              style: JomhorType.cta.copyWith(fontSize: 14, color: JomhorColors.text),
            ),
            null => const Icon(Icons.person_outline_rounded, size: 20),
          },
        ),
      ],
    );
  }
}

class _Hero extends StatelessWidget {
  const new({required this.event, required this.now});

  final FanEvent event;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final e = event;
    final facts = factsLine(l, e.facts);
    final left = countdown(now, e.startsAt);
    final open = e.saleState(now) == SaleState.onSale;
    final football = e.facts is FootballFacts;
    void openEvent() => context.push('/events/${e.id}');

    return TapCard(
      onTap: openEvent,
      radius: JomhorRadius.card,
      padding: EdgeInsets.zero,
      gradient: const LinearGradient(
        begin: AlignmentDirectional.topStart,
        end: AlignmentDirectional.bottomEnd,
        colors: [JomhorColors.surface2, JomhorColors.surface, JomhorColors.ground],
        stops: [0, 0.55, 1],
      ),
      child: Stack(
        children: [
          if (football) const Positioned.fill(child: CustomPaint(painter: _PitchLines())),
          const PositionedDirectional(top: -110, end: -90, child: AccentGlow(size: 300, opacity: 0.3)),
          Padding(
            padding: const EdgeInsets.all(JomhorSpace.xl),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(
                  (football ? l.heroLabelMatch : l.heroLabelEvent).toUpperCase(),
                  style: JomhorType.label.copyWith(color: JomhorColors.accent),
                ),
                const SizedBox(height: JomhorSpace.l),
                if (e.facts case FootballFacts(:final opponent?))
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Expanded(
                        child: _Team(initial: JomhorClub.crestInitial, name: JomhorClub.name, home: true),
                      ),
                      Padding(
                        padding: const EdgeInsets.only(top: 14),
                        child: Text(
                          l.versus,
                          style: JomhorType.hero.copyWith(fontSize: 34, color: JomhorColors.textMuted),
                        ),
                      ),
                      Expanded(
                        child: _Team(initial: opponent.isEmpty ? '' : opponent[0], name: opponent, home: false),
                      ),
                    ],
                  )
                else
                  Text(
                    e.title.toUpperCase(),
                    style: JomhorType.hero.copyWith(fontSize: 36),
                    textAlign: TextAlign.center,
                  ),
                const SizedBox(height: JomhorSpace.l),
                if (facts != null)
                  Text(
                    facts,
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                    textAlign: TextAlign.center,
                  ),
                const SizedBox(height: 2),
                Text(
                  l.whenWhere(
                    venueDate(e.startsAt, e.timezone, l.localeName),
                    venueClock(e.startsAt, e.timezone, l.localeName),
                    e.venueName,
                  ),
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary, fontWeight: FontWeight.w500),
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: JomhorSpace.l),
                Row(
                  children: [
                    Expanded(
                      child: CountdownTile(value: left.days, label: l.countdownDays),
                    ),
                    const SizedBox(width: JomhorSpace.s),
                    Expanded(
                      child: CountdownTile(value: left.hours, label: l.countdownHours),
                    ),
                    const SizedBox(width: JomhorSpace.s),
                    Expanded(
                      child: CountdownTile(value: left.minutes, label: l.countdownMinutes),
                    ),
                  ],
                ),
                const SizedBox(height: JomhorSpace.l),
                FilledButton(
                  key: const Key('hero.cta'),
                  onPressed: open ? openEvent : null,
                  child: Text(ctaLabel(l, e, now), textAlign: TextAlign.center),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Faint halfway line and centre circle behind a match hero: the pitch as texture, in the border tone.
class _PitchLines extends CustomPainter {
  const new();

  @override
  void paint(Canvas canvas, Size size) {
    final line = Paint()
      ..color = JomhorColors.border.withValues(alpha: 0.55)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.2;
    final centre = Offset(size.width / 2, size.height * 0.3);
    canvas
      ..drawLine(Offset(size.width / 2, 0), Offset(size.width / 2, size.height), line)
      ..drawCircle(centre, size.width * 0.2, line)
      ..drawCircle(centre, 3, line..style = PaintingStyle.fill);
  }

  @override
  bool shouldRepaint(_PitchLines oldDelegate) => false;
}

class _Team extends StatelessWidget {
  const new({required this.initial, required this.name, required this.home});

  final String initial;
  final String name;
  final bool home;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Crest(initial: initial, size: 68, home: home, glow: home),
      const SizedBox(height: JomhorSpace.m),
      Text(
        name.toUpperCase(),
        style: JomhorType.h2.copyWith(fontSize: 20, height: 1),
        textAlign: TextAlign.center,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
    ],
  );
}

class _CalendarRow extends StatelessWidget {
  const new({required this.event, required this.now});

  final FanEvent event;
  final DateTime now;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final e = event;
    final (day, month) = venueDayMonth(e.startsAt, e.timezone, l.localeName);
    final facts = factsLine(l, e.facts);
    final clock = venueClock(e.startsAt, e.timezone, l.localeName);
    final onSale = e.saleState(now) == SaleState.onSale;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TapCard(
        onTap: () => context.push('/events/${e.id}'),
        child: Row(
          children: [
            DateBadge(day: day, month: month),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(rowTitle(e), style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(
                    facts == null ? clock : l.dotPair(facts, clock),
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                  ),
                ],
              ),
            ),
            const SizedBox(width: JomhorSpace.s),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 120),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 10),
                decoration: onSale
                    ? const BoxDecoration(
                        color: JomhorColors.ground,
                        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip)),
                      )
                    : null,
                child: Text(
                  chipLabel(l, e, now),
                  textAlign: TextAlign.end,
                  style: JomhorType.caption.copyWith(
                    fontSize: 12,
                    color: onSale ? JomhorColors.accent : JomhorColors.textMuted,
                    fontWeight: onSale ? FontWeight.w600 : FontWeight.w500,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
