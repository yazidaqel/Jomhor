import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/gate/gate_client.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';
import 'package:jomhor_scanner/features/event/manifest.dart';

/// Événement screen state.
final class EventState {
  const new({this.assignments, this.stage, this.error, this.availableAt, this.loaded});

  /// Null until the first `GET /gate/events` answered.
  final List<Assignment>? assignments;

  /// Non-null while a manifest download runs.
  final ManifestStage? stage;

  /// A [GateApiError] code, [EventController.rejected] or [EventController.drift].
  final String? error;

  /// `MANIFEST_NOT_READY` `details.available_at` (epoch ms).
  final int? availableAt;

  /// The last manifest applied: (version, rows).
  final (int, int)? loaded;
}

/// Keeps the device's event current: K_e restored at start, then every 5 min until T0 + 30 min the
/// assignments and staff are refreshed and the manifest brought up to date (docs/02 §Manifest, plan Q43).
class EventController extends Notifier<EventState> {
  static const refreshEvery = Duration(minutes: 5);
  static const rejected = 'MANIFEST_REJECTED';
  static const drift = 'CLOCK_DRIFT';

  bool _running = false;

  @override
  EventState build() {
    final timer = Timer.periodic(refreshEvery, (_) => unawaited(refresh()));
    ref.onDispose(timer.cancel);
    unawaited(_start());
    return const EventState();
  }

  EventService get _service => ref.read(eventServiceProvider);

  Future<void> _start() async {
    await _service.restoreKey();
    await refresh();
  }

  /// One refresh round; a manifest download when one is due. Errors land in the state (offline is normal).
  Future<void> refresh({bool force = false}) async {
    if (_running) return;
    _running = true;
    try {
      final config = await ref.read(databaseProvider).readConfig();
      final due = force || refreshDue(config, ref.read(serverClockProvider).nowMs());
      if (config.deviceStatus != 'ACTIVE' || !due) return;
      final events = await _service.refresh();
      _set(EventState(assignments: events, loaded: state.loaded));
      final current = events.where((e) => e.id == config.eventId).firstOrNull;
      if (current != null && _manifestDue(config, current, force: force)) await _download();
    } on Object catch (e) {
      _fail(e);
    } finally {
      _running = false;
    }
  }

  /// At or after `manifest_available_at` (or asked for: the server then says when), when there is none yet,
  /// K_e is missing, or the server has a newer one. Never for a CLOSED event: the sync loop wipes it.
  bool _manifestDue(ConfigData c, Assignment a, {required bool force}) =>
      a.status != 'CLOSED' &&
      c.eventStatus != 'CLOSED' &&
      (force || ref.read(serverClockProvider).nowMs() >= a.manifestAvailableAt) &&
      (c.manifestVersion == 0 || ref.read(eventKeyProvider) == null || (a.manifestVersion ?? 0) > c.manifestVersion);

  /// Event screen: pick the assignment, then load its manifest.
  Future<void> select(Assignment a) async {
    if (_running) return;
    _running = true;
    try {
      await _service.select(a);
      await _download();
    } on Object catch (e) {
      _fail(e);
    } finally {
      _running = false;
    }
  }

  Future<void> _download() async {
    final m = await _service.download(
      onStage: (stage) => _set(EventState(assignments: state.assignments, stage: stage, loaded: state.loaded)),
    );
    _set(EventState(assignments: state.assignments, loaded: (m.version, m.rows)));
  }

  void _fail(Object e) {
    final (code, availableAt) = switch (e) {
      GateApiError(:final code, :final details) => (code, DateTime.tryParse('${details['available_at']}')),
      ManifestRejected() => (rejected, null),
      ClockDrift() => (drift, null),
      _ => (GateApiError.unexpectedResponse, null),
    };
    _set(
      EventState(
        assignments: state.assignments,
        error: code,
        availableAt: availableAt?.millisecondsSinceEpoch,
        loaded: state.loaded,
      ),
    );
  }

  void _set(EventState s) {
    if (ref.mounted) state = s;
  }
}

final eventControllerProvider = NotifierProvider<EventController, EventState>(EventController.new);
