import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// K_e in memory, only in event mode. Persisted only in `flutter_secure_storage` (AES-GCM with the key wrapped
/// by Android Keystore); never written to SQLite, logs or `toString`.
final class EventKey {
  const new(this.bytes);

  final Uint8List bytes;

  @override
  String toString() => 'EventKey(redacted)';
}

/// The Keystore-backed store for K_e and the PIN lockout. Tests use `FlutterSecureStorage.setMockInitialValues`.
final secureStorageProvider = Provider<FlutterSecureStorage>((ref) => const FlutterSecureStorage());

class EventKeyHolder extends Notifier<EventKey?> {
  /// The secure-storage entry. One event at a time: the next full manifest overwrites it.
  static const storageKey = 'event_key';

  @override
  EventKey? build() => null;

  FlutterSecureStorage get _storage => ref.read(secureStorageProvider);

  /// K_e is 32 bytes (docs/04 §Token); anything else is refused before it can reach the scan path.
  void load(Uint8List bytes) {
    if (bytes.length != 32) throw ArgumentError.value(bytes.length, 'bytes', 'K_e must be 32 bytes');
    wipe();
    state = EventKey(Uint8List.fromList(bytes));
  }

  /// K_e from a verified full manifest: into secure storage, then memory.
  Future<void> store(Uint8List bytes) async {
    if (bytes.length != 32) throw ArgumentError.value(bytes.length, 'bytes', 'K_e must be 32 bytes');
    await _storage.write(key: storageKey, value: base64Url.encode(bytes));
    load(bytes);
  }

  /// App start in event mode: the stored K_e back into memory. False when none is stored.
  Future<bool> restore() async {
    final stored = await _storage.read(key: storageKey);
    if (stored == null) return false;
    load(base64Url.decode(stored));
    return true;
  }

  /// Zeroes the bytes before dropping them from memory.
  void wipe() {
    state?.bytes.fillRange(0, state!.bytes.length, 0);
    state = null;
  }

  /// Event close and revocation: memory and secure storage.
  Future<void> erase() async {
    wipe();
    await _storage.delete(key: storageKey);
  }
}

final eventKeyProvider = NotifierProvider<EventKeyHolder, EventKey?>(EventKeyHolder.new);
