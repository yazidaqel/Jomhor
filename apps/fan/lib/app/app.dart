import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/router.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/app_theme.dart';

class JomhorApp extends ConsumerStatefulWidget {
  const new({super.key});

  @override
  ConsumerState<JomhorApp> createState() => _JomhorAppState();
}

class _JomhorAppState extends ConsumerState<JomhorApp> {
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    // Back from the background (e.g. the fan waited for the opening in another app): re-read sale states.
    _lifecycle = AppLifecycleListener(
      onResume: () => ref
        ..invalidate(upcomingEventsProvider)
        ..invalidate(eventDetailProvider),
    );
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp.router(
    onGenerateTitle: (context) => AppLocalizations.of(context).appTitle,
    theme: buildAppTheme(),
    // FR first: an unsupported device locale resolves to FR.
    supportedLocales: AppLocalizations.supportedLocales,
    localizationsDelegates: AppLocalizations.localizationsDelegates,
    routerConfig: ref.watch(routerProvider),
  );
}
