// Goldens are recorded on Linux with the Flutter release CI runs (3.47.9; ghcr.io/cirruslabs/flutter only ships
// 3.44, so the official 3.47.9 Linux SDK goes into a cached volume). From the repository root:
//
//   docker run --rm -v "$PWD:/repo" -v jomhor-flutter-3.47.9:/opt/f ghcr.io/cirruslabs/flutter:stable bash -c '
//     set -e
//     [ -x /opt/f/flutter/bin/flutter ] || curl -sL https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/flutter_linux_3.47.9-stable.tar.xz | tar -xJ -C /opt/f
//     export PATH=/opt/f/flutter/bin:$PATH; git config --global --add safe.directory "*"
//     cd /repo/apps/fan && flutter pub get && flutter test --update-goldens test/goldens_test.dart
//     cd /repo/apps/scanner && flutter pub get && flutter test --update-goldens test/arabic_initials_golden_test.dart'
//
// Then run `flutter pub get` again in both apps on the host (the container wrote its paths into .dart_tool).
// When CI's Flutter moves (.github/workflows/ci.yml), change 3.47.9 here and record again.
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Loads the bundled fonts (FontManifest.json: Barlow, Inter, Cairo, IBM Plex Sans Arabic, Material icons), so
/// goldens show the real faces instead of the test font's boxes.
Future<void> loadAppFonts() async {
  final manifest = jsonDecode(await rootBundle.loadString('FontManifest.json')) as List<Object?>;
  for (final family in manifest.cast<Map<String, Object?>>()) {
    final loader = FontLoader(family['family']! as String);
    for (final font in (family['fonts']! as List<Object?>).cast<Map<String, Object?>>()) {
      loader.addFont(rootBundle.load(font['asset']! as String));
    }
    await loader.load();
  }
}

/// Exact on Linux, where the goldens are recorded and CI runs: a flipped chevron or reversed initials fail there.
/// On Windows and macOS, glyph edges rasterise differently: as a dev-only convenience a golden passes there with
/// up to [devTolerance] of its pixels changed. Only the Linux run is the check.
class LinuxGoldenComparator extends LocalFileComparator {
  new(super.testFile);

  static const devTolerance = 0.06;

  /// Installs it for the calling test file (whose directory holds `goldens/`).
  static void install(String testFile) => goldenFileComparator = LinuxGoldenComparator(
    Uri.parse('${(goldenFileComparator as LocalFileComparator).basedir}$testFile'),
  );

  @override
  Future<bool> compare(Uint8List imageBytes, Uri golden) async {
    final result = await GoldenFileComparator.compareLists(imageBytes, await getGoldenBytes(golden));
    try {
      if (result.passed || (!Platform.isLinux && result.diffPercent <= devTolerance)) return true;
      throw FlutterError(await generateFailureOutput(result, golden, basedir));
    } finally {
      result.dispose();
    }
  }
}
