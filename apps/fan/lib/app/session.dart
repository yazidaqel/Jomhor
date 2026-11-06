import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Whether a token was stored at startup. `main` overrides it with [startSession]'s result.
final initialSignedInProvider = Provider<bool>((ref) => false);

/// Signed in = a fan session token is stored. Signing out forgets it and wipes the ticket cache (K_t included)
/// locally; the server-side logout (`POST /fan/me/logout`) is FL-8.
class Session extends Notifier<bool> {
  @override
  bool build() => ref.read(initialSignedInProvider);

  Future<void> signIn(String token) async {
    await ref.read(tokenStoreProvider).write(token);
    state = true;
  }

  Future<void> signOut() async {
    try {
      // Both run even if one fails.
      await Future.wait([ref.read(tokenStoreProvider).clear(), ref.read(ticketCacheProvider).wipe()]);
    } finally {
      state = false;
    }
  }

  /// A request sent with [token] got a 401. Sign out only if that token is still the stored one:
  /// a late 401 for an old session must not end a newer one.
  Future<void> rejected(String token) async {
    if (await ref.read(tokenStoreProvider).read() == token) await signOut();
  }
}

final sessionProvider = NotifierProvider<Session, bool>(Session.new);

const _installedKey = 'installed';

/// Reads the stored token once at startup. The iOS Keychain outlives an uninstall but app
/// preferences do not: on the first launch after an install, drop any token left behind.
/// Never throws: if the Keystore/Keychain or the preferences fail (PlatformException, corrupt
/// store), the app starts signed out and the fan logs in again.
Future<bool> startSession(TokenStore tokens, SharedPreferencesAsync prefs) async {
  try {
    if (await prefs.getBool(_installedKey) != true) {
      await tokens.clear();
      await prefs.setBool(_installedKey, true);
      return false;
    }
    return await tokens.read() != null;
  } on Object {
    try {
      await tokens.clear();
    } on Object {
      // Nothing more to do: a token we cannot read is never sent either.
    }
    return false;
  }
}
