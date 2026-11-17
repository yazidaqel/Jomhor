import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';
import 'package:jomhor_fan/features/checkout/layouts.g.dart';
import 'package:jomhor_fan/features/checkout/zone_pick.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/zone_row.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Screen 3's venue map, "the stage" (DESIGN.md §Venue map adjustments): a 4:3 canvas, as wide as the content and
/// never narrower than 320 px (narrower content: edge to edge; a screen under 320 px scrolls it sideways), so a
/// 55-unit slot is at least 44 px. Slots sit at
/// `left`/`top` from the left in every locale: the venue is never mirrored, only its text follows the locale.
class StageMap extends StatelessWidget {
  const new({required this.view, required this.onTap, super.key});

  final ({VenueLayout layout, List<MapBlock> blocks}) view;

  /// The same call as tapping the zone's row.
  final ValueChanged<Zone> onTap;

  static const minWidth = 320.0;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    return LayoutBuilder(
      builder: (context, c) {
        final visible = c.maxWidth >= minWidth ? c.maxWidth : MediaQuery.sizeOf(context).width;
        final width = math.max(visible, minWidth);
        final height = width * VenueLayouts.canvasH / VenueLayouts.canvasW;
        final scale = width / VenueLayouts.canvasW;
        Positioned at(MapRect r, Widget child) =>
            Positioned(left: r.$1 * scale, top: r.$2 * scale, width: r.$3 * scale, height: r.$4 * scale, child: child);
        Widget canvas = SizedBox(
          width: width,
          height: height,
          child: Semantics(
            key: const Key('map'),
            container: true,
            explicitChildNodes: true,
            label: l.venueMap,
            // Focus follows the JSON's slot order, not the reading direction.
            child: FocusTraversalGroup(
              policy: WidgetOrderTraversalPolicy(),
              child: Stack(
                children: [
                  at(view.layout.centerRect, _Centre(kind: view.layout.center)),
                  for (final (i, b) in view.blocks.indexed)
                    at(
                      b.rect,
                      b.zone == null
                          ? const _Inert()
                          : _Block(key: Key('map.${b.slot}'), block: b, order: i, onTap: onTap),
                    ),
                ],
              ),
            ),
          ),
        );
        if (width > visible) canvas = SingleChildScrollView(scrollDirection: Axis.horizontal, child: canvas);
        return SizedBox(
          height: height,
          child: OverflowBox(minWidth: visible, maxWidth: visible, child: canvas),
        );
      },
    );
  }
}

class _Centre extends StatelessWidget {
  const new({required this.kind});

  final MapCenter kind;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    const line = BorderSide(color: JomhorColors.border);
    return ExcludeSemantics(
      child: DecoratedBox(
        decoration: const BoxDecoration(
          color: JomhorColors.surface2,
          borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip)),
        ),
        child: LayoutBuilder(
          builder: (context, c) => Stack(
            alignment: Alignment.center,
            children: [
              if (kind == MapCenter.field) ...[
                const VerticalDivider(width: 1, thickness: 1, color: JomhorColors.border),
                SizedBox.square(
                  dimension: c.maxHeight * 0.36,
                  child: const DecoratedBox(
                    decoration: BoxDecoration(shape: BoxShape.circle, border: Border.fromBorderSide(line)),
                  ),
                ),
              ],
              ColoredBox(
                color: JomhorColors.surface2,
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xs),
                  child: Text(
                    (kind == MapCenter.field ? l.mapField : l.mapStage).toUpperCase(),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: JomhorType.label.copyWith(color: JomhorColors.textMuted).tracked(context),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// A slot with no listed zone: keeps the outline whole; no text, not focusable, hidden from semantics.
class _Inert extends StatelessWidget {
  const new();

  @override
  Widget build(BuildContext context) => const ExcludeSemantics(
    child: DecoratedBox(
      decoration: BoxDecoration(
        color: JomhorColors.surface,
        border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
        borderRadius: BorderRadius.all(Radius.circular(10)),
      ),
    ),
  );
}

class _Block extends StatelessWidget {
  const new({required this.block, required this.order, required this.onTap, super.key});

  final MapBlock block;
  final int order;
  final ValueChanged<Zone> onTap;

  static const _radius = BorderRadius.all(Radius.circular(10));

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context);
    final z = block.zone!;
    final state = block.state;
    final colour = zonePalette[block.index % zonePalette.length];
    final (fill, border, borderWidth) = switch (state) {
      BlockState.selected => (JomhorColors.accent, JomhorColors.accent, 2.0),
      BlockState.soldOut => (JomhorColors.zoneDisabled.withValues(alpha: 0.3), JomhorColors.zoneDisabled, 1.5),
      _ => (colour.withValues(alpha: 0.16), colour, 1.5),
    };
    final (main, muted, members) = switch (state) {
      BlockState.selected => (JomhorColors.onAccent, JomhorColors.onAccent, JomhorColors.onAccent),
      BlockState.soldOut => (JomhorColors.textMuted, JomhorColors.textMuted, JomhorColors.textMuted),
      _ => (JomhorColors.text, JomhorColors.textMuted, JomhorColors.accent),
    };
    final soldOut = state == BlockState.soldOut;
    // Not sold out = an open price: the one a tap selects (Zone.firstOpen), members-only included, as the shop shows.
    final second = soldOut ? l.soldOut : l.chipFrom(l.dh(z.firstOpen!.priceMad));
    final lines = [
      (z.name, JomhorType.bodySmall.copyWith(fontWeight: FontWeight.w600, color: main, height: 1.25)),
      (second, JomhorType.caption.copyWith(color: muted, height: 1.3)),
      if (z.hasMembersPrice) (l.membersChip, JomhorType.caption.copyWith(color: members, height: 1.3)),
    ];
    // "{zone}, dès {price}" / "{zone}, complet", then ", membres".
    var label = l.mapBlock(z.name, soldOut ? second.toLowerCase() : second);
    if (z.hasMembersPrice) label = l.mapBlock(label, l.membersChip.toLowerCase());
    final tap = state == BlockState.open || state == BlockState.selected ? () => onTap(z) : null;

    return Semantics(
      sortKey: OrdinalSortKey(order.toDouble()),
      container: true,
      button: true,
      enabled: tap != null,
      selected: state == BlockState.selected,
      label: label,
      onTap: tap,
      excludeSemantics: true,
      child: Opacity(
        opacity: state == BlockState.closed ? 0.55 : 1,
        child: AnimatedScale(
          scale: state == BlockState.selected && !reduceMotion(context) ? 1.03 : 1,
          duration: motionOf(context, JomhorMotion.fast),
          child: Material(
            color: fill,
            shape: RoundedRectangleBorder(
              borderRadius: _radius,
              side: BorderSide(color: border, width: borderWidth),
            ),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: tap,
              child: Padding(
                padding: const EdgeInsets.all(JomhorSpace.xs),
                child: _Lines(lines: lines),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// One line each, ellipsized; the lines that don't fit the block's height drop from the bottom.
class _Lines extends StatelessWidget {
  const new({required this.lines});

  final List<(String, TextStyle)> lines;

  @override
  Widget build(BuildContext context) {
    final scaler = MediaQuery.textScalerOf(context);
    return LayoutBuilder(
      builder: (context, c) {
        var room = c.maxHeight;
        final shown = <Widget>[];
        for (final (text, style) in lines) {
          final h = scaler.scale(style.fontSize!) * style.height!;
          if (h > room) break;
          room -= h;
          shown.add(
            Text(text, maxLines: 1, overflow: TextOverflow.ellipsis, textAlign: TextAlign.center, style: style),
          );
        }
        // Rounding never overflows: the column is measured above, clipped here.
        return ClipRect(
          child: OverflowBox(
            maxHeight: double.infinity,
            child: Column(mainAxisSize: MainAxisSize.min, children: shown),
          ),
        );
      },
    );
  }
}
