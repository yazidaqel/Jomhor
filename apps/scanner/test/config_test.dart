import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_scanner/app/config.dart';

void main() {
  void accepts(String url, {required bool debug}) => expect(
    () => AppConfig.checkApiUrl(debug: debug, baseUrl: url),
    returnsNormally,
    reason: url,
  );
  void rejects(String url, {required bool debug}) => expect(
    () => AppConfig.checkApiUrl(debug: debug, baseUrl: url),
    throwsStateError,
    reason: url,
  );

  const lan = [
    'http://192.168.8.148:8090/v1',
    'http://10.1.2.3/v1',
    'http://172.20.0.5/v1',
    'http://172.16.0.1/v1',
    'http://172.31.255.255/v1',
  ];

  test('profile and release: https, or http to a local dev backend only', () {
    for (final ok in ['https://api.jomhor.ma/v1', 'http://10.0.2.2:8080/v1', 'http://localhost:8080/v1']) {
      accepts(ok, debug: false);
    }
    for (final bad in [...lan, 'http://api.jomhor.ma/v1', 'ftp://x', '']) {
      rejects(bad, debug: false);
    }
  });

  test('debug: http also to a private IPv4 LAN address', () {
    for (final ok in ['https://api.jomhor.ma/v1', 'http://localhost:8080/v1', ...lan]) {
      accepts(ok, debug: true);
    }
    for (final bad in [
      'http://172.15.255.255/v1',
      'http://172.32.0.1/v1',
      'http://8.8.8.8/v1',
      'http://api.jomhor.ma/v1',
      'http://[fd00::1]/v1',
      'http://[::ffff:192.168.1.1]/v1',
      'http://192.168.8.148:port/v1',
      '',
    ]) {
      rejects(bad, debug: true);
    }
  });

  test('the default URL passes', () => expect(AppConfig.checkApiUrl, returnsNormally));
}
