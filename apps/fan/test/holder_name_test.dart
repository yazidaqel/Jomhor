import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

/// docs/04-api.md §Holder names, case by case (same order as backend HolderNames.normalize).
void main() {
  // docs/holder-name-vectors.json: the case list Java, Dart and TS share (written by backend).
  group('docs/holder-name-vectors.json', () {
    final cases = (jsonDecode(File('../../docs/holder-name-vectors.json').readAsStringSync()) as List<Object?>)
        .cast<Map<String, Object?>>();
    test('is there', () => expect(cases, isNotEmpty));
    for (final (i, c) in cases.indexed) {
      final input = c['input']! as String;
      test('#$i ${input.runes.map((r) => r.toRadixString(16)).join(' ')}', () {
        final name = normalizeHolderName(input);
        if (c['ok'] != true) return expect(name, isNull);
        expect(name, c['normalized']);
        expect((holderLabel(name!), holderInitials(name)), (c['label'], c['initials']));
      });
    }
  });

  group('normalizeHolderName accepts and normalises', () {
    for (final (input, stored) in [
      ('Yazid Aqel', 'Yazid Aqel'),
      ('  Yazid    Aqel  ', 'Yazid Aqel'), // collapsed, trimmed
      ('Yazid\u00a0Aqel', 'Yazid Aqel'), // no-break space
      ('Yazid\u202fAqel', 'Yazid Aqel'), // narrow no-break space
      ('Yazid\t\u3000Aqel', 'Yazid Aqel'), // tab + ideographic space
      ('N\u2019Diaye', "N'Diaye"), // typographic apostrophe
      ('\uff39\uff41\uff5a\uff49\uff44 Aqel', 'Yazid Aqel'), // fullwidth via NFKC
      ('E\u0301lodie Lef\u00e8vre', '\u00c9lodie Lef\u00e8vre'), // decomposed accent composed by NFKC
      ('Jean-Luc O.', 'Jean-Luc O.'),
      ('Al', 'Al'), // 2 characters
      ('A${'b' * 59}', 'A${'b' * 59}'), // 60 characters
      // 60 code points but 119 UTF-16 units: the length counts code points (U+1DF00, a Latin letter).
      ('A${'\u{1DF00}' * 59}', 'A${'\u{1DF00}' * 59}'),
      ('Yazid\u0085Aqel\u0085', 'Yazid Aqel'), // NEL is Unicode White_Space: collapsed, trimmed
      ('\u001fYazid Aqel\u001c', 'Yazid Aqel'), // U+001C..U+001F: Java strip() removes them at the ends
    ]) {
      test('${input.codeUnits}', () => expect(normalizeHolderName(input), stored));
    }
  });

  group('normalizeHolderName refuses', () {
    for (final (why, input) in [
      ('empty', ''),
      ('one character', 'Y'),
      ('61 characters', 'A${'b' * 60}'),
      ('61 code points', 'A${'\u{1DF00}' * 60}'),
      ('a letter number (Roman numeral twelve), before NFKC', '\u216b Smith'),
      ('a digit', 'Yazid2'),
      ('starting with an apostrophe', "'Yazid"),
      ('starting with a space-only name', '   '),
      ('another symbol', 'Yazid & Aqel'),
      ('Greek letters', '\u0391\u03bb\u03ad'),
      ('a leading BOM (Java strip() keeps U+FEFF)', '\ufeffYazid Aqel'),
      ('a trailing BOM', 'Yazid Aqel\ufeff'),
      ('U+001F inside the name', 'Yazid\u001fAqel'),
    ]) {
      test(why, () => expect(normalizeHolderName(input), isNull));
    }
  });

  // The Arabic cases of backend HolderNamesTest.arabic (BE-34, lot-4 Q66), one for one. Marks are escapes.
  group('normalizeHolderName, Arabic script', () {
    for (final (input, stored) in [
      (' يزيد  عقل ', 'يزيد عقل'),
      ('عبد-الله', 'عبد-الله'),
      ('ي\u064Eز\u0650يد ع\u064Eق\u0652ل', 'يزيد عقل'), // harakat
      ('محم\u0651د ه\u0670ذا', 'محمد هذا'), // shadda, superscript alef
      ('يز\u0640\u0640يد عقل', 'يزيد عقل'), // tatweel
      ('\uFEF3\uFEB0\uFEF4\uFEAA \uFECB\uFED8\uFEDE', 'يزيد عقل'), // presentation forms
      ('\uFEFB\uFEE1', 'لام'), // the lam-alef ligature
      ('ي${'ز' * 59}', 'ي${'ز' * 59}'), // 60 characters
      ('Yazid\u064E Aqel\u0651', 'Yazid Aqel'), // marks are stripped from Latin names too
      ('\uD835\uDC00b', 'Ab'), // a non-BMP letter NFKC'd to ASCII
    ]) {
      test('accepts ${input.codeUnits}', () => expect(normalizeHolderName(input), stored));
    }
    for (final (why, input) in [
      ('only marks and tatweel', '\u0640\u064E\u0640'),
      ('Latin then Arabic', 'Yazid عقل'),
      ('Arabic then Latin', 'يزيد Aqel'),
      ('an Arabic-Indic digit', 'يزيد \u0663'),
      ('an extended Arabic-Indic digit', 'يزيد \u06F3'),
      ('the Arabic comma', 'يزيد\u060C عقل'),
      ('a Western digit', 'يزيد 3'),
      ('one letter', 'ي'),
      ('starting with a hyphen', '-يزيد'),
      ('61 characters', 'ي${'ز' * 60}'),
      ('an apostrophe (Latin only)', "يزيد 'عقل"),
      ('an extended letter (gaf)', '\u06ADيزيد'),
      ('an extended letter (peh)', '\u067Eيزيد'),
      ('an extended letter (Farsi yeh)', 'يزي\u06CC'),
      ('an extended letter (keheh)', '\u06A9ريم'),
      ('a zero-width space', 'يزيد\u200Bعقل'),
      ('a zero-width non-joiner', 'يزيد\u200C عقل'),
      ('an Arabic letter mark', '\u061Cيزيد عقل'),
      ('a bidi isolate', '\u2068يزيد عقل\u2069'),
      ('a right-to-left mark', 'Yazid Aqel\u200F'),
      ('a BOM inside', 'Yazid\uFEFF Aqel'),
    ]) {
      test('refuses $why', () => expect(normalizeHolderName(input), isNull));
    }
  });

  group('return URL matcher', () {
    test('jomhor-fan://orders/{id} gives the id', () {
      expect(
        returnedOrderId('jomhor-fan://orders/0b8e7a52-0000-4000-8000-0000000000aa'),
        '0b8e7a52-0000-4000-8000-0000000000aa',
      );
    });

    for (final url in [
      'https://api.jomhor.ma/v1/payments/card/p1/return',
      'jomhor-fan://orders/',
      'jomhor-fan://orders/a/b',
      'jomhor-fan://orders/0B8E7A52-0000-4000-8000-0000000000AA', // upper case
      'jomhor-fan://orders/0b8e7a52-0000-4000-8000-0000000000aa?x=1',
      'jomhor-fan://orders/..%2Fevents%2F1%2Fcheckout%3Fprice%3Dpc-1-0%26qty%3D4',
      'jomhor-fan://orders/..%252Fevents%252F1%252Fcheckout%253Fprice%253Dpc-1-0%2526qty%253D4',
      'jomhor-fan://tickets/a',
      'other-app://orders/a',
      'https://evil.example/jomhor-fan://orders/a',
      'not a url',
    ]) {
      test('ignores $url', () => expect(returnedOrderId(url), isNull));
    }
  });

  test('places per order: the account limit or what is left, whichever is lower', () {
    expect(maxPlaces(perAccountLimit: 4, remaining: 100), 4);
    expect(maxPlaces(perAccountLimit: 4, remaining: 2), 2);
    expect(maxPlaces(perAccountLimit: 4, remaining: 0), 0);
  });

  group('payment page origin (https only in release)', () {
    const api = 'https://api.jomhor.ma/v1';
    test(
      'our API, https',
      () => expect(isPaymentPage('https://api.jomhor.ma/v1/payments/card/p1/redirect', apiBaseUrl: api), isTrue),
    );
    for (final url in [
      'https://evil.example/v1/payments/card/p1/redirect',
      'https://api.jomhor.ma.evil.example/v1/payments/card/p1/redirect',
      'https://api.jomhor.ma:8443/v1/payments/card/p1/redirect',
      'http://api.jomhor.ma/v1/payments/card/p1/redirect',
      'file:///data/x.html',
      'javascript:alert(1)',
      'not a url',
    ]) {
      test('refuses $url', () => expect(isPaymentPage(url, apiBaseUrl: api, release: true), isFalse));
    }
    test('http only outside release (the dev API default)', () {
      const url = 'http://10.0.2.2:8080/v1/payments/card/p1/redirect';
      expect(isPaymentPage(url), isTrue);
      expect(isPaymentPage(url, release: true), isFalse);
      expect(webSchemeAllowed('http', release: true), isFalse);
      expect(webSchemeAllowed('intent'), isFalse);
    });
  });

  group('payAgain', () {
    FanOrder order({String status = 'RESERVED', Object? payment = 'absent'}) => FanOrder.fromJson({
      'id': 'o1',
      'status': status,
      'total_mad': 100,
      'items': <Object?>[],
      if (payment != 'absent') 'payment_status': payment,
    });
    PayAgain again(FanOrder o, {bool waitedLong = false, bool holdOver = false}) =>
        payAgain(o, waitedLong: waitedLong, holdOver: holdOver);

    test('FAILED: failed; no attempt yet (null): notStarted; right away', () {
      expect(again(order(payment: 'FAILED')), PayAgain.failed);
      expect(again(order(payment: null)), PayAgain.notStarted);
    });

    test('PENDING reported: never, even after the wait (it may still succeed)', () {
      expect(again(order(payment: 'PENDING')), PayAgain.no);
      expect(again(order(payment: 'PENDING'), waitedLong: true), PayAgain.no);
    });

    test('payment_status not reported: only after the wait, with caution', () {
      expect(again(order()), PayAgain.no);
      expect(again(order(), waitedLong: true), PayAgain.withCaution);
    });

    test('SUCCEEDED, PAID or settled: never', () {
      expect(again(order(payment: 'SUCCEEDED'), waitedLong: true), PayAgain.no);
      expect(again(order(status: 'PAID'), waitedLong: true), PayAgain.no);
      expect(again(order(status: 'EXPIRED', payment: 'FAILED'), waitedLong: true), PayAgain.no);
    });

    test('hold over on server time: expired for FAILED or null, keep waiting for PENDING or unknown', () {
      expect(again(order(payment: 'FAILED'), holdOver: true), PayAgain.holdOver);
      expect(again(order(payment: null), holdOver: true), PayAgain.holdOver);
      expect(again(order(payment: 'PENDING'), holdOver: true, waitedLong: true), PayAgain.no);
      expect(again(order(), holdOver: true, waitedLong: true), PayAgain.no);
      expect(again(order(payment: 'SUCCEEDED'), holdOver: true), PayAgain.no);
    });
  });
}

/// `holder_label` of a normalized name, as the server computes it (backend HolderNames.label; the app only reads
/// labels, so this copy lives here, for the shared vectors): "Yazid aqel" → "Yazid A." (first word as typed,
/// last initial uppercased), "يزيد عقل" → "يزيد ع."; one word → as is.
String holderLabel(String name) {
  final words = name.split(' ');
  final last = _firstLetter(words.last).toUpperCase();
  return words.length == 1 || last.isEmpty ? words.first : '${words.first} $last.';
}

/// `holder_initials` of a normalized name: "Yazid Aqel" → "Y. A.", "يزيد عقل" → "ي. ع."; one word → "Y.".
String holderInitials(String name) {
  final words = name.split(' ');
  final first = _firstLetter(words.first).toUpperCase();
  final last = _firstLetter(words.last).toUpperCase();
  return words.length == 1 || last.isEmpty ? '$first.' : '$first. $last.';
}

final _letter = RegExp(r'\p{L}', unicode: true);

/// The first letter of a word (it may start with an apostrophe or a hyphen), or "".
String _firstLetter(String word) => _letter.firstMatch(word)?[0] ?? '';
