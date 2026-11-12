import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/seen_set.dart';

const _seenKey = 'club.seen';

/// What screen 7 has already shown on this phone (lot-4 Q95), so the member number rolls once and a newly attended
/// dot fills once: organizer ids (the number was revealed) and `orgId:eventId` (that dot was filled). Wiped on
/// sign-out ([wipeClubSeen]).
final clubSeenProvider = AsyncNotifierProvider<SeenSet, Set<String>>(() => SeenSet(_seenKey));

/// The dot of [eventId] was filled.
String seenDotKey(String orgId, int eventId) => '$orgId:$eventId';

/// Never throws.
Future<void> wipeClubSeen() => wipeSeen(_seenKey);
