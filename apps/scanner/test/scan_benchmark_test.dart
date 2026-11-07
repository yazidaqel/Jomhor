import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';

import 'helpers.dart';
import 'scan_fixtures.dart';

/// Plan lot-2 FL-11: p95 per scan under 50 ms on a 45,000-row manifest, in CI. Deterministic inputs (seeded
/// ids, fixed clock); the whole scan is timed, decode to committed scan + used rows. SQLite runs in this
/// isolate here; on the device drift_flutter adds one isolate hop per query.
void main() {
  test('p95 < 50 ms per scan with 45,000 manifest rows and 20,000 already used', () async {
    final db = memoryDatabase();
    addTearDown(db.close);
    final random = Random(7);
    final ids = [for (var i = 0; i < 45000; i++) Uint8List.fromList(List.generate(16, (_) => random.nextInt(256)))];
    await seedEventMode(db, {
      for (final (i, id) in ids.indexed) ticketId(id): (i % 10 == 0 ? zoneVirage : zoneNord, 'VALID', 'Y. A.'),
    });
    await db.batch(
      (b) => b.insertAll(db.used, [
        for (final id in ids.skip(25000))
          UsedCompanion.insert(ticketId: ticketId(id), direction: 'IN', firstAt: 1, source: 'REMOTE'),
      ]),
    );
    final clock = ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(nowMs));
    final service = ScanService(db, clock: clock, eventKey: () => EventKey(testKe), random: Random(8));
    final slot = slotOf(nowMs);

    // Admits, duplicates (remote used), wrong zone, rotating tokens and forgeries, in a fixed mix.
    String token(int i) => switch (i % 5) {
      0 => rotatingToken(ids[i], slot: slot, zone: i % 10 == 0 ? zoneVirage : zoneNord),
      1 || 2 => staticToken(ids[i]),
      3 => staticToken(ids[25000 + i]),
      _ => tampered(staticToken(ids[i])),
    };

    for (var i = 0; i < 50; i++) {
      await service.scan(token(1000 + i)); // warm-up (JIT), not timed
    }
    final times = <int>[];
    final results = <ScanResult, int>{};
    for (var i = 0; i < 1000; i++) {
      final raw = token(i);
      final watch = Stopwatch()..start();
      final o = await service.scan(raw);
      times.add(watch.elapsedMicroseconds);
      results.update(o.verdict.result, (n) => n + 1, ifAbsent: () => 1);
    }
    times.sort();
    final p50 = times[times.length ~/ 2] / 1000;
    final p95 = times[(times.length * 95) ~/ 100] / 1000;
    // The figures go to the CI log for the report; the assertion below is the gate.
    // ignore: avoid_print
    print('scan p50 ${p50.toStringAsFixed(2)} ms, p95 ${p95.toStringAsFixed(2)} ms, $results');
    expect(results.keys, containsAll([ScanResult.OK, ScanResult.DUPLICATE, ScanResult.WRONG_ZONE, ScanResult.BAD_SIG]));
    expect(p95, lessThan(50));
  });
}
