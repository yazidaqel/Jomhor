import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/events/event.dart';
import 'package:jomhor_fan/features/queue/queue_store.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';

/// The CTA of screens 1 and 2 for an event with a room (DESIGN.md Lot 5).
enum RoomCta {
  /// `SCHEDULED`: disabled, "File d'attente dès {opens_at}".
  opensAt,

  /// Joins are open: "Rejoindre la file".
  join,

  /// The phone holds a queue token for the event: "Reprendre ma place".
  resume,

  /// The fan's turn is running: straight to screen 3.
  choose,
}

/// Null without a room (the sale CTA then).
RoomCta? roomCta(FanEvent e, DateTime now, QueuePass? pass) {
  final room = e.room;
  if (room == null) return null;
  if (!room.joinable(now)) return RoomCta.opensAt;
  if (pass == null) return RoomCta.join;
  return pass.admitted(now) ? RoomCta.choose : RoomCta.resume;
}

String roomCtaLabel(AppLocalizations l, FanEvent e, RoomCta cta, DateTime now) => switch (cta) {
  RoomCta.opensAt => l.queueOpensAt(opensAtText(l, e.room!.opensAt, e.timezone, now)),
  RoomCta.join => l.queueJoin,
  RoomCta.resume => l.queueResume,
  RoomCta.choose => l.queueChoose,
};

/// Where the CTA goes; null = disabled.
String? roomCtaPath(FanEvent e, RoomCta cta) => switch (cta) {
  RoomCta.opensAt => null,
  RoomCta.choose => '/events/${e.id}/zones',
  RoomCta.join || RoomCta.resume => '/events/${e.id}/queue',
};

/// "15:30" the same day in the venue zone, else "Sam. 6 mars 15:30".
String opensAtText(AppLocalizations l, DateTime at, String zone, DateTime now) =>
    venueDaysUntil(now, at, zone) == 0 ? l.ltr(venueClock(at, zone, l.localeName)) : venueWhen(at, zone, l.localeName);

/// Screen 2: "Réservée aux fans vérifiés: compte vérifié avant le 6 nov. · 3 matchs au stade", only the parts set;
/// null without a rule.
String? eligibilityLine(AppLocalizations l, FanEvent e) {
  final rule = e.room?.eligibility;
  if (rule == null) return null;
  final parts = [
    if (rule.verifiedBefore case final d?) l.roomVerifiedBefore(venueDay(d, e.timezone, l.localeName)),
    if (rule.memberJoinedBefore case final d?) l.roomMemberBefore(venueDay(d, e.timezone, l.localeName)),
    if (rule.minEventsAttended case final n?) l.roomMinEvents(n),
  ];
  return parts.isEmpty ? null : l.roomEligibility(parts.reduce(l.dotPair));
}
