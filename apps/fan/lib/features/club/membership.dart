// Read-only DTOs for `GET/PUT /fan/me/membership` (docs/04-api.md §Fan, snake_case). Hand-written like
// events/event.dart: a few read-only shapes, nothing to keep in sync.

import 'dart:math';

import 'package:jomhor_fan/features/clubs/club.dart';

/// One club's membership answer: the fan's card ([member], null when not a member) and the loyalty row, which
/// non-members get too.
class Membership {
  const new({required this.loyalty, this.member});

  factory fromJson(Map<String, Object?> j) => Membership(
    member: switch (j['member']) {
      final Map<String, Object?> m => Member.fromJson(m),
      _ => null,
    },
    loyalty: Loyalty.fromJson(j['loyalty']! as Map<String, Object?>),
  );

  final Member? member;
  final Loyalty loyalty;
}

class Member {
  const new({
    required this.number,
    required this.joinedAt,
    required this.marketingPush,
    required this.marketingWhatsapp,
  });

  factory fromJson(Map<String, Object?> j) => Member(
    number: j['number']! as int,
    joinedAt: DateTime.parse(j['joined_at']! as String).toUtc(),
    marketingPush: j['marketing_push']! as bool,
    marketingWhatsapp: j['marketing_whatsapp']! as bool,
  );

  final int number;
  final DateTime joinedAt;
  final bool marketingPush;
  final bool marketingWhatsapp;

  /// "000042": the card and the Accueil strip.
  String get padded => '$number'.padLeft(6, '0');
}

class Loyalty {
  const new({required this.eventsAttended, required this.tier, required this.lastEvents, this.nextTier});

  factory fromJson(Map<String, Object?> j) => Loyalty(
    eventsAttended: j['events_attended']! as int,
    tier: Tier.fromJson(j['tier']! as Map<String, Object?>),
    nextTier: switch (j['next_tier']) {
      final Map<String, Object?> t => Tier.fromJson(t),
      _ => null,
    },
    lastEvents: [for (final e in j['last_events']! as List<Object?>) PastEvent.fromJson(e! as Map<String, Object?>)],
  );

  final int eventsAttended;
  final Tier tier;

  /// Null at the top tier.
  final Tier? nextTier;

  /// The club's latest closed events (at most 11), oldest first.
  final List<PastEvent> lastEvents;

  /// [lastEvents], capped at the 11 newest: the past dots of "Ta saison".
  List<PastEvent> get season => lastEvents.length > 11 ? lastEvents.sublist(lastEvents.length - 11) : lastEvents;

  /// Events still to attend to reach [nextTier]; null at the top tier.
  int? get toNextTier => switch (nextTier?.minEvents) {
    final min? => max(1, min - eventsAttended),
    null => null,
  };

  /// "matchs" rather than "événements" (lot-4 Q92): every past event is a football match (at least one); with none,
  /// when the club is a sports club.
  bool countsMatches(Club club) =>
      lastEvents.isEmpty ? club.sportsClub : lastEvents.every((e) => e.eventType == 'FOOTBALL_MATCH');
}

/// `tier` and `next_tier` (`min_events` on the latter only); [name] is in the fan's locale.
class Tier {
  const new({required this.code, required this.name, this.minEvents});

  factory fromJson(Map<String, Object?> j) =>
      Tier(code: j['code']! as String, name: j['name']! as String, minEvents: j['min_events'] as int?);

  final String code;
  final String name;
  final int? minEvents;
}

class PastEvent {
  const new({
    required this.eventId,
    required this.title,
    required this.eventType,
    required this.startsAt,
    required this.timezone,
    required this.attended,
  });

  factory fromJson(Map<String, Object?> j) => PastEvent(
    eventId: j['event_id']! as int,
    title: j['title']! as String,
    eventType: j['event_type']! as String,
    startsAt: DateTime.parse(j['starts_at']! as String).toUtc(),
    timezone: j['timezone']! as String,
    attended: j['attended']! as bool,
  );

  final int eventId;
  final String title;
  final String eventType;
  final DateTime startsAt;
  final String timezone;
  final bool attended;
}

/// One dot of "Ta saison" (DESIGN.md wow table, screen 7).
enum SeasonDot { placeholder, missed, attended, next }

/// Always twelve (lot-4 Q93): faint placeholders on the oldest side for missing past slots, then the last 11 closed
/// events oldest first, then the twelfth, the next event, always amber.
List<SeasonDot> seasonDots(Loyalty loyalty) => [
  for (var i = loyalty.season.length; i < 11; i++) SeasonDot.placeholder,
  for (final e in loyalty.season)
    if (e.attended) SeasonDot.attended else SeasonDot.missed,
  SeasonDot.next,
];
