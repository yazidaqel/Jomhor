import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/theme/tokens.g.dart';
import 'package:jomhor_fan/ui/bidi.dart';
import 'package:jomhor_fan/ui/blocks.dart';

Map<String, Object?> arb(String locale) =>
    jsonDecode(File('lib/l10n/app_$locale.arb').readAsStringSync()) as Map<String, Object?>;

Set<String> messageKeys(String locale) => {
  for (final k in arb(locale).keys)
    if (!k.startsWith('@')) k,
};

void main() {
  final fr = lookupAppLocalizations(const Locale('fr'));
  final ar = lookupAppLocalizations(const Locale('ar'));

  test('FR is the first supported locale (fallback for an unsupported device locale)', () {
    expect(AppLocalizations.supportedLocales.first, const Locale('fr'));
  });

  for (final locale in ['ar', 'en']) {
    test('every app_fr.arb key exists in app_$locale.arb, and no other', () {
      expect(messageKeys(locale), messageKeys('fr'));
    });
  }

  test('AR is Darija in Arabic script, not the FR fallback', () {
    expect(ar.errorGeneric, isNot(fr.errorGeneric));
    expect(ar.navTickets, 'تذاكري');
  });

  test('list separators per locale: Arabic uses the Arabic comma', () {
    expect(fr.listSeparator, ', ');
    expect(ar.listSeparator, '، ');
  });

  test('Arabic plurals: 1, 2, 3 to 10, then 11 and more', () {
    expect(ar.zoneRemaining(1), 'بلاصة وحدة باقية');
    expect(ar.zoneRemaining(2), 'جوج بلايص باقيين');
    expect(ar.zoneRemaining(4), '4 بلايص باقيين');
    expect(ar.zoneRemaining(1200), '1,200 بلاصة باقية'); // Western digits (DESIGN.md Lot 4)
  });

  test('prices, codes and ticket numbers are LTR isolates in AR only', () {
    expect(ar.dh(5000), '\u206650\u00A0DH\u2069');
    expect(ar.ltr('JMH 4F7K 29'), '\u2066JMH 4F7K 29\u2069');
    expect(fr.dh(5000), '50\u00A0DH');
    // Names: isolated only when their script runs against the locale.
    expect(ar.isolate('Yazid A.'), '\u2068Yazid A.\u2069');
    expect(ar.isolate('يزيد ع.'), 'يزيد ع.');
    expect(fr.isolate('يزيد ع.'), '\u2068يزيد ع.\u2069');
    expect(fr.isolate('Yazid A.'), 'Yazid A.');
  });

  for (final (locale, spacing, weight) in [('fr', 1.5, FontWeight.w600), ('ar', 0.0, FontWeight.w700)]) {
    testWidgets('$locale label style: letter-spacing $spacing, weight $weight', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          locale: Locale(locale),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: const SectionLabel('Langue'),
        ),
      );
      final style = tester.widget<Text>(find.byType(Text)).style!;
      expect((style.letterSpacing, style.fontWeight), (spacing, weight));
      expect(style.fontFamilyFallback, [JomhorFonts.arabicBody]);
    });
  }
}
