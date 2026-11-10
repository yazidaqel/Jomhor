import 'package:flutter/material.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// The Jomhor mark (design/logo/jomhor-mark-transparent.svg): eleven white dots draw the J, the twelfth, amber and
/// bigger, ends the hook. Always Jomhor's colours, never the club's; on an amber ground all twelve dots are ink
/// ([onAmber], design/BRAND.md). Drawn, not an asset, so it stays crisp at any size; its viewBox holds the clear space.
class JomhorMark extends StatelessWidget {
  const new({this.size = 64, this.onAmber = false, this.entrance = false, this.busy = false, super.key});

  /// The tile; 24 at least (design/BRAND.md).
  final double size;
  final bool onAmber;

  /// The dots assemble along the J once, on first paint, the amber one last.
  final bool entrance;

  /// A progress indicator: a wave runs along the J to the amber dot for as long as this is true.
  final bool busy;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: SizedBox.square(
      dimension: size,
      child: _AnimatedBrand(onAmber: onAmber, entrance: entrance, busy: busy),
    ),
  );
}

/// The horizontal lockup (design/logo/jomhor-lockup-dark.svg): mark + wordmark, built from the logo's paths, never
/// retyped. It is a picture, so it is not mirrored in AR; a row puts it at the start (the right side in AR).
class JomhorLockup extends StatelessWidget {
  /// [height] 30 at least: below 120 px wide, the mark goes alone (design/BRAND.md).
  const new({this.height = 32, this.entrance = false, this.busy = false, super.key});

  final double height;

  /// As on [JomhorMark]; the wordmark stays still.
  final bool entrance;
  final bool busy;

  @override
  Widget build(BuildContext context) => Semantics(
    label: JomhorBrand.name,
    image: true,
    child: SizedBox(
      width: height * JomhorBrand.lockupWidth / 120,
      height: height,
      child: _AnimatedBrand(lockup: true, entrance: entrance, busy: busy),
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

/// The painted mark or lockup, with the controllers of its motion (DESIGN.md §6): none for a still one, none
/// under reduced motion.
class _AnimatedBrand extends StatefulWidget {
  const new({required this.entrance, required this.busy, this.onAmber = false, this.lockup = false});

  final bool entrance;
  final bool busy;
  final bool onAmber;
  final bool lockup;

  @override
  State<_AnimatedBrand> createState() => _AnimatedBrandState();
}

class _AnimatedBrandState extends State<_AnimatedBrand> with TickerProviderStateMixin {
  AnimationController? _entrance;
  AnimationController? _wave;

  /// The entrance is for the first paint: it plays then or never (not when reduced motion is turned off later).
  var _entranceDecided = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(_AnimatedBrand old) {
    super.didUpdateWidget(old);
    if (old.busy != widget.busy) _sync();
  }

  void _sync() {
    final still = reduceMotion(context);
    if (!_entranceDecided) {
      _entranceDecided = true;
      if (widget.entrance && !still) {
        _entrance = AnimationController(
          vsync: this,
          duration: const Duration(milliseconds: _BrandPainter.entranceMs),
        )..forward();
      }
    }
    if (still) {
      _entrance?.dispose();
      _wave?.dispose();
      _entrance = _wave = null;
      return;
    }
    if (widget.busy) {
      (_wave ??= AnimationController(vsync: this, duration: const Duration(milliseconds: 1100))).repeat();
    } else if (_wave?.isAnimating ?? false) {
      // The pass under way ends on the still mark: no snap back.
      _wave!.forward();
    }
  }

  @override
  void dispose() {
    _entrance?.dispose();
    _wave?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => CustomPaint(
    painter: _BrandPainter(onAmber: widget.onAmber, lockup: widget.lockup, entrance: _entrance, wave: _wave),
  );
}

class _BrandPainter extends CustomPainter {
  new({this.onAmber = false, this.lockup = false, this.entrance, this.wave})
    : super(repaint: Listenable.merge([entrance, wave]));

  /// DESIGN.md principle 6: an entrance lasts 420 ms at most.
  static const entranceMs = 420;

  final bool onAmber;
  final bool lockup;

  /// 0 to 1, once: each white dot grows and fades in over 170 ms, 25 ms after the one before it along the J; the
  /// amber one comes last and overshoots. Null, or at 1: the still mark.
  final Animation<double>? entrance;

  /// 0 to 1, looped while busy: the eleven dim (in by 0.1, back by 0.9) and a bump runs along them until 0.6; the
  /// amber dot swells as it lands (0.5 to 0.8) and never dims. Null, at 0 or at 1: the still mark, exactly.
  final Animation<double>? wave;

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
    final e = entrance?.value ?? 1;
    final w = wave?.value ?? 0;
    final dim = (w < 0.5 ? w / 0.1 : (0.9 - w) / 0.3).clamp(0.0, 1.0);
    final swell = 1 - (2 * ((w - 0.5) / 0.3).clamp(0.0, 1.0) - 1).abs();
    final paint = Paint();
    for (final (i, (x, y, r)) in JomhorBrand.dots.indexed) {
      final twelfth = i == JomhorBrand.dots.length - 1;
      final grow = twelfth
          ? const Interval(270 / entranceMs, 1, curve: Curves.easeOutBack).transform(e)
          : Interval(i * 25 / entranceMs, (i * 25 + 170) / entranceMs, curve: Curves.easeOut).transform(e);
      // The bump is two dots wide each side; it starts before the first dot and has left the eleventh at 0.6.
      final bump = (1 - (i - (w / 0.6 * 14 - 2)).abs() / 2).clamp(0.0, 1.0);
      // The amber dot stops at 1.18: it would touch the eleventh from 1.198.
      final scale = grow * (twelfth ? 1 + 0.18 * swell : 1 + 0.2 * bump);
      final alpha = grow.clamp(0.0, 1.0) * (twelfth ? 1 : 1 - 0.55 * dim * (1 - bump));
      paint.color = (onAmber ? JomhorBrand.ink : (twelfth ? JomhorBrand.amber : JomhorBrand.white)).withValues(
        alpha: alpha,
      );
      canvas.drawCircle(Offset(x, y), r * scale, paint);
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
  bool shouldRepaint(_BrandPainter old) =>
      old.onAmber != onAmber || old.lockup != lockup || old.entrance != entrance || old.wave != wave;
}

/// A club's crest (docs/04-api.md §Club directory): the `crest_url` image as supplied, never recoloured; with no
/// url, or when it fails to load (offline wallet included), the stand-in: the name's first letter on a disc, in
/// Jomhor tokens only ([disc] and [ink]: the accent disc by default, paper for the away side of a match).
class Crest extends StatelessWidget {
  const new({
    required this.name,
    this.url,
    this.size = 36,
    this.disc = JomhorColors.accent,
    this.ink = JomhorColors.onAccent,
    this.glow = false,
    super.key,
  });

  final String name;
  final String? url;
  final double size;
  final Color disc;
  final Color ink;

  /// The accent halo of the home side in the hero.
  final bool glow;

  @override
  Widget build(BuildContext context) {
    final standIn = Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: disc,
        boxShadow: glow ? [BoxShadow(color: JomhorColors.accent.withValues(alpha: 0.4), blurRadius: size * 0.7)] : null,
      ),
      child: Text(
        crestInitial(name),
        textScaler: TextScaler.noScaling, // Sized by the disc, not by the text scale.
        style: JomhorType.number.copyWith(fontSize: size * 0.48, color: ink, height: 1.2),
      ),
    );
    // Next to the club's name everywhere: decoration for screen readers.
    return ExcludeSemantics(
      child: switch (url) {
        final url? => Image.network(
          url,
          width: size,
          height: size,
          fit: BoxFit.contain,
          errorBuilder: (_, _, _) => standIn,
        ),
        null => standIn,
      },
    );
  }
}

final _firstLetter = RegExp(r'\p{L}', unicode: true);

/// The stand-in letter: the first letter of [name], uppercased ('Olympique Tensift' -> 'O'; an Arabic name keeps
/// its first Arabic letter).
String crestInitial(String name) => (_firstLetter.firstMatch(name)?[0] ?? '').toUpperCase();

/// The club line of event cards and tickets (DESIGN.md §Multi-club adjustments): crest 20 + the club name in
/// caption, in a bidi isolate of its own direction; [trailing] at the end ("Suivre" on Accueil).
class ClubLine extends StatelessWidget {
  const new({required this.name, this.crestUrl, this.trailing, this.color = JomhorColors.textSecondary, super.key});

  final String name;
  final String? crestUrl;
  final Widget? trailing;
  final Color color;

  @override
  Widget build(BuildContext context) => Row(
    children: [
      Crest(name: name, url: crestUrl, size: 20),
      const SizedBox(width: JomhorSpace.s),
      Flexible(
        child: Text(
          AppLocalizations.of(context).isolate(name),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: JomhorType.caption.copyWith(color: color, fontSize: 12, fontWeight: FontWeight.w600),
        ),
      ),
      ?trailing,
    ],
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
