const _nbsp = '\u00a0'; // between the amount and "DH": never wraps
const _nnbsp = '\u202f'; // FR thousands separator (what Intl 'fr' uses)

/// Integer centimes to FR display, integer maths only (same rules as web/shop `formatDh`):
/// 5050 -> "50,50 DH", 5000 -> "50 DH", 123456 -> "1 234,56 DH" (no-break spaces).
String formatDh(int centimes) {
  final abs = centimes.abs();
  final whole = (abs ~/ 100).toString().replaceAllMapped(RegExp(r'\B(?=(\d{3})+$)'), (_) => _nnbsp);
  final rest = abs % 100;
  final amount = rest == 0 ? whole : '$whole,${rest.toString().padLeft(2, '0')}';
  return '${centimes < 0 ? '-' : ''}$amount${_nbsp}DH';
}
