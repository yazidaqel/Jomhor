/// Arabic-Indic (U+0660-0669) and extended Arabic-Indic (U+06F0-06F9) digits to ASCII.
String asciiDigits(String s) => s.replaceAllMapped(RegExp(r'[\u0660-\u0669\u06f0-\u06f9]'), (m) {
  final c = m[0]!.codeUnitAt(0);
  return '${c - (c >= 0x06f0 ? 0x06f0 : 0x0660)}';
});

// Separators, plus bidi marks pasted from RTL text: ALM U+061C, LRM/RLM U+200E/F,
// embeddings/overrides U+202A-202E, isolates U+2066-2069. Regex escapes only: no invisible
// character is ever written into this file.
final _noise = RegExp(r'[\s.()\-\u061c\u200e\u200f\u202a-\u202e\u2066-\u2069]');
final _moroccan = RegExp(r'^(?:(?:\+|00)?2120?|0)([5-7]\d{8})$');

/// Moroccan phone to E.164 (`+212` + 9 digits), or null. Accepts `06 12 34 56 78`, `+212 6...`,
/// `+212 (0)6...`, `212...`, `00212...`, with spaces, dots, dashes, parentheses or bidi marks, in ASCII
/// or Arabic-Indic digits. Ranges 5, 6, 7. Same rules as web/shop and web/backoffice `normalizePhone`.
String? normalizePhone(String input) {
  final m = _moroccan.firstMatch(asciiDigits(input).replaceAll(_noise, ''));
  return m == null ? null : '+212${m[1]}';
}
