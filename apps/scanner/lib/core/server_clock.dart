import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The server-corrected clock: device clock + [offsetMs] (docs/05 `config.server_time_offset_ms`). Used for
/// `X-Device-Time` (lib/core/gate/gate_client.dart), and for scan times and token slots on the gate path.
class ServerClock {
  new({this.offsetMs = 0, this.measured = false, DateTime Function()? deviceNow})
    : _deviceNow = deviceNow ?? DateTime.now;

  final DateTime Function() _deviceNow;

  /// Server time − device time, in ms.
  int offsetMs;

  /// An offset has been measured at least once (docs/05 `config.last_server_time_at` is set).
  bool measured;

  int deviceMs() => _deviceNow().millisecondsSinceEpoch;

  int nowMs() => deviceMs() + offsetMs;
}

/// Overridden in `main` with the offset saved in `config`.
final serverClockProvider = Provider<ServerClock>((ref) => ServerClock());
