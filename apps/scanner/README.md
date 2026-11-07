# jomhor_scanner

Flutter gate scanner (Android 10+, package `ma.jomhor.scanner`). Shares `packages/core` (token codec).

```sh
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080/v1
```

- `API_BASE_URL` includes `/v1` (part of the signed path, docs/04-api.md §Gate auth). The app refuses to
  start unless it is https, or http to `localhost`/`127.0.0.1`/`10.0.2.2` (any build mode).
- Device key: ECDSA P-256 in Android Keystore, `android/app/src/main/kotlin/ma/jomhor/scanner/DeviceKey.kt`,
  Dart side `lib/core/device_key.dart`. `FLAG_SECURE` is set on the activity.
- Database: `lib/data/schema.drift` = `docs/05-scanner-sqlite.sql`. The generated `scanner_database.g.dart` is
  committed; after a schema change run `dart run build_runner build && dart format lib/data` (build_runner
  formats at 80 columns, the project at 120; versions pinned by `pubspec.lock`).
  `test/database_test.dart` fails if the Drift schema differs from docs/05.
- Theme: `lib/theme/tokens.g.dart` comes from `design/tokens.json` through the fan app's generator:
  `dart run tool/gen_tokens.dart`; `test/theme_test.dart` fails if it is stale.
- Copy: `lib/l10n/app_fr.arb` is the template. Keys missing in `app_ar.arb` / `app_en.arb` fall back to FR.
- Release signing: `android/key.properties` (git-ignored), as in apps/fan.

CI (`.github/workflows/ci.yml`, job `scanner`): `flutter pub get`, `dart format --output=none
--set-exit-if-changed lib test tool`, `flutter analyze --fatal-infos`, `flutter test`.
