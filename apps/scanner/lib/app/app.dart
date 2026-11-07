import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/app/router.dart';
import 'package:jomhor_scanner/features/staff/staff_session.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';

class ScannerApp extends ConsumerWidget {
  const new({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) => MaterialApp.router(
    onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
    theme: buildAppTheme(),
    // FR first: an unsupported device locale resolves to FR. AR is laid out RTL.
    supportedLocales: AppLocalizations.supportedLocales,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    routerConfig: ref.watch(routerProvider),
    // Any touch restarts the 10-min idle lock (scans restart it in ScanController).
    builder: (context, child) => Listener(
      behavior: HitTestBehavior.translucent,
      onPointerDown: (_) => ref.read(staffSessionProvider).touch(),
      child: child,
    ),
  );
}
