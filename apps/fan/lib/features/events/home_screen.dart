import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/checkout/checkout_repository.dart';
import 'package:jomhor_fan/features/club/member_strip.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/clubs_repository.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/features/profile/profile_controller.dart';
import 'package:jomhor_fan/features/tickets/my_ticket_strip.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 1 · Accueil: greeting, the club chips, the pending cash code strip, next event hero, the "Mon billet"
/// strip (saved tickets) and the calendar.
class HomeScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final events = ref.watch(upcomingEventsProvider);
    final now = ref.watch(clockProvider).value ?? ref.read(nowProvider)();
    // The name for the greeting: the Profil tab's `/fan/me`, read once per session (shared with that tab).
    final name = ref.watch(sessionProvider) ? ref.watch(profileProvider).value?.displayName : null;
    final view = ref.watch(homeViewProvider).value;

    return SafeArea(
      bottom: false,
      child: RefreshIndicator(
        color: JomhorColors.accent,
        backgroundColor: JomhorColors.surface,
        onRefresh: () {
          ref.invalidate(pendingCashProvider);
          // A failed directory read stays until invalidated: retry it with the events.
          if (ref.read(clubsProvider).hasError) ref.invalidate(clubsProvider);
          return ref.refresh(upcomingEventsProvider.future);
        },
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
                  (true, final first?) => l.homeGreeting(l.isolate(first)),
                  (true, null) => l.homeGreetingNoName,
                },
                key: const Key('home.greeting'),
                style: JomhorType.h1.copyWith(fontSize: 34),
              ),
            ),
            const SizedBox(height: JomhorSpace.l),
            if (view != null) ...[_ChipRow(view: view), const SizedBox(height: JomhorSpace.l)],
            const _CashStrip(),
            ...events.when(
              // Without the hero (offline on matchday, say) the strip still leads to the ticket.
              loading: () => [const MyTicketStrip(), const MemberStrip(), const SkeletonList(hero: true)],
              error: (e, _) => [
                const MyTicketStrip(),
                const MemberStrip(),
                ErrorPanel(
                  error: e,
                  onRetry: () => ref
                    ..invalidate(clubsProvider)
                    ..invalidate(upcomingEventsProvider),
                ),
              ],
              data: (list) => list.isEmpty
                  ? [const MyTicketStrip(), const MemberStrip(), _Empty(view: view)]
                  : [
                      Entrance(
                        index: 2,
                        child: _Hero(event: list.first, now: now, view: view),
                      ),
                      const Entrance(index: 3, child: MyTicketStrip()),
                      const Entrance(index: 3, child: MemberStrip()),
                      if (list.length > 1) ...[
                        const SizedBox(height: JomhorSpace.l),
                        Entrance(index: 4, child: Text(l.calendar, style: JomhorType.h2)),
                        const SizedBox(height: JomhorSpace.m),
                        for (final (i, e) in list.skip(1).take(3).indexed)
                          Entrance(
                            index: 5 + i,
                            child: _CalendarRow(event: e, now: now, view: view),
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

/// The club chips (DESIGN.md §Multi-club adjustments): `Mes clubs` (when following), one per followed club, `Tout`.
/// Scrolls sideways and starts at the reading start (the right in AR). The choice is remembered on the phone.
class _ChipRow extends ConsumerWidget {
  const new({required this.view});

  final HomeView view;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final chips = [
      if (view.followed.isNotEmpty) (chipMine, l.myClubs, null),
      for (final c in view.followed) (c.id, l.isolate(c.name), c),
      (chipAll, l.chipAll, null),
    ];
    return SizedBox(
      height: JomhorSize.touchMin,
      child: ListView.separated(
        key: const Key('home.chips'),
        scrollDirection: Axis.horizontal,
        itemCount: chips.length,
        separatorBuilder: (_, _) => const SizedBox(width: JomhorSpace.s),
        itemBuilder: (context, i) {
          final (chip, label, club) = chips[i];
          return _Chip(
            key: Key('home.chip.$chip'),
            label: label,
            club: club,
            selected: chip == view.chip,
            onTap: () => ref.read(followsProvider.notifier).choose(chip),
          );
        },
      ),
    );
  }
}

/// A pill, 36 high in a 44 target: accent when selected, else surface + border.
class _Chip extends StatelessWidget {
  const new({required this.label, required this.selected, required this.onTap, this.club, super.key});

  final String label;
  final Club? club;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
    button: true,
    selected: selected,
    child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Center(
        child: AnimatedContainer(
          duration: motionOf(context, JomhorMotion.base),
          height: 36,
          padding: EdgeInsetsDirectional.only(start: club == null ? 14 : JomhorSpace.s, end: 14),
          decoration: BoxDecoration(
            color: selected ? JomhorColors.accent : JomhorColors.surface,
            border: Border.all(color: selected ? JomhorColors.accent : JomhorColors.border),
            borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (club case final c?) ...[Crest(name: c.name, url: c.crestUrl, size: 20), const SizedBox(width: 6)],
              ConstrainedBox(
                // ponytail: the API has no short name; a long club name is cut here.
                constraints: const BoxConstraints(maxWidth: 140),
                child: Text(
                  label,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: JomhorType.bodySmall.copyWith(
                    color: selected ? JomhorColors.onAccent : JomhorColors.textSecondary,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

/// No event: in `Tout`, "Rien au programme"; for followed clubs, "Pas de match à venir" (sports clubs) or
/// "Pas d'événement à venir", and "Voir tout" → `Tout`.
class _Empty extends ConsumerWidget {
  const new({required this.view});

  final HomeView? view;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final v = view;
    if (v == null || v.chip == chipAll) return StatePanel(title: l.homeEmptyTitle, message: l.homeEmpty);
    final clubs = v.chip == chipMine ? v.followed : v.followed.where((c) => c.id == v.chip);
    return StatePanel(
      key: const Key('home.emptyClub'),
      message: clubs.every((c) => c.sportsClub) ? l.homeNoMatch : l.homeNoEvent,
      action: TextButton(
        key: const Key('home.seeAll'),
        onPressed: () => ref.read(followsProvider.notifier).choose(chipAll),
        child: Text(l.homeSeeAll),
      ),
    );
  }
}

/// The club line of an Accueil card. In `Tout`, it ends with "Suivre" for a club the fan does not follow (a tap
/// follows it, `Tout` stays selected) and "Suivi" for one they follow.
class _CardClubLine extends ConsumerWidget {
  const new({required this.event, required this.view});

  final FanEvent event;
  final HomeView? view;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final club = event.organizer;
    final followed = ref.watch(followsProvider.select((f) => f.ids.contains(club.id)));
    return ClubLine(
      name: club.name,
      crestUrl: club.crestUrl,
      trailing: switch ((view?.chip == chipAll, followed)) {
        (false, _) => null,
        // Start padding only: the calendar row leaves the club line about 126 px on a 390 px phone.
        (true, true) => Padding(
          key: Key('home.following.${event.id}'),
          padding: const EdgeInsetsDirectional.only(start: JomhorSpace.s),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.check_rounded, size: 14, color: JomhorColors.accent),
              const SizedBox(width: JomhorSpace.xs),
              Text(
                l.clubFollowing,
                style: JomhorType.caption.copyWith(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: JomhorColors.accent,
                ),
              ),
            ],
          ),
        ),
        (true, false) => TextButton(
          key: Key('home.follow.${event.id}'),
          onPressed: () => ref.read(followsProvider.notifier).follow(club.id),
          style: TextButton.styleFrom(
            minimumSize: const Size(JomhorSize.touchMin, JomhorSize.touchMin),
            padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.s),
            foregroundColor: JomhorColors.accent,
            textStyle: JomhorType.caption.copyWith(fontSize: 12, fontWeight: FontWeight.w600),
          ),
          child: Text(l.clubFollow),
        ),
      },
    );
  }
}

/// DESIGN.md Lot 3 and Lot 4, screen 1: the order waiting on the fan that runs out first, in amber: a cash code →
/// the cash order screen, or a place a friend kept → screen 9 member. Nothing while loading, signed out, offline or
/// on error: it never holds the home back.
class _CashStrip extends ConsumerWidget {
  const new();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final o = ref.watch(pendingCashProvider).value;
    if (o == null) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    final member = o.status == 'RESERVED'; // pendingCash: else AWAITING_CASH with a code.
    final deadline = venueWhen(o.reservedUntil!, o.timezone, l.localeName);
    return Entrance(
      child: Padding(
        padding: const EdgeInsets.only(bottom: JomhorSpace.l),
        child: TapCard(
          inkKey: const Key('home.cashOrder'),
          onTap: () => context.push('/orders/${o.id}'),
          color: JomhorColors.accent.withValues(alpha: 0.12),
          borderColor: JomhorColors.accent.withValues(alpha: 0.5),
          radius: JomhorRadius.card,
          padding: const EdgeInsets.symmetric(vertical: JomhorSpace.l, horizontal: JomhorSpace.xl),
          child: Row(
            children: [
              Icon(member ? Icons.event_seat_outlined : Icons.payments_outlined, size: 22, color: JomhorColors.accent),
              const SizedBox(width: JomhorSpace.m),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      member
                          ? l.homeMemberTitle(l.ltr(venueClock(o.reservedUntil!, o.timezone, l.localeName)))
                          : l.homeCashTitle,
                      style: JomhorType.body.copyWith(color: JomhorColors.text, fontWeight: FontWeight.w600),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      member ? o.eventTitle : l.dotPair(o.eventTitle, l.homeCashDeadline(deadline)),
                      key: const Key('home.cashDeadline'),
                      style: JomhorType.bodySmall.copyWith(color: JomhorColors.accentHover),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// The Jomhor lockup ([BrandBar]) and the fan's avatar (Profil). The bell (notifications) is Lot 4.
class _TopBar extends StatelessWidget {
  const new({required this.initials});

  final String? initials;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return BrandBar(
      trailing: IconButton(
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
    );
  }
}

class _Hero extends StatelessWidget {
  const new({required this.event, required this.now, required this.view});

  final FanEvent event;
  final DateTime now;
  final HomeView? view;

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
                  style: JomhorType.label.copyWith(color: JomhorColors.accent).tracked(context),
                ),
                const SizedBox(height: JomhorSpace.s),
                _CardClubLine(event: e, view: view),
                const SizedBox(height: JomhorSpace.m),
                if (e.facts case FootballFacts(:final opponent?))
                  Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Expanded(
                        child: _Team(name: e.organizer.name, crestUrl: e.organizer.crestUrl, home: true),
                      ),
                      Padding(
                        padding: const EdgeInsets.only(top: 14),
                        child: Text(
                          l.versus,
                          style: JomhorType.hero.copyWith(fontSize: 34, color: JomhorColors.textMuted),
                        ),
                      ),
                      Expanded(child: _Team(name: opponent, home: false)),
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

/// One side of a match: the club's crest (home), or the opponent's stand-in on paper (metadata has no crest).
class _Team extends StatelessWidget {
  const new({required this.name, required this.home, this.crestUrl});

  final String name;
  final String? crestUrl;
  final bool home;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      if (home)
        Crest(name: name, url: crestUrl, size: 68, glow: true)
      else
        Crest(name: name, size: 68, disc: JomhorColors.ticketPaper), // ink: onAccent = ticket ink
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
  const new({required this.event, required this.now, required this.view});

  final FanEvent event;
  final DateTime now;
  final HomeView? view;

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
                  _CardClubLine(event: e, view: view),
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
