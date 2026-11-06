import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/config.dart';

void main() {
  const https = 'https://api.jomhor.ma/v1';

  test('a release build needs an https API and an organizer', () {
    expect(() => AppConfig.checkRelease(release: true, baseUrl: https, organizer: 'org-1'), returnsNormally);
    expect(
      () => AppConfig.checkRelease(release: true, baseUrl: 'http://api.jomhor.ma/v1', organizer: 'org-1'),
      throwsStateError,
    );
    // Defaults: no ORGANIZER_ID dart-define in tests.
    expect(() => AppConfig.checkRelease(release: true, baseUrl: https), throwsStateError);
  });

  test('debug builds may use the http dev default without an organizer', () {
    expect(AppConfig.checkRelease, returnsNormally);
  });
}
