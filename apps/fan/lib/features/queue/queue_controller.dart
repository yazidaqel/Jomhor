import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/core/device_id.dart';
import 'package:jomhor_fan/features/events/events_repository.dart';
import 'package:jomhor_fan/features/queue/queue.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';

/// What screen 12 shows.
class QueueView {
  const new({
    this.status,
    this.error,
    this.retryIn,
    this.offline = false,
    this.firstPosition,
    this.offset = Duration.zero,
  });

  /// The last answer of the room; null before the first one.
  final QueueStatus? status;

  /// A refused join (`NOT_ELIGIBLE`, `QUEUE_DEVICE_LIMIT`, `QUEUE_NOT_OPEN`, `QUEUE_RATE_LIMITED` `IP`, ...) or a
  /// first answer that never came.
  final ApiError? error;

  /// `QUEUE_RATE_LIMITED` `IP`: seconds before the one automatic retry; null once it is spent.
  final int? retryIn;

  /// The last poll found no network: [status] is the last known state, the place is kept.
  final bool offline;

  /// The first position this phone saw in this walk (the dots count from it).
  final int? firstPosition;

  /// Server time minus this phone's clock, at the last answer: countdowns run on server time.
  final Duration offset;

  DateTime serverNow(DateTime deviceNow) => deviceNow.add(offset);
}

/// Screen 12's loop (docs/04-api.md §Waiting room): join (or resume with the stored queue token), then poll
/// `GET /fan/queue` exactly `poll_after_s` after each answer until `ADMITTED`, `EXPIRED` or `ENDED`. Polling stops
/// while the app is in the background ([pause]) and polls once on [resume]. No push (lot-5 Q153): the turn waits
/// for the fan (Q143).
class QueueController extends Notifier<QueueView> {
  new(this.eventId);

  final int eventId;
  Timer? _next;
  Timer? _countdown;
  var _background = false;

  /// The one automatic retry after `QUEUE_RATE_LIMITED` `IP` is spent.
  var _ipRetried = false;

  @override
  QueueView build() {
    ref.onDispose(_stop);
    unawaited(Future.microtask(_start));
    return const QueueView();
  }

  QueueRepository get _repo => ref.read(queueRepositoryProvider);
  QueuePasses get _passes => ref.read(queuePassesProvider.notifier);
  DateTime _now() => ref.read(nowProvider)();

  void _stop() {
    _next?.cancel();
    _countdown?.cancel();
  }

  Future<void> _start() async {
    final pass = (await ref.read(queuePassesProvider.future))[eventId];
    if (!ref.mounted) return;
    if (pass == null) {
      await join();
    } else {
      state = QueueView(firstPosition: pass.firstPosition);
      await _poll(pass.queueToken);
    }
  }

  /// `POST /fan/queue`: a new entry, or the fan's own with a fresh token. Also the manual retry.
  Future<void> join() async {
    _stop();
    state = QueueView(status: state.status, firstPosition: state.firstPosition, offset: state.offset);
    try {
      final status = await _repo.join(eventId, await deviceId());
      if (ref.mounted) await _apply(status);
    } on ApiError catch (e) {
      if (ref.mounted) _refused(e);
    } on Object {
      if (ref.mounted) _refused(const ApiError(status: 0, code: ApiError.unexpectedResponse, message: 'join'));
    }
  }

  /// "Reprendre une place" after an expired turn: back of the queue, so the dots start over (the server clock offset
  /// stays).
  Future<void> rejoin() async {
    state = QueueView(offset: state.offset);
    _ipRetried = false;
    await _passes.put(eventId, null);
    if (ref.mounted) await join();
  }

  /// The manual "Réessayer" after a refused join: a fresh automatic retry is allowed again.
  Future<void> retry() {
    _ipRetried = false;
    return join();
  }

  /// The app went to the background: no poll, timed join or automatic retry until [resume].
  void pause() {
    _background = true;
    _stop();
  }

  /// Back in front: one poll now, then the server's rhythm again.
  void resume() {
    if (!_background) return;
    _background = false;
    final status = state.status;
    switch (state.error) {
      case null when status != null && status.phase.polls:
        unawaited(_poll(status.queueToken));
      // The automatic retry's countdown was stopped by [pause]: it goes on from where it was.
      case final ApiError e when e.code == 'QUEUE_RATE_LIMITED' && state.retryIn != null:
        _countDown(e, state.retryIn!);
      // The timed join (at `opens_at`, or after a failed first answer) was dropped by [pause]: ask again now.
      case ApiError(code: 'QUEUE_NOT_OPEN', details: {'reason': 'NOT_STARTED'}) || ApiError(status: 0):
        unawaited(join());
      default:
        break;
    }
  }

  Future<void> _poll(String token) async {
    _next?.cancel();
    try {
      final status = await _repo.poll(token);
      if (ref.mounted) await _apply(status);
    } on Object catch (thrown) {
      if (!ref.mounted) return;
      // A reply that can't be read counts as a server hiccup.
      final e = thrown is ApiError
          ? thrown
          : const ApiError(status: 0, code: ApiError.unexpectedResponse, message: 'poll');
      if (e.code == 'QUEUE_RATE_LIMITED') {
        // `POLL`: silent, the next poll at `retry_after_s`.
        return _schedule(token, _retryAfter(e, 1));
      }
      if (e.status == 401) {
        // Expired or purged entry: join again (the server returns the fan's entry if it still has one), at the back
        // of the line as far as the dots know.
        return await rejoin();
      }
      // Offline or a server hiccup: the last state stays, the place is kept, and we try again.
      state = QueueView(
        status: state.status,
        firstPosition: state.firstPosition,
        offset: state.offset,
        offline: true,
        error: state.status == null ? e : null,
      );
      _schedule(token, state.status?.pollAfterS.clamp(5, 30) ?? 5);
    }
  }

  Future<void> _apply(QueueStatus s) async {
    final offset = s.serverTime.difference(_now());
    final first = state.firstPosition ?? s.position;
    state = QueueView(status: s, firstPosition: first, offset: offset);
    if (s.phase == QueuePhase.ended) {
      // No room any more: orders need no token. Screens 1, 2 and 3 read the event again.
      await _passes.put(eventId, null);
      if (ref.mounted) ref.invalidate(eventDetailProvider(eventId));
      return;
    }
    final admitted = s.phase == QueuePhase.admitted ? s.admissionToken : null;
    await _passes.put(
      eventId,
      QueuePass(
        queueToken: s.queueToken,
        firstPosition: first,
        admissionToken: admitted,
        admissionUntil: admitted == null ? null : s.admissionExpiresAt?.subtract(offset),
      ),
    );
    if (ref.mounted && s.phase.polls && s.pollAfterS > 0) _schedule(s.queueToken, s.pollAfterS);
  }

  void _schedule(String token, int seconds) => _after(Duration(seconds: seconds), () => _poll(token));

  /// The one timed call (a poll or a join), dropped in the background: [resume] asks again.
  void _after(Duration wait, Future<void> Function() call) {
    _next?.cancel();
    if (_background) return;
    _next = Timer(wait, () => unawaited(call()));
  }

  /// `QUEUE_RATE_LIMITED` `IP`: said with [left] seconds, then the automatic retry.
  void _countDown(ApiError e, int left) {
    _countdown?.cancel();
    state = QueueView(
      status: state.status,
      firstPosition: state.firstPosition,
      offset: state.offset,
      error: e,
      retryIn: left,
    );
    if (_background) return;
    _countdown = Timer(const Duration(seconds: 1), () => left > 1 ? _countDown(e, left - 1) : unawaited(join()));
  }

  void _refused(ApiError e) {
    final base = QueueView(status: state.status, firstPosition: state.firstPosition, offset: state.offset, error: e);
    switch ((e.code, e.details['reason'])) {
      case ('QUEUE_RATE_LIMITED', _) when !_ipRetried:
        // `IP`: said with the countdown, then one automatic retry.
        _ipRetried = true;
        _countDown(e, _retryAfter(e, 60));
      case ('QUEUE_NOT_OPEN', 'NOT_STARTED'):
        state = base;
        // Joins open at `opens_at` (server time): try again then, spread by the jitter.
        if (e.details['opens_at'] case final String at) {
          final wait = DateTime.parse(at).difference(state.serverNow(_now()));
          final jitter = ref.read(jitterProvider)(const Duration(seconds: 5));
          _after((wait.isNegative ? Duration.zero : wait) + jitter, join);
        }
      case ('QUEUE_NOT_OPEN', 'ENDED' || 'NO_ROOM'):
        state = base;
        ref.invalidate(eventDetailProvider(eventId));
      default:
        state = base;
    }
  }

  static int _retryAfter(ApiError e, int fallback) => switch (e.details['retry_after_s']) {
    final int s when s > 0 => s.clamp(1, 300),
    _ => fallback,
  };
}

final NotifierProviderFamily<QueueController, QueueView, int> queueControllerProvider = NotifierProvider.autoDispose
    .family<QueueController, QueueView, int>(QueueController.new);
