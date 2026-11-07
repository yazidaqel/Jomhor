/// Build-time configuration: `flutter run --dart-define=API_BASE_URL=https://api.jomhor.ma/v1`.
abstract final class AppConfig {
  /// Includes `/v1` (it is part of the signed path). Default: the dev backend on the host, seen from the
  /// Android emulator.
  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://10.0.2.2:8080/v1');

  /// Plain http is allowed only to a local dev backend (the emulator's host included).
  static const cleartextHosts = {'localhost', '127.0.0.1', '10.0.2.2'};

  /// Called first in `main`, in every build mode: an API other than a local dev backend must be https (the
  /// full manifest carries K_e, and Dart's HTTP client ignores Android's cleartext policy).
  static void checkApiUrl({String baseUrl = apiBaseUrl}) {
    final url = Uri.tryParse(baseUrl);
    if (url?.scheme == 'https' || (url?.scheme == 'http' && cleartextHosts.contains(url!.host))) return;
    throw StateError(
      'API_BASE_URL must be https (http only for ${cleartextHosts.join(', ')}), got "$baseUrl". '
      'Build with --dart-define=API_BASE_URL=https://<host>/v1.',
    );
  }
}
