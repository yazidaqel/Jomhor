# jomhor_fan

Flutter fan app (Android, iOS). Shares `packages/core` (token codec, K_t, slot maths).

```sh
flutter run --dart-define=API_BASE_URL=http://10.0.2.2:8080/v1
```

- `API_BASE_URL` includes `/v1`; the default is the dev backend seen from the Android emulator. A release
  build refuses to start unless it is https. There is no club setting: one app serves every club, and the fan
  picks the clubs they follow (`lib/features/clubs/`, kept on the phone; `docs/04-api.md` §Club directory).
- Release signing: `android/key.properties` (git-ignored: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
  Without it a release build fails; debug builds are unaffected.
- Theme: `lib/theme/tokens.g.dart` is generated from `design/tokens.json`. After changing tokens
  (the app is always Jomhor ink + amber, never a club's colours), run `dart run tool/gen_tokens.dart`; `test/theme_test.dart` fails if it is stale.
- Copy: `lib/l10n/app_fr.arb` is the template (FR, "tu" for fans). Keys missing in `app_ar.arb` /
  `app_en.arb` fall back to FR. Generated `app_localizations*.dart` are rebuilt by `flutter pub get`.

CI (`.github/workflows/ci.yml`): `flutter pub get`, `dart format --output=none --set-exit-if-changed lib test`,
`flutter analyze --fatal-infos`, `flutter test`.
