import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/features/checkout/card_payment.dart';
import 'package:jomhor_fan/features/checkout/checkout_controller.dart';
import 'package:jomhor_fan/features/checkout/holder_name.dart';
import 'package:jomhor_fan/features/checkout/order.dart';

/// docs/04-api.md §Holder names, case by case (same order as backend HolderNames.normalize).
void main() {
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
      ('a letter number (Roman numeral twelve), before NFKC', '\u216b Smith'),
      ('Arabic script (Lot 4)', '\u0645\u062d\u0645\u062f'),
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
