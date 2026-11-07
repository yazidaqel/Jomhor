import 'package:flutter/material.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';

const List<Color> _zonePalette = [JomhorColors.zoneA, JomhorColors.zoneB, JomhorColors.zoneC];

/// A zone card (screens 2 and 3): colour bar, name, seating and gates, price, places left.
/// On screen 3 it is tappable ([onTap]) and shows [selected] with the accent border.
class ZoneRow extends StatelessWidget {
  const new({required this.zone, required this.index, this.selected = false, this.onTap, super.key});

  final Zone zone;
  final int index;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final z = zone;
    final price = z.fromPriceMad;
    final open = z.prices.where((p) => p.remaining > 0).length;
    final description = [
      if (z.seated) l.zoneSeated else l.zoneStanding,
      if (z.gateCodes.isNotEmpty) l.zoneGates(z.gateCodes.length, z.gateCodes.join(', ')),
    ].reduce(l.dotPair);

    return Opacity(
      opacity: z.soldOut ? 0.55 : 1,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: TapCard(
          key: Key('zone.${z.id}'),
          onTap: z.soldOut ? null : onTap,
          borderColor: selected ? JomhorColors.accent : JomhorColors.border,
          borderWidth: selected ? 2 : 1,
          color: selected ? JomhorColors.surface2 : JomhorColors.surface,
          child: Row(
            children: [
              Container(
                width: 10,
                height: 40,
                decoration: BoxDecoration(
                  color: z.soldOut ? JomhorColors.zoneDisabled : _zonePalette[index % _zonePalette.length],
                  borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.pill)),
                ),
              ),
              const SizedBox(width: JomhorSpace.m),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(z.name, style: JomhorType.body.copyWith(fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(description, style: JomhorType.bodySmall.copyWith(color: JomhorColors.textMuted)),
                  ],
                ),
              ),
              const SizedBox(width: JomhorSpace.s),
              // Bounded, not flexible: the name keeps the room; the price wraps only with very large text.
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 130),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (price != null)
                      Text(
                        open > 1 ? l.chipFrom(l.dh(price)) : l.dh(price),
                        style: JomhorType.h2,
                        textAlign: TextAlign.end,
                      ),
                    Text(
                      z.soldOut
                          ? l.soldOut
                          : z.lastSeats
                          ? l.zoneLastSeats
                          : l.zoneRemaining(z.remaining),
                      style: JomhorType.caption.copyWith(
                        fontSize: 12,
                        color: z.lastSeats
                            ? Theme.of(context).colorScheme.error
                            : z.soldOut
                            ? JomhorColors.textMuted
                            : JomhorColors.successText,
                      ),
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
