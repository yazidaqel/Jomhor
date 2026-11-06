import 'dart:ui';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

void main() {
  final fr = lookupAppLocalizations(const Locale('fr'));
  final ar = lookupAppLocalizations(const Locale('ar'));

  test('FR is the first supported locale (fallback for an unsupported device locale)', () {
    expect(AppLocalizations.supportedLocales.first, const Locale('fr'));
  });

  test('a key translated in AR uses the AR string', () {
    expect(ar.navHome, isNot(fr.navHome));
  });

  test('a key missing from app_ar.arb falls back to FR', () {
    expect(ar.errorGeneric, fr.errorGeneric);
  });

  test('list separators per locale: Arabic uses the Arabic comma', () {
    expect(fr.listSeparator, ', ');
    expect(ar.listSeparator, '، ');
  });
}
