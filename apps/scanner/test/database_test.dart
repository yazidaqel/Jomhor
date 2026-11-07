import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart';

import 'helpers.dart';

/// Every table and index with its columns (name, type, not null, default, pk) and index columns, as SQLite
/// itself reports them, plus each index's SQL (case and spacing folded), which carries partial-index predicates.
Future<Map<String, Object?>> describe(Future<List<Map<String, Object?>>> Function(String sql) query) async {
  final out = <String, Object?>{};
  final objects = await query(
    "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name",
  );
  for (final o in objects) {
    final name = o['name']! as String;
    if (o['type'] == 'table') {
      out['table $name'] = [
        for (final c in await query('PRAGMA table_info("$name")'))
          (c['name'], '${c['type']}'.toLowerCase(), c['notnull'], c['dflt_value'], c['pk']),
      ];
      out['indexes $name'] = [
        for (final i in await query('PRAGMA index_list("$name")'))
          if (i['origin'] == 'c')
            (
              i['name'],
              i['unique'],
              i['partial'],
              [for (final c in await query('PRAGMA index_info("${i['name']}")')) c['name']].join(','),
            ),
      ];
    } else if (o['type'] == 'index') {
      out['index sql $name'] = '${o['sql']}'.toLowerCase().replaceAll(RegExp(r'\s+'), ' ').replaceAll('"', '');
    }
  }
  return out;
}

void main() {
  test('Drift creates exactly the tables, columns and indexes of docs/05-scanner-sqlite.sql', () async {
    final reference = sqlite3.openInMemory()..execute(File('../../docs/05-scanner-sqlite.sql').readAsStringSync());
    addTearDown(reference.close);
    final expected = await describe((sql) async => [for (final row in reference.select(sql)) Map.of(row)]);

    final db = memoryDatabase();
    addTearDown(db.close);
    final actual = await describe((sql) async => [for (final row in await db.customSelect(sql).get()) row.data]);

    expect(expected.keys, containsAll(['table config', 'table manifest_tickets', 'table used', 'table scans']));
    expect(expected['index sql idx_scans_unsynced'], contains('where synced = 0'));
    expect(actual, expected);
  });

  test('config holds one row only (check id = 1)', () async {
    final db = memoryDatabase();
    addTearDown(db.close);
    await expectLater(db.customStatement('INSERT INTO config (id) VALUES (2)'), throwsA(isA<SqliteException>()));
    expect(await db.select(db.config).get(), hasLength(1));
  });

  test('the single config row exists from the first open, with the 05 defaults', () async {
    final db = memoryDatabase();
    addTearDown(db.close);
    final config = await db.readConfig();
    expect(
      (
        config.id,
        config.deviceId,
        config.serverTimeOffsetMs,
        config.manifestVersion,
        config.reentryPolicy,
        config.mode,
      ),
      (1, null, 0, 0, 'NONE', 'IN'),
    );
  });

  test('saveServerOffset writes the offset and when it was measured', () async {
    final db = memoryDatabase();
    addTearDown(db.close);
    await db.saveServerOffset(-2500, 1767225600000);
    final config = await db.readConfig();
    expect((config.serverTimeOffsetMs, config.lastServerTimeAt), (-2500, 1767225600000));
  });
}
