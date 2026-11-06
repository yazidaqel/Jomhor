import 'dart:async';
import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/app/config.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/events/event.dart';

/// Client-side code: the build has no `ORGANIZER_ID`, so there is no club to list.
const missingOrganizer = 'MISSING_ORGANIZER_ID';

/// Overridden in tests.
final organizerIdProvider = Provider<String>((ref) => AppConfig.organizerId);

class EventsRepository {
  new(this._api, this._organizerId, this._now);

  final ApiClient _api;
  final String _organizerId;
  final DateTime Function() _now;

  /// Upcoming events of this build's club, by `starts_at` (public: no token sent).
  Future<List<FanEvent>> upcoming() async {
    _requireOrganizer();
    final res = await _api.getPublicResponse('/fan/events', query: {'organizer': _organizerId});
    // Server time from the `Date` header: a fast or slow device clock does not move the opening.
    final fetchedAt = res.date ?? _now();
    return [
      for (final e in res.body! as List<Object?>) FanEvent.fromJson(e! as Map<String, Object?>, fetchedAt: fetchedAt),
    ];
  }

  /// One event of this build's club; another organizer's event reads as NOT_FOUND.
  Future<EventDetail> detail(int id) async {
    _requireOrganizer();
    final res = await _api.getPublicResponse('/fan/events/$id');
    final detail = EventDetail.fromJson(res.body! as Map<String, Object?>, fetchedAt: res.date ?? _now());
    if (detail.event.organizerId != _organizerId) {
      throw const ApiError(status: 404, code: 'NOT_FOUND', message: 'Event of another organizer');
    }
    return detail;
  }

  void _requireOrganizer() {
    if (_organizerId.isEmpty) {
      throw const ApiError(status: 0, code: missingOrganizer, message: 'ORGANIZER_ID is not set');
    }
  }
}

final eventsRepositoryProvider = Provider<EventsRepository>(
  (ref) => EventsRepository(ref.watch(apiClientProvider), ref.watch(organizerIdProvider), ref.watch(nowProvider)),
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

final FutureProvider<List<FanEvent>> upcomingEventsProvider = FutureProvider.autoDispose<List<FanEvent>>((ref) async {
  final events = await ref.watch(eventsRepositoryProvider).upcoming();
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
