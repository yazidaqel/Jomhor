import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// The Jomhor mark (design/logo/jomhor-mark-transparent.svg): eleven white dots draw the J, the twelfth, amber and
/// bigger, ends the hook. Always Jomhor's colours, never the club's; on an amber ground all twelve dots are ink
/// ([onAmber], design/BRAND.md). Drawn, not an asset, so it stays crisp at any size; its viewBox holds the clear space.
class JomhorMark extends StatelessWidget {
  const new({this.size = 64, this.onAmber = false, super.key});

  /// The tile; 24 at least (design/BRAND.md).
  final double size;
  final bool onAmber;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: _BrandPainter(onAmber: onAmber)),
    ),
  );
}

/// The horizontal lockup (design/logo/jomhor-lockup-dark.svg): mark + wordmark, built from the logo's paths, never
/// retyped. It is a picture, so it is not mirrored in AR; a row puts it at the start (the right side in AR).
class JomhorLockup extends StatelessWidget {
  /// [height] 30 at least: below 120 px wide, the mark goes alone (design/BRAND.md).
  const new({this.height = 32, super.key});

  final double height;

  @override
  Widget build(BuildContext context) => Semantics(
    label: JomhorBrand.name,
    image: true,
    child: SizedBox(
      width: height * JomhorBrand.lockupWidth / 120,
      height: height,
      child: const CustomPaint(painter: _BrandPainter(lockup: true)),
    ),
  );
}

/// The Jomhor lockup at the start of the top bar of each main tab, [trailing] at the end.
class BrandBar extends StatelessWidget {
  const new({this.trailing, super.key});

  final Widget? trailing;

  @override
  Widget build(BuildContext context) =>
      SizedBox(height: 48, child: Row(children: [const JomhorLockup(), const Spacer(), ?trailing]));
}

/// [JomhorBrand.wordmark] as a path; the logo uses absolute M, L, H, V, C and Z only.
final Path jomhorWordmark = () {
  final path = Path();
  final tokens = [for (final m in RegExp('[MLHVCZ]|[0-9.]+').allMatches(JomhorBrand.wordmark)) m[0]!];
  var (i, x, y, command) = (0, 0.0, 0.0, '');
  double next() => double.parse(tokens[i++]);
  while (i < tokens.length) {
    if (double.tryParse(tokens[i]) == null) command = tokens[i++];
    switch (command) {
      case 'M':
        path.moveTo(x = next(), y = next());
        command = 'L'; // Further pairs after M are lines (SVG).
      case 'L':
        path.lineTo(x = next(), y = next());
      case 'H':
        path.lineTo(x = next(), y);
      case 'V':
        path.lineTo(x, y = next());
      case 'C':
        path.cubicTo(next(), next(), next(), next(), x = next(), y = next());
      case 'Z':
        path.close();
      default:
        throw FormatException('Unsupported path command', command);
    }
  }
  return path;
}();

class _BrandPainter extends CustomPainter {
  const new({this.onAmber = false, this.lockup = false});

  final bool onAmber;
  final bool lockup;

  @override
  void paint(Canvas canvas, Size size) {
    canvas
      ..save()
      ..scale(size.height / 120);
    if (lockup) {
      canvas
        ..save()
        ..translate(0, JomhorBrand.lockupMarkDy)
        ..scale(JomhorBrand.lockupMarkScale);
    }
    final dot = Paint()..color = onAmber ? JomhorBrand.ink : JomhorBrand.white;
    final twelfth = Paint()..color = onAmber ? JomhorBrand.ink : JomhorBrand.amber;
    for (final (i, (x, y, r)) in JomhorBrand.dots.indexed) {
      canvas.drawCircle(Offset(x, y), r, i == JomhorBrand.dots.length - 1 ? twelfth : dot);
    }
    if (lockup) {
      canvas
        ..restore()
        ..translate(JomhorBrand.lockupWordmarkX, 0)
        ..drawPath(jomhorWordmark, Paint()..color = JomhorBrand.white);
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(_BrandPainter old) => old.onAmber != onAmber || old.lockup != lockup;
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
