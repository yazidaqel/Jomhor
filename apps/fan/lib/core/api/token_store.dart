import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Where the fan session token (JWT from `/fan/auth/verify`) lives.
/// Tests override [tokenStoreProvider] with an in-memory store.
abstract interface class TokenStore {
  Future<String?> read();
  Future<void> write(String token);
  Future<void> clear();
}

/// Keystore (Android) / Keychain (iOS) backed store.
class SecureTokenStore implements TokenStore {
  new([
    this._storage = const FlutterSecureStorage(
      // Not restored to another device from a backup.
      iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
    ),
  ]);

  static const _key = 'fan_session_token';
  final FlutterSecureStorage _storage;

  @override
  Future<String?> read() => _storage.read(key: _key);

  @override
  Future<void> write(String token) => _storage.write(key: _key, value: token);

  @override
  Future<void> clear() => _storage.delete(key: _key);
}

final tokenStoreProvider = Provider<TokenStore>((ref) => SecureTokenStore());
