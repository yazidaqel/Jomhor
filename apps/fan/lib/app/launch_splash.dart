import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// The hand-off from the native splash (pubspec.yaml `flutter_native_splash`). Its first frame is that splash
/// exactly: ink ground, the mark 160 wide, centred. Then the wave runs along the J to the amber dot, the splash fades
/// out over the screen already built under it and leaves the tree; a tap skips to the fade. Once per launch; under
/// reduced motion, never.
class LaunchSplash extends StatefulWidget {
  const new({required this.child, super.key});

  final Widget child;

  @override
  State<LaunchSplash> createState() => _LaunchSplashState();
}

class _LaunchSplashState extends State<LaunchSplash> with SingleTickerProviderStateMixin {
  /// The amber dot is at the top of its swell (0.65 of the 1100 ms wave of [JomhorMark.busy]).
  static const _hold = 715;
  static const int _total = _hold + JomhorMotion.base;

  late final _controller =
      AnimationController(
        vsync: this,
        duration: const Duration(milliseconds: _total),
      )..addStatusListener((status) {
        if (status.isCompleted) setState(() => _showing = false);
      });
  var _showing = true;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (reduceMotion(context)) {
      _controller.stop();
      _showing = false;
    } else if (_showing && _controller.isDismissed) {
      _controller.forward();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Stack(
    fit: StackFit.expand,
    children: [
      // Always the first of the stack: the app keeps its place in the tree when the splash leaves.
      widget.child,
      if (_showing)
        AnimatedBuilder(
          animation: _controller,
          // Taps reach the app as soon as the fade starts. The mark is already out of the semantics tree.
          builder: (context, splash) {
            final fade = const Interval(_hold / _total, 1).transform(_controller.value);
            return IgnorePointer(
              ignoring: fade > 0,
              child: Opacity(opacity: 1 - fade, child: splash),
            );
          },
          // During the hold, a tap skips to the fade.
          child: Listener(
            behavior: HitTestBehavior.opaque,
            onPointerDown: (_) {
              if (_controller.value < _hold / _total) _controller.forward(from: _hold / _total);
            },
            child: const ColoredBox(
              color: JomhorBrand.ink,
              child: Center(child: JomhorMark(size: 160, busy: true)),
            ),
          ),
        ),
    ],
  );
}
