import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show DateFormat;
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/club/club_repository.dart';
import 'package:jomhor_fan/features/club/club_seen.dart';
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/tickets/ticket_screen.dart' show TicketBand;
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Join months are shown in Morocco's zone (lot-4 Q97 uses it as the default zone too).
const _homeZone = 'Africa/Casablanca';

/// One page of the Club pager: the member card, the outlined join card, or their loading and failed states. A join
/// swaps the join card for the member card, which rises from the CTA and settles (DESIGN.md wow table, screen 7).
class ClubCard extends ConsumerStatefulWidget {
  const new({required this.club, required this.current, required this.onJoin, super.key});

  final Club club;

  /// The page in view: only its number rolls on a first reveal (the peeking card waits for its turn).
  final bool current;
  final VoidCallback onJoin;

  @override
  ConsumerState<ClubCard> createState() => _ClubCardState();
}

class _ClubCardState extends ConsumerState<ClubCard> {
  /// The key of the card shown, and whether the last change was a join (the only change that animates).
  Key? _shown;
  var _rise = false;

  @override
  Widget build(BuildContext context) {
    final club = widget.club;
    final membership = ref.watch(membershipProvider(club.id));
    final seen = ref.watch(clubSeenProvider).value;
    final Widget child;
    if (membership.value case final m? when seen != null) {
      child = switch (m.member) {
        final member? => MemberCard(
          key: const ValueKey('member'),
          club: club,
          member: member,
          tier: m.loyalty.tier,
          firstReveal: !seen.contains(club.id),
          current: widget.current,
        ),
        null => _JoinCard(key: const ValueKey('join'), club: club, onJoin: widget.onJoin),
      };
    } else if (membership.error case final error?) {
      child = _FailedCard(
        key: const ValueKey('failed'),
        error: error,
        onRetry: () => ref.invalidate(membershipProvider(club.id)),
      );
    } else {
      child = const Skeleton(key: ValueKey('loading'), height: double.infinity, radius: JomhorRadius.cardLarge);
    }
    if (child.key != _shown) {
      _rise = _shown == const ValueKey('join') && child.key == const ValueKey('member');
      _shown = child.key;
    }
    return AnimatedSwitcher(
      duration: _rise ? motionOf(context, JomhorMotion.ticketFlip) : Duration.zero,
      switchInCurve: Curves.easeOutCubic,
      switchOutCurve: Curves.easeInCubic,
      transitionBuilder: (child, animation) => FadeTransition(
        opacity: animation,
        child: SlideTransition(
          position: Tween(begin: const Offset(0, 0.18), end: Offset.zero).animate(animation),
          child: child,
        ),
      ),
      child: child,
    );
  }
}

/// The member card: a dark `surface2` card, radius 28, on the Jomhor accent band with the club's crest and name (the
/// club is content, never a colour), "MEMBRE N° 000042" (rolls on its first reveal), the tier and the join month.
class MemberCard extends ConsumerStatefulWidget {
  const new({
    required this.club,
    required this.member,
    required this.tier,
    required this.firstReveal,
    required this.current,
    super.key,
  });

  final Club club;
  final Member member;
  final Tier tier;

  /// Never shown on this phone yet (lot-4 Q95): the number rolls, once, when the card is [current].
  final bool firstReveal;
  final bool current;

  @override
  ConsumerState<MemberCard> createState() => _MemberCardState();
}

class _MemberCardState extends ConsumerState<MemberCard> {
  bool get _reveal => widget.firstReveal && widget.current;

  @override
  void initState() {
    super.initState();
    if (_reveal) _saveSeen();
  }

  @override
  void didUpdateWidget(MemberCard old) {
    super.didUpdateWidget(old);
    if (_reveal && !(old.firstReveal && old.current)) _saveSeen();
  }

  void _saveSeen() => WidgetsBinding.instance.addPostFrameCallback((_) {
    if (mounted) unawaited(ref.read(clubSeenProvider.notifier).add([widget.club.id]));
  });

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final since = DateFormat('MMM y', intlLocale(l.localeName)).format(venueTime(widget.member.joinedAt, _homeZone));
    final accent = JomhorType.bodySmall.copyWith(color: JomhorColors.accent, fontWeight: FontWeight.w600);
    return ClipRRect(
      key: Key('club.card.${widget.club.id}'),
      borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
      child: ColoredBox(
        color: JomhorColors.surface2,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TicketBand(
              overline: l.clubMemberCard,
              title: widget.club.name,
              club: widget.club,
              crestKey: Key('club.crest.${widget.club.id}'),
              titleMaxLines: 1,
            ),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 18, JomhorSpace.xl, 18),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Semantics(
                      label: '${l.clubMemberNo} ${widget.member.padded}',
                      excludeSemantics: true,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            l.clubMemberNo.toUpperCase(),
                            style: JomhorType.label.copyWith(color: JomhorColors.textMuted).tracked(context),
                          ),
                          const SizedBox(height: JomhorSpace.xs),
                          // Never wraps: shrinks instead at the largest text sizes.
                          FittedBox(
                            fit: BoxFit.scaleDown,
                            alignment: AlignmentDirectional.centerStart,
                            child: MemberNumber(
                              number: widget.member.padded,
                              roll: widget.firstReveal && !reduceMotion(context),
                              play: widget.current,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Expanded(
                          child: Text(
                            widget.tier.name.toUpperCase(),
                            key: Key('club.tier.${widget.club.id}'),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: JomhorType.h2.copyWith(color: JomhorColors.accent),
                          ),
                        ),
                        const SizedBox(width: JomhorSpace.m),
                        Text(l.clubMemberSince(since), style: accent),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// "000042" in display 44, tabular, left to right in every locale. With [roll] (first reveal), each digit slides up
/// into place, staggered, in at most 420 ms, once [play] (its card is in view); hidden until then, static after.
class MemberNumber extends StatefulWidget {
  const new({required this.number, required this.roll, this.play = true, super.key});

  final String number;
  final bool roll;
  final bool play;

  @override
  State<MemberNumber> createState() => _MemberNumberState();
}

class _MemberNumberState extends State<MemberNumber> with SingleTickerProviderStateMixin {
  late final _roll = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: JomhorMotion.ticketFlip),
    value: widget.roll ? 0 : 1,
  );

  static final TextStyle _style = JomhorType.number.copyWith(
    fontSize: 44,
    color: JomhorColors.text,
    fontFeatures: const [FontFeature.tabularFigures()],
  );

  /// Each digit takes this share of the roll; the last one starts at 1 - [_share].
  static const _share = 0.64;

  var _started = false;

  @override
  void initState() {
    super.initState();
    _play();
  }

  @override
  void didUpdateWidget(MemberNumber old) {
    super.didUpdateWidget(old);
    _play();
    // Seen elsewhere before it ever played: shown as is.
    if (!_started && !widget.roll) _roll.value = 1;
  }

  void _play() {
    if (_started || !widget.roll || !widget.play) return;
    _started = true;
    _roll.forward(from: 0);
  }

  @override
  void dispose() {
    _roll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _roll,
    builder: (context, _) {
      if (_roll.isCompleted) {
        return Text(widget.number, key: const Key('club.number'), textDirection: TextDirection.ltr, style: _style);
      }
      final digits = widget.number.characters.toList();
      final step = (1 - _share) / max(1, digits.length - 1);
      return Directionality(
        textDirection: TextDirection.ltr,
        child: Row(
          key: Key(_roll.isAnimating ? 'club.number.roll' : 'club.number.waiting'),
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final (i, digit) in digits.indexed)
              Builder(
                builder: (context) {
                  final t = Interval(i * step, i * step + _share, curve: Curves.easeOutCubic).transform(_roll.value);
                  return ClipRect(
                    child: Opacity(
                      opacity: t,
                      child: FractionalTranslation(
                        translation: Offset(0, 1 - t),
                        child: Text(digit, style: _style),
                      ),
                    ),
                  );
                },
              ),
          ],
        ),
      );
    },
  );
}

/// Not a member: an outlined card with the crest and name, and the free join CTA.
class _JoinCard extends StatelessWidget {
  const new({required this.club, required this.onJoin, super.key});

  final Club club;
  final VoidCallback onJoin;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return DecoratedBox(
      key: Key('club.joinCard.${club.id}'),
      decoration: const BoxDecoration(
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(JomhorSpace.xl),
        // Centred as one block: an invitation, not an empty member card.
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Row(
              children: [
                Crest(key: Key('club.crest.${club.id}'), name: club.name, url: club.crestUrl, size: 56),
                const SizedBox(width: JomhorSpace.l),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        club.name.toUpperCase(),
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: JomhorType.h1.copyWith(fontSize: 26),
                      ),
                      const SizedBox(height: JomhorSpace.xs),
                      Text(
                        l.clubJoinPitch,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: JomhorSpace.xl),
            FilledButton(key: Key('club.join.${club.id}'), onPressed: onJoin, child: Text(l.clubJoin)),
          ],
        ),
      ),
    );
  }
}

class _FailedCard extends StatelessWidget {
  const new({required this.error, required this.onRetry, super.key});

  final Object error;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return DecoratedBox(
      decoration: const BoxDecoration(
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.cardLarge)),
      ),
      child: Center(
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                errorText(l, switch (error) {
                  ApiError(:final code) => code,
                  _ => ApiError.unexpectedResponse,
                }),
                textAlign: TextAlign.center,
                style: JomhorType.body.copyWith(color: JomhorColors.textSecondary),
              ),
              const SizedBox(height: JomhorSpace.m),
              OutlinedButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh_rounded, size: 20),
                label: Text(l.retry),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// "Ta saison" in twelve dots (DESIGN.md wow table, screen 7): placeholders, then the last 11 closed events oldest
/// first (white = attended, outlined = missed), then the next event in amber. A straight row in reading direction
/// (mirrored in AR), never the J mark. A dot attended since this phone last showed it fills once. One label for
/// screen readers.
class SeasonRow extends ConsumerWidget {
  const new({required this.club, required this.loyalty, required this.seen, this.next, super.key});

  final Club club;
  final Loyalty loyalty;
  final Set<String> seen;

  /// The club's next event (the twelfth dot's caption); null when there is none.
  final FanEvent? next;

  static const dotSize = 14.0;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = AppLocalizations.of(context);
    final dots = seasonDots(loyalty);
    final past = loyalty.season;
    final firstPast = dots.length - 1 - past.length;
    final count = l.clubSeasonLabel(past.where((e) => e.attended).length, past.length);
    var fresh = 0;
    return Semantics(
      key: Key('club.season.${club.id}'),
      container: true,
      label: next == null ? count : l.dotPair(count, l.clubSeasonLabelNext(next!.title)),
      excludeSemantics: true,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              for (final (i, dot) in dots.indexed)
                switch (dot) {
                  SeasonDot.attended => _attended(past[i - firstPast].eventId, () => fresh++),
                  SeasonDot.missed => _Dot(kind: dot, key: Key('club.dot.missed.${past[i - firstPast].eventId}')),
                  SeasonDot.placeholder => _Dot(kind: dot, key: Key('club.dot.placeholder.$i')),
                  SeasonDot.next => _Dot(kind: dot, key: const Key('club.dot.next')),
                },
            ],
          ),
          if (next case final next?) ...[
            const SizedBox(height: JomhorSpace.s),
            Text(
              l.clubSeasonNext(venueDate(next.startsAt, next.timezone, l.localeName)),
              key: Key('club.next.${club.id}'),
              textAlign: TextAlign.end,
              style: JomhorType.caption.copyWith(color: JomhorColors.accent, fontSize: 12, fontWeight: FontWeight.w600),
            ),
          ],
        ],
      ),
    );
  }

  /// [nextFresh] numbers the fresh dots: they fill in turn, all within 420 ms.
  Widget _attended(int eventId, int Function() nextFresh) {
    final key = seenDotKey(club.id, eventId);
    final fresh = !seen.contains(key);
    return _AttendedDot(
      key: Key('club.dot.attended.$eventId'),
      seenKey: key,
      fresh: fresh,
      delay: fresh ? 18 * nextFresh() : 0,
    );
  }
}

class _Dot extends StatelessWidget {
  const new({required this.kind, super.key});

  final SeasonDot kind;

  @override
  Widget build(BuildContext context) => Container(
    width: SeasonRow.dotSize,
    height: SeasonRow.dotSize,
    decoration: BoxDecoration(
      shape: BoxShape.circle,
      color: switch (kind) {
        SeasonDot.placeholder => JomhorColors.border,
        SeasonDot.attended => JomhorColors.text,
        SeasonDot.next => JomhorColors.accent,
        SeasonDot.missed => null,
      },
      border: kind == SeasonDot.missed ? Border.all(color: JomhorColors.textMuted, width: 1.5) : null,
    ),
  );
}

/// An attended dot; [fresh] (never shown filled on this phone) fills once, scale and fill over `motion.base` after
/// [delay] ms, and is then saved as seen.
class _AttendedDot extends ConsumerStatefulWidget {
  const new({required this.seenKey, required this.fresh, required this.delay, super.key});

  final String seenKey;
  final bool fresh;
  final int delay;

  @override
  ConsumerState<_AttendedDot> createState() => _AttendedDotState();
}

class _AttendedDotState extends ConsumerState<_AttendedDot> with SingleTickerProviderStateMixin {
  late final _fill = AnimationController(
    vsync: this,
    duration: Duration(milliseconds: JomhorMotion.base + widget.delay),
    value: 1,
  );
  var _started = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _start();
  }

  @override
  void didUpdateWidget(_AttendedDot old) {
    super.didUpdateWidget(old);
    _start();
  }

  void _start() {
    if (!widget.fresh || _started) return;
    _started = true;
    if (!reduceMotion(context)) _fill.forward(from: 0);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(ref.read(clubSeenProvider.notifier).add([widget.seenKey]));
    });
  }

  @override
  void dispose() {
    _fill.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _fill,
    builder: (context, _) {
      final start = widget.delay / (JomhorMotion.base + widget.delay);
      final t = Interval(start, 1, curve: Curves.easeOut).transform(_fill.value);
      return Transform.scale(
        key: _fill.isAnimating ? const Key('club.dot.filling') : null,
        scale: 1 + 0.35 * sin(pi * t),
        child: Container(
          width: SeasonRow.dotSize,
          height: SeasonRow.dotSize,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: JomhorColors.text.withValues(alpha: t),
            border: t < 1
                ? Border.all(color: Color.lerp(JomhorColors.textMuted, JomhorColors.text, t)!, width: 1.5)
                : null,
          ),
        ),
      );
    },
  );
}
