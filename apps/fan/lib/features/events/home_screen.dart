import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/events/sale_copy.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// Screen 1 · Accueil: next event hero + calendar. The "Mon billet" strip comes with FL-7.
class HomeScreen extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final events = ref.watch(upcomingEventsProvider);
    final now = ref.watch(clockProvider).value ?? ref.read(nowProvider)();

    return SafeArea(
      child: RefreshIndicator(
        onRefresh: () => ref.refresh(upcomingEventsProvider.future),
        child: ListView(
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, JomhorSpace.xxl),
          children: [
            // Top bar: the club of this build (tokens.json `club`). The bell (notifications) is Lot 4.
            const Row(
              children: [
                _Crest(initial: JomhorClub.crestInitial, size: 36, home: true),
                SizedBox(width: JomhorSpace.s),
                Text(JomhorClub.name, style: JomhorType.h2),
              ],
            ),
            const SizedBox(height: JomhorSpace.l),
            ...events.when(
              loading: () => [const Center(child: CircularProgressIndicator())],
              error: (e, _) => [ErrorPanel(error: e, onRetry: () => ref.invalidate(upcomingEventsProvider))],
              data: (list) => list.isEmpty
                  ? [Text(l.homeEmpty, style: const TextStyle(color: JomhorColors.textSecondary))]
                  : [
                      _Hero(event: list.first, now: now),
                      if (list.length > 1) ...[
                        const SizedBox(height: JomhorSpace.xl),
                        Text(l.calendar, style: JomhorType.h2),
                        const SizedBox(height: JomhorSpace.m),
                        for (final e in list.skip(1).take(3)) _CalendarRow(event: e, now: now),
                      ],
                    ],
            ),
          ],
        ),
      ),
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
    void openEvent() => context.push('/events/${e.id}');

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: openEvent,
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                (e.facts is FootballFacts ? l.heroLabelMatch : l.heroLabelEvent).toUpperCase(),
                style: JomhorType.label.copyWith(color: JomhorColors.accent),
              ),
              const SizedBox(height: JomhorSpace.m),
              if (e.facts case FootballFacts(:final opponent?))
                Row(
                  children: [
                    const Expanded(
                      child: _Team(initial: JomhorClub.crestInitial, name: JomhorClub.name, home: true),
                    ),
                    Text(l.versus, style: JomhorType.h1.copyWith(color: JomhorColors.textMuted)),
                    Expanded(
                      child: _Team(initial: opponent.isEmpty ? '' : opponent[0], name: opponent, home: false),
                    ),
                  ],
                )
              else
                Text(e.title.toUpperCase(), style: JomhorType.h1, textAlign: TextAlign.center),
              if (facts != null) ...[
                const SizedBox(height: JomhorSpace.xs),
                Text(
                  facts,
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                  textAlign: TextAlign.center,
                ),
              ],
              const SizedBox(height: JomhorSpace.m),
              Text(
                l.whenWhere(
                  venueDate(e.startsAt, e.timezone, l.localeName),
                  venueClock(e.startsAt, e.timezone, l.localeName),
                  e.venueName,
                ),
                style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: JomhorSpace.m),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  _Tile(value: left.days, label: l.countdownDays),
                  const SizedBox(width: JomhorSpace.s),
                  _Tile(value: left.hours, label: l.countdownHours),
                  const SizedBox(width: JomhorSpace.s),
                  _Tile(value: left.minutes, label: l.countdownMinutes),
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
      ),
    );
  }
}

/// Club crest stand-in: a circle with the initial (tokens.json `club.crest` is null for now).
class _Crest extends StatelessWidget {
  const new({required this.initial, required this.size, required this.home});

  final String initial;
  final double size;

  /// Home = the club colours; away = paper.
  final bool home;

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    alignment: Alignment.center,
    decoration: BoxDecoration(shape: BoxShape.circle, color: home ? JomhorColors.accent : JomhorColors.ticketPaper),
    child: Text(
      initial.toUpperCase(),
      style: JomhorType.number.copyWith(
        fontSize: size / 2,
        color: home ? JomhorColors.onAccent : JomhorColors.ticketInk,
      ),
    ),
  );
}

class _Team extends StatelessWidget {
  const new({required this.initial, required this.name, required this.home});

  final String initial;
  final String name;
  final bool home;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      _Crest(initial: initial, size: 64, home: home),
      const SizedBox(height: JomhorSpace.xs),
      Text(name.toUpperCase(), style: JomhorType.h2, textAlign: TextAlign.center),
    ],
  );
}

/// Countdown tile: display number + caption.
class _Tile extends StatelessWidget {
  const new({required this.value, required this.label});

  final int value;
  final String label;

  @override
  Widget build(BuildContext context) => Container(
    constraints: const BoxConstraints(minWidth: 64),
    padding: const EdgeInsets.symmetric(vertical: JomhorSpace.s, horizontal: JomhorSpace.m),
    decoration: const BoxDecoration(
      color: JomhorColors.ground,
      borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip)),
    ),
    child: Column(
      children: [
        Text(value.toString().padLeft(2, '0'), style: JomhorType.number),
        Text(label.toUpperCase(), style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
      ],
    ),
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
          onTap: () => context.push('/events/${e.id}'),
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
                      Text(rowTitle(e), style: JomhorType.body),
                      Text(
                        facts == null ? clock : l.dotPair(facts, clock),
                        style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                      ),
                    ],
                  ),
                ),
                Text(chipLabel(l, e, now), style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
