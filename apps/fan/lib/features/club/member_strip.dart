import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/features/club/club_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Screen 1 member strip (DESIGN.md Lot 4, screen 1): "N° 000042 · Fidèle · 7 matchs" for the first followed club
/// where the fan is a member → that club's card on screen 7. Nothing signed out, or when not a member anywhere.
class MemberStrip extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    if (ref.watch(memberStripProvider).value case (final club, final m)) {
      final l = AppLocalizations.of(context);
      final count = m.loyalty.countsMatches(club)
          ? l.memberStripMatches(m.loyalty.eventsAttended)
          : l.memberStripEvents(m.loyalty.eventsAttended);
      final muted = JomhorType.bodySmall.copyWith(color: JomhorColors.textSecondary);
      return Padding(
        padding: const EdgeInsets.only(bottom: JomhorSpace.m),
        child: TapCard(
          inkKey: const Key('home.member'),
          // A fresh extra each tap: the same link brings the club back into view after a swipe.
          onTap: () => context.go('/club?organizer=${club.id}', extra: DateTime.now().microsecondsSinceEpoch),
          padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m, horizontal: JomhorSpace.l),
          child: Row(
            children: [
              Crest(name: club.name, url: club.crestUrl, size: 20),
              const SizedBox(width: JomhorSpace.m),
              Expanded(
                child: Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: l.memberStripNo(l.ltr(m.member!.padded)),
                        style: JomhorType.number.copyWith(fontSize: 17, color: JomhorColors.text),
                      ),
                      TextSpan(text: l.dotPair('', ''), style: muted),
                      TextSpan(
                        text: m.loyalty.tier.name,
                        style: muted.copyWith(color: JomhorColors.accent, fontWeight: FontWeight.w600),
                      ),
                      TextSpan(text: l.dotPair('', count), style: muted),
                    ],
                  ),
                  key: const Key('home.memberText'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              const Icon(Icons.chevron_right_rounded, size: 20, color: JomhorColors.textMuted),
            ],
          ),
        ),
      );
    }
    return const SizedBox.shrink();
  }
}
