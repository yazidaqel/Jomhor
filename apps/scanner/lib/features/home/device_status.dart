import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';

/// The one screen the device shows, from docs/05 `config` and whether K_e is in memory.
enum GateScreen { enrol, event, unlock, scan }

/// Enrolment until ACTIVE; event mode needs an event, a gate, a manifest, K_e and a clock within 20 s; then the
/// staff lock.
GateScreen gateScreenOf(ConfigData c, {required bool keyLoaded}) {
  if (c.deviceId == null || c.deviceStatus != 'ACTIVE') return GateScreen.enrol;
  if (c.eventId == null || c.gateId == null || c.manifestVersion == 0 || !keyLoaded || !driftOk(c)) {
    return GateScreen.event;
  }
  return c.staffId == null ? GateScreen.unlock : GateScreen.scan;
}

final configProvider = StreamProvider<ConfigData>((ref) => ref.watch(databaseProvider).watchConfig());
