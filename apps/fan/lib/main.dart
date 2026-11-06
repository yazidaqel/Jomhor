import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/app.dart';
import 'package:jomhor_fan/app/config.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:timezone/data/latest.dart' as tzdata;

Future<void> main() async {
  AppConfig.checkRelease();
  WidgetsFlutterBinding.ensureInitialized();
  tzdata.initializeTimeZones();
  final tokens = SecureTokenStore();
  final signedIn = await startSession(tokens, SharedPreferencesAsync());
  runApp(
    ProviderScope(
      // No automatic retry: screens show the error and a "Réessayer" button.
      retry: (_, _) => null,
      overrides: [tokenStoreProvider.overrideWithValue(tokens), initialSignedInProvider.overrideWithValue(signedIn)],
      child: const JomhorApp(),
    ),
  );
}
