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
final _latinName = RegExp(
  "^$_latinLetter(?:$_latinLetter|[ '.-]){1,59}"
  r'$',
  unicode: true,
);

/// Tatweel and the Arabic combining marks (harakat, shadda, Quranic marks): removed after NFKC.
final _arabicMarks = RegExp('[\u0640\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED]');

/// One of the 36 Arabic base letters (U+0621-U+063A, U+0641-U+064A), the letters of the CIN's Arabic line:
/// extended letters (ڭ, ڤ, پ, ی...), Arabic-Indic digits and Arabic punctuation are refused.
const _arabicLetter = '[\u0621-\u063A\u0641-\u064A]';

/// First an Arabic letter, then 1 to 59 of: Arabic letter, space, hyphen.
final _arabicName = RegExp(
  '^$_arabicLetter(?:$_arabicLetter|[ -]){1,59}'
  r'$',
  unicode: true,
);

/// The holder name as the server will store it, or null if it would be refused (422).
/// Letter-number refused, NFKC (fullwidth to ASCII, Arabic presentation forms to letters), U+2019 to apostrophe,
/// tatweel and Arabic marks removed from every name, whitespace runs to one space, trimmed, then 2 to 60 code points
/// (the `unicode` regexes count code points) in one script: Latin letters with space, apostrophe, dot and hyphen,
/// or the Arabic base letters with space and hyphen (lot-4 Q66). Format characters (U+200B-U+200F, U+061C, bidi
/// controls, U+FEFF) match neither and are refused.
String? normalizeHolderName(String raw) {
  if (_letterNumber.hasMatch(raw)) return null;
  final name = unorm
      .nfkc(raw)
      .replaceAll('\u2019', "'")
      .replaceAll(_arabicMarks, '')
      .replaceAll(_spaces, ' ')
      .replaceAll(_ends, '');
  return _latinName.hasMatch(name) || _arabicName.hasMatch(name) ? name : null;
}
