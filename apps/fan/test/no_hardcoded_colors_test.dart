import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Colours come from design/tokens.json via lib/theme/tokens.g.dart only.
void main() {
  test('no raw colour literal in lib/ outside the generated tokens', () {
    final raw = RegExp(r'Color\(0x|Color\.fromARGB|Color\.fromRGBO|0x[0-9A-Fa-f]{8}\b|#[0-9A-Fa-f]{6}\b');
    final offenders = <String>[];
    for (final file in Directory('lib').listSync(recursive: true).whereType<File>()) {
      final path = file.uri.pathSegments.join('/');
      if (!path.endsWith('.dart') || path.endsWith('theme/tokens.g.dart') || path.contains('l10n/app_localizations')) {
        continue;
      }
      final lines = file.readAsLinesSync();
      for (var i = 0; i < lines.length; i++) {
        if (raw.hasMatch(lines[i])) offenders.add('$path:${i + 1}: ${lines[i].trim()}');
      }
    }
    expect(offenders, isEmpty);
  });
}
