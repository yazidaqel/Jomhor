import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_scanner/core/event_key.dart';
import 'package:jomhor_scanner/core/event_time.dart';
import 'package:jomhor_scanner/core/server_clock.dart';
import 'package:jomhor_scanner/data/scanner_database.dart';
import 'package:jomhor_scanner/features/event/event_service.dart';
import 'package:jomhor_scanner/features/scan/result_view.dart';
import 'package:jomhor_scanner/features/scan/scan_service.dart';
import 'package:jomhor_scanner/features/scan/validator.dart';
import 'package:jomhor_scanner/l10n/app_localizations.dart';

import 'helpers.dart';
import 'scan_fixtures.dart';

/// 2026-11-07 11:00:00 UTC: 20:00 in Tokyo (UTC+9, no DST). Casablanca's offset comes from the bundled tzdata
/// (Morocco has changed it by decree), so the tests only require it to differ from Tokyo's.
final int instant = DateTime.utc(2026, 11, 7, 11).millisecondsSinceEpoch;

void main() {
  test('times are the event zone wall clock, whatever the phone zone', () {
    expect(clockTime(instant, 'Asia/Tokyo'), '20:00:00');
    expect(clockTime(instant, 'Africa/Casablanca'), isNot('20:00:00'));
    expect(clockTime(instant, 'UTC'), '11:00:00');
    expect(shortTime(instant + 90000, 'Asia/Tokyo'), '20:01');
    // No event zone yet (or one the bundled database lacks): the phone's own zone.
    final local = DateTime.fromMillisecondsSinceEpoch(instant);
    final phone = '${local.hour.toString().padLeft(2, '0')}:00:00';
    expect([clockTime(instant, null), clockTime(instant, 'Mars/Olympus')], [phone, phone]);
  });

  test('a scan carries the event zone from config.event_timezone', () async {
    final db = memoryDatabase();
    addTearDown(db.close);
    final ticket = ticketBytes(0x11);
    await seedEventMode(db, {ticketId(ticket): (zoneNord, 'VALID', 'Y. A.')});
    await db.update(db.config).write(const ConfigCompanion(eventTimezone: Value('Asia/Tokyo')));
    final service = ScanService(
      db,
      clock: ServerClock(deviceNow: () => DateTime.fromMillisecondsSinceEpoch(nowMs)),
      eventKey: () => EventKey(testKe),
    );
    final outcome = await service.scan(staticToken(ticket));
    expect((outcome.verdict.result, outcome.timeZone), (ScanResult.OK, 'Asia/Tokyo'));
  });

  testWidgets('a Tokyo event on a Casablanca phone shows Tokyo time on the result screen', (tester) async {
    for (final (zone, shown) in [
      ('Asia/Tokyo', '20:00:00'),
      ('Africa/Casablanca', clockTime(instant, 'Africa/Casablanca')),
    ]) {
      await tester.pumpWidget(
        MaterialApp(
          locale: const Locale('fr'),
          supportedLocales: AppLocalizations.supportedLocales,
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          home: ResultView(
            outcome: ScanOutcome(
              verdict: const Verdict(ScanResult.DUPLICATE, firstAt: 0, firstGateCode: 'N1'),
              scanId: 's',
              atMs: instant,
              elapsedMs: 12,
              timeZone: zone,
            ),
            topLine: '',
            onNext: () {},
          ),
        ),
      );
      expect(find.text('Contrôlé hors ligne en 12 ms · $shown'), findsOneWidget, reason: zone);
    }
  });

  test('the manifest refresh runs until T0 + 30 min, not after', () {
    ConfigData config(int? startsAt) => ConfigData(
      id: 1,
      serverTimeOffsetMs: 0,
      manifestVersion: 1,
      reentryPolicy: 'NONE',
      syncCursor: 0,
      mode: 'IN',
      startsAt: startsAt,
    );
    const t0 = 1000000000;
    expect(refreshDue(config(t0), t0 + 30 * 60000), isTrue);
    expect(refreshDue(config(t0), t0 + 30 * 60000 + 1), isFalse);
    expect(refreshDue(config(null), t0 * 2), isTrue);
  });
}
