import 'package:flutter/material.dart';
import 'package:jomhor_scanner/theme/tokens.g.dart';

/// Dark theme from `design/tokens.json` (via tokens.g.dart), the same components as the fan app. The
/// club accent is `JomhorColors.accent`; the scan result colours are `JomhorColors.scan*`.
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

  return ThemeData(
    colorScheme: scheme,
    scaffoldBackgroundColor: JomhorColors.ground,
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
        textStyle: JomhorType.cta,
      ),
    ),
  );
}
