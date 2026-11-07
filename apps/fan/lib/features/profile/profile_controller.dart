import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart';

/// The Profil tab: the signed-in fan (`GET /fan/me`) and the name edit. Logout is [Session.logout].
class Profile extends AsyncNotifier<FanUser> {
  @override
  Future<FanUser> build() {
    // Rebuilt on sign-in/out, so another fan never sees the previous one. Signed out: no unauthenticated GET;
    // the router is already leaving the tab for the login.
    if (!ref.watch(sessionProvider)) return Completer<FanUser>().future;
    return ref.read(authRepositoryProvider).me();
  }

  /// `PATCH /fan/me`. Returns null when saved (the state then holds the name as the server stored it), else the
  /// error; see [isBadName].
  Future<ApiError?> saveName(String name) async {
    try {
      state = AsyncData(await ref.read(authRepositoryProvider).setDisplayName(name));
      return null;
    } on ApiError catch (e) {
      return e;
    } on Object {
      return const ApiError(status: 0, code: ApiError.unexpectedResponse, message: 'Bad /fan/me answer');
    }
  }
}

/// The 422 of `PATCH /fan/me` on `display_name` (docs/04-api.md §Holder names).
bool isBadName(ApiError e) =>
    e.code == 'VALIDATION_FAILED' &&
    e.details['fields'] is Map &&
    (e.details['fields']! as Map).containsKey('display_name');

final AsyncNotifierProvider<Profile, FanUser> profileProvider = AsyncNotifierProvider.autoDispose<Profile, FanUser>(
  Profile.new,
);

/// "Yazid" for "Yazid Aqel" (the Accueil greeting); null without a name.
String? firstName(String? displayName) {
  final name = displayName?.trim() ?? '';
  return name.isEmpty ? null : name.split(RegExp(r'\s+')).first;
}

/// "YA" for "Yazid Aqel", "Y" for "Yazid": the avatar on Accueil and Profil; null without a name.
String? initials(String? displayName) {
  final words = (displayName?.trim() ?? '').split(RegExp(r'\s+')).where((w) => w.isNotEmpty).take(2);
  return words.isEmpty ? null : words.map((w) => String.fromCharCode(w.runes.first).toUpperCase()).join();
}
