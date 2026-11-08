import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';

class ClubsRepository {
  new(this._api);

  final ApiClient _api;

  /// The listed clubs, by name (public: no token sent).
  Future<List<Club>> directory() async => [
    for (final c in (await _api.getPublic('/fan/organizers'))! as List<Object?>)
      Club.fromJson(c! as Map<String, Object?>),
  ];
}

final clubsRepositoryProvider = Provider<ClubsRepository>((ref) => ClubsRepository(ref.watch(apiClientProvider)));

/// The club directory, read once per launch; a failed read stays until a "Réessayer" invalidates it.
final FutureProvider<List<Club>> clubsProvider = FutureProvider<List<Club>>(
  (ref) => ref.watch(clubsRepositoryProvider).directory(),
);

/// The followed clubs, in follow order. Ids missing from the directory (unlisted since) are dropped, here and on
/// the phone (docs/plans/multi-club.md Q75), but only against a fresh read: the launch's directory may predate a
/// club listed since and followed from a `Tout` card. No follows: no directory read.
final FutureProvider<List<Club>> followedClubsProvider = FutureProvider<List<Club>>((ref) async {
  final ids = ref.watch(followsProvider.select((f) => f.ids));
  if (ids.isEmpty) return const [];
  var byId = {for (final c in await ref.watch(clubsProvider.future)) c.id: c};
  if (ids.any((id) => !byId.containsKey(id))) {
    try {
      // Read directly, not by invalidating clubsProvider: this provider watches it and would loop.
      byId = {for (final c in await ref.read(clubsRepositoryProvider).directory()) c.id: c};
      if (ids.any((id) => !byId.containsKey(id))) await ref.read(followsProvider.notifier).retain(byId.keys.toSet());
    } on Object {
      // Offline: every follow is kept; the missing ones just get no chip this time.
    }
  }
  return [for (final id in ids) ?byId[id]];
});

/// What Accueil lists: the followed clubs (the chips) and the chip shown.
typedef HomeView = ({List<Club> followed, String chip});

final FutureProvider<HomeView> homeViewProvider = FutureProvider<HomeView>((ref) async {
  final remembered = ref.watch(followsProvider.select((f) => f.chip));
  final followed = await ref.watch(followedClubsProvider.future);
  return (followed: followed, chip: homeChip(remembered, followed));
});

/// The `organizer` filter of [view]: none for `Tout` (every listed club), else the chip's club or every followed one.
List<String>? organizersOf(HomeView view) => switch (view.chip) {
  chipAll => null,
  chipMine => [for (final c in view.followed) c.id],
  final id => [id],
};
