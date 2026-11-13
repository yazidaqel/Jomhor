import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/inbox/inbox.dart';
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

  test('signOut never throws: a Keystore failure still signs out', () async {
    final container = ProviderContainer.test(
      overrides: [
        tokenStoreProvider.overrideWithValue(_BrokenStore()),
        initialSignedInProvider.overrideWithValue(true),
      ],
    );
    await container.read(sessionProvider.notifier).signOut();
    expect(container.read(sessionProvider), isFalse);
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

  test('signOut deletes the push token on the phone and wipes push.sent and inbox.seen (lot-4 Q122, Q124)', () async {
    final prefs = SharedPreferencesAsync();
    await prefs.setString(pushSentKey, 'fcm-token-1');
    await prefs.setString(inboxSeenKey, '2027-02-20T11:00:00.000Z');
    await prefs.setBool(pushAskedKey, true);
    final push = FakePushMessaging();
    final container = ProviderContainer.test(
      overrides: [
        tokenStoreProvider.overrideWithValue(MemoryTokenStore('jwt')),
        initialSignedInProvider.overrideWithValue(true),
        pushMessagingProvider.overrideWithValue(push),
      ],
    );
    await container.read(sessionProvider.notifier).signOut();
    expect(push.count('deleteToken'), 1);
    expect(await prefs.getString(pushSentKey), isNull);
    expect(await prefs.getString(inboxSeenKey), isNull);
    expect(await prefs.getBool(pushAskedKey), isTrue, reason: 'asked once per install, not per session');
  });

  for (final (name, push) in [('fails (offline)', _OfflinePush()), ('never answers', _HangingPush())]) {
    test('a deleteToken that $name does not hold the sign-out up', () async {
      final container = ProviderContainer.test(
        overrides: [
          tokenStoreProvider.overrideWithValue(MemoryTokenStore('jwt')),
          initialSignedInProvider.overrideWithValue(true),
          pushMessagingProvider.overrideWithValue(push),
        ],
      );
      await container.read(sessionProvider.notifier).signOut().timeout(const Duration(seconds: 1));
      expect(container.read(sessionProvider), isFalse);
    });
  }
}

class _OfflinePush extends FakePushMessaging {
  @override
  Future<void> deleteToken() async => throw PlatformException(code: 'SERVICE_NOT_AVAILABLE');
}

class _HangingPush extends FakePushMessaging {
  @override
  Future<void> deleteToken() => Completer<void>().future;
}

class _BrokenStore implements TokenStore {
  @override
  Future<String?> read() => throw PlatformException(code: 'BAD_DECRYPT');

  @override
  Future<void> write(String token) => throw PlatformException(code: 'BAD_DECRYPT');

  @override
  Future<void> clear() => throw PlatformException(code: 'BAD_DECRYPT');
}
