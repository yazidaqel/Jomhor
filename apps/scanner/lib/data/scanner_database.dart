import 'package:drift/drift.dart';
import 'package:drift_flutter/drift_flutter.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

part 'scanner_database.g.dart';

/// The scanner's SQLite database (docs/05-scanner-sqlite.sql via schema.drift). K_e is never stored here.
@DriftDatabase(include: {'schema.drift'})
class ScannerDatabase extends _$ScannerDatabase {
  new(super.e);

  /// The app database file. drift_flutter runs SQLite in its own background isolate, so queries never
  /// block the UI isolate.
  new open() : super(driftDatabase(name: 'scanner'));

  /// 2: `config.event_timezone` (FL-12).
  @override
  int get schemaVersion => 2;

  @override
  MigrationStrategy get migration => MigrationStrategy(
    onUpgrade: (m, from, to) async {
      if (from < 2) await m.addColumn(config, config.eventTimezone);
    },
  );

  Future<ConfigData> readConfig() => select(config).getSingle();

  Stream<ConfigData> watchConfig() => select(config).watchSingle();

  /// App start: no staff session survives a restart (the PIN is asked again), then the config.
  Future<ConfigData> openLocked() async {
    await update(config).write(const ConfigCompanion(staffId: Value(null)));
    return await readConfig();
  }

  /// docs/05 `config.server_time_offset_ms` and `last_server_time_at` (device epoch ms).
  Future<void> saveServerOffset(int offsetMs, int atDeviceMs) =>
      update(config).write(ConfigCompanion(serverTimeOffsetMs: Value(offsetMs), lastServerTimeAt: Value(atDeviceMs)));

  /// REVOKED (docs/05): every table emptied and config back to a new device, in one transaction. The clock
  /// offset stays (it describes this phone's clock, not the enrolment). K_e is wiped by the caller.
  Future<void> wipeAll() => transaction(() async {
    for (final table in <TableInfo<Table, Object?>>[zones, gates, staff, manifestTickets, used, scans]) {
      await delete(table).go();
    }
    await update(config).write(
      const ConfigCompanion(
        deviceId: Value(null),
        deviceStatus: Value(null),
        manifestPublicKey: Value(null),
        eventId: Value(null),
        eventTitle: Value(null),
        startsAt: Value(null),
        eventTimezone: Value(null),
        eventStatus: Value(null),
        gateId: Value(null),
        manifestVersion: Value(0),
        reentryPolicy: Value('NONE'),
        syncCursor: Value(0),
        staffId: Value(null),
        mode: Value('IN'),
      ),
    );
  });
}

/// Overridden in `main` with the opened database, and in tests with an in-memory one.
final databaseProvider = Provider<ScannerDatabase>((ref) => throw UnimplementedError('opened in main'));
