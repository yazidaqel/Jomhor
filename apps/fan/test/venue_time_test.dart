import 'package:flutter_test/flutter_test.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:timezone/data/latest.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;

/// Expectations come from the bundled tz database, never from hard-coded offsets: when tzdata changes
/// Morocco's rules again, these tests follow it.
void main() {
  const zone = 'Africa/Casablanca';
  late tz.Location casablanca;

  setUpAll(() async {
    tzdata.initializeTimeZones();
    await initializeDateFormatting('fr');
    casablanca = tz.getLocation(zone);
  });

  Duration offsetAt(DateTime utc) => casablanca.timeZone(utc.millisecondsSinceEpoch).offset;

  /// Instants (UTC ms) where the offset changes, from the database.
  List<DateTime> transitions() => [
    for (final ms in casablanca.transitionAt)
      // The first entry is a "since the big bang" sentinel.
      if (ms > DateTime.utc(1900).millisecondsSinceEpoch) DateTime.fromMillisecondsSinceEpoch(ms, isUtc: true),
  ];

  /// The wall clock the venue shows for [utc], built only from the database offset.
  String expectedClock(DateTime utc) {
    final local = utc.add(offsetAt(utc));
    return '${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}';
  }

  test('across the 2026 Ramadan change, a 19:00 UTC kickoff follows the venue offset', () {
    // The first 2026 transition that lowers the offset is the Ramadan change.
    final ramadan = transitions().firstWhere(
      (t) => t.year == 2026 && offsetAt(t) < offsetAt(t.subtract(const Duration(hours: 1))),
    );
    final before = DateTime.utc(ramadan.year, ramadan.month, ramadan.day - 2, 19);
    final during = DateTime.utc(ramadan.year, ramadan.month, ramadan.day + 2, 19);
    expect(offsetAt(before), isNot(offsetAt(during)), reason: 'the test must straddle a real change');

    for (final kickoff in [before, during]) {
      expect(venueClock(kickoff, zone, 'fr'), expectedClock(kickoff));
      expect(venueTime(kickoff, zone).timeZoneOffset, offsetAt(kickoff));
    }
    expect(venueClock(before, zone, 'fr'), isNot(venueClock(during, zone, 'fr')));
  });

  test('after the last transition in the database, the venue stays on that offset (+00 per tzdata 2026e)', () {
    final last = transitions().where((t) => offsetAt(t) != offsetAt(t.subtract(const Duration(hours: 1)))).last;
    final permanent = offsetAt(last);
    for (final kickoff in [
      last.add(const Duration(days: 30)),
      DateTime.utc(last.year + 1, 3, 15, 19), // Where Ramadan used to switch the clock.
      DateTime.utc(last.year + 1, 7, 15, 19), // Where summer +01 used to be.
    ]) {
      expect(offsetAt(kickoff), permanent);
      expect(venueClock(kickoff, zone, 'fr'), expectedClock(kickoff));
    }
  });

  test('the date is the venue date, not the UTC date', () {
    // 23:30 UTC on a day the venue is ahead of UTC is already the next day there.
    final ahead = transitions().firstWhere((t) => t.year == 2026 && offsetAt(t) > Duration.zero);
    final late = DateTime.utc(ahead.year, ahead.month, ahead.day + 3, 23, 30);
    final local = late.add(offsetAt(late));
    expect(venueDayMonth(late, zone, 'fr').$1, '${local.day}');
    expect(local.day, isNot(late.day));
    expect(venueDate(late, zone, 'fr'), venueDate(local, 'Etc/UTC', 'fr'));
  });
}
