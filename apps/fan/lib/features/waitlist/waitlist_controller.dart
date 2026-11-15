import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/push/push.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/waitlist/waitlist_repository.dart';

/// Events whose "Liste d'attente" was tapped signed out: joined once the session starts (DESIGN.md Lot 5, screen 1).
/// An id leaves when its login is left without a session (the waitlist button), and every id on sign-out.
final waitlistJoinAfterLoginProvider = Provider<Set<int>>((ref) => {});

/// The join remembered through the login failed: the button says why (any [ApiError] but `WAITLIST_CLOSED`).
class JoinAfterLoginFailed implements Exception {
  const new(this.error);

  final Object error;
}

/// The fan's entry on one event's waitlist. Read once when screen 1's hero or screen 2's CTA shows the waitlist (no
/// polling); signed out: null, with no call.
class WaitlistController extends AsyncNotifier<WaitlistEntry?> {
  new(this.eventId);

  final int eventId;

  WaitlistRepository get _repo => ref.read(waitlistRepositoryProvider);

  @override
  Future<WaitlistEntry?> build() async {
    if (!ref.watch(sessionProvider)) return null;
    if (ref.read(waitlistJoinAfterLoginProvider).remove(eventId)) {
      // Kept through the PUT: the login hands the fan from screen 1 to screen 2 meanwhile.
      final link = ref.keepAlive();
      try {
        return await _put();
      } on Object catch (e) {
        throw JoinAfterLoginFailed(e);
      } finally {
        link.close();
      }
    }
    return await _repo.get(eventId);
  }

  /// Signed out: remembered, and [build] joins once the session starts.
  void joinAfterLogin() => ref.read(waitlistJoinAfterLoginProvider).add(eventId);

  /// PUT; its answer becomes the state. Any [ApiError] but `WAITLIST_CLOSED` is rethrown with the state unchanged.
  Future<void> join() async {
    final entry = await _put();
    if (ref.mounted && ref.read(sessionProvider)) state = AsyncData(entry);
  }

  /// DELETE (204): off the list. An [ApiError] is rethrown with the state unchanged.
  Future<void> leave() async {
    await _repo.leave(eventId);
    if (ref.mounted && ref.read(sessionProvider)) state = const AsyncData(null);
  }

  /// The PUT answer. `WAITLIST_CLOSED` (sold out no more, a room phase live, the sale over) re-reads the event, whose
  /// CTA then shows its state, and gives null. Joined: the push prompt, once per install (notices come by push).
  Future<WaitlistEntry?> _put() async {
    try {
      final entry = await _repo.join(eventId);
      if (ref.mounted) unawaited(maybeAskPermission(ref.read(pushMessagingProvider)));
      return entry;
    } on ApiError catch (e) {
      if (e.code != 'WAITLIST_CLOSED') rethrow;
      if (ref.mounted) {
        ref
          ..invalidate(eventDetailProvider(eventId))
          ..invalidate(upcomingEventsProvider);
      }
      return null;
    }
  }
}

final AsyncNotifierProviderFamily<WaitlistController, WaitlistEntry?, int> waitlistProvider = AsyncNotifierProvider
    .autoDispose
    .family<WaitlistController, WaitlistEntry?, int>(WaitlistController.new);
