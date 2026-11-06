import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

/// DESIGN.md §Components (bottom nav icons); not in tokens.json.
const _navIconSize = 22.0;

/// Dark theme from `design/tokens.json` (via tokens.g.dart). The club accent
/// is `JomhorColors.accent`; nothing here names a club colour.
ThemeData buildAppTheme() {
  const scheme = ColorScheme.dark(
    primary: JomhorColors.accent,
    onPrimary: JomhorColors.onAccent,
    secondary: JomhorColors.accent,
    onSecondary: JomhorColors.onAccent,
    surface: JomhorColors.ground,
    // Same as the dark default today; kept so a tokens.json change flows through.
    // ignore: avoid_redundant_argument_values
    onSurface: JomhorColors.text,
    onSurfaceVariant: JomhorColors.textSecondary,
    surfaceContainer: JomhorColors.surface,
    surfaceContainerHigh: JomhorColors.surface2,
    outline: JomhorColors.border,
    outlineVariant: JomhorColors.borderMuted,
    error: JomhorColors.danger,
    onError: JomhorColors.ground,
  );

  WidgetStateProperty<T> selected<T>(T on, T off) =>
      WidgetStateProperty.resolveWith((states) => states.contains(WidgetState.selected) ? on : off);

  return ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: JomhorColors.ground,
    // Material roles -> JomhorType. Text() defaults to bodyMedium.
    textTheme: const TextTheme(
      displayLarge: JomhorType.hero,
      headlineMedium: JomhorType.h1,
      titleLarge: JomhorType.h2,
      bodyMedium: JomhorType.body,
      bodySmall: JomhorType.bodySmall,
      labelMedium: JomhorType.label,
      labelSmall: JomhorType.caption,
    ),
    cardTheme: const CardThemeData(
      color: JomhorColors.surface,
      elevation: 0,
      margin: EdgeInsets.zero,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: JomhorColors.border),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.card)),
      ),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: JomhorColors.accent,
        foregroundColor: JomhorColors.onAccent,
        minimumSize: const Size.fromHeight(JomhorSize.ctaHeight),
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control))),
        // DESIGN.md asks 16/600 for the CTA; tokens.json has no such style yet, so body until it does.
        textStyle: JomhorType.body,
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: JomhorSize.navHeight,
      backgroundColor: JomhorColors.surface,
      indicatorColor: Colors.transparent,
      iconTheme: selected(
        const IconThemeData(size: _navIconSize, color: JomhorColors.accent),
        const IconThemeData(size: _navIconSize, color: JomhorColors.textMuted),
      ),
      labelTextStyle: selected(
        JomhorType.caption.copyWith(color: JomhorColors.accent),
        JomhorType.caption.copyWith(color: JomhorColors.textMuted),
      ),
    ),
  );
}
