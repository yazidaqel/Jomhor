import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';

/// K_e in memory, only in event mode. FL-12 loads it from `flutter_secure_storage` (Keystore-wrapped) after a
/// verified manifest; it is never written to SQLite, logs or `toString`.
final class EventKey {
  const new(this.bytes);

  final Uint8List bytes;

  @override
  String toString() => 'EventKey(redacted)';
}

class EventKeyHolder extends Notifier<EventKey?> {
  @override
  EventKey? build() => null;

  /// K_e is 32 bytes (docs/04 §Token); anything else is refused before it can reach the scan path.
  void load(Uint8List bytes) {
    if (bytes.length != 32) throw ArgumentError.value(bytes.length, 'bytes', 'K_e must be 32 bytes');
    wipe();
    state = EventKey(Uint8List.fromList(bytes));
  }

  /// Zeroes the bytes before dropping them (event close, revocation).
  void wipe() {
    state?.bytes.fillRange(0, state!.bytes.length, 0);
    state = null;
  }
}

final eventKeyProvider = NotifierProvider<EventKeyHolder, EventKey?>(EventKeyHolder.new);
