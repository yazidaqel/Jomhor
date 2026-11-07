import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/brand.dart';
import 'package:jomhor_fan/ui/motion.dart';

/// Empty, error and offline states: the 12-dot mark, a title, the copy and one action.
class StatePanel extends StatelessWidget {
  const new({required this.message, this.title, this.action, super.key});

  final String message;
  final String? title;
  final Widget? action;

  @override
  Widget build(BuildContext context) => Entrance(
    child: Padding(
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.xxl, horizontal: JomhorSpace.l),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const JomhorMark(size: 72),
          const SizedBox(height: JomhorSpace.m),
          if (title case final title?) ...[
            Text(title, style: JomhorType.h2, textAlign: TextAlign.center),
            const SizedBox(height: JomhorSpace.s),
          ],
          Text(
            message,
            textAlign: TextAlign.center,
            style: JomhorType.body.copyWith(color: JomhorColors.textSecondary, fontWeight: FontWeight.w400),
          ),
          if (action case final action?) ...[const SizedBox(height: JomhorSpace.xl), action],
        ],
      ),
    ),
  );
}

/// Uppercase section label (`type.label`), muted.
class SectionLabel extends StatelessWidget {
  const new(this.text, {this.color = JomhorColors.textMuted, super.key});

  final String text;
  final Color color;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(start: JomhorSpace.xs, bottom: JomhorSpace.s),
    child: Text(text.toUpperCase(), style: JomhorType.label.copyWith(color: color)),
  );
}

/// Day over month, for calendar and ticket rows.
class DateBadge extends StatelessWidget {
  const new({required this.day, required this.month, this.highlight = false, this.past = false, super.key});

  final String day;
  final String month;

  /// Accent day: the fan holds a ticket for it.
  final bool highlight;

  /// Past event: the badge melts into the card (surface instead of ground); text colours stay (contrast AA).
  final bool past;

  @override
  Widget build(BuildContext context) => Container(
    width: 52,
    padding: const EdgeInsets.symmetric(vertical: 6),
    decoration: BoxDecoration(
      color: past ? JomhorColors.surface : JomhorColors.ground,
      borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.chip)),
    ),
    child: Column(
      children: [
        Text(day, style: JomhorType.number.copyWith(color: highlight ? JomhorColors.accent : JomhorColors.text)),
        Text(month, style: JomhorType.caption.copyWith(color: JomhorColors.textMuted, letterSpacing: 1)),
      ],
    ),
  );
}

/// Status banner (DESIGN.md components): success colours, or neutral; leading icon.
class InfoBanner extends StatelessWidget {
  const new({required this.text, this.success = false, this.icon, super.key});

  final String text;
  final bool success;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final fg = success ? JomhorColors.successText : JomhorColors.textSecondary;
    return Container(
      padding: const EdgeInsets.symmetric(vertical: JomhorSpace.m, horizontal: 14),
      decoration: BoxDecoration(
        color: success ? JomhorColors.successBg : JomhorColors.surface2,
        border: Border.all(color: success ? JomhorColors.success : JomhorColors.border),
        borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon ?? (success ? Icons.check_rounded : Icons.info_outline_rounded), size: 18, color: fg),
          const SizedBox(width: 10),
          Expanded(
            child: Text(text, style: JomhorType.bodySmall.copyWith(color: fg)),
          ),
        ],
      ),
    );
  }
}

/// Chip (DESIGN.md components) with an optional leading icon.
class InfoChip extends StatelessWidget {
  const new(this.text, {this.icon, super.key});

  final String text;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(vertical: JomhorSpace.s, horizontal: JomhorSpace.m),
    decoration: const BoxDecoration(
      color: JomhorColors.ground,
      border: Border.fromBorderSide(BorderSide(color: JomhorColors.border)),
      borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (icon != null) ...[Icon(icon, size: 16, color: JomhorColors.textSecondary), const SizedBox(width: 6)],
        Flexible(
          child: Text(text, style: JomhorType.bodySmall.copyWith(fontWeight: FontWeight.w500)),
        ),
      ],
    ),
  );
}
