/// Jomhor ticket token codec, shared by the fan app, the scanner and tools.
///
/// Byte layout (docs/04-api.md §Token, docs/02 §Ticket token & QR format):
///
/// ```
/// version(1) | event_id(4, BE) | ticket_id(16) | zone_id(2, BE) | [slot(4, BE)] | tag(16)
/// ```
///
/// v1 (static, PDF/print) is 39 bytes, v2 (rotating, fan app) is 43 bytes,
/// both encoded as base64url without padding (52 / 58 characters).
/// A token carries no personal data. Nothing in this library logs keys.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// Scan result codes, named exactly as in docs/02 §Offline validation
/// algorithm and docs/06-glossary.md. [Enum.name] is the wire value.
// ignore_for_file: constant_identifier_names
enum ScanResult {
  OK,
  DUPLICATE,
  BAD_SIG,
  BAD_FORMAT,
  UNKNOWN,
  CANCELLED,
  BLOCKED,
  WRONG_ZONE,
  WRONG_EVENT,
  EXPIRED_SLOT,
  OVERRIDE,
}

/// Version byte of a static token (PDF / print).
const int versionStatic = 0x01;

/// Version byte of a rotating token (fan app).
const int versionRotating = 0x02;

/// Length in seconds of one rotating-token slot.
const int slotSeconds = 30;

const int _tagLength = 16;
const int _ticketIdLength = 16;
const int _keyLength = 32;
const int _v1Length = 39;
const int _v2Length = 43;

/// Thrown by [decode] when the input is not a well-formed Jomhor token.
/// The scanner maps it to [ScanResult.BAD_FORMAT].
final class TokenFormatException implements Exception {
  TokenFormatException(this.reason);

  /// Short, key-free description of what was wrong (safe to log).
  final String reason;

  /// Always [ScanResult.BAD_FORMAT].
  ScanResult get result => ScanResult.BAD_FORMAT;

  @override
  String toString() => 'TokenFormatException: $reason';
}

/// A structurally valid token. Authenticity is checked by [verify], not here.
final class ParsedToken {
  ParsedToken._(this._bytes);

  final Uint8List _bytes;

  ByteData get _view => ByteData.sublistView(_bytes);

  /// [versionStatic] or [versionRotating].
  int get version => _bytes[0];

  /// Event id (unsigned 32-bit).
  int get eventId => _view.getUint32(1);

  /// Ticket id, 16 bytes (UUID). A copy.
  Uint8List get ticketId => Uint8List.fromList(_bytes.sublist(5, 21));

  /// Zone id (unsigned 16-bit).
  int get zoneId => _view.getUint16(21);

  /// Slot (unsigned 32-bit) for v2; null for v1.
  int? get slot => version == versionRotating ? _view.getUint32(23) : null;

  /// Truncated HMAC tag, 16 bytes. A copy.
  Uint8List get tag => Uint8List.fromList(_bytes.sublist(_bytes.length - _tagLength));

  /// The signed part: every byte before the tag.
  Uint8List get _signed => Uint8List.sublistView(_bytes, 0, _bytes.length - _tagLength);
}

/// K_t = HMAC-SHA256(K_e, ticket_id), 32 bytes.
///
/// [eventKey] must be 32 bytes and [ticketId] 16 bytes, else [ArgumentError].
Uint8List deriveTicketKey(Uint8List eventKey, Uint8List ticketId) {
  _checkLength(eventKey, _keyLength, 'eventKey');
  _checkLength(ticketId, _ticketIdLength, 'ticketId');
  return Uint8List.fromList(Hmac(sha256, eventKey).convert(ticketId).bytes);
}

/// Encodes a v1 static token signed with the event key K_e.
String encodeStatic(Uint8List eventKey, int eventId, Uint8List ticketId, int zoneId) {
  _checkLength(eventKey, _keyLength, 'eventKey');
  final body = _body(versionStatic, eventId, ticketId, zoneId, null);
  return _encode(body, _tag(eventKey, body));
}

/// Encodes a v2 rotating token signed with the ticket key K_t (see
/// [deriveTicketKey]); the fan app only ever holds K_t, never K_e.
String encodeRotating(Uint8List ticketKey, int eventId, Uint8List ticketId, int zoneId, int slot) {
  _checkLength(ticketKey, _keyLength, 'ticketKey');
  final body = _body(versionRotating, eventId, ticketId, zoneId, slot);
  return _encode(body, _tag(ticketKey, body));
}

/// Parses [token] (steps 1-2 of the offline algorithm): canonical base64url
/// without padding, known version, exact length for that version.
/// Throws [TokenFormatException] otherwise.
ParsedToken decode(String token) {
  final Uint8List bytes;
  try {
    bytes = base64Url.decode(base64Url.normalize(token));
  } on FormatException {
    throw TokenFormatException('not base64url');
  }
  // Rejects padding, the '+' '/' alphabet and non-zero trailing bits, so
  // exactly one string maps to a given token.
  if (_b64(bytes) != token) throw TokenFormatException('not canonical base64url');
  if (bytes.isEmpty) throw TokenFormatException('empty');
  final expected = switch (bytes[0]) {
    versionStatic => _v1Length,
    versionRotating => _v2Length,
    _ => throw TokenFormatException('unknown version'),
  };
  if (bytes.length != expected) throw TokenFormatException('wrong length for version');
  return ParsedToken._(bytes);
}

/// Steps 3-4 of the offline algorithm for one loaded event.
///
/// Returns the first failure ([ScanResult.WRONG_EVENT],
/// [ScanResult.EXPIRED_SLOT] for v2 outside `nowSlot ± 1`, or
/// [ScanResult.BAD_SIG]), or null when the token is authentic for
/// [eventId]. Null is not [ScanResult.OK]: manifest, zone and used-set checks
/// (steps 5-8) remain. [nowSlot] is the server-time-corrected [slotAt], in
/// 0..0xFFFFFFFF like the token's slot field, else [RangeError].
ScanResult? verify(ParsedToken token, {required int eventId, required Uint8List eventKey, required int nowSlot}) {
  // Bounding nowSlot keeps `slot - nowSlot` below int overflow.
  _checkRange(nowSlot, 0xFFFFFFFF, 'nowSlot');
  if (token.eventId != eventId) return ScanResult.WRONG_EVENT;
  final Uint8List key;
  if (token.version == versionRotating) {
    if ((token.slot! - nowSlot).abs() > 1) return ScanResult.EXPIRED_SLOT;
    key = deriveTicketKey(eventKey, token.ticketId);
  } else {
    _checkLength(eventKey, _keyLength, 'eventKey');
    key = eventKey;
  }
  return _constantTimeEquals(_tag(key, token._signed), token.tag) ? null : ScanResult.BAD_SIG;
}

/// Slot number at [time]: floor(unix_seconds / 30). Time zone does not matter.
int slotAt(DateTime time) => (time.millisecondsSinceEpoch / (slotSeconds * 1000)).floor();

Uint8List _body(int version, int eventId, Uint8List ticketId, int zoneId, int? slot) {
  _checkLength(ticketId, _ticketIdLength, 'ticketId');
  _checkRange(eventId, 0xFFFFFFFF, 'eventId');
  _checkRange(zoneId, 0xFFFF, 'zoneId');
  final body = ByteData(slot == null ? _v1Length - _tagLength : _v2Length - _tagLength)
    ..setUint8(0, version)
    ..setUint32(1, eventId)
    ..setUint16(21, zoneId);
  if (slot != null) {
    _checkRange(slot, 0xFFFFFFFF, 'slot');
    body.setUint32(23, slot);
  }
  final bytes = body.buffer.asUint8List();
  bytes.setRange(5, 21, ticketId);
  return bytes;
}

Uint8List _tag(Uint8List key, Uint8List body) =>
    Uint8List.fromList(Hmac(sha256, key).convert(body).bytes.sublist(0, _tagLength));

String _encode(Uint8List body, Uint8List tag) => _b64(Uint8List.fromList([...body, ...tag]));

String _b64(Uint8List bytes) => base64Url.encode(bytes).replaceAll('=', '');

/// Runs over every byte whatever the content, so timing leaks no tag prefix.
bool _constantTimeEquals(Uint8List a, Uint8List b) {
  if (a.length != b.length) return false;
  var diff = 0;
  for (var i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff == 0;
}

void _checkLength(Uint8List bytes, int length, String name) {
  if (bytes.length != length) throw ArgumentError.value(bytes.length, name, 'must be $length bytes');
}

void _checkRange(int value, int max, String name) => RangeError.checkValueInInterval(value, 0, max, name);
