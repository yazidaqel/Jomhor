import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/club/membership.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/clubs/clubs_repository.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';

/// `/fan/me/membership` (docs/04-api.md §Fan). PUT answers with the GET shape.
class ClubRepository {
  new(this._api);

  final ApiClient _api;

  Future<Membership> membership(String orgId) async =>
      Membership.fromJson((await _api.get('/fan/me/membership', query: {'organizer': orgId}))! as Map<String, Object?>);

  /// Joins (201) or changes the opt-ins (200); both booleans every time.
  Future<Membership> save(String orgId, {required bool push, required bool whatsapp}) async => Membership.fromJson(
    (await _api.put(
          '/fan/me/membership',
          body: {'organizer_id': orgId, 'marketing_push': push, 'marketing_whatsapp': whatsapp},
        ))!
        as Map<String, Object?>,
  );

  Future<void> leave(String orgId) => _api.delete('/fan/me/membership', query: {'organizer': orgId});
}

final clubRepositoryProvider = Provider<ClubRepository>((ref) => ClubRepository(ref.watch(apiClientProvider)));

/// Read while signed out: the tab is behind the login and the strip checks the session first, so nothing waits on
/// it; it fails at once instead of hanging.
class SignedOut implements Exception {
  const new();
}

/// One club's membership, read once per session and shared by the Club tab and the Accueil strip (not autoDispose).
/// A join, an opt-in change or a leave sets it from the server's answer, with no second read.
class MembershipNotifier extends AsyncNotifier<Membership> {
  new(this.orgId);

  final String orgId;

  ClubRepository get _repo => ref.read(clubRepositoryProvider);

  @override
  Future<Membership> build() async {
    if (!ref.watch(sessionProvider)) throw const SignedOut();
    return await _repo.membership(orgId);
  }

  /// Join or opt-in change: the PUT answer becomes the state. On an [ApiError] (rethrown) the state is unchanged.
  Future<void> save({required bool push, required bool whatsapp}) async {
    final m = await _repo.save(orgId, push: push, whatsapp: whatsapp);
    if (ref.mounted && ref.read(sessionProvider)) state = AsyncData(m);
  }

  /// Leave (204): no longer a member, the loyalty row stays. On an [ApiError] (rethrown) the state is unchanged.
  Future<void> leave() async {
    await _repo.leave(orgId);
    if (!ref.mounted || !ref.read(sessionProvider)) return;
    if (state.value case final m?) {
      state = AsyncData(Membership(loyalty: m.loyalty));
    } else {
      ref.invalidateSelf();
    }
  }
}

final AsyncNotifierProviderFamily<MembershipNotifier, Membership, String> membershipProvider =
    AsyncNotifierProvider.family<MembershipNotifier, Membership, String>(MembershipNotifier.new);

/// The twelfth dot of each followed club (lot-4 Q96): one public `GET /fan/events` with every followed organizer,
/// the first row per organizer. Clubs with no upcoming event are absent.
final FutureProvider<Map<String, FanEvent>> nextEventsProvider = FutureProvider<Map<String, FanEvent>>((ref) async {
  // Joined: a new chip choice (same ids) does not read again.
  final ids = ref.watch(followsProvider.select((f) => f.ids.join(',')));
  if (ids.isEmpty) return const {};
  final next = <String, FanEvent>{};
  for (final e in await ref.watch(eventsRepositoryProvider).upcoming(ids.split(','))) {
    next.putIfAbsent(e.organizerId, () => e);
  }
  return next;
});

/// The Accueil member strip: the first followed club, in follow order, where the fan is a member; null when none
/// (or signed out). A club whose membership fails to load is skipped.
final FutureProvider<(Club, Membership)?> memberStripProvider = FutureProvider<(Club, Membership)?>((ref) async {
  if (!ref.watch(sessionProvider)) return null;
  final ids = ref.watch(followsProvider.select((f) => f.ids.join(',')));
  if (ids.isEmpty) return null;
  // Every read starts at once; the order decides, not the first answer.
  final reads = [
    for (final id in ids.split(','))
      ref.watch(membershipProvider(id).future).then<Membership?>((m) => m, onError: (Object _) => null),
  ];
  final clubs = {for (final c in await ref.watch(followedClubsProvider.future)) c.id: c};
  for (final (i, id) in ids.split(',').indexed) {
    final m = await reads[i];
    if (m?.member != null && clubs[id] != null) return (clubs[id]!, m!);
  }
  return null;
});
