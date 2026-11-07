import 'dart:async';
import 'dart:convert';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/api/token_store.dart';
import 'package:jomhor_fan/core/clock.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';

/// The last `/fan/tickets?with_keys=true` answer (K_t included) and its clock offset, in the Keystore/Keychain,
/// so the tickets and their QR show at launch and in airplane mode. Wiped on logout and on a 401.
class TicketCache {
  new([
    this._storage = const FlutterSecureStorage(
      // Not restored to another device from a backup.
      iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
    ),
  ]);

  static const _key = 'fan_tickets_cache';
  final FlutterSecureStorage _storage;

  /// Storage calls run one after the other, in the order they are made, whatever the plugin does underneath: a
  /// read made after a write sees that write ([Tickets.retire] relies on it).
  Future<void> _tail = Future<void>.value();

  Future<T> _serial<T>(Future<T> Function() op) {
    final next = _tail.then((_) => op());
    _tail = next.then<void>((_) {}, onError: (Object _) {});
    return next;
  }

  /// The saved answer, or null when there is none. Data that does not decode is dropped; a storage failure
  /// (PlatformException) is thrown, and the saved tickets are kept.
  Future<({Map<String, Object?> response, Duration offset})?> read() => _serial(_read);

  Future<({Map<String, Object?> response, Duration offset})?> _read() async {
    final raw = await _storage.read(key: _key);
    if (raw == null) return null;
    try {
      final saved = jsonDecode(raw) as Map<String, Object?>;
      return (
        response: saved['response']! as Map<String, Object?>,
        offset: Duration(milliseconds: saved['offset_ms']! as int),
      );
    } on Object catch (e) {
      // Only a decode error drops the data: bad JSON, or JSON of another shape (a failed cast is a TypeError).
      if (e is! FormatException && e is! TypeError) rethrow;
      await _storage.delete(key: _key);
      return null;
    }
  }

  Future<void> write(Map<String, Object?> response, Duration offset) => _serial(
    () => _storage.write(key: _key, value: jsonEncode({'offset_ms': offset.inMilliseconds, 'response': response})),
  );

  Future<void> wipe() => _serial(() => _storage.delete(key: _key));
}

final ticketCacheProvider = Provider<TicketCache>((ref) => TicketCache());

/// What the Billets tab, screen 5 and the "Mon billet" strip show. [snapshot] null and [error] null = the first
/// read is still running; [error] with a [snapshot] = the refresh failed and the saved tickets are shown.
class TicketsState {
  const new({this.snapshot, this.error});

  final TicketsSnapshot? snapshot;
  final Object? error;
}

/// Saved tickets first, then `GET /fan/tickets?with_keys=true` in the background. Rebuilt on sign-in and sign-out,
/// and by the app on resume (which also re-anchors the server clock). Screen 5 only reads: it never calls [refresh].
class Tickets extends AsyncNotifier<TicketsState> {
  Future<void>? _refreshing;

  /// Bumped by [retire]: an answer to a request sent before a transfer or re-issue may still carry the old K_t,
  /// so it is dropped instead of saved.
  var _generation = 0;

  @override
  Future<TicketsState> build() async {
    final cache = ref.read(ticketCacheProvider);
    if (!ref.watch(sessionProvider)) {
      // Also drops a cache left behind with no session (e.g. the Keychain after a reinstall).
      await cache.wipe();
      return const TicketsState();
    }
    _refreshing = null;
    final deviceNow = ref.read(nowProvider)();
    final ({Map<String, Object?> response, Duration offset})? saved;
    try {
      saved = await cache.read();
    } on Exception {
      // The Keystore/Keychain failed this time: keep what is on screen, re-anchored like a fresh read.
      unawaited(Future.microtask(refresh));
      final previous = state.value?.snapshot;
      return previous == null
          ? const TicketsState()
          : TicketsState(
              snapshot: TicketsSnapshot(
                tickets: previous.tickets,
                offset: previous.offset,
                clock: ServerClock(deviceNow.add(previous.offset)),
              ),
            );
    }
    unawaited(Future.microtask(refresh));
    if (saved == null) return const TicketsState();
    try {
      return TicketsState(
        snapshot: TicketsSnapshot.fromResponse(
          saved.response,
          serverTime: deviceNow.add(saved.offset),
          deviceNow: deviceNow,
        ),
      );
    } on Object catch (e) {
      if (e is! FormatException && e is! TypeError) rethrow;
      await cache.wipe(); // Saved by an older build in another shape: the refresh brings it back.
      return const TicketsState();
    }
  }

  /// One request at a time. Errors are kept in the state, never thrown: the saved tickets stay on screen.
  /// [force]: after a POST that may have changed the list (a transfer or re-issue whose outcome is unknown), a
  /// request already running was sent before it and may carry an old K_t: its answer is dropped and a new one sent.
  Future<void> refresh({bool force = false}) {
    if (force) _forget();
    if (_refreshing case final running?) return running;
    late final Future<void> started;
    started = _refresh(_generation).whenComplete(() {
      if (identical(_refreshing, started)) _refreshing = null;
    });
    return _refreshing = started;
  }

  /// After a 201 transfer ([transferred]: ticket id → its `transferred`) or re-issue ([reissued], no longer listed
  /// by the API): the old K_t leaves the saved answer and the screen at once, then the list is fetched again with
  /// keys. The refetch may fail offline; the old key is already gone by then.
  Future<void> retire({Map<String, Transferred> transferred = const {}, Set<String> reissued = const {}}) async {
    _forget();
    final cache = ref.read(ticketCacheProvider);
    try {
      if (await cache.read() case final saved?) {
        await cache.write({
          ...saved.response,
          'tickets': [
            for (final t in (saved.response['tickets']! as List<Object?>).cast<Map<String, Object?>>())
              if (!reissued.contains(t['id']))
                if (transferred[t['id']] case final to?)
                  {
                    ...t,
                    'status': 'CANCELLED',
                    'transferred': {'to_label': to.toLabel, 'at': to.at.toIso8601String()},
                  }..remove('k_t')
                else
                  t,
          ],
        }, saved.offset);
      }
    } on Object {
      // Could not rewrite it: no saved answer rather than one with a dead K_t; the refetch saves a new one.
      try {
        await cache.wipe();
      } on Object {
        // Storage is failing altogether; the refetch below overwrites it when it can.
      }
    }
    if (!ref.mounted) return;
    if (state.value case TicketsState(snapshot: final s?, :final error)) {
      state = AsyncData(
        TicketsState(
          snapshot: TicketsSnapshot(
            tickets: [
              for (final t in s.tickets)
                if (!reissued.contains(t.id))
                  if (transferred[t.id] case final to?) t.transferredAway(to) else t,
            ],
            offset: s.offset,
            clock: s.clock,
          ),
          error: error,
        ),
      );
    }
    unawaited(refresh());
  }

  /// The running request predates a change: its answer is dropped, and the next refresh sends a new one.
  void _forget() {
    _generation++;
    _refreshing = null;
  }

  Future<void> _refresh(int generation) async {
    final before = await future;
    if (!ref.mounted || !ref.read(sessionProvider)) return;
    final tokens = ref.read(tokenStoreProvider);
    final cache = ref.read(ticketCacheProvider);
    final token = await tokens.read();
    try {
      final res = await ref.read(apiClientProvider).getResponse('/fan/tickets', query: {'with_keys': 'true'});
      final deviceNow = ref.read(nowProvider)();
      final body = res.body! as Map<String, Object?>;
      final serverTime = switch (body['server_time']) {
        final String t => DateTime.parse(t).toUtc(),
        _ => res.date ?? deviceNow,
      };
      final snapshot = TicketsSnapshot.fromResponse(body, serverTime: serverTime, deviceNow: deviceNow);
      // Signed out (or signed in as someone else) meanwhile, or a ticket was retired since the request left: this
      // answer must not land in the cache. The generation is read after the last await before the write.
      if (!ref.mounted || token == null || await tokens.read() != token || generation != _generation) return;
      // No await between that check and the write call: a [retire] from here on makes its cache read after this
      // write, and [TicketCache] runs its calls in order (flutter_secure_storage alone does not promise it), so that
      // read sees this answer and takes the old K_t out of it again.
      await cache.write(body, snapshot.offset);
      // A logout that ran during the write must not leave K_t behind.
      if (await tokens.read() != token) return await cache.wipe();
      if (ref.mounted && generation == _generation) state = AsyncData(TicketsState(snapshot: snapshot));
    } on Object catch (e) {
      // A 401 already signed out (ApiClient.onUnauthorized), which wipes the cache and rebuilds this provider.
      if (ref.mounted && generation == _generation) {
        state = AsyncData(TicketsState(snapshot: before.snapshot, error: e));
      }
    }
  }
}

final ticketsProvider = AsyncNotifierProvider<Tickets, TicketsState>(Tickets.new);

enum Resend { sent, rateLimited, notValid, offline, failed }

/// `POST /fan/tickets/{id}/whatsapp` (202): the ticket's PDF goes again to the holder's WhatsApp. Screen 5's only
/// network call, and only on a tap. 429 = more than 3 re-sends of this ticket in 24 h; 409 = no longer VALID.
final resendWhatsAppProvider = Provider<Future<Resend> Function(String ticketId)>(
  (ref) => (id) async {
    try {
      await ref.read(apiClientProvider).post('/fan/tickets/${Uri.encodeComponent(id)}/whatsapp');
      return Resend.sent;
    } on ApiError catch (e) {
      return switch (e.status) {
        _ when e.code == ApiError.network => Resend.offline, // also timeouts
        429 => Resend.rateLimited,
        409 => Resend.notValid,
        _ => Resend.failed,
      };
    }
  },
);
