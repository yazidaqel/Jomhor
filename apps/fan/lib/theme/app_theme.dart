import 'package:flutter/cupertino.dart' show CupertinoPageTransitionsBuilder;
import 'package:flutter/material.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';

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
    surfaceContainerHighest: JomhorColors.surface2,
    outline: JomhorColors.border,
    outlineVariant: JomhorColors.borderMuted,
    error: JomhorColors.danger,
    onError: JomhorColors.ground,
  );

  WidgetStateProperty<T> selected<T>(T on, T off) =>
      WidgetStateProperty.resolveWith((states) => states.contains(WidgetState.selected) ? on : off);

  const control = RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)));
  OutlineInputBorder field(Color color, [double width = 1]) => OutlineInputBorder(
    borderRadius: const BorderRadius.all(Radius.circular(JomhorRadius.control)),
    borderSide: BorderSide(color: color, width: width),
  );

  return ThemeData(
    colorScheme: scheme,
    fontFamily: JomhorFonts.body,
    scaffoldBackgroundColor: JomhorColors.ground,
    // Material roles -> JomhorType. Text() defaults to bodyMedium.
    textTheme: const TextTheme(
      displayLarge: JomhorType.hero,
      headlineMedium: JomhorType.h1,
      titleLarge: JomhorType.h2,
      titleMedium: JomhorType.body,
      bodyLarge: JomhorType.body,
      bodyMedium: JomhorType.body,
      bodySmall: JomhorType.bodySmall,
      labelLarge: JomhorType.cta,
      labelMedium: JomhorType.label,
      labelSmall: JomhorType.caption,
    ),
    // DESIGN.md principle 6: Android cross-fades (system back and predictive back still pop the route), iOS keeps
    // its native slide and swipe-back.
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {TargetPlatform.android: _CrossFade(), TargetPlatform.iOS: CupertinoPageTransitionsBuilder()},
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
        disabledBackgroundColor: JomhorColors.surface2,
        disabledForegroundColor: JomhorColors.textMuted,
        minimumSize: const Size.fromHeight(JomhorSize.ctaHeight),
        padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xl, vertical: JomhorSpace.m),
        shape: control,
        textStyle: JomhorType.cta,
      ),
    ),
    // Secondary button: surface, border, white text.
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        backgroundColor: JomhorColors.surface,
        foregroundColor: JomhorColors.text,
        disabledForegroundColor: JomhorColors.textDisabled,
        minimumSize: const Size.fromHeight(JomhorSize.ctaHeight),
        padding: const EdgeInsets.symmetric(horizontal: JomhorSpace.xl, vertical: JomhorSpace.m),
        side: const BorderSide(color: JomhorColors.border),
        shape: control,
        textStyle: JomhorType.cta,
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: JomhorColors.textSecondary,
        minimumSize: const Size(JomhorSize.touchMin, 48),
        shape: control,
        textStyle: JomhorType.cta.copyWith(fontSize: 15),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: JomhorColors.surface,
      contentPadding: const EdgeInsets.symmetric(horizontal: JomhorSpace.l, vertical: JomhorSpace.l),
      labelStyle: JomhorType.body.copyWith(color: JomhorColors.textMuted),
      floatingLabelStyle: JomhorType.body.copyWith(color: JomhorColors.accent),
      hintStyle: JomhorType.body.copyWith(color: JomhorColors.textMuted),
      helperStyle: JomhorType.caption.copyWith(color: JomhorColors.textMuted, fontSize: 12),
      errorStyle: JomhorType.caption.copyWith(color: JomhorColors.danger, fontSize: 12),
      prefixIconColor: JomhorColors.textMuted,
      border: field(JomhorColors.border),
      enabledBorder: field(JomhorColors.border),
      disabledBorder: field(JomhorColors.surface2),
      focusedBorder: field(JomhorColors.accent, 2),
      errorBorder: field(JomhorColors.danger),
      focusedErrorBorder: field(JomhorColors.danger, 2),
    ),
    chipTheme: ChipThemeData(
      backgroundColor: JomhorColors.ground,
      selectedColor: JomhorColors.accent,
      side: const BorderSide(color: JomhorColors.border),
      labelStyle: JomhorType.bodySmall.copyWith(color: JomhorColors.text),
      secondaryLabelStyle: JomhorType.bodySmall.copyWith(color: JomhorColors.onAccent, fontWeight: FontWeight.w600),
      checkmarkColor: JomhorColors.onAccent,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.chip))),
    ),
    checkboxTheme: CheckboxThemeData(
      fillColor: selected(JomhorColors.accent, Colors.transparent),
      checkColor: const WidgetStatePropertyAll(JomhorColors.onAccent),
      side: const BorderSide(color: JomhorColors.borderMuted, width: 2),
    ),
    // Icon buttons are the 44 px circles of DESIGN.md (surface, border), on a 48 dp target.
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(
        backgroundColor: JomhorColors.surface,
        foregroundColor: JomhorColors.text,
        disabledBackgroundColor: JomhorColors.surface,
        disabledForegroundColor: JomhorColors.textDisabled,
        side: const BorderSide(color: JomhorColors.border),
        fixedSize: const Size.square(JomhorSize.touchMin),
        minimumSize: const Size.square(JomhorSize.touchMin),
        iconSize: 20,
        tapTargetSize: MaterialTapTargetSize.padded,
      ),
    ),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: JomhorColors.accent),
    dividerTheme: const DividerThemeData(color: JomhorColors.border, space: 1, thickness: 1),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: JomhorColors.surface2,
      contentTextStyle: JomhorType.body.copyWith(color: JomhorColors.text),
      shape: const RoundedRectangleBorder(
        side: BorderSide(color: JomhorColors.border),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.control)),
      ),
    ),
    dialogTheme: const DialogThemeData(
      backgroundColor: JomhorColors.surface,
      surfaceTintColor: Colors.transparent,
      titleTextStyle: JomhorType.h2,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: JomhorColors.border),
        borderRadius: BorderRadius.all(Radius.circular(JomhorRadius.card)),
      ),
    ),
    listTileTheme: const ListTileThemeData(
      contentPadding: EdgeInsets.symmetric(horizontal: JomhorSpace.l),
      minTileHeight: 56,
      iconColor: JomhorColors.textSecondary,
      selectedColor: JomhorColors.text,
    ),
    navigationBarTheme: NavigationBarThemeData(
      height: JomhorSize.navHeight,
      backgroundColor: JomhorColors.surface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      indicatorColor: JomhorColors.accent.withValues(alpha: 0.14),
      indicatorShape: const StadiumBorder(),
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
      iconTheme: selected(
        const IconThemeData(size: JomhorSize.navIcon, color: JomhorColors.accent),
        const IconThemeData(size: JomhorSize.navIcon, color: JomhorColors.textMuted),
      ),
      labelTextStyle: selected(
        JomhorType.caption.copyWith(color: JomhorColors.accent, fontWeight: FontWeight.w600),
        JomhorType.caption.copyWith(color: JomhorColors.textMuted),
      ),
    ),
  );
}

/// Cross-fade between pages in `motion.base`, instant under reduced motion (DESIGN.md §Accessibility).
class _CrossFade extends PageTransitionsBuilder {
  const new();

  static final _easeOut = CurveTween(curve: Curves.easeOut);

  @override
  Duration get transitionDuration => const Duration(milliseconds: JomhorMotion.base);

  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> animation,
    Animation<double> secondaryAnimation,
    Widget child,
  ) => MediaQuery.maybeDisableAnimationsOf(context) ?? false
      ? child
      : FadeTransition(opacity: animation.drive(_easeOut), child: child);
}
