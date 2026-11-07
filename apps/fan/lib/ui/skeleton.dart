import 'package:flutter/material.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// A placeholder block with a soft shimmer, shaped like the content it stands for. Static under reduced motion.
class Skeleton extends StatefulWidget {
  const new({this.height = 14, this.width, this.radius = JomhorRadius.chip, super.key});

  final double height;
  final double? width;
  final double radius;

  @override
  State<Skeleton> createState() => _SkeletonState();
}

class _SkeletonState extends State<Skeleton> with SingleTickerProviderStateMixin {
  late final _shimmer = AnimationController(vsync: this, duration: const Duration(milliseconds: 1400));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (reduceMotion(context)) {
      _shimmer.stop();
    } else if (!_shimmer.isAnimating) {
      _shimmer.repeat();
    }
  }

  @override
  void dispose() {
    _shimmer.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => SizedBox(
    height: widget.height,
    width: widget.width,
    child: AnimatedBuilder(
      animation: _shimmer,
      builder: (context, _) {
        final x = _shimmer.value * 3 - 1.5;
        return DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.all(Radius.circular(widget.radius)),
            gradient: LinearGradient(
              begin: Alignment(x - 1, -0.3),
              end: Alignment(x + 1, 0.3),
              colors: const [JomhorColors.surface, JomhorColors.surface2, JomhorColors.surface],
            ),
          ),
        );
      },
    ),
  );
}

/// The loading state of a list: [rows] row cards (date badge, two lines, chip), under an optional [hero] block.
/// Read by screen readers as one "Chargement" label.
class SkeletonList extends StatelessWidget {
  const new({this.rows = 3, this.hero = false, super.key});

  final int rows;
  final bool hero;

  @override
  Widget build(BuildContext context) => Semantics(
    label: AppLocalizations.of(context).loading,
    container: true,
    child: ExcludeSemantics(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (hero) ...[
            const Skeleton(height: 300, radius: JomhorRadius.card),
            const SizedBox(height: JomhorSpace.xxl),
            const Skeleton(height: 22, width: 140),
            const SizedBox(height: JomhorSpace.m),
          ],
          for (var i = 0; i < rows; i++)
            Padding(
              padding: const EdgeInsets.only(bottom: 10),
              child: DecoratedBox(
                decoration: const BoxDecoration(
                  border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
                  borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
                ),
                child: Padding(
                  padding: const EdgeInsets.all(14),
                  child: Row(
                    children: [
                      const Skeleton(height: 44, width: 44),
                      const SizedBox(width: JomhorSpace.m),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            FractionallySizedBox(widthFactor: i.isEven ? 0.7 : 0.55, child: const Skeleton()),
                            const SizedBox(height: JomhorSpace.s),
                            const FractionallySizedBox(widthFactor: 0.4, child: Skeleton(height: 10)),
                          ],
                        ),
                      ),
                      const SizedBox(width: JomhorSpace.m),
                      const Skeleton(height: 28, width: 64),
                    ],
                  ),
                ),
              ),
            ),
        ],
      ),
    ),
  );
}
