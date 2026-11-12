import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// What this phone has already shown once (a figure rolled, a dot filled, a check settled), as a set of ids under
/// one preferences [key]. No personal data; wiped on sign-out ([wipeSeen]).
class SeenSet extends AsyncNotifier<Set<String>> {
  new(this.key);

  final String key;

  @override
  Future<Set<String>> build() async {
    if (!ref.watch(sessionProvider)) {
      await wipeSeen(key);
      return const {};
    }
    try {
      return {...?await SharedPreferencesAsync().getStringList(key)};
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
      await SharedPreferencesAsync().setStringList(key, [...all]);
      // A sign-out that ran during the write must not leave it behind.
      if (!ref.mounted || !ref.read(sessionProvider)) await wipeSeen(key);
    } on Object {
      // Not saved: this run remembers it; after a restart it plays once more.
    }
  }
}

/// Never throws.
Future<void> wipeSeen(String key) async {
  try {
    await SharedPreferencesAsync().remove(key);
  } on Object {
    // Preferences unavailable: nothing was saved either.
  }
}
