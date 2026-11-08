import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/app/error_panel.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/clubs_repository.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';
import 'package:jomhor_fan/ui/skeleton.dart';

/// Screen 11 · Tes clubs (DESIGN.md §Multi-club adjustments). On first launch (the router's redirect from `/`):
/// "Continuer" from one follow and "Tout voir", both leave for Accueil. Pushed from Profil: back and "Terminé".
class ClubsScreen extends ConsumerStatefulWidget {
  const new({super.key});

  /// More clubs than this: a search field above the grid.
  static const searchFrom = 8;

  @override
  ConsumerState<ClubsScreen> createState() => _ClubsScreenState();
}

class _ClubsScreenState extends ConsumerState<ClubsScreen> {
  var _query = '';

  /// First launch: leaves the picker with the "seen" flag on the phone first, so `/` does not redirect back.
  Future<void> _leave({String? chip}) async {
    await ref.read(followsProvider.notifier).markSeen(chip: chip);
    if (mounted) context.go('/');
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final fromProfile = context.canPop();
    final clubs = ref.watch(clubsProvider);
    final ids = ref.watch(followsProvider.select((f) => f.ids));
    final following = clubs.value?.any((c) => ids.contains(c.id)) ?? false;

    return Scaffold(
      body: SafeArea(
        bottom: false,
        child: CustomScrollView(
          slivers: [
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.l, JomhorSpace.xl, 0),
              sliver: SliverList.list(
                children: [
                  SizedBox(
                    height: 48,
                    child: Row(
                      children: [
                        if (fromProfile) ...[
                          BackButton(key: const Key('clubs.back'), onPressed: () => context.pop()),
                          const SizedBox(width: JomhorSpace.xs),
                        ],
                        const JomhorLockup(),
                      ],
                    ),
                  ),
                  const SizedBox(height: JomhorSpace.s),
                  Text(l.clubsTitle, style: JomhorType.h1.copyWith(fontSize: 34)),
                  const SizedBox(height: JomhorSpace.s),
                  Text(l.clubsIntro, style: JomhorType.body.copyWith(color: JomhorColors.textSecondary)),
                  const SizedBox(height: JomhorSpace.xl),
                  if ((clubs.value?.length ?? 0) > ClubsScreen.searchFrom) ...[
                    TextField(
                      key: const Key('clubs.search'),
                      onChanged: (q) => setState(() => _query = q),
                      decoration: InputDecoration(
                        hintText: l.clubsSearch,
                        prefixIcon: const Icon(Icons.search_rounded),
                      ),
                    ),
                    const SizedBox(height: JomhorSpace.l),
                  ],
                ],
              ),
            ),
            ...switch (clubs) {
              AsyncData(:final value) => _grid(l, searchClubs(value, _query), ids),
              AsyncError(:final error) => [
                SliverToBoxAdapter(
                  child: ErrorPanel(error: error, onRetry: () => ref.invalidate(clubsProvider)),
                ),
              ],
              _ => [
                const SliverPadding(
                  padding: EdgeInsets.symmetric(horizontal: JomhorSpace.xl),
                  sliver: SliverToBoxAdapter(child: SkeletonList(rows: 4)),
                ),
              ],
            },
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, JomhorSpace.m, JomhorSpace.xl, JomhorSpace.s),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: fromProfile
                ? [FilledButton(key: const Key('clubs.done'), onPressed: () => context.pop(), child: Text(l.clubsDone))]
                : [
                    FilledButton(
                      key: const Key('clubs.continue'),
                      onPressed: following ? _leave : null,
                      child: Text(l.clubsContinue),
                    ),
                    const SizedBox(height: JomhorSpace.xs),
                    TextButton(
                      key: const Key('clubs.seeAll'),
                      onPressed: () => _leave(chip: chipAll),
                      child: Text(l.clubsSeeAll),
                    ),
                  ],
          ),
        ),
      ),
    );
  }

  List<Widget> _grid(AppLocalizations l, List<Club> clubs, List<String> ids) => [
    if (clubs.isEmpty)
      SliverToBoxAdapter(
        child: Padding(
          padding: const EdgeInsets.all(JomhorSpace.xl),
          child: Text(
            l.clubsNoResult,
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.textMuted),
          ),
        ),
      ),
    SliverPadding(
      padding: const EdgeInsets.fromLTRB(JomhorSpace.xl, 0, JomhorSpace.xl, JomhorSpace.xxl),
      sliver: SliverGrid.builder(
        gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
          crossAxisCount: 2,
          mainAxisSpacing: JomhorSpace.m,
          crossAxisSpacing: JomhorSpace.m,
          mainAxisExtent: 204,
        ),
        itemCount: clubs.length,
        itemBuilder: (context, i) => Entrance(
          index: i,
          child: _ClubCard(
            club: clubs[i],
            followed: ids.contains(clubs[i].id),
            onTap: () => ref.read(followsProvider.notifier).toggle(clubs[i].id),
          ),
        ),
      ),
    ),
  ];
}

class _ClubCard extends StatelessWidget {
  const new({required this.club, required this.followed, required this.onTap});

  final Club club;
  final bool followed;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return Semantics(
      toggled: followed,
      child: TapCard(
        inkKey: Key('club.${club.id}'),
        onTap: onTap,
        radius: JomhorRadius.card,
        padding: const EdgeInsets.all(JomhorSpace.l),
        child: Column(
          children: [
            Crest(name: club.name, url: club.crestUrl, size: 56),
            const SizedBox(height: JomhorSpace.m),
            Text(
              l.isolate(club.name),
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: JomhorType.h2.copyWith(fontSize: 18, height: 1.1),
            ),
            if (club.kind case final kind?) ...[
              const SizedBox(height: 2),
              Text(l.clubKind(kind), style: JomhorType.caption.copyWith(color: JomhorColors.textMuted)),
            ],
            const Spacer(),
            _FollowChip(followed: followed),
          ],
        ),
      ),
    );
  }
}

/// "Suivre" (outlined) or "Suivi" (accent, with a check); the change eases over `motion.base`.
class _FollowChip extends StatelessWidget {
  const new({required this.followed});

  final bool followed;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final ink = followed ? JomhorColors.onAccent : JomhorColors.text;
    return AnimatedContainer(
      duration: motionOf(context, JomhorMotion.base),
      height: 36,
      padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.l),
      decoration: BoxDecoration(
        color: followed ? JomhorColors.accent : JomhorColors.surface,
        border: Border.all(color: followed ? JomhorColors.accent : JomhorColors.borderMuted),
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (followed) ...[Icon(Icons.check_rounded, size: 16, color: ink), const SizedBox(width: JomhorSpace.xs)],
          Text(
            followed ? l.clubFollowing : l.clubFollow,
            style: JomhorType.bodySmall.copyWith(color: ink, fontWeight: FontWeight.w600),
          ),
        ],
      ),
    );
  }
}
