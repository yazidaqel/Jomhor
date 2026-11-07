import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/core/venue_time.dart';
import 'package:jomhor_fan/features/tickets/ticket.dart';
import 'package:jomhor_fan/features/tickets/tickets_repository.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';
import 'package:jomhor_fan/ui/bidi.dart';

// Screen 8 and the lost-phone re-issue (docs/04-api.md §Fan transfer and reissue rows, lot-4 Q59).

/// What `POST /fan/tickets/{id}/transfer` came to.
sealed class TransferResult {
  const new();
}

/// 201: the old ticket is retired on this phone and the list is being fetched again.
final class TransferDone extends TransferResult {
  const new(this.to);

  final Transferred to;
}

/// Refused, or the outcome is unknown ([TransferRefusal.unknown]: network, 5xx; the list is fetched again, since the
/// transfer may have happened).
final class TransferRefused extends TransferResult {
  const new(this.reason, {this.until});

  final TransferRefusal reason;

  /// `details.transfer_until` of a `TRANSFER_CLOSED`.
  final DateTime? until;
}

enum TransferRefusal {
  /// 422 on `to_phone`, reason `SELF`: the caller's own number (that is a re-issue).
  self,

  /// 422 on `to_phone`.
  phone,

  /// 422 on `name` (§Holder names).
  name,

  /// 409 `TRANSFER_CLOSED`.
  closed,

  /// 409 `TICKET_NOT_VALID`, reason `ADMITTED`: the ticket went through a gate.
  admitted,

  /// 409 `TICKET_NOT_VALID`.
  notValid,

  /// 404: not (or no longer) held by the caller.
  notFound,
  unknown,
}

/// The server's refusals this run, by ticket id: `/fan/tickets` does not say a ticket was admitted, and a closed
/// window may be ahead of the device's view. The action stays off with their copy. Reset on sign-in and sign-out.
class TransferRefusals extends Notifier<Map<String, TransferRefused>> {
  @override
  Map<String, TransferRefused> build() {
    ref.watch(sessionProvider);
    return const {};
  }

  void add(String ticketId, TransferRefused refusal) => state = {...state, ticketId: refusal};
}

final transferRefusalsProvider = NotifierProvider<TransferRefusals, Map<String, TransferRefused>>(TransferRefusals.new);

/// Why [t] cannot be transferred or re-issued now, the server's last word first.
TransferBlock? blockOf(FanTicket t, DateTime serverNow, Map<String, TransferRefused> refusals) =>
    switch (refusals[t.id]?.reason) {
      TransferRefusal.admitted => TransferBlock.admitted,
      TransferRefusal.closed => TransferBlock.closed,
      TransferRefusal.notValid => TransferBlock.notValid,
      _ => t.transferBlock(serverNow),
    };

TransferRefused _refusal(ApiError e) {
  final fields = e.details['fields'];
  return switch ((e.status, e.code, e.details)) {
    (422, _, {'reason': 'SELF'}) => const TransferRefused(TransferRefusal.self),
    (422, _, _) when fields is Map && fields.containsKey('to_phone') => const TransferRefused(TransferRefusal.phone),
    (422, _, _) when fields is Map && fields.containsKey('name') => const TransferRefused(TransferRefusal.name),
    (409, 'TRANSFER_CLOSED', final d) => TransferRefused(
      TransferRefusal.closed,
      until: switch (d['transfer_until']) {
        final String at => DateTime.tryParse(at)?.toUtc(),
        _ => null,
      },
    ),
    (409, 'TICKET_NOT_VALID', {'reason': 'ADMITTED'}) => const TransferRefused(TransferRefusal.admitted),
    (409, 'TICKET_NOT_VALID', _) => const TransferRefused(TransferRefusal.notValid),
    (404, _, _) => const TransferRefused(TransferRefusal.notFound),
    _ => const TransferRefused(TransferRefusal.unknown),
  };
}

/// Field refusals send the fan back to the form; anything else may have changed the list, so it is fetched again.
bool _refetches(TransferRefusal r) =>
    r != TransferRefusal.self && r != TransferRefusal.phone && r != TransferRefusal.name;

void _remember(Ref ref, String ticketId, TransferRefused r) {
  if (r.reason case TransferRefusal.closed || TransferRefusal.admitted || TransferRefusal.notValid) {
    ref.read(transferRefusalsProvider.notifier).add(ticketId, r);
  }
}

/// `POST /fan/tickets/{id}/transfer` with `{to_phone, name}`, both already normalised (E.164 and §Holder names).
final transferTicketProvider =
    Provider<Future<TransferResult> Function(FanTicket ticket, {required String toPhone, required String name})>(
      (ref) => (t, {required toPhone, required name}) async {
        final tickets = ref.read(ticketsProvider.notifier);
        try {
          final body = await ref
              .read(apiClientProvider)
              .post('/fan/tickets/${Uri.encodeComponent(t.id)}/transfer', body: {'to_phone': toPhone, 'name': name});
          if (body is! Map<String, Object?>) throw const FormatException('transfer answer');
          final to = (
            toLabel: body['to_label'] as String?,
            at: DateTime.parse(body['transferred_at']! as String).toUtc(),
          );
          await tickets.retire(transferred: {t.id: to});
          return TransferDone(to);
        } on ApiError catch (e) {
          final r = _refusal(e);
          _remember(ref, t.id, r);
          if (_refetches(r.reason)) unawaited(tickets.refresh(force: true));
          return r;
        } on Object {
          // A 2xx with another body: the transfer probably happened; the list says.
          unawaited(tickets.refresh(force: true));
          return const TransferRefused(TransferRefusal.unknown);
        }
      },
    );

/// The tickets "Téléphone perdu ?" re-issues: upcoming, VALID, inside the transfer window.
List<FanTicket> reissuable(TicketsSnapshot s, Map<String, TransferRefused> refusals) {
  final now = s.clock.now();
  return [
    for (final t in s.tickets)
      if (!t.isPast(now) && blockOf(t, now, refusals) == null) t,
  ];
}

/// `POST /fan/tickets/{id}/reissue` for each ticket, one after the other: each 201 retires the old K_t, then the
/// list is fetched again with keys. Returns how many were re-issued and how many were not.
final reissueTicketsProvider = Provider<Future<({int done, int failed})> Function(List<FanTicket> tickets)>(
  (ref) => (list) async {
    final done = <String>{};
    for (final t in list) {
      try {
        await ref.read(apiClientProvider).post('/fan/tickets/${Uri.encodeComponent(t.id)}/reissue');
        done.add(t.id);
      } on ApiError catch (e) {
        _remember(ref, t.id, _refusal(e));
      }
    }
    final tickets = ref.read(ticketsProvider.notifier);
    // Unknown outcomes (offline, 5xx) included: the refetch tells.
    await (done.isEmpty ? tickets.refresh(force: true) : tickets.retire(reissued: done));
    return (done: done.length, failed: list.length - done.length);
  },
);

/// "Transferts fermés 1 h 30 min avant le début" (lead = start − [until]), or the no-transfer line without one.
String transferClosedText(AppLocalizations l, FanTicket t, {DateTime? until}) {
  final at = until ?? t.transferUntil;
  final minutes = at == null ? 0 : t.event.startsAt.difference(at).inMinutes;
  if (minutes <= 0) return l.transferUnavailable;
  final (h, m) = (minutes ~/ 60, minutes % 60);
  return l.transferClosed([if (h > 0) l.durationHours(h), if (m > 0) l.durationMinutes(m)].join(' '));
}

/// Why the Transférer action is off, for the caption under it; null when it is on or the card already says why
/// (not VALID).
String? transferBlockText(AppLocalizations l, FanTicket t, TransferBlock? block, TransferRefused? refusal) =>
    switch (block) {
      TransferBlock.closed => transferClosedText(l, t, until: refusal?.until),
      TransferBlock.admitted => l.transferAdmitted,
      _ => null,
    };

/// The copy of a refusal that ends the sheet (field refusals show on their field).
String transferRefusalText(AppLocalizations l, FanTicket t, TransferRefused r) => switch (r.reason) {
  TransferRefusal.closed => transferClosedText(l, t, until: r.until),
  TransferRefusal.admitted => l.transferAdmitted,
  TransferRefusal.notValid => l.transferNotValid,
  TransferRefusal.notFound => l.errorNotFound,
  TransferRefusal.self => l.transferSelf,
  TransferRefusal.phone => l.holderPhoneError(l.ltr(examplePhone)),
  TransferRefusal.name => l.holderNameError,
  TransferRefusal.unknown => l.transferUnknown,
};

/// "Transféré à Karim B. · Sam. 21 févr." for a ticket given away, in the venue's calendar.
String transferredText(AppLocalizations l, FanTicket t, Transferred to) {
  final date = venueDate(to.at, t.event.timezone, l.localeName);
  return switch (to.toLabel) {
    final name? => l.transferRow(l.isolate(name), date),
    null => l.transferRowNoName(date),
  };
}
