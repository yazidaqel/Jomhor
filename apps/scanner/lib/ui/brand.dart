import 'package:flutter/material.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// The top bar of the device's home screens (enrolment, event, unlock): the Jomhor lockup at the start (the right
/// side in AR), the screen's [title] after it. The scan and result screens keep their full-colour fields.
AppBar brandAppBar(String title) => AppBar(
  titleSpacing: JomhorSpace.l,
  title: Row(
    children: [
      const JomhorLockup(),
      const SizedBox(width: JomhorSpace.l),
      Flexible(child: Text(title, overflow: TextOverflow.ellipsis)),
    ],
  ),
);

/// The horizontal lockup (design/logo/jomhor-lockup-dark.svg), as in apps/fan/lib/ui/brand.dart: mark + wordmark
/// drawn from the logo's paths ([JomhorBrand]), never retyped, never mirrored.
class JomhorLockup extends StatelessWidget {
  /// [height] 30 at least: below 120 px wide, the mark goes alone (design/BRAND.md).
  const new({this.height = 30, super.key});

  final double height;

  @override
  Widget build(BuildContext context) => Semantics(
    label: JomhorBrand.name,
    image: true,
    child: SizedBox(
      width: height * JomhorBrand.lockupWidth / 120,
      height: height,
      child: const CustomPaint(painter: _LockupPainter()),
    ),
  );
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

class _LockupPainter extends CustomPainter {
  const new();

  @override
  void paint(Canvas canvas, Size size) {
    canvas
      ..save()
      ..scale(size.height / 120)
      ..save()
      ..translate(0, JomhorBrand.lockupMarkDy)
      ..scale(JomhorBrand.lockupMarkScale);
    final dot = Paint()..color = JomhorBrand.white;
    for (final (i, (x, y, r)) in JomhorBrand.dots.indexed) {
      canvas.drawCircle(Offset(x, y), r, i == JomhorBrand.dots.length - 1 ? (Paint()..color = JomhorBrand.amber) : dot);
    }
    canvas
      ..restore()
      ..translate(JomhorBrand.lockupWordmarkX, 0)
      ..drawPath(jomhorWordmark, Paint()..color = JomhorBrand.white)
      ..restore();
  }

  @override
  bool shouldRepaint(_LockupPainter old) => false;
}
