// The waiting room (docs/04-api.md §Waiting room): the `/fan/queue` shape, the calls, and the pure rules screen 12
// shows (dots, wait copy). Hand-written like the other read-only DTOs.

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

/// `status` of `GET /fan/queue`.
enum QueuePhase {
  /// Before the draw: no position yet.
  preQueue,

  /// In line; the position is null until the room tick ranks the entry.
  waiting,
  admitted,
  expired,
  paused,
  ended;

  /// An unknown status keeps the fan waiting (and polling at the server's pace) rather than failing.
  static QueuePhase of(Object? s) => switch (s) {
    'PRE_QUEUE' => preQueue,
    'ADMITTED' => admitted,
    'EXPIRED' => expired,
    'PAUSED' => paused,
    'ENDED' => ended,
    _ => waiting,
  };

  /// The server keeps the turn waiting for the fan here: polling goes on.
  bool get polls => this == preQueue || this == waiting || this == paused;
}

/// The answer of `POST` and `GET /fan/queue`. [queueToken] and [admissionToken] are credentials: kept in secure
/// storage, sent only as headers, never logged.
class QueueStatus {
  const new({
    required this.eventId,
    required this.phase,
    required this.inRoom,
    required this.ratePerMin,
    required this.soldOut,
    required this.queueToken,
    required this.pollAfterS,
    required this.serverTime,
    this.saleStartsAt,
    this.position,
    this.estimatedWaitS,
    this.admissionToken,
    this.admissionExpiresAt,
  });

  factory fromJson(Map<String, Object?> j) {
    final admission = j['admission'] as Map<String, Object?>?;
    return QueueStatus(
      eventId: j['event_id']! as int,
      phase: QueuePhase.of(j['status']),
      saleStartsAt: switch (j['sale_starts_at']) {
        final String t => DateTime.parse(t).toUtc(),
        _ => null,
      },
      position: j['position'] as int?,
      inRoom: j['in_room']! as int,
      ratePerMin: j['rate_per_min']! as int,
      estimatedWaitS: j['estimated_wait_s'] as int?,
      soldOut: j['sold_out']! as bool,
      admissionToken: admission?['token'] as String?,
      admissionExpiresAt: switch (admission?['expires_at']) {
        final String t => DateTime.parse(t).toUtc(),
        _ => null,
      },
      queueToken: j['queue_token']! as String,
      pollAfterS: j['poll_after_s']! as int,
      serverTime: DateTime.parse(j['server_time']! as String).toUtc(),
    );
  }

  final int eventId;
  final QueuePhase phase;

  /// Null once the room has `ENDED`; only the pre-queue reads it.
  final DateTime? saleStartsAt;

  /// `rank − admitted_through` (≥ 1) once ranked and waiting.
  final int? position;

  /// Fans still in line, or every entry before the draw.
  final int inRoom;
  final int ratePerMin;
  final int? estimatedWaitS;
  final bool soldOut;
  final String? admissionToken;
  final DateTime? admissionExpiresAt;
  final String queueToken;

  /// 0 = stop polling.
  final int pollAfterS;
  final DateTime serverTime;
}

class QueueRepository {
  new(this._api);

  final ApiClient _api;

  /// `POST /fan/queue` with the session: joins, or returns the fan's entry with a fresh token (idempotent per account).
  Future<QueueStatus> join(int eventId, String deviceId) async => QueueStatus.fromJson(
    (await _api.post('/fan/queue', body: {'event_id': eventId, 'device_id': deviceId}))! as Map<String, Object?>,
  );

  /// `GET /fan/queue` with `X-Queue-Token` only: no session token goes with it (docs/04-api.md).
  Future<QueueStatus> poll(String queueToken) async => QueueStatus.fromJson(
    (await _api.getPublic('/fan/queue', headers: {'X-Queue-Token': queueToken}))! as Map<String, Object?>,
  );
}

final queueRepositoryProvider = Provider<QueueRepository>((ref) => QueueRepository(ref.watch(apiClientProvider)));

/// How many of the twelve dots are lit (DESIGN.md Lot 5, screen 12): ⌊12 × (1 − position ÷ first position seen)⌋,
/// at most 11 until the turn; all twelve at the turn.
int litDots({required int position, required int first, bool admitted = false}) {
  if (admitted) return 12;
  if (first <= 0) return 0;
  return (12 * (1 - position / first)).floor().clamp(0, 11);
}

/// The honest wait (lot-5 Q165): null when there is no estimate (paused, rate 0, not ranked).
String? waitCopy(AppLocalizations l, int? seconds) => switch (seconds) {
  null => null,
  < 60 => l.queueWaitLess,
  > 3600 => l.queueWaitHour,
  final s => l.queueWaitMin((s / 60).round()),
};

/// mm:ss of a turn or a countdown; zero once past.
String mmss(Duration d) {
  final s = d.isNegative ? 0 : d.inSeconds;
  return '${(s ~/ 60).toString().padLeft(2, '0')}:${(s % 60).toString().padLeft(2, '0')}';
}
