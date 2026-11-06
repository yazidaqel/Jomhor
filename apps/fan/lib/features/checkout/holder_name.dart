import 'package:unorm_dart/unorm_dart.dart' as unorm;

// docs/04-api.md §Holder names, in the server's order (backend common/HolderNames.java). The server checks
// again; this copy only lets the fan fix a name before the order is sent.

/// Letter numbers (Roman numerals U+2160...): NFKC would turn them into ASCII letters, so refused first.
final _letterNumber = RegExp(r'\p{Nl}', unicode: true);

/// Java `(?U)\s` = Unicode White_Space, no-break spaces U+00A0 and U+202F included.
final _spaces = RegExp(r'\p{White_Space}+', unicode: true);

/// Java `strip()` at the ends, after the collapse: space and U+001C..U+001F (Java whitespace, not Unicode
/// White_Space). Dart `trim()` would also drop U+FEFF, which Java keeps (and the name check then refuses).
final _ends = RegExp(r'^[ \x1c-\x1f]+|[ \x1c-\x1f]+$');

/// A Latin-script letter (Script=Latin and a letter: not a Latin-script symbol).
const _latinLetter = r'(?=\p{L})\p{Script=Latin}';

/// First a Latin letter, then 1 to 59 of: Latin letter, space, apostrophe, dot, hyphen.
final _name = RegExp(
  "^$_latinLetter(?:$_latinLetter|[ '.-]){1,59}"
  r'$',
  unicode: true,
);

/// The holder name as the server will store it, or null if it would be refused (422).
/// Letter-number refused, NFKC (fullwidth to ASCII), U+2019 to apostrophe, whitespace runs to one space,
/// trimmed, 2 to 60 characters, Latin letters with space, apostrophe, dot and hyphen.
String? normalizeHolderName(String raw) {
  if (_letterNumber.hasMatch(raw)) return null;
  final name = unorm.nfkc(raw).replaceAll('\u2019', "'").replaceAll(_spaces, ' ').replaceAll(_ends, '');
  return _name.hasMatch(name) ? name : null;
}
