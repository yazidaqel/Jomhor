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

/// Prefixed by the phase label when it has one ("Prévente membres · file dès 12:00", DESIGN.md Lot 5 screen 1).
String roomCtaLabel(AppLocalizations l, FanEvent e, RoomCta cta, DateTime now) {
  final room = e.room!;
  final label = room.label;
  final opens = opensAtText(l, room.opensAt, e.timezone, now);
  if (label == null) {
    return switch (cta) {
      RoomCta.opensAt => l.queueOpensAt(opens),
      RoomCta.join => l.queueJoin,
      RoomCta.resume => l.queueResume,
      RoomCta.choose => l.queueChoose,
    };
  }
  // After the label, the state reads on in lower case.
  return l.dotPair(l.isolate(label), switch (cta) {
    RoomCta.opensAt => l.roomStatusScheduled(opens),
    RoomCta.join => l.roomStatusJoin,
    RoomCta.resume => l.roomStatusResume,
    RoomCta.choose => l.roomStatusChoose,
  });
}

/// Screen 2's room line: "Prévente membres · tirage à 12:30" with a phase label, else the step-1 line.
String roomLine(AppLocalizations l, FanEvent e, DateTime now) {
  final room = e.room!;
  final label = room.label;
  if (label == null) return l.roomHighDemand;
  String at(DateTime t) => opensAtText(l, t, e.timezone, now);
  final status = switch (room.status) {
    'PRE_QUEUE' => l.roomStatusDraw(at(room.saleStartsAt ?? e.saleStartsAt ?? room.opensAt)),
    'OPEN' => l.roomStatusOpen,
    'PAUSED' => l.roomStatusPaused,
    _ => l.roomStatusScheduled(at(room.opensAt)),
  };
  return l.dotPair(l.isolate(label), status);
}

/// "Ensuite : Vente générale · file dès Sam. 21 févr. 10:00" (screens 2 and 12); null without a next phase.
String? nextPhaseLine(AppLocalizations l, FanEvent e, DateTime now) => switch (e.room?.next) {
  null => null,
  final n => l.roomNext(
    n.label == null ? l.roomNextDefault : l.isolate(n.label!),
    opensAtText(l, n.opensAt, e.timezone, now),
  ),
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
    if (rule.minEventsAttended case final n? when e.isMatch) l.roomMinEvents(n),
    if (rule.minEventsAttended case final n? when !e.isMatch) l.roomMinEventsAny(n),
  ];
  return parts.isEmpty ? null : l.roomEligibility(parts.reduce(l.dotPair));
}
