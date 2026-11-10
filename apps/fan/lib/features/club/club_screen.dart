import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/app/error_text.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/club/club_repository.dart';
import 'package:jomhor_fan/features/club/club_seen.dart';
import 'package:jomhor_fan/features/club/join_sheet.dart';
import 'package:jomhor_fan/features/club/member_card.dart';
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/clubs_repository.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 7 · Club (DESIGN.md Lot 4 and §Multi-club, "7 Club"): one card per followed club, swiped in follow order
/// with the next card peeking (no page dots: they would compete with the twelve), then the shown club's season,
/// its latest events and, for a member, the opt-ins and "Quitter le club". [organizer] opens on that club's card.
class ClubScreen extends ConsumerStatefulWidget {
  const new({this.organizer, this.request, super.key});

  final String? organizer;

  /// Changes with each tap on the Accueil strip, so the same link brings its club back into view.
  final Object? request;

  @override
  ConsumerState<ClubScreen> createState() => _ClubScreenState();
}

class _ClubScreenState extends ConsumerState<ClubScreen> {
  PageController? _pager;
  var _page = 0;

  /// A club asked for by a link (the Accueil strip) once the cards are there.
  String? _wanted;

  @override
  void initState() {
    super.initState();
    _wanted = widget.organizer;
  }

  @override
  void didUpdateWidget(ClubScreen old) {
    super.didUpdateWidget(old);
    if (widget.organizer != old.organizer || widget.request != old.request) _wanted = widget.organizer;
  }

  @override
  void dispose() {
    _pager?.dispose();
    super.dispose();
  }

  /// Space between two cards.
  static const double _gap = JomhorSpace.s;

  /// The pager over [clubs], on the [_wanted] club when there is one. Each card lines up with the 20 px gutter of
  /// the sections below, and the next one peeks by the gutter less the gap (left or right, by reading direction).
  // ponytail: the fraction is set once from the width at first build; recreate the controller if rotation matters.
  PageController _pagerFor(List<Club> clubs) {
    final wanted = clubs.indexWhere((c) => c.id == _wanted);
    _wanted = null;
    final width = MediaQuery.sizeOf(context).width;
    final pager = _pager ??= PageController(
      viewportFraction: 1 - 2 * (JomhorSpace.xl - _gap / 2) / width,
      initialPage: wanted < 0 ? 0 : wanted,
    );
    if (wanted >= 0 && wanted != _page) {
      _page = wanted;
      if (pager.hasClients) WidgetsBinding.instance.addPostFrameCallback((_) => pager.jumpToPage(wanted));
    }
    _page = _page.clamp(0, clubs.length - 1);
    return pager;
  }

  Future<void> _join(Club club) async {
    if (await showJoinSheet(context, club) == true) unawaited(HapticFeedback.mediumImpact());
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final following = ref.watch(followsProvider.select((f) => f.ids.length));
    final clubs = ref.watch(followedClubsProvider);
    const pad = EdgeInsets.symmetric(horizontal: JomhorSpace.xl);

    final List<Widget> body;
    if (following == 0 || clubs.value?.isEmpty == true) {
      body = [
        StatePanel(
          title: l.clubNoFollowsTitle,
          message: l.clubNoFollows,
          action: FilledButton(
            key: const Key('club.pickClubs'),
            onPressed: () => context.push('/clubs'),
            child: Text(l.clubNoFollowsAction),
          ),
        ),
      ];
    } else if (clubs.value case final list?) {
      final pager = _pagerFor(list);
      final club = list[_page];
      body = [
        Entrance(
          index: 1,
          child: SizedBox(
            height: _cardHeight(context),
            child: PageView(
              key: const Key('club.pager'),
              controller: pager,
              onPageChanged: (i) {
                unawaited(HapticFeedback.selectionClick());
                setState(() => _page = i);
              },
              children: [
                for (final (i, c) in list.indexed)
                  Padding(
                    padding: const EdgeInsets.symmetric(horizontal: _gap / 2),
                    child: ClubCard(club: c, current: i == _page, onJoin: () => _join(c)),
                  ),
              ],
            ),
          ),
        ),
        const SizedBox(height: JomhorSpace.xxl),
        Entrance(
          index: 2,
          child: Padding(
            padding: pad,
            child: AnimatedSwitcher(
              duration: motionOf(context, JomhorMotion.base),
              layoutBuilder: (current, previous) =>
                  Stack(alignment: Alignment.topCenter, children: [...previous, ?current]),
              child: _ClubDetails(key: ValueKey(club.id), club: club),
            ),
          ),
        ),
      ];
    } else if (clubs.error case final error?) {
      body = [ErrorPanel(error: error, onRetry: () => ref.invalidate(clubsProvider))];
    } else {
      body = [
        Padding(
          padding: pad,
          child: SizedBox(
            height: _cardHeight(context),
            child: const Skeleton(height: double.infinity, radius: JomhorRadius.cardLarge),
          ),
        ),
      ];
    }

    return SafeArea(
      bottom: false,
      child: RefreshIndicator(
        color: JomhorColors.accent,
        backgroundColor: JomhorColors.surface,
        onRefresh: () async {
          ref
            ..invalidate(membershipProvider)
            ..invalidate(nextEventsProvider);
          if (ref.read(clubsProvider).hasError) ref.invalidate(clubsProvider);
          if (clubs.value case final list? when list.isNotEmpty) {
            await ref.read(membershipProvider(list[_page.clamp(0, list.length - 1)].id).future);
          }
        },
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.only(top: JomhorSpace.m, bottom: JomhorSpace.xxl),
          children: [
            const Padding(
              padding: pad,
              child: Entrance(child: BrandBar()),
            ),
            const SizedBox(height: JomhorSpace.s),
            Padding(
              padding: pad,
              child: Text(l.clubTabTitle(following < 1 ? 1 : following), style: JomhorType.h1.copyWith(fontSize: 34)),
            ),
            const SizedBox(height: JomhorSpace.xl),
            ...body,
          ],
        ),
      ),
    );
  }
}

/// Room for a card at the fan's text size (DESIGN.md §Accessibility: up to 130 %).
double _cardHeight(BuildContext context) => 100 + MediaQuery.textScalerOf(context).scale(140);

/// Under the pager, for the club shown: "Ta saison" (twelve dots, the count, the tier progress), "Derniers" and, for
/// a member, the opt-ins and "Quitter le club". Non-members see the season too.
class _ClubDetails extends ConsumerWidget {
  const new({required this.club, super.key});

  final Club club;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = ref.watch(membershipProvider(club.id)).value;
    final seen = ref.watch(clubSeenProvider).value;
    if (m == null || seen == null) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    final loyalty = m.loyalty;
    final recent = loyalty.season.reversed.take(3).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionLabel(l.clubSeason),
        Container(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          decoration: const BoxDecoration(
            color: JomhorColors.surface,
            border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
            borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.card)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SeasonRow(club: club, loyalty: loyalty, seen: seen, next: ref.watch(nextEventsProvider).value?[club.id]),
              const SizedBox(height: JomhorSpace.l),
              // The figure rolls when it changes (DESIGN.md principle 6).
              AnimatedSwitcher(
                duration: motionOf(context, JomhorMotion.base),
                transitionBuilder: (child, animation) => FadeTransition(
                  opacity: animation,
                  child: SlideTransition(
                    position: Tween(begin: const Offset(0, 0.4), end: Offset.zero).animate(animation),
                    child: child,
                  ),
                ),
                child: Text(
                  loyalty.countsMatches(club)
                      ? l.clubMatchesAttended(loyalty.eventsAttended)
                      : l.clubEventsAttended(loyalty.eventsAttended),
                  key: ValueKey('club.attended.${club.id}.${loyalty.eventsAttended}'),
                  style: JomhorType.h2,
                ),
              ),
              if ((loyalty.toNextTier, loyalty.nextTier) case (final k?, final next?)) ...[
                const SizedBox(height: JomhorSpace.xs),
                Text(
                  l.clubToNextTier(k, next.name),
                  key: Key('club.toNext.${club.id}'),
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary),
                ),
              ],
            ],
          ),
        ),
        if (recent.isNotEmpty) ...[
          const SizedBox(height: JomhorSpace.xxl),
          SectionLabel(l.clubRecent),
          for (final e in recent) _PastRow(event: e),
        ],
        if (m.member case final member?) ...[
          const SizedBox(height: JomhorSpace.l),
          SectionLabel(l.clubMessages),
          _Settings(club: club, member: member),
        ],
      ],
    );
  }
}

/// A closed event: date badge, title, and whether the fan was there.
class _PastRow extends StatelessWidget {
  const new({required this.event});

  final PastEvent event;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final (day, month) = venueDayMonth(event.startsAt, event.timezone, l.localeName);
    final (fg, bg, line) = event.attended
        ? (JomhorColors.successText, JomhorColors.successBg, JomhorColors.success)
        : (JomhorColors.textMuted, JomhorColors.ground, JomhorColors.border);
    return Container(
      key: Key('club.past.${event.eventId}'),
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsetsDirectional.fromSTEB(12, 12, 14, 12),
      decoration: const BoxDecoration(
        color: JomhorColors.surface,
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      child: Row(
        children: [
          DateBadge(day: day, month: month, past: true),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  event.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: JomhorType.body.copyWith(fontWeight: FontWeight.w600),
                ),
                const SizedBox(height: 2),
                Text(
                  venueDate(event.startsAt, event.timezone, l.localeName),
                  style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                ),
              ],
            ),
          ),
          const SizedBox(width: JomhorSpace.s),
          Container(
            padding: const EdgeInsets.symmetric(vertical: 6, horizontal: 10),
            decoration: BoxDecoration(
              color: bg,
              border: Border.all(color: line),
              borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.chip)),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (event.attended) ...[Icon(Icons.check_rounded, size: 14, color: fg), const SizedBox(width: 4)],
                Text(
                  event.attended ? l.clubAttended : l.clubMissed,
                  style: JomhorType.caption.copyWith(color: fg, fontSize: 12, fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// A member's opt-ins (each change is a PUT; on failure the switch goes back, with the error) and "Quitter le club"
/// (confirm, then DELETE; the card turns back into the join card).
class _Settings extends ConsumerStatefulWidget {
  const new({required this.club, required this.member});

  final Club club;
  final Member member;

  @override
  ConsumerState<_Settings> createState() => _SettingsState();
}

class _SettingsState extends ConsumerState<_Settings> {
  /// Shown while the server has not answered.
  bool? _push;
  bool? _whatsapp;

  void _fail(Object e) {
    if (!mounted) return;
    final l = AppLocalizations.of(context);
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(errorText(l, e is ApiError ? e.code : ApiError.unexpectedResponse))));
  }

  /// One PUT at a time: the switches are off while one runs, so consent changes land in the order made.
  bool get _saving => _push != null;

  Future<void> _save({bool? push, bool? whatsapp}) async {
    final p = push ?? widget.member.marketingPush;
    final w = whatsapp ?? widget.member.marketingWhatsapp;
    setState(() {
      _push = p;
      _whatsapp = w;
    });
    try {
      await ref.read(membershipProvider(widget.club.id).notifier).save(push: p, whatsapp: w);
    } on Object catch (e) {
      _fail(e);
    }
    // Saved: the member from the answer shows it; failed: the switch goes back.
    if (mounted) {
      setState(() {
        _push = null;
        _whatsapp = null;
      });
    }
  }

  Future<void> _leave() async {
    final l = AppLocalizations.of(context);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l.clubLeaveTitle(l.isolate(widget.club.name))),
        content: Text(l.clubLeaveBody),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l.cancel)),
          FilledButton(
            key: const Key('club.leaveConfirm'),
            style: FilledButton.styleFrom(minimumSize: const Size(JomhorSize.touchMin, 48)),
            onPressed: () => Navigator.pop(context, true),
            child: Text(l.clubLeaveConfirm),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      await ref.read(membershipProvider(widget.club.id).notifier).leave();
    } on Object catch (e) {
      _fail(e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        ClubOptIns(
          push: _push ?? widget.member.marketingPush,
          whatsapp: _whatsapp ?? widget.member.marketingWhatsapp,
          onPush: _saving ? null : (v) => _save(push: v),
          onWhatsapp: _saving ? null : (v) => _save(whatsapp: v),
        ),
        const SizedBox(height: JomhorSpace.m),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: TextButton(
            key: const Key('club.leave'),
            onPressed: _leave,
            style: TextButton.styleFrom(foregroundColor: JomhorColors.danger),
            child: Text(l.clubLeave),
          ),
        ),
      ],
    );
  }
}
