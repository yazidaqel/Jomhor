import 'dart:convert';
import 'dart:typed_data';

import 'package:jomhor_core/jomhor_core.dart';
import 'package:test/test.dart';

final kE = Uint8List.fromList(List.generate(32, (i) => 255 - i));
final ticketId = Uint8List.fromList(List.generate(16, (i) => i * 7));
const eventId = 0x01020304;
const zoneId = 0x0A0B;
const slot = 58000000;

String v1() => encodeStatic(kE, eventId, ticketId, zoneId);
String v2() => encodeRotating(deriveTicketKey(kE, ticketId), eventId, ticketId, zoneId, slot);

Uint8List raw(String t) => base64Url.decode(base64Url.normalize(t));
String b64(List<int> b) => base64Url.encode(b).replaceAll('=', '');

/// Flips the lowest bit of byte [i] and re-encodes.
String flip(String token, int i) {
  final b = raw(token);
  b[i] ^= 0x01;
  return b64(b);
}

ScanResult? check(String token, {int event = eventId, int now = slot}) =>
    verify(decode(token), eventId: event, eventKey: kE, nowSlot: now);

void main() {
  group('round-trip', () {
    test('v1', () {
      final t = v1();
      expect(raw(t), hasLength(39));
      expect(t, hasLength(52));
      expect(t, isNot(contains('=')));
      final p = decode(t);
      expect([p.version, p.eventId, p.ticketId, p.zoneId, p.slot], [1, eventId, ticketId, zoneId, null]);
      expect(p.tag, hasLength(16));
      expect(check(t), isNull);
    });

    test('v2', () {
      final t = v2();
      expect(raw(t), hasLength(43));
      expect(t, hasLength(58));
      expect(t, isNot(contains('=')));
      final p = decode(t);
      expect([p.version, p.eventId, p.ticketId, p.zoneId, p.slot], [2, eventId, ticketId, zoneId, slot]);
      expect(check(t), isNull);
    });
  });

  group('BAD_FORMAT', () {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
    final cases = <String, String Function()>{
      'empty': () => '',
      'invalid character': () => '${v1().substring(1)}!',
      'impossible length (1 mod 4)': () => '${v1()}A',
      'padding': () => '${v2()}==',
      'standard alphabet': () => b64(List.filled(39, 0xFB)).replaceAll('-', '+').replaceAll('_', '/'),
      'non-canonical trailing bits': () {
        final t = v2(); // 58 chars: the last char carries 4 unused bits
        return t.substring(0, 57) + alphabet[alphabet.indexOf(t[57]) ^ 0x01];
      },
      'unknown version 0': () => b64([0, ...raw(v1()).sublist(1)]),
      'unknown version 3': () => b64([3, ...raw(v2()).sublist(1)]),
      'v1 too short': () => b64(raw(v1()).sublist(0, 38)),
      'v1 too long': () => b64([...raw(v1()), 0]),
      'v1 with v2 length': () => b64([1, ...raw(v2()).sublist(1)]),
      'v2 with v1 length': () => b64([2, ...raw(v1()).sublist(1)]),
    };
    cases.forEach((name, token) {
      test(name, () {
        expect(
          () => decode(token()),
          throwsA(isA<TokenFormatException>().having((e) => e.result, 'result', ScanResult.BAD_FORMAT)),
        );
      });
    });
  });

  test('WRONG_EVENT', () {
    expect(check(v1(), event: eventId + 1), ScanResult.WRONG_EVENT);
    expect(check(v2(), event: eventId + 1), ScanResult.WRONG_EVENT);
  });

  group('check order', () {
    test('wrong event beats an out-of-window slot', () {
      expect(check(v2(), event: eventId + 1, now: slot + 5), ScanResult.WRONG_EVENT);
    });
    test('out-of-window slot beats a bad tag', () {
      expect(check(flip(v2(), 42), now: slot + 5), ScanResult.EXPIRED_SLOT);
    });
    test('v2 signed with K_e instead of K_t is BAD_SIG', () {
      expect(check(encodeRotating(kE, eventId, ticketId, zoneId, slot)), ScanResult.BAD_SIG);
    });
  });

  test('nowSlot outside 0..0xFFFFFFFF is a RangeError, not an overflowed accept', () {
    final zeroSlot = decode(encodeRotating(deriveTicketKey(kE, ticketId), eventId, ticketId, zoneId, 0));
    for (final now in [-1, 0x100000000, -0x8000000000000000]) {
      expect(() => verify(zeroSlot, eventId: eventId, eventKey: kE, nowSlot: now), throwsRangeError);
    }
  });

  group('slot window', () {
    for (final d in [-1, 0, 1]) {
      test('accepted at nowSlot $d', () => expect(check(v2(), now: slot + d), isNull));
    }
    for (final d in [-2, 2]) {
      test('EXPIRED_SLOT at nowSlot $d', () => expect(check(v2(), now: slot + d), ScanResult.EXPIRED_SLOT));
    }
    test('v1 ignores the slot', () => expect(check(v1(), now: 0), isNull));
  });

  group('BAD_SIG on a flipped bit', () {
    // Last byte of each field. A flipped event_id is checked against a loaded
    // event equal to the flipped value, to get past WRONG_EVENT; the slot's
    // low bit moves it by 1, still inside the window.
    for (final (name, token, tagStart) in [('v1', v1, 23), ('v2', v2, 27)]) {
      final bytes = {
        'event_id': 4,
        'ticket_id': 20,
        'zone_id': 22,
        if (name == 'v2') 'slot': 26,
        'tag first byte': tagStart,
        'tag last byte': tagStart + 15,
      };
      bytes.forEach((field, i) {
        test('$name $field', () {
          final t = flip(token(), i);
          expect(check(t, event: decode(t).eventId), ScanResult.BAD_SIG);
        });
      });
    }

    test('wrong event key', () {
      final other = Uint8List.fromList(List.filled(32, 1));
      expect(verify(decode(v1()), eventId: eventId, eventKey: other, nowSlot: slot), ScanResult.BAD_SIG);
      expect(verify(decode(v2()), eventId: eventId, eventKey: other, nowSlot: slot), ScanResult.BAD_SIG);
    });

    test('a flipped version byte is BAD_FORMAT', () {
      expect(() => decode(flip(v1(), 0)), throwsA(isA<TokenFormatException>()));
      expect(() => decode(flip(v2(), 0)), throwsA(isA<TokenFormatException>()));
    });
  });

  group('slotAt', () {
    test('floor(unix / 30)', () {
      expect(slotAt(DateTime.utc(1970)), 0);
      expect(slotAt(DateTime.fromMillisecondsSinceEpoch(29999, isUtc: true)), 0);
      expect(slotAt(DateTime.fromMillisecondsSinceEpoch(30000, isUtc: true)), 1);
      expect(slotAt(DateTime.utc(2026, 11, 6, 20)), 1793995200 ~/ 30);
    });
    test('time zone independent', () {
      final t = DateTime.utc(2026, 11, 6, 20, 0, 45);
      expect(slotAt(t.toLocal()), slotAt(t));
    });
  });

  test('encode rejects bad inputs', () {
    expect(() => encodeStatic(Uint8List(16), eventId, ticketId, zoneId), throwsArgumentError);
    expect(() => encodeStatic(kE, eventId, Uint8List(15), zoneId), throwsArgumentError);
    expect(() => encodeStatic(kE, -1, ticketId, zoneId), throwsRangeError);
    expect(() => encodeStatic(kE, eventId, ticketId, 0x10000), throwsRangeError);
    expect(() => encodeRotating(kE, eventId, ticketId, zoneId, 0x100000000), throwsRangeError);
  });
}
