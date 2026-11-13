// Screen 10: the campaigns sent to the fan by any club (docs/04-api.md `GET /fan/me/inbox`). Hand-written like
// events/event.dart.

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/seen_set.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/core/api/api_client.dart';
import 'package:jomhor_fan/features/clubs/club.dart';
import 'package:shared_preferences/shared_preferences.dart';

class InboxMessage {
  const new({
    required this.id,
    required this.club,
    required this.title,
    required this.body,
    required this.sentAt,
    this.eventId,
  });

  /// `{id, organizer:{id, slug, name, crest_url}, title, body, event_id, sent_at}`; `id` is the campaign.
  factory fromJson(Map<String, Object?> j) => InboxMessage(
    id: j['id']! as String,
    club: Club.fromJson(j['organizer']! as Map<String, Object?>),
    title: j['title']! as String,
    body: j['body']! as String,
    eventId: (j['event_id'] as num?)?.toInt(),
    sentAt: DateTime.parse(j['sent_at']! as String),
  );

  final String id;
  final Club club;
  final String title;
  final String body;
  final int? eventId;

  /// Server time.
  final DateTime sentAt;
}

class InboxRepository {
  new(this._api);

  final ApiClient _api;

  /// Newest first, at most 30.
  Future<List<InboxMessage>> list() async {
    final body = (await _api.get('/fan/me/inbox'))! as Map<String, Object?>;
    return [for (final m in body['messages']! as List<Object?>) InboxMessage.fromJson(m! as Map<String, Object?>)];
  }
}

final inboxRepositoryProvider = Provider<InboxRepository>((ref) => InboxRepository(ref.watch(apiClientProvider)));

/// Read on the bell's first paint, again on resume, on a foreground push and on "Réessayer". Signed out: empty.
final FutureProvider<List<InboxMessage>> inboxProvider = FutureProvider<List<InboxMessage>>((ref) async {
  if (!ref.watch(sessionProvider)) return const [];
  return await ref.watch(inboxRepositoryProvider).list();
});

const inboxSeenKey = 'inbox.seen';

/// The newest `sent_at` (server time) the fan has seen in the inbox, one value for every club (lot-4 Q122). Wiped on
/// sign-out.
class InboxSeen extends AsyncNotifier<DateTime?> {
  @override
  Future<DateTime?> build() async {
    if (!ref.watch(sessionProvider)) return null;
    try {
      return DateTime.tryParse(await SharedPreferencesAsync().getString(inboxSeenKey) ?? '');
    } on Object {
      return null;
    }
  }

  /// Keeps the later of the stored time and [sentAt].
  Future<void> saw(DateTime sentAt) async {
    final seen = await future;
    if (!ref.mounted || !ref.read(sessionProvider) || (seen != null && !sentAt.isAfter(seen))) return;
    state = AsyncData(sentAt);
    try {
      await SharedPreferencesAsync().setString(inboxSeenKey, sentAt.toUtc().toIso8601String());
      if (!ref.mounted || !ref.read(sessionProvider)) await wipeSeen(inboxSeenKey);
    } on Object {
      // Not saved: the dot comes back after a restart.
    }
  }
}

final inboxSeenProvider = AsyncNotifierProvider<InboxSeen, DateTime?>(InboxSeen.new);

/// The bell's dot: a message newer than the last visit. Hidden while unknown and on any error (lot-4 Q129).
final unreadProvider = Provider<bool>((ref) {
  final inbox = ref.watch(inboxProvider);
  final seen = ref.watch(inboxSeenProvider);
  if (inbox.hasError || seen.hasError || !seen.hasValue) return false;
  final newest = newestSentAt(inbox.value ?? const []);
  return newest != null && (seen.value == null || newest.isAfter(seen.value!));
});

DateTime? newestSentAt(List<InboxMessage> messages) =>
    messages.map((m) => m.sentAt).fold<DateTime?>(null, (a, b) => a == null || b.isAfter(a) ? b : a);
