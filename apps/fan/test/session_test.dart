import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import 'helpers.dart';

void main() {
  setUp(() {
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.empty();
    FlutterSecureStorage.setMockInitialValues({});
  });

  test('first launch after an install drops a token left in the Keychain', () async {
    final tokens = MemoryTokenStore('left-over-from-a-previous-install');
    expect(await startSession(tokens, SharedPreferencesAsync()), isFalse);
    expect(tokens.token, isNull);
  });

  test('later launches keep the stored token', () async {
    final prefs = SharedPreferencesAsync();
    final tokens = MemoryTokenStore();
    await startSession(tokens, prefs);
    await tokens.write('jwt');
    expect(await startSession(tokens, prefs), isTrue);
    expect(tokens.token, 'jwt');
  });

  test('a Keystore/Keychain failure at startup starts signed out instead of blocking the app', () async {
    final prefs = SharedPreferencesAsync();
    await prefs.setBool('installed', true);
    expect(await startSession(_BrokenStore(), prefs), isFalse);
  });

  test('a 401 for an old token does not end a newer session', () async {
    final tokens = MemoryTokenStore('new');
    final container = ProviderContainer.test(
      overrides: [tokenStoreProvider.overrideWithValue(tokens), initialSignedInProvider.overrideWithValue(true)],
    );
    await container.read(sessionProvider.notifier).rejected('old');
    expect((container.read(sessionProvider), tokens.token), (true, 'new'));
    await container.read(sessionProvider.notifier).rejected('new');
    expect((container.read(sessionProvider), tokens.token), (false, null));
  });
}

class _BrokenStore implements TokenStore {
  @override
  Future<String?> read() => throw PlatformException(code: 'BAD_DECRYPT');

  @override
  Future<void> write(String token) => throw PlatformException(code: 'BAD_DECRYPT');

  @override
  Future<void> clear() => throw PlatformException(code: 'BAD_DECRYPT');
}
