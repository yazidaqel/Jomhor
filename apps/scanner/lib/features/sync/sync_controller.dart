import 'dart:async';
import 'dart:math';

import 'package:drift/drift.dart' show TableUpdateQuery, UpdateKind;
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/sync/sync_service.dart';

/// The scan screen's top line: whether the last round reached the server.
final class SyncState {
  const new({this.online, this.rounds = 0});

  /// Null until a round talked to the server.
  final bool? online;

  /// Rounds that reached the server: a new value after each one, so listeners re-read the unsynced count.
  final int rounds;
}

/// The sync loop (docs/02 §Sync step 1): a round every 10 s while online, right after each new scan row, and
/// on failure exponential backoff 2 s → 60 s with jitter. Single-flight: a trigger during a round runs one
/// more round right after it. It never waits on the scan path, and the scan path never waits on it.
///
/// Connectivity regained is detected by the backoff retry itself, no plugin: an interface coming back
/// (connectivity_plus) does not prove the API is reachable from a packed stadium cell, so a probe would be
/// needed anyway, and the retry is that probe.
// ponytail: a reconnection is noticed within the current backoff (≤ 60 s); add connectivity_plus as an extra
// trigger if the dry run shows that is too slow.
class SyncController extends Notifier<SyncState> {
  new({Random? random}) : _random = random ?? Random();

  static const every = Duration(seconds: 10);

  final Random _random;
  Timer? _timer;
  bool _running = false;
  bool _again = false;
  int _failures = 0;
  int _rounds = 0;

  @override
  SyncState build() {
    final inserts = ref
        .read(databaseProvider)
        .tableUpdates(const TableUpdateQuery.onTableName('scans', limitUpdateKind: UpdateKind.insert))
        .listen((_) {
          // Offline, the backoff retry stays the probe: a busy gate does not hammer a dead network.
          if (state.online != false) _schedule(Duration.zero);
        });
    ref.onDispose(() {
      _timer?.cancel();
      unawaited(inserts.cancel());
    });
    _schedule(Duration.zero);
    return const SyncState();
  }

  void _schedule(Duration delay) {
    if (_running) {
      _again = _again || delay == Duration.zero;
      return;
    }
    _timer?.cancel();
    _timer = Timer(delay, () => unawaited(_round()));
  }

  Future<void> _round() async {
    _running = true;
    _again = false;
    Duration delay;
    try {
      final talked = await ref.read(syncServiceProvider).run();
      _failures = 0;
      delay = every;
      if (talked && ref.mounted) state = SyncState(online: true, rounds: ++_rounds);
    } on Object {
      _failures++;
      delay = backoff(_failures, _random.nextDouble());
      if (ref.mounted) state = SyncState(online: false, rounds: _rounds);
    } finally {
      _running = false;
    }
    // A scan during a good round goes out at once; after a failure the backoff holds.
    if (ref.mounted) _schedule(_again && _failures == 0 ? Duration.zero : delay);
  }
}

final syncControllerProvider = NotifierProvider<SyncController, SyncState>(SyncController.new);
