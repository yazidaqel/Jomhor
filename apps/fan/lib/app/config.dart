import 'package:flutter/foundation.dart';

/// Build-time configuration:
/// `flutter run --dart-define=API_BASE_URL=https://api.jomhor.ma/v1 --dart-define=ORGANIZER_ID=<uuid>`.
abstract final class AppConfig {
  /// Includes `/v1`. Default: the dev backend on the host, seen from the Android emulator.
  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://10.0.2.2:8080/v1');

  /// The one club this build serves (`GET /fan/events?organizer=`).
  static const organizerId = String.fromEnvironment('ORGANIZER_ID');

  /// Called first in `main`. A release build without an https API (the Bearer token would go over
  /// cleartext) or without a club does not start. Debug and profile builds may use the http dev default.
  static void checkRelease({bool release = kReleaseMode, String baseUrl = apiBaseUrl, String organizer = organizerId}) {
    if (!release) return;
    if (Uri.tryParse(baseUrl)?.scheme != 'https') {
      throw StateError(
        'API_BASE_URL must be https in a release build, got "$baseUrl". '
        'Build with --dart-define=API_BASE_URL=https://<host>/v1.',
      );
    }
    if (organizer.isEmpty) {
      throw StateError('ORGANIZER_ID is empty. Build with --dart-define=ORGANIZER_ID=<organizer uuid>.');
    }
  }
}
