import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_scanner/theme/app_theme.dart';

// The fan app's generator, run from apps/scanner (tool/gen_tokens.dart): one generator, no copy.
import '../../fan/tool/gen_tokens.dart';

void main() {
  final tokens = jsonDecode(File(tokensPath).readAsStringSync()) as Map<String, Object?>;
  final color = tokens['color']! as Map<String, Object?>;
  Color hex(String key) => Color(int.parse('FF${(color[key]! as String).substring(1)}', radix: 16));

  test('tokens.g.dart matches design/tokens.json (else run `dart run tool/gen_tokens.dart`)', () {
    expect(File(outputPath).readAsStringSync(), generateTokens(tokens));
  });

  test('the theme accent and ground are the ones in design/tokens.json', () {
    final theme = buildAppTheme();
    expect(theme.colorScheme.primary, hex('accent'));
    expect(theme.colorScheme.onPrimary, hex('onAccent'));
    expect(theme.scaffoldBackgroundColor, hex('ground'));
  });
}
