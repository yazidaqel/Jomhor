import 'dart:async';
import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/clubs/clubs_repository.dart';
import 'package:jomhor_fan/features/clubs/follows.dart';
import 'package:jomhor_fan/features/events/event.dart';

class EventsRepository {
  new(this._api, this._now);

  final ApiClient _api;
  final DateTime Function() _now;

  /// Upcoming events by `starts_at` (public: no token sent). [organizers] null = every listed club (`Tout`), else
  /// those clubs (`organizer` repeated).
  Future<List<FanEvent>> upcoming(List<String>? organizers) async {
    final res = await _api.getPublicResponse('/fan/events', query: {'organizer': ?organizers});
    // Server time from the `Date` header: a fast or slow device clock does not move the opening.
    final fetchedAt = res.date ?? _now();
    return [
      for (final e in res.body! as List<Object?>) FanEvent.fromJson(e! as Map<String, Object?>, fetchedAt: fetchedAt),
    ];
  }

  /// One event, of any club (listed or not).
  Future<EventDetail> detail(int id) async {
    final res = await _api.getPublicResponse('/fan/events/$id');
    return EventDetail.fromJson(res.body! as Map<String, Object?>, fetchedAt: res.date ?? _now());
  }
}

final eventsRepositoryProvider = Provider<EventsRepository>(
  (ref) => EventsRepository(ref.watch(apiClientProvider), ref.watch(nowProvider)),
);

final _random = Random();

/// Random extra wait in [0, max]. Tests override it with zero for a deterministic timeline.
final jitterProvider = Provider<Duration Function(Duration max)>(
  (ref) =>
      (max) => Duration(milliseconds: _random.nextInt(max.inMilliseconds + 1)),
);

/// Re-reads [ref] when a sale should open ([FanEvent.reopenDelay], server time), so "Vente le" turns
/// into the open sale on its own, and keeps re-reading with back-off while the server still says
/// "not on sale". The jitter (up to 5 s, at most half the delay) spreads fans watching the same opening.
void _refetchAtOpenings(Ref ref, Iterable<FanEvent> events) {
  final delays = [for (final e in events) ?e.reopenDelay()];
  if (delays.isEmpty) return;
  final delay = delays.reduce((a, b) => a < b ? a : b);
  final jitter = ref.read(jitterProvider)(Duration(milliseconds: min(5000, delay.inMilliseconds ~/ 2)));
  final timer = Timer(delay + jitter, ref.invalidateSelf);
  ref.onDispose(timer.cancel);
}

/// Filtered by the follows on the phone only, never the club directory: Accueil lists events while it is down.
final FutureProvider<List<FanEvent>> upcomingEventsProvider = FutureProvider.autoDispose<List<FanEvent>>((ref) async {
  final f = ref.watch(followsProvider.select((f) => (ids: f.ids, chip: f.chip)));
  final events = await ref.watch(eventsRepositoryProvider).upcoming(organizersOf(homeChip(f.chip, f.ids), f.ids));
  _refetchAtOpenings(ref, events);
  return events;
});

final FutureProviderFamily<EventDetail, int> eventDetailProvider = FutureProvider.autoDispose.family<EventDetail, int>((
  ref,
  id,
) async {
  final detail = await ref.watch(eventsRepositoryProvider).detail(id);
  _refetchAtOpenings(ref, [detail.event]);
  return detail;
});
