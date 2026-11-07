import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// The OS asked for less motion (DESIGN.md §Accessibility): every animation below then runs in zero time.
bool reduceMotion(BuildContext context) => MediaQuery.maybeDisableAnimationsOf(context) ?? false;

/// A `motion` token in milliseconds, or zero under reduced motion.
Duration motionOf(BuildContext context, int ms) => reduceMotion(context) ? Duration.zero : Duration(milliseconds: ms);

/// Fades and lifts its child into place once, on first paint; [index] staggers siblings.
class Entrance extends StatelessWidget {
  const new({required this.child, this.index = 0, super.key});

  final Widget child;
  final int index;

  /// DESIGN.md principle 6: at most 420 ms in all, stagger capped after 5 items.
  static const _stagger = 40;

  @override
  Widget build(BuildContext context) {
    if (reduceMotion(context)) return child;
    final delay = index.clamp(0, 5) * _stagger;
    final total = delay + JomhorMotion.base;
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: 1),
      duration: Duration(milliseconds: total),
      curve: Interval(delay / total, 1, curve: Curves.easeOutCubic),
      builder: (context, t, child) => Opacity(
        opacity: t,
        child: Transform.translate(offset: Offset(0, 12 * (1 - t)), child: child),
      ),
      child: child,
    );
  }
}

/// A tappable surface: ink ripple, the `tapScale` press and a hover lift (DESIGN.md motion tokens). The press
/// follows the ink highlight, so a scroll that starts on the card does not leave it pressed. A change of
/// [borderColor] / [color] (a selection) eases in over `motion.base`.
class TapCard extends StatefulWidget {
  const new({
    required this.child,
    this.onTap,
    this.color = JomhorColors.surface,
    this.borderColor = JomhorColors.border,
    this.borderWidth = 1,
    this.radius = JomhorRadius.control,
    this.padding = const EdgeInsets.all(14),
    this.gradient,
    this.inkKey,
    super.key,
  });

  final Widget child;
  final VoidCallback? onTap;
  final Color color;
  final Color borderColor;
  final double borderWidth;
  final double radius;
  final EdgeInsetsGeometry padding;

  /// Painted instead of [color].
  final Gradient? gradient;

  /// Key of the [InkWell], for tests that tap it.
  final Key? inkKey;

  @override
  State<TapCard> createState() => _TapCardState();
}

class _TapCardState extends State<TapCard> {
  var _pressed = false;
  var _hovered = false;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.all(Radius.circular(widget.radius));
    return AnimatedScale(
      scale: _pressed
          ? JomhorMotion.tapScale
          : _hovered
          ? 1.01
          : 1,
      duration: motionOf(context, JomhorMotion.fast),
      curve: Curves.easeOut,
      child: AnimatedContainer(
        duration: motionOf(context, JomhorMotion.base),
        curve: Curves.easeOut,
        decoration: BoxDecoration(
          color: widget.gradient == null ? widget.color : null,
          gradient: widget.gradient,
          border: Border.all(color: widget.borderColor, width: widget.borderWidth),
          borderRadius: radius,
        ),
        child: Material(
          type: MaterialType.transparency,
          borderRadius: radius,
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            key: widget.inkKey,
            onTap: widget.onTap,
            onHighlightChanged: (on) => setState(() => _pressed = on),
            onHover: (on) => setState(() => _hovered = on),
            child: Padding(padding: widget.padding, child: widget.child),
          ),
        ),
      ),
    );
  }
}
