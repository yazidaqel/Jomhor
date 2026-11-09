// Read-only DTOs for `GET /fan/tickets?with_keys=true` (docs/04-api.md §Fan), hand-written like events/event.dart.
// K_t never leaves [FanTicket]: no getter, no toString, no route. It only signs the QR on this device.

import 'dart:convert';
import 'dart:typed_data';

import 'package:clock/clock.dart';
import 'package:jomhor_core/jomhor_core.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:jomhor_fan/features/events/event.dart';

/// A ticket id as the API writes it (lowercase UUID). Route parameters are checked against it.
final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

bool isTicketId(String id) => _uuid.hasMatch(id);

class FanTicket {
  new({
    required this.id,
    required this.ticketNo,
    required this.status,
    required this.event,
    required this.zone,
    required this.gateCodes,
    this.gates = const [],
    this.holderLabel,
    this.seatLabel,
    this.transferUntil,
    this.transferred,
    this._ticketKey,
  }) : _idBytes = _uuidBytes(id);

  factory fromJson(Map<String, Object?> j) {
    final zone = j['zone']! as Map<String, Object?>;
    return FanTicket(
      id: j['id']! as String,
      ticketNo: j['ticket_no']! as String,
      status: j['status']! as String,
      holderLabel: j['holder_label'] as String?,
      seatLabel: j['seat_label'] as String?,
      event: TicketEvent.fromJson(j['event']! as Map<String, Object?>),
      zone: (id: zone['id']! as int, code: zone['code']! as String, name: zone['name']! as String),
      gateCodes: [for (final g in j['gate_codes']! as List<Object?>) g! as String],
      // Lot 4, optional: a cache saved before it has no `gates` and must still open offline (no wipe). Malformed
      // rows are skipped the same way.
      gates: switch (j['gates']) {
        final List<Object?> rows => [
          for (final g in rows)
            if (g case {'code': final String _, 'name': final String _} && final Map<String, Object?> m)
              TicketGate.fromJson(m),
        ],
        _ => const [],
      },
      transferUntil: switch (j['transfer_until']) {
        final String at => DateTime.parse(at).toUtc(),
        _ => null,
      },
      transferred: switch (j['transferred']) {
        {'at': final String at} && final Map<String, Object?> x => (
          toLabel: x['to_label'] as String?,
          at: DateTime.parse(at).toUtc(),
        ),
        _ => null,
      },
      ticketKey: _key(j['k_t']),
    );
  }

  final String id;

  /// `4D5C 6B7A`, printed under the QR for manual lookup.
  final String ticketNo;

  /// `VALID`, `CANCELLED`, `REFUNDED`, `BLOCKED`.
  final String status;

  /// "TITULAIRE": `Yazid A.`; null for tickets issued before named tickets.
  final String? holderLabel;
  final String? seatLabel;
  final TicketEvent event;
  final ({int id, String code, String name}) zone;
  final List<String> gateCodes;

  /// The gates serving the zone, by code (`gates[]`, Lot 4); empty in a cache saved before Lot 4.
  final List<TicketGate> gates;

  /// What screen 5 shows: [gates], or the bare codes of an older cache. "Your gate" is the first (lot-4 Q81).
  List<TicketGate> get shownGates => gates.isNotEmpty ? gates : [for (final c in gateCodes) TicketGate(code: c)];

  /// Transfers and re-issues are refused from this instant (`refund_cutoff_at − 30 min`); null = never possible.
  final DateTime? transferUntil;

  /// Set on a ticket this holder transferred away (status `CANCELLED`): "Transféré à Karim B.".
  final Transferred? transferred;
  final Uint8List _idBytes;
  final Uint8List? _ticketKey;

  /// The server sends K_t only for a VALID ticket of an event that is not CLOSED.
  bool get hasQr => _ticketKey != null;

  /// The v2 rotating token for the slot of [serverNow] (docs/04-api.md §Token), or null without K_t.
  /// Carries version, event, ticket, zone, slot and tag only: no personal data.
  String? rotatingToken(DateTime serverNow) => switch (_ticketKey) {
    final key? => encodeRotating(key, event.id, _idBytes, zone.id, slotAt(serverNow)),
    null => null,
  };

  /// This ticket once given away: `CANCELLED`, its K_t dropped, [to] recorded. Used between the 201 and the refetch.
  FanTicket transferredAway(Transferred? to) => FanTicket(
    id: id,
    ticketNo: ticketNo,
    status: 'CANCELLED',
    event: event,
    zone: zone,
    gateCodes: gateCodes,
    gates: gates,
    holderLabel: holderLabel,
    seatLabel: seatLabel,
    transferUntil: transferUntil,
    transferred: to,
  );

  /// Why the fan cannot transfer or re-issue this ticket at [serverNow] (docs/04-api.md transfer row), or null.
  /// An admitted ticket is only known from the server's refusal (`transfer.dart` `blockOf`).
  TransferBlock? transferBlock(DateTime serverNow) => switch (transferUntil) {
    _ when status != 'VALID' => TransferBlock.notValid,
    final until? when serverNow.isBefore(until) => null,
    _ => TransferBlock.closed,
  };

  /// Past, as the API orders them: the event is CLOSED or started more than 12 h ago.
  bool isPast(DateTime serverNow) =>
      event.status == 'CLOSED' || !serverNow.isBefore(event.startsAt.add(const Duration(hours: 12)));
}

/// A gate serving the ticket's zone: where it is and how to get there. No personal data.
class TicketGate {
  const new({required this.code, this.name, this.directions, this.latitude, this.longitude});

  factory fromJson(Map<String, Object?> j) {
    final (lat, lng) = (j['latitude'], j['longitude']);
    // Both or neither (the API's rule); anything else means no Maps link rather than a wrong pin.
    final located = lat is num && lng is num;
    return TicketGate(
      code: j['code']! as String,
      name: j['name']! as String,
      directions: switch (j['directions']) {
        final String d when d.trim().isNotEmpty => d,
        _ => null,
      },
      latitude: located ? lat.toDouble() : null,
      longitude: located ? lng.toDouble() : null,
    );
  }

  final String code;

  /// Null for a gate known by its code only (a cache saved before Lot 4).
  final String? name;
  final String? directions;
  final double? latitude;
  final double? longitude;
}

/// The `transferred` field of a ticket the holder gave away.
typedef Transferred = ({String? toLabel, DateTime at});

/// Why the Transférer action is off: the ticket is not `VALID`, it was admitted at a gate, or the window is closed
/// (after `transfer_until`, or no cutoff on the event).
enum TransferBlock { notValid, admitted, closed }

class TicketEvent {
  new({
    required this.id,
    required this.title,
    required this.eventType,
    required this.startsAt,
    required this.timezone,
    required this.venueName,
    required this.metadata,
    this.doorsOpenAt,
    this.venueCity,
    this.status,
    this.organizer,
  });

  factory fromJson(Map<String, Object?> j) {
    final venue = j['venue']! as Map<String, Object?>;
    return TicketEvent(
      // Optional and never fatal: a cache from before multi-club, or a malformed block, means no club line.
      organizer: switch (j['organizer']) {
        {'id': final String _, 'name': final String _} && final Map<String, Object?> o => Club.fromJson(o),
        _ => null,
      },
      id: j['id']! as int,
      title: j['title']! as String,
      eventType: j['event_type']! as String,
      status: j['status'] as String?,
      startsAt: DateTime.parse(j['starts_at']! as String).toUtc(),
      doorsOpenAt: switch (j['doors_open_at']) {
        final String at => DateTime.parse(at).toUtc(),
        _ => null,
      },
      timezone: j['timezone']! as String,
      venueName: venue['name']! as String,
      venueCity: venue['city'] as String?,
      metadata: j['metadata'] as Map<String, Object?>? ?? const {},
    );
  }

  final int id;
  final String title;
  final String eventType;

  /// `ON_SALE` / `GATES_OPEN` / `CLOSED`; optional until the backend sends it.
  final String? status;
  final DateTime startsAt;
  final DateTime? doorsOpenAt;

  /// IANA zone of the venue: every displayed time goes through it.
  final String timezone;
  final String venueName;
  final String? venueCity;
  final Map<String, Object?> metadata;

  /// The club `{id, slug, name, crest_url}`; null in a ticket cache saved before multi-club (no club line then).
  final Club? organizer;

  EventFacts get facts => EventFacts.of(eventType, metadata);
}

/// Server time on this device: an anchor on server time plus the monotonic stopwatch since then, so changing
/// the device clock while the app runs does not move the QR slot.
/// ponytail: the monotonic clock may stop while the phone sleeps, so the app re-anchors on every resume
/// (`ticketsProvider` rebuilds from the device clock + the saved offset).
class ServerClock {
  new(this._anchor) : _since = clock.stopwatch()..start();

  final DateTime _anchor;
  final Stopwatch _since;

  DateTime now() => _anchor.add(_since.elapsed);
}

/// The held tickets of the last good answer, in the API's order (upcoming by `starts_at`, then past).
/// [offset] = server time − device clock at that answer; it is saved with the cache.
class TicketsSnapshot {
  new({required this.tickets, required this.offset, required this.clock});

  /// [response] is the `/fan/tickets` body; [serverTime] its `server_time` (else the `Date` header).
  factory fromResponse(Map<String, Object?> response, {required DateTime serverTime, required DateTime deviceNow}) =>
      TicketsSnapshot(
        tickets: [
          for (final t in response['tickets']! as List<Object?>) FanTicket.fromJson(t! as Map<String, Object?>),
        ],
        offset: serverTime.difference(deviceNow),
        clock: ServerClock(serverTime),
      );

  final List<FanTicket> tickets;
  final Duration offset;
  final ServerClock clock;

  /// The tickets of [eventId], in the API's order (the screen 5 pager).
  List<FanTicket> ofEvent(int eventId) => [
    for (final t in tickets)
      if (t.event.id == eventId) t,
  ];

  /// The first upcoming ticket that can show a QR: the "Mon billet" strip on screen 1.
  FanTicket? get next {
    final now = clock.now();
    for (final t in tickets) {
      if (t.hasQr && !t.isPast(now)) return t;
    }
    return null;
  }
}

/// Matchday mode (docs/02 §Fan app design direction) starts this long before kickoff.
const matchdayBefore = Duration(hours: 4);

/// ponytail: no end is specified; the mode lasts until 3 h after kickoff (end of the match and the way out).
const matchdayAfter = Duration(hours: 3);

bool isMatchday(FanTicket t, DateTime serverNow) =>
    !serverNow.isBefore(t.event.startsAt.subtract(matchdayBefore)) &&
    serverNow.isBefore(t.event.startsAt.add(matchdayAfter));

/// The ticket the app opens straight on: the earliest one with a QR whose event is in matchday mode.
FanTicket? matchdayTicket(List<FanTicket> tickets, DateTime serverNow) {
  FanTicket? best;
  for (final t in tickets) {
    if (t.hasQr && isMatchday(t, serverNow) && (best == null || t.event.startsAt.isBefore(best.event.startsAt))) {
      best = t;
    }
  }
  return best;
}

/// Seconds until the next code (1..30) at [serverNow].
int secondsToNextSlot(DateTime serverNow) => slotSeconds - (serverNow.millisecondsSinceEpoch ~/ 1000) % slotSeconds;

Uint8List _uuidBytes(String id) {
  if (!isTicketId(id)) throw FormatException('ticket id is not a lowercase UUID', id);
  final hex = id.replaceAll('-', '');
  return Uint8List.fromList([for (var i = 0; i < 32; i += 2) int.parse(hex.substring(i, i + 2), radix: 16)]);
}

/// `k_t`: base64url (padded or not) of 32 bytes. Anything else is treated as absent: the ticket shows no QR
/// rather than a QR the gate would refuse.
Uint8List? _key(Object? v) {
  if (v is! String) return null;
  try {
    final bytes = base64Url.decode(base64Url.normalize(v));
    return bytes.length == 32 ? bytes : null;
  } on FormatException {
    return null;
  }
}
