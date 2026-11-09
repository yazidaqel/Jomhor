import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _seenKey = 'club.seen';

/// What screen 7 has already shown on this phone (lot-4 Q95), so the member number rolls once and a newly attended
/// dot fills once: organizer ids (the number was revealed) and `orgId:eventId` (that dot was filled). No personal
/// data; wiped on sign-out ([wipeClubSeen]).
class ClubSeen extends AsyncNotifier<Set<String>> {
  @override
  Future<Set<String>> build() async {
    if (!ref.watch(sessionProvider)) {
      await wipeClubSeen();
      return const {};
    }
    try {
      return {...?await SharedPreferencesAsync().getStringList(_seenKey)};
    } on Object {
      return const {}; // Unreadable: shown again once, then saved.
    }
  }

  /// Saves [keys] once shown.
  Future<void> add(Iterable<String> keys) async {
    await future;
    // Read after the wait: another add of the same frame may have landed first.
    final known = state.value ?? const <String>{};
    final fresh = {...keys.where((k) => !known.contains(k))};
    if (fresh.isEmpty || !ref.mounted || !ref.read(sessionProvider)) return;
    final all = {...known, ...fresh};
    state = AsyncData(all);
    try {
      await SharedPreferencesAsync().setStringList(_seenKey, [...all]);
      // A sign-out that ran during the write must not leave it behind.
      if (!ref.mounted || !ref.read(sessionProvider)) await wipeClubSeen();
    } on Object {
      // Not saved: this run remembers it; after a restart it plays once more.
    }
  }
}

final clubSeenProvider = AsyncNotifierProvider<ClubSeen, Set<String>>(ClubSeen.new);

/// The dot of [eventId] was filled.
String seenDotKey(String orgId, int eventId) => '$orgId:$eventId';

/// Never throws.
Future<void> wipeClubSeen() async {
  try {
    await SharedPreferencesAsync().remove(_seenKey);
  } on Object {
    // Preferences unavailable: nothing was saved either.
  }
}
