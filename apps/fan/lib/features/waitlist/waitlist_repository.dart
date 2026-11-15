// The sold-out waitlist (docs/04-api.md §Waitlist, `/fan/waitlist/{event_id}`): notify only, no hold, no priority.
// Hand-written like events/event.dart.

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/events/event.dart';

/// The caller's `entry` of an event's waitlist.
class WaitlistEntry {
  const new({required this.joinedAt, this.notifiedAt});

  final DateTime joinedAt;

  /// Set once the job sent the notice; joining again then puts the fan at the back.
  final DateTime? notifiedAt;

  /// On the list and not yet told: screens 1 and 2 show "Sur la liste d'attente".
  bool get waiting => notifiedAt == null;
}

/// `{entry: {joined_at, notified_at} | null}`, the answer of GET and PUT.
WaitlistEntry? _entry(Object? body) => switch ((body! as Map<String, Object?>)['entry']) {
  final Map<String, Object?> e => WaitlistEntry(
    joinedAt: DateTime.parse(e['joined_at']! as String).toUtc(),
    notifiedAt: switch (e['notified_at']) {
      final String t => DateTime.parse(t).toUtc(),
      _ => null,
    },
  ),
  _ => null,
};

class WaitlistRepository {
  new(this._api);

  final ApiClient _api;

  Future<WaitlistEntry?> get(int eventId) async => _entry(await _api.get('/fan/waitlist/$eventId'));

  /// 201 new or moved to the back, 200 already waiting; 409 `WAITLIST_CLOSED` when the event can't take it.
  Future<WaitlistEntry?> join(int eventId) async => _entry(await _api.put('/fan/waitlist/$eventId'));

  /// 204, idempotent.
  Future<void> leave(int eventId) => _api.delete('/fan/waitlist/$eventId');
}

final waitlistRepositoryProvider = Provider<WaitlistRepository>(
  (ref) => WaitlistRepository(ref.watch(apiClientProvider)),
);

/// Screens 1 and 2 offer the waitlist: on sale, sold out, and no waiting-room phase left (DESIGN.md Lot 5; the room
/// is the queue while a phase is live, lot-5 Q179).
bool waitlistOffered(FanEvent e, DateTime now) => e.room == null && e.saleState(now) == SaleState.soldOut;
