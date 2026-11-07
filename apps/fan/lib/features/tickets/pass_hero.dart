import 'dart:ui' show lerpDouble;

import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// Where screen 5 was opened from, so its pass flies out of the card the fan tapped (DESIGN.md principle 6).
/// Its `name` is the route `extra` (a plain String); a deep link or the matchday auto-open has none, and opens without the flight.
enum PassOrigin { home, list }

/// Hero tag of the pass of [ticketId] opened from [origin]. One per origin: the Accueil strip and the Billets row
/// live side by side in the tab stack, and two heroes of one route must not share a tag.
String passHeroTag(PassOrigin origin, String ticketId) => 'pass:${origin.name}:$ticketId';

/// The pass in miniature (paper, accent band, QR glyph): the peek on the Accueil strip and the Billets rows.
class MiniPass extends StatelessWidget {
  const new({this.width = 44, this.dimmed = false, super.key});

  final double width;

  /// No QR to show (past, cancelled): band in muted ink.
  final bool dimmed;

  @override
  Widget build(BuildContext context) => SizedBox(
    width: width,
    height: width * 1.32,
    child: _PassShape(band: dimmed ? JomhorColors.ticketMuted : JomhorColors.accent, radius: width * 0.16, glyph: 1),
  );
}

/// What flies between the mini pass and the full card: the same paper shape at the size of the moment, so no
/// content is laid out at an in-between size.
Widget passFlight(
  BuildContext flightContext,
  Animation<double> animation,
  HeroFlightDirection direction,
  BuildContext fromContext,
  BuildContext toContext,
) => AnimatedBuilder(
  animation: animation,
  builder: (context, _) {
    final t = Curves.easeOutCubic.transform(animation.value);
    return _PassShape(
      band: JomhorColors.accent,
      radius: lerpDouble(7, JomhorRadius.cardLarge, t)!,
      glyph: 1 - t,
      shadow: t,
    );
  },
);

class _PassShape extends StatelessWidget {
  const new({required this.band, required this.radius, required this.glyph, this.shadow = 0.6});

  final Color band;
  final double radius;

  /// Opacity of the QR glyph (it fades as the real QR takes over).
  final double glyph;
  final double shadow;

  @override
  Widget build(BuildContext context) => DecoratedBox(
    decoration: BoxDecoration(
      color: JomhorColors.ticketPaper,
      borderRadius: BorderRadius.all(Radius.circular(radius)),
      boxShadow: [
        BoxShadow(
          color: JomhorColors.ground.withValues(alpha: 0.5 * shadow),
          blurRadius: 18,
          offset: const Offset(0, 6),
        ),
      ],
    ),
    child: ClipRRect(
      borderRadius: BorderRadius.all(Radius.circular(radius)),
      child: LayoutBuilder(
        builder: (context, box) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            SizedBox(
              height: box.maxHeight * 0.26,
              child: ColoredBox(color: band),
            ),
            Expanded(
              child: Opacity(
                opacity: glyph.clamp(0, 1),
                child: FittedBox(
                  child: Padding(
                    padding: const EdgeInsets.all(3),
                    child: Icon(Icons.qr_code_2_rounded, color: JomhorColors.ticketInk, size: box.maxWidth),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    ),
  );
}
