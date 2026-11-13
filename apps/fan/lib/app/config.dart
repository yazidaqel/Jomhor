import 'package:flutter/foundation.dart';

/// Build-time configuration:
/// `flutter run --dart-define=API_BASE_URL=https://api.jomhor.ma/v1`. One app serves every club (docs/04-api.md
/// §Club directory): no club is set at build time.
abstract final class AppConfig {
  /// Includes `/v1`. Default: the dev backend on the host, seen from the Android emulator.
  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://10.0.2.2:8080/v1');

  /// Push (FCM, lot-4 Q61): off unless built with `--dart-define=PUSH_ENABLED=true` and the Firebase defines below
  /// (Q119). Off, the app never touches Firebase; the inbox still works.
  static const pushEnabled = bool.fromEnvironment('PUSH_ENABLED');
  static const firebaseApiKey = String.fromEnvironment('FIREBASE_API_KEY');
  static const firebaseAppId = String.fromEnvironment('FIREBASE_APP_ID');
  static const firebaseMessagingSenderId = String.fromEnvironment('FIREBASE_MESSAGING_SENDER_ID');
  static const firebaseProjectId = String.fromEnvironment('FIREBASE_PROJECT_ID');

  /// iOS only; empty elsewhere.
  static const firebaseIosBundleId = String.fromEnvironment('FIREBASE_IOS_BUNDLE_ID');

  /// Called first in `main`. A release build without an https API (the Bearer token would go over
  /// cleartext) does not start. Debug and profile builds may use the http dev default.
  static void checkRelease({bool release = kReleaseMode, String baseUrl = apiBaseUrl}) {
    if (!release) return;
    if (Uri.tryParse(baseUrl)?.scheme != 'https') {
      throw StateError(
        'API_BASE_URL must be https in a release build, got "$baseUrl". '
        'Build with --dart-define=API_BASE_URL=https://<host>/v1.',
      );
    }
  }
}
