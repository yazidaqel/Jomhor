import 'dart:convert';
import 'dart:io';
import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/theme/app_theme.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/brand.dart';

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

  test('the club identity comes from tokens.json', () {
    final club = tokens['club']! as Map<String, Object?>;
    expect((JomhorClub.name, JomhorClub.crestInitial), (club['name'], club['crestInitial']));
  });

  test('tokens.g.dart matches tokens.json (else run `dart run tool/gen_tokens.dart`)', () {
    expect(File(outputPath).readAsStringSync(), generateTokens(tokens));
  });

  test('the wordmark is the logo outline: it parses and fills its viewBox (design/logo)', () {
    final box = jomhorWordmark.getBounds();
    expect(box.left, closeTo(2.5, 0.1)); // the J
    expect(box.right, closeTo(JomhorBrand.wordmarkWidth, 0.1)); // the r
    expect(box.top, closeTo(23.2, 0.1));
    expect(box.bottom, closeTo(97.2, 0.1));
    expect(JomhorBrand.dots.last, (38.0, 73.0, 6.9)); // the twelfth, bigger
    expect(JomhorBrand.amber, const Color(0xFFF5B700));
  });

  test('another club accent in tokens.json changes the generated accent', () {
    final other = {
      ...tokens,
      'color': {...color, 'accent': '#C8102E'},
    };
    expect(generateTokens(other), contains('static const Color accent = Color(0xFFC8102E);'));
  });
}
