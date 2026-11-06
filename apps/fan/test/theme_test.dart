import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/theme/app_theme.dart';

import '../tool/gen_tokens.dart';

void main() {
  final tokens = jsonDecode(File(tokensPath).readAsStringSync()) as Map<String, Object?>;
  final color = tokens['color']! as Map<String, Object?>;
  Color hex(String key) => Color(int.parse('FF${(color[key]! as String).substring(1)}', radix: 16));

  test('the theme accent is the accent in design/tokens.json', () {
    final theme = buildAppTheme();
    expect(theme.colorScheme.primary, hex('accent'));
    expect(theme.colorScheme.onPrimary, hex('onAccent'));
    expect(theme.scaffoldBackgroundColor, hex('ground'));
  });

  test('tokens.g.dart matches tokens.json (else run `dart run tool/gen_tokens.dart`)', () {
    expect(File(outputPath).readAsStringSync(), generateTokens(tokens));
  });

  test('another club accent in tokens.json changes the generated accent', () {
    final other = {
      ...tokens,
      'color': {...color, 'accent': '#C8102E'},
    };
    expect(generateTokens(other), contains('static const Color accent = Color(0xFFC8102E);'));
  });
}
