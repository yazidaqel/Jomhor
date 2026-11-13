import 'dart:io';

import 'package:flutter/foundation.dart';

/// Build-time configuration: `flutter run --dart-define=API_BASE_URL=https://api.jomhor.ma/v1`.
abstract final class AppConfig {
  /// Includes `/v1` (it is part of the signed path). Default: the dev backend on the host, seen from the
  /// Android emulator.
  static const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://10.0.2.2:8080/v1');

  /// Plain http is allowed only to a local dev backend (the emulator's host included).
  static const cleartextHosts = {'localhost', '127.0.0.1', '10.0.2.2'};

  /// Called first in `main`: an API other than a local dev backend must be https (the full manifest carries
  /// K_e, and Dart's HTTP client ignores Android's cleartext policy).
  /// Debug builds also accept http to a private IPv4 LAN address (matching the debug-only cleartext manifest): the
  /// local-network test path only, never profile or release.
  static void checkApiUrl({bool debug = kDebugMode, String baseUrl = apiBaseUrl}) {
    final url = Uri.tryParse(baseUrl);
    if (url?.scheme == 'https') return;
    if (url?.scheme == 'http' && (cleartextHosts.contains(url!.host) || (debug && _isPrivateIPv4(url.host)))) {
      return;
    }
    throw StateError(
      'API_BASE_URL must be https (http only for ${cleartextHosts.join(', ')}'
      '${debug ? ' or a private IPv4 address' : ''}), got "$baseUrl". '
      'Build with --dart-define=API_BASE_URL=https://<host>/v1.',
    );
  }

  /// 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16; IPv4 literals only.
  static bool _isPrivateIPv4(String host) {
    final ip = InternetAddress.tryParse(host);
    if (ip == null || ip.type != InternetAddressType.IPv4) return false;
    final [a, b, ...] = ip.rawAddress;
    return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
  }
}
