import 'package:intl/intl.dart';
import 'package:timezone/data/latest.dart' as tzdata;
import 'package:timezone/timezone.dart' as tz;

bool _zonesLoaded = false;

/// [ms] as a wall-clock time in the event's IANA zone (docs/05 `config.event_timezone`; docs/04: times are shown
/// in the venue's zone, never the phone's). The phone's zone only while no event zone is known.
DateTime eventTime(int ms, String? zone) {
  if (zone != null) {
    if (!_zonesLoaded) {
      tzdata.initializeTimeZones();
      _zonesLoaded = true;
    }
    try {
      return tz.TZDateTime.fromMillisecondsSinceEpoch(tz.getLocation(zone), ms);
    } on tz.LocationNotFoundException {
      // A zone newer than the bundled database: fall through to the phone's zone.
    }
  }
  return DateTime.fromMillisecondsSinceEpoch(ms);
}

/// `HH:mm:ss` in the event zone (result screens).
String clockTime(int ms, String? zone) => DateFormat('HH:mm:ss').format(eventTime(ms, zone));

/// `HH:mm` in the event zone.
String shortTime(int ms, String? zone) => DateFormat('HH:mm').format(eventTime(ms, zone));

/// Day and time in the event zone, in the UI locale (`sam. 7 nov. · 20:00`).
String eventDateTime(int ms, String? zone, String locale) {
  final t = eventTime(ms, zone);
  return '${DateFormat.MMMEd(locale).format(t)} · ${DateFormat.Hm(locale).format(t)}';
}
