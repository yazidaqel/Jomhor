import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/config.dart';

void main() {
  const https = 'https://api.jomhor.ma/v1';

  test('a release build needs an https API, and nothing else (no club is set at build time)', () {
    expect(() => AppConfig.checkRelease(release: true, baseUrl: https), returnsNormally);
    expect(() => AppConfig.checkRelease(release: true, baseUrl: 'http://api.jomhor.ma/v1'), throwsStateError);
  });

  test('debug builds may use the http dev default', () {
    expect(AppConfig.checkRelease, returnsNormally);
  });
}
