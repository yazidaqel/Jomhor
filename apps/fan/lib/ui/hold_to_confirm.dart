import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// The primary CTA of an irreversible action (DESIGN.md screen 8): the accent fills it from the reading start while
/// the finger stays down, and only a full [hold] confirms, with a heavy haptic. Letting go earlier drains it.
/// The fill is a progress indicator (principle 6), so it runs under reduced motion too; only the drain is instant
/// then. Screen readers confirm with their long-press action; a hardware keyboard holds Space or Enter on the focused
/// control, the same 800 ms.
class HoldToConfirm extends StatefulWidget {
  const new({
    required this.label,
    required this.hint,
    required this.onConfirmed,
    this.busyLabel,
    this.icon = Icons.swap_horiz_rounded,
    super.key,
  });

  static const hold = Duration(milliseconds: 800);

  final String label;

  /// For screen readers: how to confirm.
  final String hint;

  /// Shown full, with a spinner, while non-null (the request is out); the control is then inert.
  final String? busyLabel;
  final IconData icon;
  final VoidCallback onConfirmed;

  @override
  State<HoldToConfirm> createState() => _HoldToConfirmState();
}

class _HoldToConfirmState extends State<HoldToConfirm> with SingleTickerProviderStateMixin {
  late final _fill = AnimationController(vsync: this, duration: HoldToConfirm.hold)..addStatusListener(_done);

  bool get _busy => widget.busyLabel != null;
  var _focused = false;

  /// Space or Enter held down is a finger held down: the same fill, the same full hold.
  KeyEventResult _key(FocusNode _, KeyEvent e) {
    final key = e.logicalKey;
    if (key != LogicalKeyboardKey.space && key != LogicalKeyboardKey.enter && key != LogicalKeyboardKey.numpadEnter) {
      return KeyEventResult.ignored;
    }
    switch (e) {
      case KeyDownEvent():
        _press();
      case KeyUpEvent():
        _release();
      case KeyRepeatEvent():
    }
    return KeyEventResult.handled;
  }

  void _done(AnimationStatus status) {
    if (status != AnimationStatus.completed || _busy) return;
    unawaited(HapticFeedback.heavyImpact());
    widget.onConfirmed();
  }

  void _press() {
    if (!_busy) _fill.forward();
  }

  void _release() {
    if (_busy || _fill.isCompleted) return;
    if (reduceMotion(context)) {
      _fill.value = 0;
    } else {
      _fill.animateBack(0, duration: const Duration(milliseconds: JomhorMotion.base));
    }
  }

  @override
  void didUpdateWidget(HoldToConfirm old) {
    super.didUpdateWidget(old);
    // Back from busy without a confirmation (a refusal): ready for a new hold.
    if (old.busyLabel != null && !_busy) _fill.value = 0;
  }

  @override
  void dispose() {
    _fill.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    const radius = BorderRadius.all(Radius.circular(JomhorRadius.control));
    Widget face(Color color) => Padding(
      padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xl),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          if (_busy)
            SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2, color: color))
          else
            Icon(widget.icon, size: 20, color: color),
          const SizedBox(width: JomhorSpace.s),
          Flexible(
            child: Text(
              widget.busyLabel ?? widget.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: JomhorType.cta.copyWith(color: color),
            ),
          ),
        ],
      ),
    );

    return Semantics(
      button: true,
      enabled: !_busy,
      label: widget.busyLabel ?? widget.label,
      hint: widget.hint,
      focusable: true,
      focused: _focused,
      onLongPress: _busy ? null : widget.onConfirmed,
      child: ExcludeSemantics(
        child: Focus(
          includeSemantics: false,
          onKeyEvent: _key,
          onFocusChange: (on) {
            setState(() => _focused = on);
            // Focus moved away with Space still down: its key-up never comes here, so the hold ends now.
            if (!on) _release();
          },
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: (_) => _press(),
            onTapUp: (_) => _release(),
            onTapCancel: _release,
            child: SizedBox(
              height: JomhorSize.ctaHeight,
              child: DecoratedBox(
                decoration: BoxDecoration(
                  color: JomhorColors.accent.withValues(alpha: 0.12),
                  border: Border.all(
                    color: JomhorColors.accent.withValues(alpha: _focused ? 1 : 0.55),
                    width: _focused ? 2 : 1,
                  ),
                  borderRadius: radius,
                ),
                child: ClipRRect(
                  borderRadius: radius,
                  child: LayoutBuilder(
                    builder: (context, box) => AnimatedBuilder(
                      animation: _fill,
                      builder: (context, _) => Stack(
                        fit: StackFit.expand,
                        children: [
                          face(JomhorColors.accent),
                          // The filled part: the same face in onAccent on the accent, clipped to the progress, so the
                          // label changes colour exactly where the fill passes under it.
                          Align(
                            alignment: AlignmentDirectional.centerStart,
                            child: ClipRect(
                              child: Align(
                                key: const Key('hold.fill'),
                                alignment: AlignmentDirectional.centerStart,
                                widthFactor: _busy ? 1 : _fill.value,
                                child: SizedBox(
                                  width: box.maxWidth,
                                  height: box.maxHeight,
                                  child: ColoredBox(color: JomhorColors.accent, child: face(JomhorColors.onAccent)),
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
