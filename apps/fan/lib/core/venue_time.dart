import 'package:intl/date_symbol_data_custom.dart';
import 'package:intl/date_symbols.dart';
import 'package:intl/date_time_patterns.dart';
import 'package:intl/intl.dart';
import 'package:timezone/timezone.dart' as tz;

/// Times are shown in the venue's IANA zone (bundled tz database), never the device zone or a
/// fixed offset: Africa/Casablanca changed offset around Ramadan and is +00 from 2026-09-20 (tzdata 2026e).
tz.TZDateTime venueTime(DateTime instant, String zone) => tz.TZDateTime.from(instant, tz.getLocation(zone));

/// The month names of Morocco (CLDR `ar_MA`), which intl does not ship: its `ar` says مايو, أغسطس, نوفمبر.
const _moroccanMonths = [
  'يناير',
  'فبراير',
  'مارس',
  'أبريل',
  'ماي',
  'يونيو',
  'يوليوز',
  'غشت',
  'شتنبر',
  'أكتوبر',
  'نونبر',
  'دجنبر',
];

/// The intl locale for an app locale: `ar` formats as `ar_MA`, the `ar` data flutter_localizations loads with
/// Western digits 0-9 (DESIGN.md: every locale) and the Moroccan months. Built once.
String intlLocale(String locale) {
  if (locale != 'ar') return locale;
  const arMa = 'ar_MA';
  if (!DateFormat.localeExists(arMa)) {
    // Western digits: flutter_localizations' `ar` data writes Arabic-Indic ones (١٩:٠٠).
    final symbols = DateFormat('d', 'ar').dateSymbols.serializeToMap()
      ..['NAME'] = arMa
      ..['ZERODIGIT'] = '0';
    for (final key in ['MONTHS', 'STANDALONEMONTHS', 'SHORTMONTHS', 'STANDALONESHORTMONTHS']) {
      symbols[key] = _moroccanMonths;
    }
    initializeDateFormattingCustom(
      locale: arMa,
      symbols: DateSymbols.deserializeFromMap(symbols),
      patterns: dateTimePatternMap()['ar'],
    );
  }
  return arMa;
}

/// "Sam. 14 nov." in FR, "السبت 14 نونبر" in AR.
String venueDate(DateTime instant, String zone, String locale) =>
    _capitalize(DateFormat('EEE d MMM', intlLocale(locale)).format(venueTime(instant, zone)));

/// "20:00".
String venueClock(DateTime instant, String zone, String locale) =>
    DateFormat.Hm(intlLocale(locale)).format(venueTime(instant, zone));

/// "Sam. 21 févr. 12:00": a deadline.
String venueWhen(DateTime instant, String zone, String locale) =>
    '${venueDate(instant, zone, locale)} ${venueClock(instant, zone, locale)}';

/// Day of month and short month ("22", "NOV") for calendar rows.
(String, String) venueDayMonth(DateTime instant, String zone, String locale) {
  final t = venueTime(instant, zone);
  return ('${t.day}', DateFormat('MMM', intlLocale(locale)).format(t).replaceAll('.', '').toUpperCase());
}

String _capitalize(String s) => s.isEmpty ? s : s[0].toUpperCase() + s.substring(1);

/// Whole days, hours and minutes left until [target]; zero once it has passed.
({int days, int hours, int minutes}) countdown(DateTime now, DateTime target) {
  final left = target.difference(now);
  final m = left.isNegative ? 0 : left.inMinutes;
  return (days: m ~/ (24 * 60), hours: m ~/ 60 % 24, minutes: m % 60);
}

/// Calendar days from [now] to [target] in the venue's zone: 0 = same day, 1 = tomorrow.
int venueDaysUntil(DateTime now, DateTime target, String zone) {
  final a = venueTime(now, zone);
  final b = venueTime(target, zone);
  return DateTime.utc(b.year, b.month, b.day).difference(DateTime.utc(a.year, a.month, a.day)).inDays;
}
