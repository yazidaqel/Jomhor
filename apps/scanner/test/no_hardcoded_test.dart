import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// Colours come from design/tokens.json via lib/theme/tokens.g.dart only (same rule as apps/fan).
final rawColor = RegExp(
  r'Color\(0x|Color\.fromARGB|Color\.fromRGBO|0x[0-9A-Fa-f]{8}\b|#[0-9A-Fa-f]{6}\b|\bColors\.(?!transparent\b)\w+',
);

/// Copy comes from lib/l10n only: no string literal handed to a text widget or a text field.
final rawText = RegExp(
  r'''\b(Text|SelectableText|Tooltip|SnackBar|TextSpan)\(\s*['"]|\b(label|hint|helper|error|tooltip|semanticsLabel)Text:\s*['"]|\b(title|label|tooltip|semanticLabel):\s*['"]''',
);

/// `path:line: text` of every match of [pattern] in lib/ (whole files, so a literal on the next line counts).
List<String> offenders(RegExp pattern) => [
  for (final file in Directory('lib').listSync(recursive: true).whereType<File>())
    if (file.path.endsWith('.dart') && !file.path.endsWith('.g.dart') && !file.path.contains('app_localizations'))
      for (final m in pattern.allMatches(file.readAsStringSync()))
        '${file.path}:${m.input.substring(0, m.start).split('\n').length}: ${m[0]}',
];

void main() {
  test('the patterns catch raw colours and literal copy, not tokens or l10n', () {
    for (final bad in ['Color(0xFFF5B700)', "'#F5B700'", 'Colors.red', '0xFF0B0F1A']) {
      expect(rawColor.hasMatch(bad), isTrue, reason: bad);
    }
    for (final ok in ['JomhorColors.accent', 'Colors.transparent', 'colorScheme.error']) {
      expect(rawColor.hasMatch(ok), isFalse, reason: ok);
    }
    for (final bad in ["Text('Validé')", 'Text(\n "OK")', "labelText: 'PIN'", "tooltip: 'Retour'"]) {
      expect(rawText.hasMatch(bad), isTrue, reason: bad);
    }
    for (final ok in ['Text(l10n.deviceActive)', 'labelText: l10n.pin', "path: '/'"]) {
      expect(rawText.hasMatch(ok), isFalse, reason: ok);
    }
  });

  test('no raw colour literal in lib/ outside the generated tokens', () {
    expect(offenders(rawColor), isEmpty);
  });

  test('no literal copy in lib/ widgets (FR/AR/EN via lib/l10n)', () {
    expect(offenders(rawText), isEmpty);
  });
}
