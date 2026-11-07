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

  @override
  int get schemaVersion => 1;

  Future<ConfigData> readConfig() => select(config).getSingle();

  Stream<ConfigData> watchConfig() => select(config).watchSingle();

  /// docs/05 `config.server_time_offset_ms` and `last_server_time_at` (device epoch ms).
  Future<void> saveServerOffset(int offsetMs, int atDeviceMs) =>
      update(config).write(ConfigCompanion(serverTimeOffsetMs: Value(offsetMs), lastServerTimeAt: Value(atDeviceMs)));
}

/// Overridden in `main` with the opened database, and in tests with an in-memory one.
final databaseProvider = Provider<ScannerDatabase>((ref) => throw UnimplementedError('opened in main'));
