import 'package:flutter/widgets.dart';
import 'package:jomhor_fan/core/money.dart';
import 'package:jomhor_fan/l10n/app_localizations.dart';

// DESIGN.md Lot 4, AR / RTL: codes, prices, phone and ticket numbers read left to right inside bidi isolates;
// names and organizer texts sit in an isolate of their own direction; Arabic text is never tracked.

const _lri = '\u2066'; // LEFT-TO-RIGHT ISOLATE
const _fsi = '\u2068'; // FIRST STRONG ISOLATE
const _pdi = '\u2069'; // POP DIRECTIONAL ISOLATE

final _letter = RegExp(r'\p{L}', unicode: true);
final _rtlLetter = RegExp(r'[\p{Script=Arabic}\p{Script=Hebrew}]', unicode: true);

/// The first letter of [s] is right to left (Arabic): `يزيد ع.`.
bool startsRtl(String s) => _rtlLetter.hasMatch(_letter.firstMatch(s)?[0] ?? '');

/// The example Moroccan number of the phone errors; spaced, so an isolate in AR (`l.ltr`).
const examplePhone = '06 12 34 56 78';

extension Bidi on AppLocalizations {
  bool get rtl => localeName == 'ar';

  /// [s] (a code, price, phone or ticket number) kept left to right inside right-to-left text: "50 DH", not
  /// "DH 50". Unchanged in FR and EN, where it already reads left to right.
  String ltr(String s) => rtl ? '$_lri$s$_pdi' : s;

  /// Integer centimes as "50 DH" ([formatDh]), isolated in AR.
  String dh(int centimes) => ltr(formatDh(centimes));

  /// A name, holder label or organizer text (a cash point's hours) in its own direction: "Yazid A." in AR keeps
  /// its dot on the right and "9 h - 18 h" its order; "يزيد ع." in FR keeps its dot on the left. Unchanged when
  /// [s] already runs the locale's way.
  String isolate(String s) => startsRtl(s) == rtl ? s : '$_fsi$s$_pdi';
}

/// The app runs right to left (AR).
bool isRtl(BuildContext context) => Directionality.of(context) == TextDirection.rtl;

extension ArabicType on TextStyle {
  /// Tracked caps (`type.label` and its kin) in FR and EN. In AR: letter-spacing 0, since tracking breaks the
  /// joining, and the emphasis comes from weight 700 instead (Arabic has no uppercase).
  TextStyle tracked(BuildContext context) =>
      isRtl(context) ? copyWith(letterSpacing: 0, fontWeight: FontWeight.w700) : this;
}
