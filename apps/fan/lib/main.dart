import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/config.dart';
import 'package:jomhor_fan/app/locale.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:timezone/data/latest.dart' as tzdata;

Future<void> main() async {
  AppConfig.checkRelease();
  WidgetsFlutterBinding.ensureInitialized();
  tzdata.initializeTimeZones();
  // The bundled fonts' SIL OFL notices, in the OS licence page.
  LicenseRegistry.addLicense(() async* {
    for (final (family, file) in [
      ('Barlow Condensed', 'BarlowCondensed'),
      ('Inter', 'Inter'),
      ('Cairo', 'Cairo'),
      ('IBM Plex Sans Arabic', 'IBMPlexSansArabic'),
    ]) {
      yield LicenseEntryWithLineBreaks([family], await rootBundle.loadString('assets/fonts/OFL-$file.txt'));
    }
  });
  final tokens = SecureTokenStore();
  final prefs = SharedPreferencesAsync();
  final signedIn = await startSession(tokens, prefs);
  final locale = await savedLocale(prefs);
  final follows = await savedFollows(prefs);
  final push = await startPush();
  runApp(
    ProviderScope(
      // No automatic retry: screens show the error and a "Réessayer" button.
      retry: (_, _) => null,
      overrides: [
        tokenStoreProvider.overrideWithValue(tokens),
        initialSignedInProvider.overrideWithValue(signedIn),
        initialLocaleProvider.overrideWithValue(locale),
        initialFollowsProvider.overrideWithValue(follows),
        pushMessagingProvider.overrideWithValue(push),
      ],
      child: const JomhorApp(),
    ),
  );
}
