import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Colours come from design/tokens.json via lib/theme/tokens.g.dart only.
final raw = RegExp(
  r'Color\(0x|Color\.fromARGB|Color\.fromRGBO|0x[0-9A-Fa-f]{8}\b|#[0-9A-Fa-f]{6}\b|\bColors\.(?!transparent\b)\w+',
);

void main() {
  test('the pattern catches raw colours and Material palette colours, not tokens', () {
    for (final bad in ['Color(0xFFF5B700)', "'#F5B700'", 'Colors.red', 'Colors.amber.shade200', '0xFF0B0F1A']) {
      expect(raw.hasMatch(bad), isTrue, reason: bad);
    }
    for (final ok in ['JomhorColors.accent', 'Colors.transparent', 'colorScheme.error']) {
      expect(raw.hasMatch(ok), isFalse, reason: ok);
    }
  });

  test('no raw colour literal in lib/ outside the generated tokens', () {
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
