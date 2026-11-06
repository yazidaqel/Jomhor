import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/features/auth/phone.dart';

void main() {
  group('normalizePhone (same cases as web/shop lib/phone.test.ts)', () {
    for (final input in [
      '06 12 34 56 78',
      '0612345678',
      '+212 6 12 34 56 78',
      '212612345678',
      '00212612345678',
      '06.12.34.56.78',
      '06-12-34-56-78',
      '+212 (0)6 12 34 56 78',
      '\u0660\u0666\u0661\u0662\u0663\u0664\u0665\u0666\u0667\u0668', // Arabic-Indic
      '\u06f0\u06f6\u06f1\u06f2\u06f3\u06f4\u06f5\u06f6\u06f7\u06f8', // extended
      '\u200e+212 6 12 34 56 78\u200f', // LRM / RLM
      '\u202b\u202d06 12 34 56 78\u202c\u202a\u202e', // embeddings / overrides
      '\u206606 12 34 56 78\u2069\u061c', // isolates, ALM
    ]) {
      test('normalizes ${input.codeUnits} to E.164', () => expect(normalizePhone(input), '+212612345678'));
    }

    for (final input in ['', 'abc', '061234567', '06123456789', '0812345678', '+33612345678', '+21200612345678']) {
      test('rejects "$input"', () => expect(normalizePhone(input), isNull));
    }

    test('ranges 5 and 7', () {
      expect(normalizePhone('0522 12 34 56'), '+212522123456');
      expect(normalizePhone('07 00 11 22 33'), '+212700112233');
    });
  });

  group('formatDh (same cases as web/shop lib/money.test.ts)', () {
    const n = '\u202f'; // thousands
    const s = '\u00a0'; // before DH
    for (final (centimes, text) in [
      (5050, '50,50${s}DH'),
      (5000, '50${s}DH'),
      (5005, '50,05${s}DH'),
      (5, '0,05${s}DH'),
      (0, '0${s}DH'),
      (99, '0,99${s}DH'),
      (100000, '1${n}000${s}DH'),
      (123456, '1${n}234,56${s}DH'),
      (-5050, '-50,50${s}DH'),
      (9007199254740991, '90${n}071${n}992${n}547${n}409,91${s}DH'),
    ]) {
      test('$centimes', () => expect(formatDh(centimes), text));
    }
  });
}
