// The club directory row and the `organizer` block of events and tickets (docs/04-api.md §Club directory).
// Hand-written like events/event.dart. A club is content (name and crest), never a skin: no colour is read here.

import 'package:unorm_dart/unorm_dart.dart' as unorm;

/// A club as fans see it.
class Club {
  const new({required this.id, required this.name, this.slug, this.kind, this.crestUrl});

  /// A `/fan/organizers` row `{id, slug, name, kind, crest_url}`, or an `organizer` block; [id] is given for the
  /// event block, which has no `id` of its own (`organizer_id` sits beside it).
  factory fromJson(Map<String, Object?> j, {String? id}) => Club(
    id: id ?? j['id']! as String,
    slug: j['slug'] as String?,
    name: j['name']! as String,
    kind: j['kind'] as String?,
    // The backend only returns crests on Jomhor's https asset host; anything else is drawn as the stand-in.
    crestUrl: switch (j['crest_url']) {
      final String url when url.startsWith('https://') => url,
      _ => null,
    },
  );

  final String id;

  /// Null when the club is unlisted (its events and tickets still show it).
  final String? slug;
  final String name;

  /// `SPORTS_CLUB`, `PROMOTER`, `VENUE`, `OTHER`; directory rows only.
  final String? kind;
  final String? crestUrl;

  /// "Pas de match à venir" rather than "Pas d'événement à venir" (UI copy only).
  bool get sportsClub => kind == 'SPORTS_CLUB';
}

final _marks = RegExp(r'\p{M}', unicode: true);

/// Lowercase, accents and Arabic marks removed: `Étoile` and `etoile` compare equal.
String _fold(String s) => unorm.nfd(s).replaceAll(_marks, '').toLowerCase();

/// The picker search (by name, accent-insensitive); an empty [query] keeps every club.
List<Club> searchClubs(List<Club> clubs, String query) {
  final q = _fold(query.trim());
  return q.isEmpty ? clubs : [...clubs.where((c) => _fold(c.name).contains(q))];
}
