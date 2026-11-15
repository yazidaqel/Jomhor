import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import 'package:jomhor_fan/features/passes/pass.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Screen 7 "Abonnements" (DESIGN.md Lot 5 step 3): one row card per `PASS` product on sale (name, zone,
/// "{n} matchs annoncés", price) → screen 13 buy. Nothing when the club sells none.
class PassProductCards extends StatelessWidget {
  const new({required this.products, super.key});

  /// The club's products on sale; only passes show here.
  final List<Product> products;

  @override
  Widget build(BuildContext context) {
    final passes = [...products.where((p) => p.isPass && p.pass != null)];
    if (passes.isEmpty) return const SizedBox.shrink();
    final l = AppLocalizations.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        SectionLabel(l.clubPasses),
        for (final p in passes) _PassProductCard(product: p),
        const SizedBox(height: JomhorSpace.l),
      ],
    );
  }
}

class _PassProductCard extends StatelessWidget {
  const new({required this.product});

  final Product product;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final p = product;
    final pass = p.pass!;
    final count = pass.matches
        ? l.passEventsAnnounced(pass.events.length)
        : l.passEventsAnnouncedAny(pass.events.length);
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: TapCard(
        inkKey: Key('club.pass.${p.id}'),
        onTap: () => context.push('/products/${p.id}'),
        padding: const EdgeInsetsDirectional.fromSTEB(14, 14, JomhorSpace.l, 14),
        child: Row(
          children: [
            Container(
              width: 44,
              height: 44,
              decoration: BoxDecoration(
                color: JomhorColors.accent.withValues(alpha: 0.12),
                borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.control)),
              ),
              child: const Icon(Icons.event_seat_outlined, size: 22, color: JomhorColors.accent),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(p.name, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 2),
                  Text(
                    l.dotPair(l.isolate(pass.zone.name), count),
                    style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted),
                  ),
                ],
              ),
            ),
            const SizedBox(width: JomhorSpace.m),
            Text(
              p.soldOut ? l.soldOut : l.dh(p.chargeMad),
              key: Key('club.pass.price.${p.id}'),
              style: JomhorType.body.copyWith(
                fontWeight: FontWeight.w700,
                color: p.soldOut ? JomhorColors.textMuted : JomhorColors.accent,
              ),
            ),
            const SizedBox(width: JomhorSpace.xs),
            const Icon(Icons.chevron_right_rounded, size: 20, color: JomhorColors.textMuted),
          ],
        ),
      ),
    );
  }
}
