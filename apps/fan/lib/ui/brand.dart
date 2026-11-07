import 'package:flutter/material.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// The Jomhor mark (design/logo/jomhor-mark-transparent.svg): eleven dots draw the J, the twelfth, bigger, ends the
/// hook. Drawn, not an asset, so it stays crisp at any size; its viewBox already holds the clear space.
class JomhorMark extends StatelessWidget {
  const new({this.size = 64, this.color = JomhorColors.text, this.twelfth = JomhorColors.accent, super.key});

  final double size;
  final Color color;

  /// The supporter dot. The club accent stands in for amber, as everywhere else in the club's app.
  final Color twelfth;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: _MarkPainter(color, twelfth)),
    ),
  );
}

/// Centres in the 120 x 120 viewBox of the SVG, in drawing order; the last one is the twelfth.
const List<(double, double)> _dots = [
  (37, 28), (52, 28), (67, 28), (82, 28), (82, 43), (82, 58), //
  (82, 73), (77.8, 85.9), (66.8, 93.9), (53.2, 93.9), (42.2, 85.9), (38, 73),
];

class _MarkPainter extends CustomPainter {
  const new(this.color, this.twelfth);

  final Color color;
  final Color twelfth;

  @override
  void paint(Canvas canvas, Size size) {
    final k = size.width / 120;
    final ink = Paint()..color = color;
    for (final (i, (x, y)) in _dots.indexed) {
      final last = i == _dots.length - 1;
      canvas.drawCircle(Offset(x * k, y * k), (last ? 6.9 : 5.3) * k, last ? (Paint()..color = twelfth) : ink);
    }
  }

  @override
  bool shouldRepaint(_MarkPainter old) => old.color != color || old.twelfth != twelfth;
}

/// Club crest stand-in (tokens.json `club.crest` is null): a disc with the initial. Home = the club accent,
/// away = paper. [glow] adds the accent halo of the home side in the hero.
class Crest extends StatelessWidget {
  const new({required this.initial, this.size = 36, this.home = true, this.glow = false, super.key});

  final String initial;
  final double size;
  final bool home;
  final bool glow;

  @override
  Widget build(BuildContext context) => Container(
    width: size,
    height: size,
    alignment: Alignment.center,
    decoration: BoxDecoration(
      shape: BoxShape.circle,
      color: home ? JomhorColors.accent : JomhorColors.ticketPaper,
      boxShadow: glow ? [BoxShadow(color: JomhorColors.accent.withValues(alpha: 0.4), blurRadius: size * 0.7)] : null,
    ),
    child: Text(
      initial.toUpperCase(),
      textScaler: TextScaler.noScaling, // Sized by the disc, not by the text scale.
      style: JomhorType.number.copyWith(
        fontSize: size * 0.48,
        color: home ? JomhorColors.onAccent : JomhorColors.ticketInk,
      ),
    ),
  );
}

/// A soft accent light, for the corner of a hero surface (the 12 % disc of the mockups, blurred).
class AccentGlow extends StatelessWidget {
  const new({required this.size, this.opacity = 0.22, super.key});

  final double size;
  final double opacity;

  @override
  Widget build(BuildContext context) => IgnorePointer(
    child: Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        gradient: RadialGradient(
          colors: [
            JomhorColors.accent.withValues(alpha: opacity),
            JomhorColors.accent.withValues(alpha: 0),
          ],
        ),
      ),
    ),
  );
}

/// "Propulsé par Jomhor" with the mark, at the foot of Profil and the login.
class PoweredBy extends StatelessWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context) => Row(
    mainAxisAlignment: MainAxisAlignment.center,
    children: [
      const JomhorMark(size: 28),
      const SizedBox(width: JomhorSpace.xs),
      Flexible(
        child: Text(
          AppLocalizations.of(context).poweredBy,
          style: JomhorType.caption.copyWith(color: JomhorColors.textMuted, fontSize: 12),
        ),
      ),
    ],
  );
}
