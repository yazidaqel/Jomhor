import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Follows live on the phone only (docs/04-api.md §Club directory: the server keeps no follow list).
// ponytail: device-only; server-side follows when a fan asks for them on a second phone.

const _idsKey = 'clubs.followed';
const _seenKey = 'clubs.seen';
const _chipKey = 'clubs.chip';

/// Accueil chips: `Mes clubs`, `Tout`; any other chip value is a followed club's id.
const chipMine = 'mine';
const chipAll = 'all';

/// `ids`: followed organizer ids, in follow order. `seen`: the picker was left once (it opens on first launch
/// until then). `chip`: the Accueil chip last chosen, null = the default.
typedef FollowsState = ({List<String> ids, bool seen, String? chip});

const FollowsState noFollows = (ids: [], seen: false, chip: null);

/// Read once at startup (`main` overrides it with [savedFollows]), like the saved locale.
final initialFollowsProvider = Provider<FollowsState>((ref) => noFollows);

/// Never throws: unreadable preferences mean a first launch (the picker shows again).
Future<FollowsState> savedFollows(SharedPreferencesAsync prefs) async {
  try {
    final (ids, seen, chip) = await (
      prefs.getStringList(_idsKey),
      prefs.getBool(_seenKey),
      prefs.getString(_chipKey),
    ).wait;
    return (ids: ids ?? const <String>[], seen: seen ?? false, chip: chip);
  } on Object {
    return noFollows;
  }
}

/// The chip Accueil shows: the remembered one while it still makes sense, else `Mes clubs` when following, else
/// `Tout`. A remembered club the fan no longer follows falls back to the default. [followedIds] come from the phone,
/// so the events filter never waits on the club directory.
String homeChip(String? remembered, List<String> followedIds) => switch (remembered) {
  chipAll => chipAll,
  final id? when id != chipMine && followedIds.contains(id) => id,
  _ => followedIds.isEmpty ? chipAll : chipMine,
};

class Follows extends Notifier<FollowsState> {
  @override
  FollowsState build() => ref.read(initialFollowsProvider);

  /// Picker "Suivre" / "Suivi".
  Future<void> toggle(String id) =>
      _set(ids: state.ids.contains(id) ? [...state.ids.where((i) => i != id)] : [...state.ids, id]);

  /// "Suivre" on an Accueil card (shown in `Tout` only): follows and keeps `Tout` selected, so the card turns to
  /// "Suivi" and the club's chip appears in the row.
  Future<void> follow(String id) =>
      _set(ids: state.ids.contains(id) ? state.ids : [...state.ids, id], chip: chipAll, keepChip: false);

  /// Leaving the picker on first launch; [chip] is where Accueil opens (`Tout` for "Tout voir").
  Future<void> markSeen({String? chip}) => _set(seen: true, chip: chip, keepChip: false);

  Future<void> choose(String chip) => _set(chip: chip, keepChip: false);

  /// Drops followed ids that are not in the directory any more.
  Future<void> retain(Set<String> known) => _set(ids: [...state.ids.where(known.contains)]);

  /// Changes the state at once, then saves it; awaiting it means it is on the phone (or could not be saved).
  Future<void> _set({List<String>? ids, bool? seen, String? chip, bool keepChip = true}) async {
    state = (ids: ids ?? state.ids, seen: seen ?? state.seen, chip: keepChip ? state.chip : chip);
    final s = state;
    try {
      final prefs = SharedPreferencesAsync();
      await Future.wait([
        prefs.setStringList(_idsKey, s.ids),
        prefs.setBool(_seenKey, s.seen),
        if (s.chip case final chip?) prefs.setString(_chipKey, chip) else prefs.remove(_chipKey),
      ]);
    } on Object {
      // Not saved: this launch keeps the change; the next one starts from what was saved.
    }
  }
}

final followsProvider = NotifierProvider<Follows, FollowsState>(Follows.new);
