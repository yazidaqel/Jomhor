import 'package:intl/intl.dart';
import 'package:timezone/timezone.dart' as tz;

/// Times are shown in the venue's IANA zone (bundled tz database), never the device zone or a
/// fixed offset: Africa/Casablanca changed offset around Ramadan and is +00 from 2026-09-20 (tzdata 2026e).
tz.TZDateTime venueTime(DateTime instant, String zone) => tz.TZDateTime.from(instant, tz.getLocation(zone));

/// "Sam. 14 nov." in FR.
String venueDate(DateTime instant, String zone, String locale) =>
    _capitalize(DateFormat('EEE d MMM', locale).format(venueTime(instant, zone)));

/// "20:00".
String venueClock(DateTime instant, String zone, String locale) =>
    DateFormat.Hm(locale).format(venueTime(instant, zone));

/// Day of month and short month ("22", "NOV") for calendar rows.
(String, String) venueDayMonth(DateTime instant, String zone, String locale) {
  final t = venueTime(instant, zone);
  return ('${t.day}', DateFormat('MMM', locale).format(t).replaceAll('.', '').toUpperCase());
}

String _capitalize(String s) => s.isEmpty ? s : s[0].toUpperCase() + s.substring(1);

/// Whole days, hours and minutes left until [target]; zero once it has passed.
({int days, int hours, int minutes}) countdown(DateTime now, DateTime target) {
  final left = target.difference(now);
  final m = left.isNegative ? 0 : left.inMinutes;
  return (days: m ~/ (24 * 60), hours: m ~/ 60 % 24, minutes: m % 60);
}
