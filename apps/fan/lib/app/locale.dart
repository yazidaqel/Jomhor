import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:jomhor_fan/app/session.dart';
import 'package:jomhor_fan/features/auth/auth_repository.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _key = 'locale';

/// The app languages, in the Profil order (DESIGN.md Lot 4: Français · العربية · English).
const appLocales = ['fr', 'ar', 'en'];

/// The language saved on the phone, read once at startup (`main` overrides it with [savedLocale]).
final initialLocaleProvider = Provider<String?>((ref) => null);

/// Never throws: an unreadable or unknown value means the device language (FR when unsupported).
Future<String?> savedLocale(SharedPreferencesAsync prefs) async {
  try {
    final code = await prefs.getString(_key);
    return appLocales.contains(code) ? code : null;
  } on Object {
    return null;
  }
}

/// The language picked in Profil; null = the device's.
class AppLocale extends Notifier<Locale?> {
  @override
  Locale? build() => switch (ref.read(initialLocaleProvider)) {
    final code? => Locale(code),
    null => null,
  };

  /// The language the server last confirmed for this fan (PATCH answered), so a reload does not PATCH again.
  String? _serverHas;

  /// Switches the app at once, saves the choice on the phone, then `PATCH /fan/me {locale}` so WhatsApp and push
  /// messages follow it. Neither failure undoes the switch; [reconcile] retries the PATCH on the next `/fan/me`.
  Future<void> select(String code) async {
    state = Locale(code);
    await _save(code);
    if (ref.read(sessionProvider)) await _tellServer(code);
  }

  /// The language the app shows: the Profil choice, else the device's first supported language (FR otherwise),
  /// as MaterialApp resolves it.
  String get effective =>
      state?.languageCode ??
      WidgetsBinding.instance.platformDispatcher.locales
          .map((l) => l.languageCode)
          .firstWhere(appLocales.contains, orElse: () => appLocales.first);

  /// After `GET /fan/me`: the app's language wins; when the server has another one (its `fr` may only be the
  /// default), one PATCH tells it. The server's language never changes the app.
  Future<void> reconcile(String? server) async {
    final mine = effective;
    if (mine != server && mine != _serverHas) await _tellServer(mine);
  }

  Future<void> _tellServer(String code) async {
    try {
      await ref.read(authRepositoryProvider).setLocale(code);
      _serverHas = code;
    } on Object {
      // Offline or refused: the next /fan/me read tries again.
    }
  }

  static Future<void> _save(String code) async {
    try {
      await SharedPreferencesAsync().setString(_key, code);
    } on Object {
      // Not saved: the next launch starts in the device language.
    }
  }
}

final appLocaleProvider = NotifierProvider<AppLocale, Locale?>(AppLocale.new);
