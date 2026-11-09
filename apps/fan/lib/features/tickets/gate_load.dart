// `GET /fan/gates/{event_id}/load` (docs/04-api.md §Fan): the live load of each gate and the caller's entries
// ("Tu es dedans"). Screen 5 polls it on matchday only (lot-4 Q81 to Q88); everything else on that screen works
// offline from the saved tickets.

import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/features/events/events_repository.dart' show jitterProvider;
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum GateLevel { low, medium, high, unknown }

/// The admitting scan of a ticket: when (`device_time`) and at which gate.
typedef Entry = ({DateTime at, String gateCode});

/// One `/load` answer.
class GateLoad {
  const new({required this.serverTime, required this.levels, required this.entries});

  factory fromJson(Map<String, Object?> j) => GateLoad(
    serverTime: DateTime.parse(j['server_time']! as String).toUtc(),
    levels: {
      for (final g in j['gates']! as List<Object?>)
        if (g case {'code': final String code, 'level': final Object? level}) code: _level(level),
    },
    entries: {
      for (final e in j['entries']! as List<Object?>)
        if (e case {'ticket_id': final String id, 'entered_at': final String at, 'gate_code': final String gate})
          id: (at: DateTime.parse(at).toUtc(), gateCode: gate),
    },
  );

  final DateTime serverTime;
  final Map<String, GateLevel> levels;

  /// By ticket id.
  final Map<String, Entry> entries;

  /// A level is shown while its answer is under this age (lot-4 Q88), then the chips hide.
  static const freshFor = Duration(minutes: 2);

  /// The level of gate [code] at [serverNow]; [GateLevel.unknown] (no chip) once the answer is too old.
  GateLevel levelOf(String code, DateTime serverNow) =>
      serverNow.difference(serverTime) < freshFor ? levels[code] ?? GateLevel.unknown : GateLevel.unknown;
}

GateLevel _level(Object? v) => switch (v) {
  'LOW' => GateLevel.low,
  'MEDIUM' => GateLevel.medium,
  'HIGH' => GateLevel.high,
  _ => GateLevel.unknown,
};

const loadWindowBefore = Duration(hours: 3);

/// The poll runs from T0 − 3 h to T0 + 1 h, in server time.
bool inLoadWindow(DateTime startsAt, DateTime serverNow) =>
    !serverNow.isBefore(startsAt.subtract(loadWindowBefore)) &&
    serverNow.isBefore(startsAt.add(const Duration(hours: 1)));

/// 60 s ± 10 s between polls ([jitter] is `jitterProvider`: a random wait in [0, max]), so a stadium of phones
/// opened at the same second spreads out.
Duration loadPollInterval(Duration Function(Duration max) jitter) =>
    const Duration(seconds: 50) + jitter(const Duration(seconds: 20));

/// The last `/load` answer of an event (null before the first one) and its poll schedule. The schedule lives here,
/// not in screen 5: closing and re-opening the screen does not poll again before the next due time, and phones parked
/// on the screen at T0 - 3 h start within 20 s of each other, not in the same second.
class GateLoads extends Notifier<GateLoad?> {
  new(this.eventId);

  final int eventId;

  /// A 404: the caller holds no ticket of this event (any more). No further poll this session.
  bool stopped = false;

  /// Server time of the next poll; null until the first [pollIfDue] inside the window.
  DateTime? _due;
  var _busy = false;

  @override
  GateLoad? build() {
    ref.watch(sessionProvider);
    stopped = false;
    _due = null;
    return null;
  }

  /// Screen 5's tick (docs/04-api.md: only while visible, from T0 - 3 h to T0 + 1 h, every 60 s ± 10 s, until the
  /// [shown] ticket is entered). Polls when due at [serverNow]; returns the ids of tickets newly entered (they flip
  /// once). A failed poll keeps the last state, silently.
  Future<Set<String>> pollIfDue(FanTicket shown, DateTime serverNow, {required bool visible}) async {
    if (!visible || stopped || _busy || !shown.hasQr) return const {};
    final startsAt = shown.event.startsAt;
    if (!inLoadWindow(startsAt, serverNow)) return const {};
    final jitter = ref.read(jitterProvider);
    final due = _due ??= startsAt.subtract(loadWindowBefore).add(jitter(const Duration(seconds: 20)));
    if (serverNow.isBefore(due)) return const {};
    _busy = true;
    try {
      // The saved entries first: an entered ticket is never polled again, after a restart included.
      if ((await ref.read(enteredProvider.future)).containsKey(shown.id) || !ref.mounted) return const {};
      _due = serverNow.add(loadPollInterval(jitter));
      return await _poll();
    } finally {
      _busy = false;
    }
  }

  Future<Set<String>> _poll() async {
    final tokens = ref.read(tokenStoreProvider);
    final token = await tokens.read();
    if (token == null) return const {};
    final GateLoad load;
    try {
      final body = await ref.read(apiClientProvider).get('/fan/gates/$eventId/load');
      load = GateLoad.fromJson(body! as Map<String, Object?>);
    } on ApiError catch (e) {
      stopped = e.status == 404;
      return const {}; // The last state stays, silently.
    } on Object {
      return const {}; // Offline or an odd answer: the last state stays.
    }
    // Signed out, or in as someone else, while it ran: this answer is not theirs.
    if (!ref.mounted || await tokens.read() != token) return const {};
    state = load;
    return await ref.read(enteredProvider.notifier).add(load.entries);
  }
}

final NotifierProviderFamily<GateLoads, GateLoad?, int> gateLoadProvider =
    NotifierProvider.family<GateLoads, GateLoad?, int>(GateLoads.new);

const _enteredKey = 'tickets.entered';

/// Entered tickets on this phone (lot-4 Q82): ticket id → its entry, kept across restarts so re-opening shows the
/// entered state without the flip. No personal data. Wiped on sign-out ([wipeEntered], with the ticket cache).
class Entered extends AsyncNotifier<Map<String, Entry>> {
  @override
  Future<Map<String, Entry>> build() async {
    if (!ref.watch(sessionProvider)) {
      await wipeEntered();
      return const {};
    }
    try {
      final raw = await SharedPreferencesAsync().getString(_enteredKey);
      if (raw == null) return const {};
      return {
        for (final MapEntry(:key, :value) in (jsonDecode(raw) as Map<String, Object?>).entries)
          if (value case {'at': final String at, 'gate': final String gate})
            key: (at: DateTime.parse(at).toUtc(), gateCode: gate),
      };
    } on Object {
      return const {}; // Unreadable: nothing entered; the next poll brings it back.
    }
  }

  /// Saves [entries]; returns the ids that were not known yet.
  Future<Set<String>> add(Map<String, Entry> entries) async {
    final known = await future;
    final fresh = {...entries.keys.where((id) => !known.containsKey(id))};
    // Signed out since the answer left: nothing of that account is written back.
    if (fresh.isEmpty || !ref.mounted || !ref.read(sessionProvider)) return const {};
    final all = {...known, for (final id in fresh) id: entries[id]!};
    state = AsyncData(all);
    try {
      await SharedPreferencesAsync().setString(
        _enteredKey,
        jsonEncode({
          for (final MapEntry(:key, :value) in all.entries)
            key: {'at': value.at.toIso8601String(), 'gate': value.gateCode},
        }),
      );
      // A sign-out that ran during the write must not leave it behind.
      if (!ref.mounted || !ref.read(sessionProvider)) await wipeEntered();
    } on Object {
      // Not saved: this run shows it; after a restart the next poll brings it back (and flips it again).
    }
    return fresh;
  }
}

final enteredProvider = AsyncNotifierProvider<Entered, Map<String, Entry>>(Entered.new);

/// Never throws.
Future<void> wipeEntered() async {
  try {
    await SharedPreferencesAsync().remove(_enteredKey);
  } on Object {
    // Preferences unavailable: nothing was saved either.
  }
}
